package com.example.mychat.network

import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import javax.inject.Inject
import javax.inject.Singleton

sealed class HealEvent {
    data class TextDelta(val text: String) : HealEvent()
    data class ReasoningDelta(val text: String) : HealEvent()
    data class ToolCall(val id: String, val name: String, val arguments: String) : HealEvent()
    data class Error(val message: String) : HealEvent()
    object StreamEnd : HealEvent()
}

@Singleton
class HealNetworkClient @Inject constructor(
    private val client: OkHttpClient
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun streamChat(url: String, requestBody: String, appCheckToken: String?): Flow<HealEvent> = callbackFlow {
        Log.d("HealNetwork", ">>> REQUEST BODY: $requestBody")
        
        val request = Request.Builder()
            .url(url)
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .header("Accept", "text/event-stream")
            .header("x-vercel-ai-ui-message-stream", "v1")
            .build()

        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                Log.d("HealNetwork", "SSE Connection Opened. Code: ${response.code}")
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                Log.d("HealNetwork", "<<< RAW EVENT: $data")
                try {
                    val element = json.parseToJsonElement(data) as? JsonObject ?: return
                    val streamType = element["type"]?.jsonPrimitive?.content ?: return
                    
                    when (streamType) {
                        "text-delta" -> {
                            // AI SDK 6.0 StreamTextPart for text-delta uses "text" field
                            val delta = element["text"]?.jsonPrimitive?.content ?: ""
                            if (delta.isNotEmpty()) trySend(HealEvent.TextDelta(delta))
                        }
                        "reasoning-delta" -> {
                            // AI SDK 6.0 StreamTextPart for reasoning-delta uses "text" field
                            val delta = element["text"]?.jsonPrimitive?.content ?: ""
                            if (delta.isNotEmpty()) trySend(HealEvent.ReasoningDelta(delta))
                        }
                        "tool-call" -> {
                            val toolCallId = element["toolCallId"]?.jsonPrimitive?.content ?: ""
                            val toolName = element["toolName"]?.jsonPrimitive?.content ?: ""
                            val args = element["args"]?.jsonObject?.toString() ?: "{}"
                            trySend(HealEvent.ToolCall(toolCallId, toolName, args))
                        }
                        "error" -> {
                            val msg = element["error"]?.jsonPrimitive?.content ?: "Stream error"
                            trySend(HealEvent.Error(msg))
                        }
                    }
                } catch (e: Exception) {
                    Log.w("HealNetwork", "Parse fail: ${e.message}")
                }
            }

            override fun onClosed(eventSource: EventSource) {
                Log.d("HealNetwork", "SSE Connection Closed")
                trySend(HealEvent.StreamEnd)
                close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                Log.e("HealNetwork", "SSE Failure", t)
                trySend(HealEvent.Error("Connection Failed: ${t?.message}"))
                close(t)
            }
        }

        val eventSource = EventSources.createFactory(client).newEventSource(request, listener)
        awaitClose { eventSource.cancel() }
    }
}
