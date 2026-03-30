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
    data class ReasoningDelta(val text: String) : HealEvent()
    data class ToolCall(val id: String, val name: String, val arguments: String) : HealEvent()
    data class Error(val message: String) : HealEvent()
    object StreamEnd : HealEvent()
}

class HealNetworkClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) 
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    fun streamChat(url: String, requestBody: String, appCheckToken: String?): Flow<HealEvent> = callbackFlow {
        Log.d("HealNetwork", ">>> REQUEST BODY: $requestBody")
        Log.d("HealNetwork", "Starting stream to: $url")
        
        val request = Request.Builder()
            .url(url)
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .header("Accept", "text/event-stream")
            .header("User-Agent", "Heal2.0-Android")
            .header("Cache-Control", "no-cache")
            .apply {
                if (appCheckToken != null) {
                    header("X-Firebase-AppCheck", appCheckToken)
                }
            }
            .build()

        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: okhttp3.Response) {
                Log.d("HealNetwork", "SSE Connection Opened. Code: ${response.code}")
                if (response.code != 200) {
                    val errorBody = response.body?.string() ?: "No error body"
                    Log.e("HealNetwork", "SSE Error Body: $errorBody")
                    trySend(HealEvent.Error("Server error ${response.code}: $errorBody"))
                }
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                Log.d("HealNetwork", "Event: $data")
                try {
                    if (data.length > 2 && data[1] == ':') {
                        val typeChar = data[0]
                        val payload = data.substring(2)
                        
                        if (typeChar == '0') {
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

                        if (typeChar == 'r') {
                            val cleanText = if (payload.startsWith("\"") && payload.endsWith("\"")) {
                                payload.substring(1, payload.length - 1)
                                    .replace("\\n", "\n")
                                    .replace("\\\"", "\"")
                            } else {
                                payload
                            }
                            trySend(HealEvent.ReasoningDelta(cleanText))
                            return
                        }

                        if (typeChar == '9') {
                            val toolJson = json.parseToJsonElement(payload) as JsonObject
                            val toolName = toolJson["toolName"]?.jsonPrimitive?.content ?: ""
                            val args = toolJson["args"]?.toString() ?: ""
                            val callId = toolJson["toolCallId"]?.jsonPrimitive?.content ?: ""
                            trySend(HealEvent.ToolCall(callId, toolName, args))
                            return
                        }
                    }
                    
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
                val errorBody = try { response?.peekBody(1024)?.string() } catch (e: Exception) { null }
                Log.e("HealNetwork", "SSE Connection Failed. Code: ${response?.code}, Message: ${t?.message}, Body: $errorBody", t)
                trySend(HealEvent.Error("Connection Failed (${response?.code}): ${t?.message}. Body: $errorBody"))
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
