package com.example.mychat.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mychat.data.ChatMessage
import com.example.mychat.data.ChatRole
import com.example.mychat.data.HealthManager
import com.example.mychat.data.MedicalRecordManager
import com.example.mychat.data.DocumentManager
import com.example.mychat.data.HealthDocument
import com.example.mychat.data.HealthDocumentEntity
import android.net.Uri
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.content
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val healthManager: HealthManager,
    private val medicalRecordManager: MedicalRecordManager,
    private val documentManager: DocumentManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    
    val uiState: StateFlow<ChatUiState> = combine(
        _uiState,
        documentManager.getAllDocuments()
    ) { state, docs ->
        state.copy(documents = docs.map { 
            HealthDocument(it.id, it.name, it.type, it.internalPath, it.timestamp) 
        })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChatUiState())

    val healthPermissions = healthManager.permissions

    fun getHealthSdkStatus() = healthManager.getSdkStatus()
    
    fun getHealthSettingsIntent() = healthManager.getHealthConnectSettingsIntent()

    fun uploadDocument(uri: Uri, name: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true) }
            val doc = documentManager.saveDocument(uri, name)
            if (doc != null) {
                _uiState.update { it.copy(
                    messages = it.messages + ChatMessage(
                        text = "Document '${name}' uploaded securely to vault.",
                        role = ChatRole.MODEL
                    ),
                    isSyncing = false
                ) }
            } else {
                _uiState.update { it.copy(isSyncing = false) }
            }
        }
    }

    fun deleteDocument(document: HealthDocument) {
        viewModelScope.launch {
            documentManager.deleteDocument(document)
        }
    }

    fun loadSampleMedicalData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true) }
            try {
                medicalRecordManager.saveSamplePatient()
                medicalRecordManager.saveSampleVitals()
                refreshMedicalSummary()
                _uiState.update { it.copy(
                    messages = it.messages + ChatMessage(
                        text = "Sample ABDM medical records loaded into local secure vault.",
                        role = ChatRole.MODEL
                    ),
                    isSyncing = false
                ) }
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "Failed to load samples", e)
                _uiState.update { it.copy(isSyncing = false) }
            }
        }
    }

    fun refreshMedicalSummary() {
        viewModelScope.launch {
            val summary = medicalRecordManager.getMedicalSummary()
            _uiState.update { it.copy(medicalSummary = summary) }
        }
    }

    // Modern 2026 Model: Remini 2.5 Flash using Remini Developer API
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
                // Fetch health data to provide context
                val healthSummary = if (healthManager.hasAllPermissions()) {
                    healthManager.fetchHealthSummary()
                } else {
                    ""
                }

                val medicalSummary = medicalRecordManager.getMedicalSummary()

                val promptContent = content {
                    // 1. Add all PDFs from the vault as context
                    uiState.value.documents.filter { it.type == "pdf" }.forEach { doc ->
                        try {
                            val inputStream = documentManager.getDocumentDecryptStream(doc)
                            val bytes = inputStream.readBytes()
                            inputStream.close()
                            inlineData(bytes, "application/pdf")
                        } catch (e: Exception) {
                            android.util.Log.e("ChatViewModel", "Failed to attach PDF: ${doc.name}", e)
                        }
                    }

                    // 2. Add Health Connect and ABDM text context
                    if (healthSummary.isNotEmpty()) text("Health Connect Context:\n$healthSummary\n\n")
                    text("ABDM Medical Records Context:\n$medicalSummary\n\n")
                    
                    // 3. Add the user prompt
                    text(userText)
                }

                var fullResponseText = ""
                var lastUpdateTime = System.currentTimeMillis()
                
                chat.sendMessageStream(promptContent).collect { chunk ->
                    fullResponseText += chunk.text ?: ""
                    
                    val now = System.currentTimeMillis()
                    if (now - lastUpdateTime > 50) { // Update UI every 50ms
                        updateMessage(modelMessage.id, fullResponseText, true)
                        lastUpdateTime = now
                    }
                }
                // Final update to ensure everything is caught and snap to Markdown
                updateMessage(modelMessage.id, fullResponseText, false)
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
                updateMessage(modelMessage.id, errorMessage, false, ChatRole.ERROR)
            }
        }
    }

    private fun updateMessage(id: String, text: String, isPending: Boolean, role: ChatRole? = null) {
        _uiState.update { state ->
            val newList = state.messages.toMutableList()
            val lastIndex = newList.indexOfLast { it.id == id }
            if (lastIndex != -1) {
                newList[lastIndex] = newList[lastIndex].copy(
                    text = text,
                    isPending = isPending,
                    role = role ?: newList[lastIndex].role
                )
            }
            state.copy(messages = newList)
        }
    }

    fun clearChat() {
        _uiState.update { it.copy(messages = emptyList()) }
        chat = generativeModel.startChat()
    }
}

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isSyncing: Boolean = false,
    val medicalSummary: String = "No medical records found.",
    val documents: List<HealthDocument> = emptyList()
)
