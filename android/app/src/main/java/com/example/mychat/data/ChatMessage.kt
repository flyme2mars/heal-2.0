package com.example.mychat.data

import java.util.UUID

enum class ChatRole {
    USER, MODEL, ERROR, ASSISTANT, SYSTEM, TOOL
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val role: ChatRole,
    val isPending: Boolean = false,
    val reasoning: String? = null,
    val imageUri: String? = null, // Store the local URI for display
    val timestamp: Long = System.currentTimeMillis(),
    val pendingToolCall: ToolCallInfo? = null,
    val isActionResolved: Boolean = false,
    val toolCallId: String? = null,
    val toolCalls: String? = null
)

@kotlinx.serialization.Serializable
data class ToolCallInfo(
    val toolCallId: String,
    val name: String,
    val arguments: String
)
