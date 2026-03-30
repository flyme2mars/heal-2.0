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
        val request = Request.Builder()
            .url(url)
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .header("Accept", "text/event-stream")
            .header("x-vercel-ai-ui-message-stream", "v1")
            .build()

        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                if (response.code != 200) {
                    val body = try { response.peekBody(1024).string() } catch(e: Exception) { "unavailable" }
                    trySend(HealEvent.Error("Server error ${response.code}"))
                }
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                try {
                    val element = json.parseToJsonElement(data) as? JsonObject ?: return
                    val streamType = element["type"]?.jsonPrimitive?.content ?: return
                    
                    when (streamType) {
                        "text-delta", "reasoning-delta" -> {
                            val content = element["text"]?.jsonPrimitive?.content 
                                ?: element["delta"]?.jsonPrimitive?.content 
                                ?: ""
                            if (content.isNotEmpty()) {
                                if (streamType == "text-delta") trySend(HealEvent.TextDelta(content))
                                else trySend(HealEvent.ReasoningDelta(content))
                            }
                        }
                        "tool-call", "tool-input-available" -> {
                            val toolCallId = element["toolCallId"]?.jsonPrimitive?.content ?: ""
                            val toolName = element["toolName"]?.jsonPrimitive?.content ?: ""
                            val signature = element["providerMetadata"]?.jsonObject
                                ?.get("google")?.jsonObject?.get("thoughtSignature")?.jsonPrimitive?.content
                            
                            val args = element["args"]?.jsonObject?.toString() 
                                ?: element["input"]?.jsonObject?.toString() ?: "{}"
                            trySend(HealEvent.ToolCall(toolCallId, toolName, args + "|||$signature"))
                        }
                        "error" -> {
                            val msg = element["error"]?.jsonPrimitive?.content ?: "Stream error"
                            trySend(HealEvent.Error(msg))
                        }
                    }
                } catch (e: Exception) {
                    Log.e("HealNetwork", "Parse fail", e)
                }
            }

            override fun onClosed(eventSource: EventSource) {
                trySend(HealEvent.StreamEnd)
                close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                trySend(HealEvent.Error("Connection Failed: ${t?.message}"))
                close(t)
            }
        }

        val eventSource = EventSources.createFactory(client).newEventSource(request, listener)
        awaitClose { eventSource.cancel() }
    }
}
