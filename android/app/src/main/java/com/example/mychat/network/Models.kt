package com.example.mychat.network

import kotlinx.serialization.Serializable

@Serializable
data class ChatRequest(
    val prompt: String,
    val context: ChatContext
)

@Serializable
data class ChatContext(
    val health_connect: Map<String, String>? = null,
    val fhir_records: List<String>? = null, // Simplified for now
    val memory_snapshot: Map<String, String>
)
