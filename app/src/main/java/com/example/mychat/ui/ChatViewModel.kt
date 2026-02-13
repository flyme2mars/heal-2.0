package com.example.mychat.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mychat.data.ChatMessage
import com.example.mychat.data.ChatRole
import com.google.firebase.Firebase
import com.google.firebase.vertexai.vertexAI
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

    // Modern 2026 Model: Gemini 2.5 Flash
    private val generativeModel = Firebase.vertexAI.generativeModel("gemini-2.5-flash")

    private val chat = generativeModel.startChat()

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
                _uiState.update { state ->
                    val newList = state.messages.toMutableList()
                    val lastIndex = newList.indexOfLast { it.id == modelMessage.id }
                    if (lastIndex != -1) {
                        newList[lastIndex] = modelMessage.copy(
                            text = e.localizedMessage ?: "Error occurred",
                            role = ChatRole.ERROR,
                            isPending = false
                        )
                    }
                    state.copy(messages = newList)
                }
            }
        }
    }
}

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList()
)
