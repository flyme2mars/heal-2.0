package com.example.mychat.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mychat.data.ChatMessage
import com.example.mychat.data.ChatRole
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState = _uiState.asStateFlow()

    // Modern 2026 Model: Gemini 2.5 Flash using Gemini Developer API
    private val generativeModel = Firebase.ai(backend = GenerativeBackend.googleAI())
        .generativeModel("gemini-2.5-flash")

    private var chat = generativeModel.startChat()

    fun sendMessage(userText: String) {
        if (userText.isBlank()) return

        val userMessage = ChatMessage(text = userText, role = ChatRole.USER)
        _uiState.update { it.copy(messages = it.messages + userMessage) }

        viewModelScope.launch {
            val modelMessage = ChatMessage(text = "", role = ChatRole.MODEL, isPending = true)
            _uiState.update { it.copy(messages = it.messages + modelMessage) }

            try {
                val response = chat.sendMessage(userText)
                _uiState.update { state ->
                    val newList = state.messages.toMutableList()
                    val lastIndex = newList.indexOfLast { it.id == modelMessage.id }
                    if (lastIndex != -1) {
                        newList[lastIndex] = modelMessage.copy(
                            text = response.text ?: "No response",
                            isPending = false
                        )
                    }
                    state.copy(messages = newList)
                }
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "Error sending message", e)
                val errorMessage = when {
                    e is java.net.UnknownHostException || 
                    e.message?.contains("Unable to resolve host", ignoreCase = true) == true -> 
                        "No internet connection. Please check your network."
                    
                    e is java.net.SocketTimeoutException || 
                    e.message?.contains("timeout", ignoreCase = true) == true -> 
                        "Connection timed out. Please try again."
                    
                    e.message?.contains("Something unexpected happened", ignoreCase = true) == true ->
                        "No internet connection or service unavailable. Please check your network."
                        
                    e.message?.contains("API_KEY_INVALID", ignoreCase = true) == true -> 
                        "API Key error. Please check your setup."
                    
                    e.message?.contains("QUOTA_EXCEEDED", ignoreCase = true) == true -> 
                        "Quota exceeded. Please try again later."
                    
                    else -> e.localizedMessage ?: "An unexpected error occurred"
                }
                _uiState.update { state ->
                    val newList = state.messages.toMutableList()
                    val lastIndex = newList.indexOfLast { it.id == modelMessage.id }
                    if (lastIndex != -1) {
                        newList[lastIndex] = modelMessage.copy(
                            text = errorMessage,
                            role = ChatRole.ERROR,
                            isPending = false
                        )
                    }
                    state.copy(messages = newList)
                }
            }
        }
    }

    fun clearChat() {
        _uiState.update { it.copy(messages = emptyList()) }
        chat = generativeModel.startChat()
    }
}

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList()
)
