package com.example.mychat.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mychat.data.*
import com.example.mychat.network.*
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.content.Context
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import javax.inject.Inject
import java.io.ByteArrayOutputStream

@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
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

    fun selectImage(uri: Uri?) {
        _uiState.update { it.copy(selectedImageUri = uri) }
    }

    private fun uriToBase64(uri: Uri): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val bytes = inputStream?.readBytes()
            inputStream?.close()
            if (bytes != null) {
                "data:image/jpeg;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
            } else null
        } catch (e: Exception) {
            Log.e("ChatViewModel", "Base64 conversion failed", e)
            null
        }
    }

    fun sendMessage(userText: String) {
        if (userText.isBlank() && _uiState.value.selectedImageUri == null) return

        val imageUri = _uiState.value.selectedImageUri
        val userMessage = ChatMessage(text = userText, role = ChatRole.USER)
        _uiState.update { it.copy(
            messages = it.messages + userMessage,
            selectedImageUri = null
        ) }

        viewModelScope.launch {
            val modelMessage = ChatMessage(text = "", role = ChatRole.MODEL, isPending = true)
            _uiState.update { it.copy(messages = it.messages + modelMessage) }

            try {
                val healthSummary = if (healthManager.hasAllPermissions()) healthManager.fetchHealthSummary() else "Permissions not granted."
                val medicalSummary = medicalRecordManager.getMedicalSummary()
                val memorySnapshot = documentManager.getMemorySnapshot()
                val docSummaries = uiState.value.documents.joinToString("\n") { "- ${it.name}: ${it.summary}" }

                val attachments = mutableListOf<ChatAttachment>()
                imageUri?.let { uri ->
                    uriToBase64(uri)?.let { base64 ->
                        attachments.add(ChatAttachment(url = base64))
                    }
                }

                val request = ChatRequest(
                    prompt = userText,
                    context = ChatContext(
                        health_connect = mapOf("summary" to healthSummary),
                        fhir_records = listOf(medicalSummary, "Vault Documents:\n$docSummaries"),
                        memory_snapshot = memorySnapshot
                    ),
                    attachments = attachments
                )

                val backendUrl = "https://heal-eight.vercel.app/api/chat"
                val json = Json { ignoreUnknownKeys = true }
                val requestBody = json.encodeToString(request)
                var fullResponseText = ""
                var lastUpdateTime = System.currentTimeMillis()

                networkClient.streamChat(backendUrl, requestBody, null).collect { event ->
                    when (event) {
                        is HealEvent.TextDelta -> {
                            fullResponseText += event.text
                            if (System.currentTimeMillis() - lastUpdateTime > 50) {
                                updateMessage(modelMessage.id, fullResponseText, true)
                                lastUpdateTime = System.currentTimeMillis()
                            }
                        }
                        is HealEvent.ToolCall -> {
                            if (event.name == "update_memory") {
                                try {
                                    val argsJson = json.parseToJsonElement(event.arguments) as JsonObject
                                    val filename = argsJson["filename"]?.jsonPrimitive?.content ?: ""
                                    val content = argsJson["content"]?.jsonPrimitive?.content ?: ""
                                    if (filename.isNotEmpty()) {
                                        documentManager.saveMemoryFile(filename, content)
                                    }
                                } catch (e: Exception) { Log.e("ChatViewModel", "Memory Update failed", e) }
                            } else if (event.name == "read_medical_record") {
                                try {
                                    val argsJson = json.parseToJsonElement(event.arguments) as JsonObject
                                    val filename = argsJson["filename"]?.jsonPrimitive?.content ?: ""
                                    if (filename.isNotEmpty()) {
                                        val content = documentManager.readDocumentText(filename)
                                        sendMessage("System Context: Content of '$filename' is:\n$content")
                                    }
                                } catch (e: Exception) { Log.e("ChatViewModel", "Read failed", e) }
                            }
                        }
                        is HealEvent.Error -> updateMessage(modelMessage.id, "Connection Error: ${event.message}", false, ChatRole.ERROR)
                        is HealEvent.StreamEnd -> updateMessage(modelMessage.id, fullResponseText, false)
                    }
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Request failed", e)
                updateMessage(modelMessage.id, "Failed to connect to Heal 2.0 Backend.", false, ChatRole.ERROR)
            }
        }
    }

    private fun updateMessage(id: String, text: String, isPending: Boolean, role: ChatRole? = null) {
        _uiState.update { state ->
            val newList = state.messages.toMutableList()
            val index = newList.indexOfLast { it.id == id }
            if (index != -1) {
                newList[index] = newList[index].copy(text = text, isPending = isPending, role = role ?: newList[index].role)
            }
            state.copy(messages = newList)
        }
    }

    fun refreshMedicalSummary() = refreshUserData()
    fun clearChat() { _uiState.update { it.copy(messages = emptyList()) } }
    fun uploadDocument(uri: Uri, name: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true) }
            val doc = documentManager.saveDocument(uri, name)
            if (doc != null) {
                summarizeDocument(doc)
                refreshUserData()
            }
            _uiState.update { it.copy(isSyncing = false) }
        }
    }
    private fun summarizeDocument(doc: HealthDocument) {
        viewModelScope.launch {
            documentManager.updateDocumentSummary(doc.id, "Medical record regarding ${doc.name}.")
            refreshUserData()
        }
    }
    fun deleteDocument(document: HealthDocument) { viewModelScope.launch { documentManager.deleteDocument(document) } }
}

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isSyncing: Boolean = false,
    val medicalSummary: String = "No medical records found.",
    val documents: List<HealthDocument> = emptyList(),
    val userName: String = "",
    val userWeight: String = "",
    val selectedImageUri: Uri? = null
)
