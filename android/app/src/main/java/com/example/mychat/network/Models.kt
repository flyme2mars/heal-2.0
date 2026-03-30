package com.example.mychat.network

import kotlinx.serialization.Serializable

import kotlinx.serialization.json.JsonElement

@Serializable
data class ChatRequest(
    val prompt: String,
    val history: List<Map<String, String>> = emptyList(),
    val context: ChatContext,
    val attachments: List<ChatAttachment> = emptyList(),
    val toolCallId: String? = null
)

@Serializable
data class ChatAttachment(
    val type: String = "image",
    val url: String // Base64 data URI
)

@Serializable
data class ChatContext(
    val health_connect: Map<String, String>,
    val fhir_records: List<String>,
    val memory_snapshot: Map<String, String>
)
