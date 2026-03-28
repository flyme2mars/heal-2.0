package com.example.mychat.network

import kotlinx.serialization.Serializable

@Serializable
data class ChatRequest(
    val prompt: String,
    val context: ChatContext,
    val attachments: List<ChatAttachment> = emptyList()
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
