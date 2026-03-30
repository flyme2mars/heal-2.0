package com.example.mychat.data

import java.util.UUID

enum class ChatRole {
    USER, MODEL, ERROR
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val role: ChatRole,
    val isPending: Boolean = false,
    val reasoning: String? = null,
    val imageUri: String? = null // Store the local URI for display
)
