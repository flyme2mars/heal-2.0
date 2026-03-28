package com.example.mychat.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mychat.data.ChatMessage
import com.example.mychat.data.ChatRole
import com.example.mychat.data.HealthManager
import com.example.mychat.data.MedicalRecordManager
import com.example.mychat.data.DocumentManager
import com.example.mychat.data.HealthDocument
import com.example.mychat.network.HealNetworkClient
import com.example.mychat.network.HealEvent
import com.example.mychat.network.ChatRequest
import com.example.mychat.network.ChatContext
import android.net.Uri
import android.util.Log
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.encodeToString
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val healthManager: HealthManager,
    private val medicalRecordManager: MedicalRecordManager,
    private val documentManager: DocumentManager,
    private val networkClient: HealNetworkClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    
    val uiState: StateFlow<ChatUiState> = combine(
        _uiState,
        documentManager.getAllDocuments()
    ) { state, docs ->
        state.copy(documents = docs.map { 
            HealthDocument(it.id, it.name, it.type, it.internalPath, it.timestamp, it.summary) 
        })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChatUiState())

    init {
        refreshUserData()
    }

    private fun refreshUserData() {
        viewModelScope.launch {
            val name = medicalRecordManager.getPatientName() ?: ""
            val weight = medicalRecordManager.getLatestWeight() ?: ""
            val summary = medicalRecordManager.getMedicalSummary()
            _uiState.update { it.copy(
                userName = name,
                userWeight = weight,
                medicalSummary = summary
            ) }
        }
    }

    val healthPermissions = healthManager.permissions

    fun getHealthSdkStatus() = healthManager.getSdkStatus()
    
    fun getHealthSettingsIntent() = healthManager.getHealthConnectSettingsIntent()

    fun updateProfile(firstName: String, lastName: String, gender: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true) }
            try {
                medicalRecordManager.updatePatient(firstName, lastName, gender)
                refreshUserData()
            } finally {
                _uiState.update { it.copy(isSyncing = false) }
            }
        }
    }

    fun updateWeight(weight: Double) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true) }
            try {
                medicalRecordManager.updateWeight(weight)
                refreshUserData()
            } finally {
                _uiState.update { it.copy(isSyncing = false) }
            }
        }
    }

    fun refreshMedicalSummary() {
        refreshUserData()
    }

    fun uploadDocument(uri: Uri, name: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true) }
            val doc = documentManager.saveDocument(uri, name)
            if (doc != null) {
                // Trigger background summarization
                summarizeDocument(doc)
                
                _uiState.update { it.copy(
                    messages = it.messages + ChatMessage(
                        text = "Document '${name}' uploaded securely. AI is generating a summary...",
                        role = ChatRole.MODEL
                    ),
                    isSyncing = false
                ) }
            } else {
                _uiState.update { it.copy(isSyncing = false) }
            }
        }
    }

    private fun summarizeDocument(doc: HealthDocument) {
        viewModelScope.launch {
            try {
                val placeholderSummary = "Medical record regarding ${doc.name}. Content encrypted in vault."
                documentManager.updateDocumentSummary(doc.id, placeholderSummary)
                refreshUserData()
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Summarization failed", e)
            }
        }
    }

    fun deleteDocument(document: HealthDocument) {
        viewModelScope.launch {
            documentManager.deleteDocument(document)
        }
    }

    private val backendUrl = "https://heal-eight.vercel.app/api/chat"

    fun sendMessage(userText: String) {
        if (userText.isBlank()) return

        val userMessage = ChatMessage(text = userText, role = ChatRole.USER)
        _uiState.update { it.copy(messages = it.messages + userMessage) }

        viewModelScope.launch {
            val modelMessage = ChatMessage(text = "", role = ChatRole.MODEL, isPending = true)
            _uiState.update { it.copy(messages = it.messages + modelMessage) }

            try {
                // 1. Gather all local context
                val healthSummary = if (healthManager.hasAllPermissions()) {
                    healthManager.fetchHealthSummary()
                } else {
                    "Permissions not granted."
                }

                val medicalSummary = medicalRecordManager.getMedicalSummary()
                val memorySnapshot = documentManager.getMemorySnapshot()
                val docSummaries = uiState.value.documents.joinToString("\n") { 
                    "- ${it.name} (${it.type.uppercase()}): ${it.summary ?: "No summary"}"
                }

                // 2. Build the request
                val request = ChatRequest(
                    prompt = userText,
                    context = ChatContext(
                        health_connect = mapOf("summary" to healthSummary),
                        fhir_records = listOf(medicalSummary, "Vault Documents:\n$docSummaries"),
                        memory_snapshot = memorySnapshot
                    )
                )

                val requestBody = Json.encodeToString(request)
                var fullResponseText = ""
                var lastUpdateTime = System.currentTimeMillis()

                // 3. Stream from Backend
                networkClient.streamChat(backendUrl, requestBody, null).collect { event ->
                    when (event) {
                        is HealEvent.TextDelta -> {
                            fullResponseText += event.text
                            val now = System.currentTimeMillis()
                            if (now - lastUpdateTime > 50) {
                                updateMessage(modelMessage.id, fullResponseText, true)
                                lastUpdateTime = now
                            }
                        }
                        is HealEvent.ToolCall -> {
                            Log.d("ChatViewModel", "AI requesting tool: ${event.name} with args: ${event.arguments}")
                            if (event.name == "update_memory") {
                                try {
                                    val json = Json { ignoreUnknownKeys = true }
                                    val argsJson = json.parseToJsonElement(event.arguments) as JsonObject
                                    val filename = argsJson["filename"]?.jsonPrimitive?.content ?: ""
                                    val content = argsJson["content"]?.jsonPrimitive?.content ?: ""
                                    
                                    if (filename.isNotEmpty()) {
                                        documentManager.saveMemoryFile(filename, content)
                                        Log.i("ChatViewModel", "Memory updated: $filename")
                                    }
                                } catch (e: Exception) {
                                    Log.e("ChatViewModel", "Failed to update memory", e)
                                }
                            }
                        }
                        is HealEvent.Error -> {
                            updateMessage(modelMessage.id, "Backend Error: ${event.message}", false, ChatRole.ERROR)
                        }
                        is HealEvent.StreamEnd -> {
                            updateMessage(modelMessage.id, fullResponseText, false)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error connecting to backend", e)
                updateMessage(modelMessage.id, "Could not connect to Heal 2.0 Backend. Ensure it is running on your computer.", false, ChatRole.ERROR)
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
    }
}

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isSyncing: Boolean = false,
    val medicalSummary: String = "No medical records found.",
    val documents: List<HealthDocument> = emptyList(),
    val userName: String = "",
    val userWeight: String = ""
)
