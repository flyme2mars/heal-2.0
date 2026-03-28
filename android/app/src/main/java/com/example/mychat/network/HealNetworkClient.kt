package com.example.mychat.network

import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

sealed class HealEvent {
    data class TextDelta(val text: String) : HealEvent()
    data class ToolCall(val id: String, val name: String, val arguments: String) : HealEvent()
    data class Error(val message: String) : HealEvent()
    object StreamEnd : HealEvent()
}

class HealNetworkClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) 
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    fun streamChat(url: String, requestBody: String, appCheckToken: String?): Flow<HealEvent> = callbackFlow {
        val request = Request.Builder()
            .url(url)
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .header("Accept", "text/event-stream")
            .apply {
                if (appCheckToken != null) {
                    header("X-Firebase-AppCheck", appCheckToken)
                }
            }
            .build()

        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                Log.d("HealNetwork", "SSE Connection Opened")
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                Log.d("HealNetwork", "Event: $data")
                try {
                    // Handle Vercel AI SDK format (e.g., 0:"hello")
                    if (data.length > 2 && data[1] == ':') {
                        val type = data[0]
                        val payload = data.substring(2)
                        
                        if (type == '0') {
                            val cleanText = if (payload.startsWith("\"") && payload.endsWith("\"")) {
                                payload.substring(1, payload.length - 1)
                                    .replace("\\n", "\n")
                                    .replace("\\\"", "\"")
                            } else {
                                payload
                            }
                            trySend(HealEvent.TextDelta(cleanText))
                            return
                        }

                        if (type == '9') {
                            // Tool Call Parsing
                            val toolJson = json.parseToJsonElement(payload) as JsonObject
                            val toolName = toolJson["toolName"]?.jsonPrimitive?.content ?: ""
                            val args = toolJson["args"]?.toString() ?: ""
                            val callId = toolJson["toolCallId"]?.jsonPrimitive?.content ?: ""
                            trySend(HealEvent.ToolCall(callId, toolName, args))
                            return
                        }
                    }
                    
                    // Fallback: If it's just raw data
                    if (data.isNotEmpty()) {
                        trySend(HealEvent.TextDelta(data))
                    }
                } catch (e: Exception) {
                    Log.e("HealNetwork", "Parser Error", e)
                }
            }

            override fun onClosed(eventSource: EventSource) {
                Log.d("HealNetwork", "SSE Connection Closed")
                trySend(HealEvent.StreamEnd)
                close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                Log.e("HealNetwork", "SSE Connection Failed", t)
                trySend(HealEvent.Error(t?.message ?: "Unknown Error"))
                close(t)
            }
        }

        val eventSource = EventSources.createFactory(client)
            .newEventSource(request, listener)

        awaitClose {
            eventSource.cancel()
        }
    }
}
