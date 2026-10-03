package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.ZoyaForegroundService
import com.example.accessibility.ZoyaAccessibilityService
import com.example.api.GeminiHelper
import com.example.live.ZoyaState
import com.example.model.ChatMessage
import com.example.model.MessageSender
import com.example.tools.ToolExecutionEngine
import com.example.voice.ZoyaSpeechManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

class ZoyaViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context get() = getApplication<Application>().applicationContext
    private val prefs = context.getSharedPreferences("ZoyaPrefs", Context.MODE_PRIVATE)
    private val toolEngine = ToolExecutionEngine(context)

    private val _messages = MutableStateFlow<List<ChatMessage>>(
        listOf(
            ChatMessage(
                sender = MessageSender.ZOYA,
                text = "Hello! I am ZOYA, your personal AI assistant. Main Urdu, Roman Urdu, Hindi, aur English samajh sakti hoon. Aap mujhse voice ya chat me baat kar sakte hain. How can I assist you today?"
            )
        )
    )
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _zoyaState = MutableStateFlow(ZoyaState.IDLE)
    val zoyaState: StateFlow<ZoyaState> = _zoyaState.asStateFlow()

    private val _isLiveModeEnabled = MutableStateFlow(prefs.getBoolean("live_mode_enabled", true))
    val isLiveModeEnabled: StateFlow<Boolean> = _isLiveModeEnabled.asStateFlow()

    private val _isWakeWordEnabled = MutableStateFlow(prefs.getBoolean("wake_word_enabled", true))
    val isWakeWordEnabled: StateFlow<Boolean> = _isWakeWordEnabled.asStateFlow()

    private val _languagePreference = MutableStateFlow(prefs.getString("pref_lang", "auto") ?: "auto")
    val languagePreference: StateFlow<String> = _languagePreference.asStateFlow()

    private val _apiKey = MutableStateFlow(
        prefs.getString("api_key", "")?.trim()?.ifEmpty { BuildConfig.GEMINI_API_KEY.trim() }
            ?: BuildConfig.GEMINI_API_KEY.trim()
    )
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _selectedImageUri = MutableStateFlow<Uri?>(null)
    val selectedImageUri: StateFlow<Uri?> = _selectedImageUri.asStateFlow()

    private val _selectedImageBase64 = MutableStateFlow<String?>(null)
    val selectedImageBase64: StateFlow<String?> = _selectedImageBase64.asStateFlow()

    private val _activeNotice = MutableStateFlow<String?>(null)
    val activeNotice: StateFlow<String?> = _activeNotice.asStateFlow()

    lateinit var speechManager: ZoyaSpeechManager
        private set

    init {
        speechManager = ZoyaSpeechManager(
            context = context,
            onCommandReceived = { command ->
                handleVoiceCommand(command)
            },
            onSpeechFinished = {
                if (_isLiveModeEnabled.value) {
                    _zoyaState.value = ZoyaState.LISTENING
                    // Automatically listen for next command in Live mode
                    speechManager.startListening(
                        languagePreference = _languagePreference.value,
                        continuous = _isLiveModeEnabled.value
                    )
                } else {
                    _zoyaState.value = ZoyaState.IDLE
                }
            }
        )

        // Observe speech manager speaking state to update Zoya state
        viewModelScope.launch {
            speechManager.isSpeaking.collect { speaking ->
                if (speaking) {
                    _zoyaState.value = ZoyaState.SPEAKING
                } else if (_zoyaState.value == ZoyaState.SPEAKING) {
                    if (_isLiveModeEnabled.value) {
                        _zoyaState.value = ZoyaState.LISTENING
                    } else {
                        _zoyaState.value = ZoyaState.IDLE
                    }
                }
            }
        }

        viewModelScope.launch {
            speechManager.isListening.collect { listening ->
                if (listening && _zoyaState.value != ZoyaState.SPEAKING && _zoyaState.value != ZoyaState.THINKING) {
                    _zoyaState.value = ZoyaState.LISTENING
                }
            }
        }
    }

    fun setLiveMode(enabled: Boolean) {
        _isLiveModeEnabled.value = enabled
        prefs.edit().putBoolean("live_mode_enabled", enabled).apply()
        ZoyaForegroundService.activeService?.setLiveMode(enabled)
        if (!enabled) {
            speechManager.stopListening()
            _zoyaState.value = ZoyaState.IDLE
        } else {
            startListening()
        }
    }

    fun setWakeWord(enabled: Boolean) {
        _isWakeWordEnabled.value = enabled
        prefs.edit().putBoolean("wake_word_enabled", enabled).apply()
    }

    fun setLanguage(lang: String) {
        _languagePreference.value = lang
        prefs.edit().putString("pref_lang", lang).apply()
        speechManager.configureFemaleVoice(lang)
    }

    fun saveApiKey(newKey: String) {
        val clean = newKey.trim()
        _apiKey.value = clean
        prefs.edit().putString("api_key", clean).apply()
        ZoyaForegroundService.activeService?.reconnectSession()
    }

    fun clearNotice() {
        _activeNotice.value = null
    }

    fun startListening() {
        val hasMic = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            _activeNotice.value = "Microphone permission is required to listen. Please grant it."
            return
        }
        speechManager.startListening(
            languagePreference = _languagePreference.value,
            continuous = _isLiveModeEnabled.value
        )
    }

    fun stopListening() {
        speechManager.stopListening()
        _zoyaState.value = ZoyaState.IDLE
    }

    fun toggleListening() {
        if (speechManager.isListening.value) {
            stopListening()
        } else {
            startListening()
        }
    }

    fun selectImage(uri: Uri?) {
        _selectedImageUri.value = uri
        if (uri != null) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val stream: InputStream? = context.contentResolver.openInputStream(uri)
                    val bitmap = BitmapFactory.decodeStream(stream)
                    val out = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                    val base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                    _selectedImageBase64.value = base64
                } catch (e: Exception) {
                    Log.e("ZoyaVM", "Error reading image", e)
                }
            }
        } else {
            _selectedImageBase64.value = null
        }
    }

    fun clearImage() {
        _selectedImageUri.value = null
        _selectedImageBase64.value = null
    }

    fun handleVoiceCommand(command: String) {
        if (command.isBlank()) return
        sendMessage(command)
    }

    fun sendMessage(userText: String) {
        val trimmed = userText.trim()
        if (trimmed.isEmpty() && _selectedImageBase64.value == null) return

        val imageAttached = _selectedImageUri.value?.toString()
        val imageBase64Attached = _selectedImageBase64.value

        val userMessage = ChatMessage(
            sender = MessageSender.USER,
            text = trimmed,
            attachmentType = if (imageAttached != null) "image" else null,
            attachmentUri = imageAttached
        )
        _messages.value = _messages.value + userMessage

        clearImage()
        _zoyaState.value = ZoyaState.THINKING

        // Check if the input is a local device command that can be processed directly
        viewModelScope.launch {
            val handledLocally = checkAndExecuteDirectLocalCommand(trimmed)
            if (handledLocally) {
                return@launch
            }

            // If WebSocket Live session is active and no image attached, send to live session
            val liveService = ZoyaForegroundService.activeService
            if (liveService != null && imageBase64Attached == null && ZoyaForegroundService.currentState != ZoyaState.IDLE) {
                liveService.sendTextMessage(trimmed)
                return@launch
            }

            // Fallback to Gemini REST API for reasoning, multimodal image analysis, and conversational Q&A
            val key = _apiKey.value.ifEmpty { BuildConfig.GEMINI_API_KEY }.trim()
            if (key.isEmpty() || key == "MY_GEMINI_API_KEY" || key == "YOUR_API_KEY") {
                val errMsg = "Gemini API Key is not configured. Please add your key in Settings or the Secrets panel."
                _messages.value = _messages.value + ChatMessage(
                    sender = MessageSender.ZOYA,
                    text = errMsg,
                    isError = true
                )
                _zoyaState.value = ZoyaState.IDLE
                speechManager.speak("Gemini API key is not configured. Please set it in Settings.")
                return@launch
            }

            val result = GeminiHelper.generateResponse(
                apiKey = key,
                history = _messages.value,
                prompt = trimmed,
                imageBase64 = imageBase64Attached,
                imageMimeType = "image/jpeg"
            )

            result.onSuccess { responseText ->
                _messages.value = _messages.value + ChatMessage(
                    sender = MessageSender.ZOYA,
                    text = responseText
                )
                _zoyaState.value = ZoyaState.SPEAKING
                speechManager.speak(responseText)
            }.onFailure { err ->
                val errorMsg = "Sorry, I encountered an issue: ${err.message}"
                _messages.value = _messages.value + ChatMessage(
                    sender = MessageSender.ZOYA,
                    text = errorMsg,
                    isError = true
                )
                _zoyaState.value = ZoyaState.IDLE
                speechManager.speak("Sorry, I could not complete the request. ${err.message}")
            }
        }
    }

    private suspend fun checkAndExecuteDirectLocalCommand(command: String): Boolean = withContext(Dispatchers.IO) {
        val lower = command.lowercase().trim()

        // 1. Navigation: Back, Home, Recents
        if (lower == "back" || lower == "go back" || lower == "peechay jao" || lower == "wapas" || lower == "wapas jao") {
            val res = toolEngine.execute("performGlobalNavigation", kotlinx.serialization.json.buildJsonObject {
                put("action", kotlinx.serialization.json.JsonPrimitive("back"))
            })
            postToolResponse("Navigation (Back)", res)
            return@withContext true
        }

        if (lower == "home" || lower == "go home" || lower == "home screen" || lower == "home screen jao") {
            val res = toolEngine.execute("performGlobalNavigation", kotlinx.serialization.json.buildJsonObject {
                put("action", kotlinx.serialization.json.JsonPrimitive("home"))
            })
            postToolResponse("Navigation (Home)", res)
            return@withContext true
        }

        if (lower == "recent apps" || lower == "recents" || lower == "open recents") {
            val res = toolEngine.execute("performGlobalNavigation", kotlinx.serialization.json.buildJsonObject {
                put("action", kotlinx.serialization.json.JsonPrimitive("recents"))
            })
            postToolResponse("Navigation (Recents)", res)
            return@withContext true
        }

        // 2. Media Controls: Play, Pause, Next, Previous, Stop
        if (lower == "pause" || lower == "pause music" || lower == "stop music" || lower == "gaana roko" || lower == "roko") {
            val res = toolEngine.execute("mediaControl", kotlinx.serialization.json.buildJsonObject {
                put("action", kotlinx.serialization.json.JsonPrimitive("pause"))
            })
            postToolResponse("Media Control (Pause)", res)
            return@withContext true
        }

        if (lower == "play" || lower == "resume" || lower == "play music" || lower == "gaana chalao" || lower == "chalao") {
            val res = toolEngine.execute("mediaControl", kotlinx.serialization.json.buildJsonObject {
                put("action", kotlinx.serialization.json.JsonPrimitive("play"))
            })
            postToolResponse("Media Control (Play)", res)
            return@withContext true
        }

        if (lower == "next song" || lower == "next track" || lower == "agla gaana" || lower == "skip") {
            val res = toolEngine.execute("mediaControl", kotlinx.serialization.json.buildJsonObject {
                put("action", kotlinx.serialization.json.JsonPrimitive("next"))
            })
            postToolResponse("Media Control (Next)", res)
            return@withContext true
        }

        if (lower == "previous song" || lower == "previous track" || lower == "pichla gaana") {
            val res = toolEngine.execute("mediaControl", kotlinx.serialization.json.buildJsonObject {
                put("action", kotlinx.serialization.json.JsonPrimitive("previous"))
            })
            postToolResponse("Media Control (Previous)", res)
            return@withContext true
        }

        // 3. Torch / Flashlight
        if (lower.contains("torch on") || lower.contains("flashlight on") || lower.contains("light on kar") || lower.contains("batti jalao")) {
            val res = toolEngine.execute("toggleTorch", kotlinx.serialization.json.buildJsonObject {
                put("state", kotlinx.serialization.json.JsonPrimitive("on"))
            })
            postToolResponse("Torch", res)
            return@withContext true
        }

        if (lower.contains("torch off") || lower.contains("flashlight off") || lower.contains("light off kar") || lower.contains("batti bujhao")) {
            val res = toolEngine.execute("toggleTorch", kotlinx.serialization.json.buildJsonObject {
                put("state", kotlinx.serialization.json.JsonPrimitive("off"))
            })
            postToolResponse("Torch", res)
            return@withContext true
        }

        // 4. Volume commands
        if (lower.contains("volume up") || lower.contains("awaz barhao") || lower.contains("awaaz badhao")) {
            val res = toolEngine.execute("adjustVolume", kotlinx.serialization.json.buildJsonObject {
                put("direction", kotlinx.serialization.json.JsonPrimitive("up"))
            })
            postToolResponse("Volume", res)
            return@withContext true
        }

        if (lower.contains("volume down") || lower.contains("awaz kam karo") || lower.contains("awaaz kam karo")) {
            val res = toolEngine.execute("adjustVolume", kotlinx.serialization.json.buildJsonObject {
                put("direction", kotlinx.serialization.json.JsonPrimitive("down"))
            })
            postToolResponse("Volume", res)
            return@withContext true
        }

        // 5. Web Search
        if (lower.startsWith("search for ") || lower.startsWith("google ") || lower.startsWith("search web for ")) {
            val query = lower.removePrefix("search for ").removePrefix("google ").removePrefix("search web for ").trim()
            val res = toolEngine.execute("webSearch", kotlinx.serialization.json.buildJsonObject {
                put("query", kotlinx.serialization.json.JsonPrimitive(query))
            })
            postToolResponse("Web Search", res)
            return@withContext true
        }

        // 6. Open specific common apps
        if (lower.startsWith("open ") || lower.startsWith("kholo ")) {
            val appName = lower.removePrefix("open ").removePrefix("kholo ").trim()
            val res = toolEngine.execute("openApp", kotlinx.serialization.json.buildJsonObject {
                put("packageName", kotlinx.serialization.json.JsonPrimitive(appName))
            })
            postToolResponse("Open App", res)
            return@withContext true
        }

        false
    }

    private fun postToolResponse(toolName: String, result: String) {
        val isError = result.lowercase().contains("error") || result.lowercase().contains("failed") || result.lowercase().contains("cannot")
        _messages.value = _messages.value + ChatMessage(
            sender = MessageSender.ZOYA,
            text = result,
            toolName = toolName,
            toolResult = result,
            isError = isError
        )
        _zoyaState.value = ZoyaState.SPEAKING
        speechManager.speak(result)
    }

    fun speakText(text: String) {
        speechManager.speak(text)
    }

    fun testVoice() {
        val testPhrase = when (_languagePreference.value) {
            "ur" -> "السلام علیکم! میں زویا ہوں، آپ کی ذاتی اے آئی اسسٹنٹ۔"
            "hi" -> "नमस्ते! मैं ज़ोया हूँ, आपकी पर्सनल एआई असिस्टेंट।"
            else -> "Hello! I am Zoya, your personal AI assistant. Main Urdu, Hindi, aur English me aapki madad ke liye tayar hoon."
        }
        speechManager.speak(testPhrase)
    }

    fun checkPermissionsStatus(): Map<String, Boolean> {
        val audio = ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val contacts = ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val call = ContextCompat.checkSelfPermission(context, android.Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        val access = ZoyaAccessibilityService.isRunning()
        val writeSettings = Settings.System.canWrite(context)

        return mapOf(
            "Microphone (Voice & Wake Phrase)" to audio,
            "Accessibility (Navigation & Auto-click)" to access,
            "Contacts (Call search)" to contacts,
            "Phone (Direct Calling)" to call,
            "Notifications (Background Service)" to notif,
            "System Settings (Brightness control)" to writeSettings
        )
    }

    override fun onCleared() {
        super.onCleared()
        speechManager.release()
    }
}
