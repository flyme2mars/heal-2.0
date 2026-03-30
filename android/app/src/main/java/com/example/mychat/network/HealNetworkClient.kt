package com.example.mychat.network

import android.content.Context
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
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
    private val client: OkHttpClient,
    private val context: Context // We need context for file logging
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun logToFile(message: String) {
        try {
            val logFile = File(context.filesDir, "network_trace.log")
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            logFile.appendText("[$ts] $message\n")
        } catch (e: Exception) {
            Log.e("HealNetwork", "File logging failed", e)
        }
    }

    fun streamChat(url: String, requestBody: String, appCheckToken: String?): Flow<HealEvent> = callbackFlow {
        logToFile(">>> REQUEST: $requestBody")
        
        val request = Request.Builder()
            .url(url)
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .header("Accept", "text/event-stream")
            .header("x-vercel-ai-ui-message-stream", "v1")
            .build()

        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                logToFile("<<< SSE OPEN: ${response.code}")
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                logToFile("<<< SSE EVENT: $data")
                try {
                    val element = json.parseToJsonElement(data) as? JsonObject ?: return
                    val streamType = element["type"]?.jsonPrimitive?.content ?: return
                    
                    when (streamType) {
                        "text-delta" -> {
                            val delta = element["text"]?.jsonPrimitive?.content 
                                ?: element["delta"]?.jsonPrimitive?.content 
                                ?: ""
                            if (delta.isNotEmpty()) trySend(HealEvent.TextDelta(delta))
                        }
                        "reasoning-delta" -> {
                            val delta = element["text"]?.jsonPrimitive?.content 
                                ?: element["delta"]?.jsonPrimitive?.content 
                                ?: ""
                            if (delta.isNotEmpty()) trySend(HealEvent.ReasoningDelta(delta))
                        }
                        "tool-call", "tool-input-available" -> {
                            val toolCallId = element["toolCallId"]?.jsonPrimitive?.content ?: ""
                            val toolName = element["toolName"]?.jsonPrimitive?.content ?: ""
                            val args = element["args"]?.jsonObject?.toString() 
                                ?: element["input"]?.jsonObject?.toString() 
                                ?: "{}"
                            trySend(HealEvent.ToolCall(toolCallId, toolName, args))
                        }
                        "error" -> {
                            val msg = element["error"]?.jsonPrimitive?.content ?: "Stream error"
                            trySend(HealEvent.Error(msg))
                        }
                    }
                } catch (e: Exception) {
                    logToFile("!!! PARSE ERROR: ${e.message}")
                }
            }

            override fun onClosed(eventSource: EventSource) {
                logToFile("<<< SSE CLOSED")
                trySend(HealEvent.StreamEnd)
                close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val errorBody = try { response?.peekBody(1024)?.string() } catch (e: Exception) { null }
                logToFile("!!! SSE FAILURE: Code=${response?.code}, Msg=${t?.message}, Body=$errorBody")
                trySend(HealEvent.Error("Connection Failed (${response?.code}): ${t?.message}"))
                close(t)
            }
        }

        val eventSource = EventSources.createFactory(client).newEventSource(request, listener)
        awaitClose { eventSource.cancel() }
    }
}
