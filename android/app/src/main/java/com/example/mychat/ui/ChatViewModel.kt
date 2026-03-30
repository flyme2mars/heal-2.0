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
    private val localIndexer: LocalIndexer,
    private val networkClient: HealNetworkClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    
    val uiState: StateFlow<ChatUiState> = combine(
        _uiState,
        documentManager.getAllDocuments()
    ) { state: ChatUiState, docs: List<HealthDocument> ->
        state.copy(documents = docs)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChatUiState())

    init {
        refreshUserData()
        checkHealthPermissions()
    }

    private fun checkHealthPermissions() {
        viewModelScope.launch {
            val granted = healthManager.hasAllPermissions()
            _uiState.update { it.copy(healthPermissionGranted = granted) }
        }
    }

    fun onHealthPermissionResult() {
        checkHealthPermissions()
    }

    fun syncHealthData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true) }
            try {
                if (healthManager.hasAllPermissions()) {
                    val summary = healthManager.fetchHealthSummary()
                    _uiState.update { it.copy(healthPermissionGranted = true) }
                    // We can also trigger a refresh of other data if needed
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Health sync failed", e)
            } finally {
                _uiState.update { it.copy(isSyncing = false) }
            }
        }
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
        val userMessage = ChatMessage(
            text = userText, 
            role = ChatRole.USER,
            imageUri = imageUri?.toString()
        )
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
                
                // Agent Context: Only sending Metadata "Map"
                val docMap = uiState.value.documents.joinToString("\n") { 
                    "- ID: ${it.id} | Label: ${it.userLabel ?: it.name} | Type: ${it.recordType ?: "Unknown"} | Tags: ${it.tags.joinToString(", ")} | Date: ${it.recordDate ?: "No Date"} | AI Summary: ${it.summary}"
                }

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
                        fhir_records = listOf(medicalSummary, "LOCAL VAULT INDEX:\n$docMap"),
                        memory_snapshot = memorySnapshot
                    ),
                    attachments = attachments
                )

                val backendUrl = "https://heal-eight.vercel.app/api/chat"
                val json = Json { ignoreUnknownKeys = true }
                val requestBody = json.encodeToString(request)
                var fullResponseText = ""
                var fullReasoningText = ""
                var lastUpdateTime = System.currentTimeMillis()

                networkClient.streamChat(backendUrl, requestBody, null).collect { event ->
                    when (event) {
                        is HealEvent.TextDelta -> {
                            fullResponseText += event.text
                            if (System.currentTimeMillis() - lastUpdateTime > 50) {
                                updateMessage(modelMessage.id, fullResponseText, true, reasoning = fullReasoningText)
                                lastUpdateTime = System.currentTimeMillis()
                            }
                        }
                        is HealEvent.ReasoningDelta -> {
                            fullReasoningText += event.text
                            if (System.currentTimeMillis() - lastUpdateTime > 50) {
                                updateMessage(modelMessage.id, fullResponseText, true, reasoning = fullReasoningText)
                                lastUpdateTime = System.currentTimeMillis()
                            }
                        }
                        is HealEvent.ToolCall -> {
                            handleToolCall(event, modelMessage.id)
                        }
                        is HealEvent.Error -> updateMessage(modelMessage.id, "Error: ${event.message}", false, ChatRole.ERROR)
                        is HealEvent.StreamEnd -> updateMessage(modelMessage.id, fullResponseText, false, reasoning = fullReasoningText)
                    }
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Request failed", e)
                updateMessage(modelMessage.id, "Failed to connect to Heal Agent.", false, ChatRole.ERROR)
            }
        }
    }

    private fun handleToolCall(event: HealEvent.ToolCall, messageId: String) {
        val json = Json { ignoreUnknownKeys = true }
        when (event.name) {
            "update_memory" -> {
                try {
                    val argsJson = json.parseToJsonElement(event.arguments) as JsonObject
                    val filename = argsJson["filename"]?.jsonPrimitive?.content ?: ""
                    val content = argsJson["content"]?.jsonPrimitive?.content ?: ""
                    if (filename.isNotEmpty()) {
                        documentManager.saveMemoryFile(filename, content)
                    }
                } catch (e: Exception) { Log.e("ChatViewModel", "Memory Update failed", e) }
            }
            "request_medical_record" -> {
                try {
                    val argsJson = json.parseToJsonElement(event.arguments) as JsonObject
                    val id = argsJson["id"]?.jsonPrimitive?.content ?: ""
                    val reason = argsJson["reason"]?.jsonPrimitive?.content ?: "Needed for analysis"
                    
                    val document = uiState.value.documents.find { it.id == id }
                    if (document != null) {
                        _uiState.update { it.copy(
                            pendingApprovals = it.pendingApprovals + PermissionRequest(
                                id = id,
                                documentName = document.name,
                                reason = reason
                            )
                        ) }
                    }
                } catch (e: Exception) { Log.e("ChatViewModel", "Record Request failed", e) }
            }
        }
    }

    fun approveRecord(requestId: String) {
        val request = uiState.value.pendingApprovals.find { it.id == requestId } ?: return
        _uiState.update { it.copy(pendingApprovals = it.pendingApprovals.filter { r -> r.id != requestId }) }
        
        viewModelScope.launch {
            val doc = uiState.value.documents.find { it.id == requestId } ?: return@launch
            val fullText = doc.fullText ?: documentManager.readDocumentText(doc.name)
            
            // Re-sending with Full Context as a "System Context" injection
            sendMessage("System Context: User approved access to '${doc.name}'. Full content is:\n$fullText")
        }
    }

    fun rejectRecord(requestId: String) {
        _uiState.update { it.copy(pendingApprovals = it.pendingApprovals.filter { r -> r.id != requestId }) }
        // Potentially inform the model that access was denied
    }

    private fun updateMessage(id: String, text: String, isPending: Boolean, role: ChatRole? = null, reasoning: String? = null) {
        _uiState.update { state ->
            val newList = state.messages.toMutableList()
            val index = newList.indexOfLast { it.id == id }
            if (index != -1) {
                newList[index] = newList[index].copy(
                    text = text, 
                    isPending = isPending, 
                    role = role ?: newList[index].role,
                    reasoning = reasoning ?: newList[index].reasoning
                )
            }
            state.copy(messages = newList)
        }
    }

    fun uploadDocument(uri: Uri, name: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true) }
            val doc = documentManager.saveDocument(uri, name)
            if (doc != null) {
                localIndexer.indexDocument(doc)
            }
            _uiState.update { it.copy(isSyncing = false) }
        }
    }

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

    fun refreshMedicalSummary() = refreshUserData()

    fun deleteDocument(document: HealthDocument) { viewModelScope.launch { documentManager.deleteDocument(document) } }
    
    fun updateDocumentLabel(documentId: String, newLabel: String) {
        viewModelScope.launch {
            documentManager.updateUserLabel(documentId, newLabel)
        }
    }

    fun clearChat() { _uiState.update { it.copy(messages = emptyList()) } }
}

data class PermissionRequest(
    val id: String,
    val documentName: String,
    val reason: String
)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isSyncing: Boolean = false,
    val healthPermissionGranted: Boolean = false,
    val medicalSummary: String = "No medical records found.",
    val documents: List<HealthDocument> = emptyList(),
    val pendingApprovals: List<PermissionRequest> = emptyList(),
    val userName: String = "",
    val userWeight: String = "",
    val selectedImageUri: Uri? = null
)

