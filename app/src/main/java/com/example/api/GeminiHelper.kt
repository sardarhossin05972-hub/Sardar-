package com.example.api

import android.util.Log
import com.example.model.ChatMessage
import com.example.model.MessageSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object GeminiHelper {

    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/"
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private const val SYSTEM_INSTRUCTION = """You are ZOYA, a fast, intelligent, polite, and helpful female AI assistant and companion on the user's Android phone.
You fluently understand and speak Urdu (اردو), Roman Urdu (e.g. 'Zoya light on kar do', 'kya haal hai', 'aap kaisi hain'), Hindi (हिंदी / Hinglish), and English.
Always respond in the same language or dialect that the user used:
- If user speaks or writes in Roman Urdu, answer in warm, friendly, natural Roman Urdu.
- If user uses Urdu script (اردو), respond in polite Urdu.
- If user speaks in Hindi, respond in Hindi.
- If user speaks in English, respond in English.
Your tone is naturally feminine, conversational, empathetic, and respectful.
Keep your responses direct and concise suitable for voice reading.
Never claim you completed a system action if an error or permission restriction prevented it."""

    suspend fun generateResponse(
        apiKey: String,
        history: List<ChatMessage>,
        prompt: String,
        imageBase64: String? = null,
        imageMimeType: String = "image/jpeg"
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val modelName = if (imageBase64 != null) "gemini-2.5-flash-image" else "gemini-3.5-flash"
            val url = "$BASE_URL$modelName:generateContent?key=$apiKey"

            val contentsArray = buildJsonArray {
                // Include past turns for context (up to last 10 messages)
                val relevantHistory = history.takeLast(10)
                for (msg in relevantHistory) {
                    if (msg.text.isBlank() || msg.sender == MessageSender.SYSTEM) continue
                    add(buildJsonObject {
                        put("role", if (msg.sender == MessageSender.USER) "user" else "model")
                        putJsonArray("parts") {
                            add(buildJsonObject { put("text", msg.text) })
                        }
                    })
                }

                // Add current user prompt
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        if (imageBase64 != null) {
                            add(buildJsonObject {
                                putJsonObject("inlineData") {
                                    put("mimeType", imageMimeType)
                                    put("data", imageBase64)
                                }
                            })
                        }
                        add(buildJsonObject {
                            put("text", if (prompt.isNotBlank()) prompt else "Please describe this image.")
                        })
                    }
                })
            }

            val requestJson = buildJsonObject {
                put("contents", contentsArray)
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") {
                        add(buildJsonObject { put("text", SYSTEM_INSTRUCTION) })
                    }
                }
                putJsonObject("generationConfig") {
                    put("temperature", 0.7)
                    put("topP", 0.95)
                }
            }

            val requestBody = requestJson.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Log.e("GeminiHelper", "API Error: ${response.code} - $responseBody")
                return@withContext Result.failure(Exception("Gemini API error (${response.code}): $responseBody"))
            }

            val parsedJson = json.parseToJsonElement(responseBody).jsonObject
            val candidates = parsedJson["candidates"]?.jsonArray
            val firstCandidate = candidates?.firstOrNull()?.jsonObject
            val content = firstCandidate?.get("content")?.jsonObject
            val parts = content?.get("parts")?.jsonArray
            val text = parts?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content

            if (!text.isNullOrBlank()) {
                Result.success(text)
            } else {
                Result.failure(Exception("No response generated by Gemini."))
            }
        } catch (e: Exception) {
            Log.e("GeminiHelper", "Exception calling Gemini API", e)
            Result.failure(e)
        }
    }
}
