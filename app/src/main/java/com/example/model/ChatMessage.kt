package com.example.model

enum class MessageSender {
    USER,
    ZOYA,
    SYSTEM
}

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sender: MessageSender,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val toolName: String? = null,
    val toolResult: String? = null,
    val isError: Boolean = false,
    val attachmentType: String? = null, // "image", "audio", "file"
    val attachmentUri: String? = null
)
