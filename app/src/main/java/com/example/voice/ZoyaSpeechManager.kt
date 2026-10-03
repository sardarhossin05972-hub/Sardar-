package com.example.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

class ZoyaSpeechManager(
    private val context: Context,
    private val onCommandReceived: (String) -> Unit,
    private val onSpeechFinished: () -> Unit
) {
    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false
    private var speechRecognizer: SpeechRecognizer? = null

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _rmsLevel = MutableStateFlow(0f)
    val rmsLevel: StateFlow<Float> = _rmsLevel.asStateFlow()

    private val _lastRecognizedText = MutableStateFlow("")
    val lastRecognizedText: StateFlow<String> = _lastRecognizedText.asStateFlow()

    init {
        initTTS()
    }

    private fun initTTS() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isTtsInitialized = true
                configureFemaleVoice()
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _isSpeaking.value = true
                    }

                    override fun onDone(utteranceId: String?) {
                        _isSpeaking.value = false
                        onSpeechFinished()
                    }

                    override fun onError(utteranceId: String?) {
                        _isSpeaking.value = false
                        onSpeechFinished()
                    }
                })
            } else {
                Log.e("ZoyaSpeech", "TextToSpeech init failed with status: $status")
            }
        }
    }

    fun configureFemaleVoice(languageCode: String = "auto") {
        val engine = tts ?: return
        try {
            val targetLocale = when (languageCode) {
                "ur" -> Locale.forLanguageTag("ur-PK")
                "hi" -> Locale.forLanguageTag("hi-IN")
                "en" -> Locale.US
                else -> Locale.getDefault()
            }
            engine.language = targetLocale

            // Prioritize natural female voices
            val availableVoices = engine.voices
            if (!availableVoices.isNullOrEmpty()) {
                val femaleVoice = availableVoices.find { voice ->
                    val name = voice.name.lowercase()
                    (name.contains("female") || name.contains("woman") || name.contains("#female") || name.contains("sfg") || name.contains("hie"))
                            && !voice.isNetworkConnectionRequired
                } ?: availableVoices.find { voice ->
                    val name = voice.name.lowercase()
                    name.contains("female") || name.contains("woman") || name.contains("#female")
                }

                if (femaleVoice != null) {
                    engine.voice = femaleVoice
                }
            }
            engine.setPitch(1.06f) // Slightly higher, warm feminine pitch
            engine.setSpeechRate(0.98f) // Clear, conversational cadence
        } catch (e: Exception) {
            Log.e("ZoyaSpeech", "Error configuring TTS female voice", e)
        }
    }

    fun speak(text: String, utteranceId: String = "zoya_${System.currentTimeMillis()}") {
        if (!isTtsInitialized || tts == null) {
            Log.w("ZoyaSpeech", "TTS not initialized yet")
            return
        }
        _isSpeaking.value = true
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun stopSpeaking() {
        tts?.stop()
        _isSpeaking.value = false
    }

    fun startListening(languagePreference: String = "auto", continuous: Boolean = false) {
        if (SpeechRecognizer.isRecognitionAvailable(context).not()) {
            Log.e("ZoyaSpeech", "Speech recognition is not available on this device")
            return
        }

        stopSpeaking()
        stopListening()

        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        _isListening.value = true
                    }

                    override fun onBeginningOfSpeech() {
                        _isListening.value = true
                    }

                    override fun onRmsChanged(rmsdB: Float) {
                        // Normalize -2dB..10dB to 0..1 range
                        val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                        _rmsLevel.value = normalized
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        _isListening.value = false
                        _rmsLevel.value = 0f
                    }

                    override fun onError(error: Int) {
                        _isListening.value = false
                        _rmsLevel.value = 0f
                        Log.d("ZoyaSpeech", "SpeechRecognizer error: $error")
                        if (continuous) {
                            // If live mode is continuous, restart listening after a brief pause
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                if (!_isSpeaking.value) {
                                    startListening(languagePreference, continuous = true)
                                }
                            }, 1200)
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        _isListening.value = false
                        _rmsLevel.value = 0f
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            val recognized = matches[0]
                            _lastRecognizedText.value = recognized
                            handleRecognizedText(recognized)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            _lastRecognizedText.value = matches[0]
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)

                when (languagePreference) {
                    "ur" -> {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ur-PK")
                    }
                    "hi" -> {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
                    }
                    "en" -> {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                    }
                    else -> {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString())
                        putExtra(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES, arrayListOf("ur-PK", "hi-IN", "en-US"))
                    }
                }
            }

            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e("ZoyaSpeech", "Error starting speech recognizer", e)
            _isListening.value = false
        }
    }

    private fun handleRecognizedText(rawText: String) {
        val trimmed = rawText.trim()
        val lower = trimmed.lowercase()

        val wakeWords = listOf(
            "hey zoya", "zoya", "ok zoya", "hello zoya", "hi zoya",
            "سنو زویا", "زویا", "ہیلو زویا",
            "सुनो ज़ोया", "ज़ोया"
        )

        var matchedWakeWord: String? = null
        for (w in wakeWords) {
            if (lower.startsWith(w)) {
                matchedWakeWord = w
                break
            }
        }

        if (matchedWakeWord != null) {
            triggerWakeVibration()
            val command = trimmed.substring(matchedWakeWord.length).trim().removePrefix(",").trim()
            if (command.isEmpty()) {
                val greeting = if (matchedWakeWord.contains("سنو") || matchedWakeWord.contains("زویا")) {
                    "جی، میں سن رہی ہوں۔ بتائیے میں کیا مدد کر سکتی ہوں؟"
                } else if (matchedWakeWord.contains("सुनो") || matchedWakeWord.contains("ज़ोया")) {
                    "हाँ, मैं सुन रही हूँ। बताइए मैं क्या मदद कर सकती हूँ?"
                } else {
                    "Ji, main sun rahi hoon. Boliye, kya madad kar sakti hoon?"
                }
                speak(greeting)
            } else {
                onCommandReceived(command)
            }
        } else {
            onCommandReceived(trimmed)
        }
    }

    private fun triggerWakeVibration() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(70)
                }
            }
        } catch (e: Exception) {
            Log.e("ZoyaSpeech", "Error triggering vibration", e)
        }
    }

    fun stopListening() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.e("ZoyaSpeech", "Error stopping speech recognizer", e)
        }
        speechRecognizer = null
        _isListening.value = false
        _rmsLevel.value = 0f
    }

    fun release() {
        stopSpeaking()
        stopListening()
        tts?.shutdown()
        tts = null
    }
}
