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
    private val networkClient: HealNetworkClient,
    private val chatDao: ChatDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    
    val uiState: StateFlow<ChatUiState> = combine(
        _uiState,
        documentManager.getAllDocuments(),
        chatDao.getAllSessions()
    ) { state: ChatUiState, docs: List<HealthDocument>, sessions: List<ChatSessionEntity> ->
        state.copy(
            documents = docs,
            sessions = sessions
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChatUiState())

    init {
        refreshUserData()
        checkHealthPermissions()
        viewModelScope.launch {
            val recovered = documentManager.reconcileVault()
            recovered.forEach { doc ->
                localIndexer.indexDocument(doc)
            }
            initializeSession()
        }
    }

    private suspend fun initializeSession() {
        val sessions = chatDao.getAllSessions().firstOrNull()
        if (sessions.isNullOrEmpty()) {
            createNewSession()
        } else {
            loadSession(sessions.first().id)
        }
    }

    fun createNewSession() {
        viewModelScope.launch {
            val sessionId = java.util.UUID.randomUUID().toString()
            val newSession = ChatSessionEntity(
                id = sessionId,
                title = "New Health Chat",
                createdAt = System.currentTimeMillis(),
                lastUpdatedAt = System.currentTimeMillis()
            )
            chatDao.insertSession(newSession)
            loadSession(sessionId)
        }
    }

    fun loadSession(sessionId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(activeSessionId = sessionId) }
            val messages = chatDao.getMessagesForSessionList(sessionId).map { entity ->
                ChatMessage(
                    id = entity.id.toString(),
                    text = entity.content,
                    role = ChatRole.valueOf(entity.role.uppercase()),
                    timestamp = entity.timestamp,
                    reasoning = entity.reasoning,
                    toolCallId = entity.toolCallId,
                    toolCalls = entity.toolCallsJson
                )
            }.filter { it.role != ChatRole.TOOL && it.role != ChatRole.SYSTEM } // Hide internal data from UI but keep in DB
            _uiState.update { it.copy(messages = messages) }
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            chatDao.deleteSession(sessionId)
            if (_uiState.value.activeSessionId == sessionId) {
                initializeSession()
            }
        }
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

    fun sendMessage(userText: String, isHiddenData: Boolean = false, toolCallId: String? = null) {
        if (userText.isBlank() && _uiState.value.selectedImageUri == null) return

        val sessionId = _uiState.value.activeSessionId ?: return
        val imageUri = _uiState.value.selectedImageUri
        
        // UI: Only add to the list if not hidden
        if (!isHiddenData) {
            val userMessage = ChatMessage(
                text = userText, 
                role = ChatRole.USER,
                imageUri = imageUri?.toString()
            )
            _uiState.update { it.copy(
                messages = it.messages + userMessage,
                selectedImageUri = null
            ) }
        }

        viewModelScope.launch {
            // Persist message with correct role
            val dbRole = when {
                toolCallId != null -> "tool"
                isHiddenData -> "system"
                else -> "user"
            }
            
            chatDao.insertMessage(ChatMessageEntity(
                sessionId = sessionId,
                role = dbRole,
                content = userText,
                timestamp = System.currentTimeMillis(),
                toolCallId = toolCallId
            ))

            val modelMessage = ChatMessage(text = "", role = ChatRole.MODEL, isPending = true)
            // UI: Don't add a new model message if this is a follow-up to a tool approval
            if (!isHiddenData) {
                _uiState.update { it.copy(messages = it.messages + modelMessage) }
            } else {
                // If it's a follow-up, mark the LAST model message as pending again
                _uiState.update { state ->
                    val newList = state.messages.toMutableList()
                    val lastModel = newList.findLast { it.role == ChatRole.MODEL || it.role == ChatRole.ASSISTANT }
                    if (lastModel != null) {
                        val index = newList.indexOf(lastModel)
                        newList[index] = lastModel.copy(isPending = true, isActionResolved = true, pendingToolCall = null)
                    }
                    state.copy(messages = newList)
                }
            }

            try {
                // Determine target message ID for updates
                val targetId = if (isHiddenData) {
                   _uiState.value.messages.findLast { it.role == ChatRole.MODEL || it.role == ChatRole.ASSISTANT }?.id ?: modelMessage.id
                } else modelMessage.id

                val healthSummary = if (healthManager.hasAllPermissions()) healthManager.fetchHealthSummary() else "Permissions not granted."
                val medicalSummary = medicalRecordManager.getMedicalSummary()
                val memorySnapshot = documentManager.getMemorySnapshot()
                
                // 2026 History Construction: Include Tool Metadata
                val history = chatDao.getMessagesForSessionList(sessionId).takeLast(20).map { 
                    val map = mutableMapOf(
                        "role" to it.role,
                        "content" to it.content
                    )
                    it.toolCallId?.let { id -> map["toolCallId"] = id }
                    it.toolCallsJson?.let { json -> map["toolCalls"] = json }
                    map.toMap()
                }

                // Discovery Index
                val docMap = uiState.value.documents.joinToString("\n") { 
                    "DOCUMENT [ID: ${it.id}] | Label: ${it.userLabel ?: it.name} | Type: ${it.recordType ?: "Unknown"} | Tags: ${it.tags.joinToString(", ")} | Date: ${it.recordDate ?: "No Date"} | AI Summary: ${it.summary}"
                }

                val attachments = mutableListOf<ChatAttachment>()
                imageUri?.let { uri ->
                    uriToBase64(uri)?.let { base64 ->
                        attachments.add(ChatAttachment(url = base64))
                    }
                }

                val request = ChatRequest(
                    prompt = userText,
                    history = history,
                    context = ChatContext(
                        health_connect = mapOf("summary" to healthSummary),
                        fhir_records = listOf(medicalSummary, "LOCAL VAULT INDEX:\n$docMap"),
                        memory_snapshot = memorySnapshot
                    ),
                    attachments = attachments,
                    toolCallId = toolCallId
                )

                val backendUrl = "https://heal-eight.vercel.app/api/chat"
                val json = Json { ignoreUnknownKeys = true }
                val requestBody = json.encodeToString(request)
                
                var currentMessageState = _uiState.value.messages.find { it.id == targetId }
                var fullResponseText = if (isHiddenData) currentMessageState?.text ?: "" else ""
                var fullReasoningText = if (isHiddenData) currentMessageState?.reasoning ?: "" else ""
                var lastUpdateTime = System.currentTimeMillis()
                
                // Track tool calls for final persistence
                val currentToolCalls = mutableListOf<ToolCallInfo>()

                networkClient.streamChat(backendUrl, requestBody, null).collect { event ->
                    when (event) {
                        is HealEvent.TextDelta -> {
                            fullResponseText += event.text
                            if (System.currentTimeMillis() - lastUpdateTime > 50) {
                                updateMessage(targetId, fullResponseText, true, reasoning = fullReasoningText)
                                lastUpdateTime = System.currentTimeMillis()
                            }
                        }
                        is HealEvent.ReasoningDelta -> {
                            fullReasoningText += event.text
                            if (System.currentTimeMillis() - lastUpdateTime > 50) {
                                updateMessage(targetId, fullResponseText, true, reasoning = fullReasoningText)
                                lastUpdateTime = System.currentTimeMillis()
                            }
                        }
                        is HealEvent.ToolCall -> {
                            val info = ToolCallInfo(event.id, event.name, event.arguments)
                            currentToolCalls.add(info)
                            handleToolCall(event, targetId)
                        }
                        is HealEvent.Error -> updateMessage(targetId, "Error: ${event.message}", false, ChatRole.ERROR)
                        is HealEvent.StreamEnd -> {
                            updateMessage(targetId, fullResponseText, false, reasoning = fullReasoningText)
                            
                            // Persist Assistant Message WITH tool calls if any
                            val toolCallsJson = if (currentToolCalls.isNotEmpty()) {
                                json.encodeToString(currentToolCalls)
                            } else null

                            chatDao.insertMessage(ChatMessageEntity(
                                sessionId = sessionId,
                                role = "assistant",
                                content = fullResponseText,
                                reasoning = fullReasoningText,
                                timestamp = System.currentTimeMillis(),
                                toolCallsJson = toolCallsJson
                            ))
                            
                            if (!isHiddenData && uiState.value.messages.size <= 3) {
                                val newTitle = if (userText.length > 30) userText.take(27) + "..." else userText
                                chatDao.updateSessionTitle(sessionId, newTitle, System.currentTimeMillis())
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Request failed", e)
                updateMessage(modelMessage.id, "Failed to connect to Heal Agent.", false, ChatRole.ERROR)
            }
        }
    }

    private fun handleToolCall(event: HealEvent.ToolCall, messageId: String) {
        val info = ToolCallInfo(
            toolCallId = event.id,
            name = event.name,
            arguments = event.arguments
        )
        
        _uiState.update { state ->
            val newList = state.messages.toMutableList()
            val index = newList.indexOfLast { it.id == messageId }
            if (index != -1) {
                newList[index] = newList[index].copy(
                    isPending = false,
                    pendingToolCall = info,
                    toolCallId = event.id // Store active call ID
                )
            }
            state.copy(messages = newList)
        }
    }

    fun approveRecord(requestId: String, messageId: String) {
        Log.d("ChatViewModel", "approveRecord called for docId: $requestId, messageId: $messageId")
        viewModelScope.launch {
            val doc = uiState.value.documents.find { it.id == requestId }
            if (doc == null) {
                Log.e("ChatViewModel", "Could not find document with ID: $requestId in ${uiState.value.documents.size} docs")
                return@launch
            }
            
            val fullText = doc.fullText ?: documentManager.readDocumentText(doc.name)
            Log.d("ChatViewModel", "Document text loaded (length: ${fullText.length}). Sending hidden data...")
            
            val activeMessage = _uiState.value.messages.find { it.id == messageId }
            val callId = activeMessage?.pendingToolCall?.toolCallId
            Log.d("ChatViewModel", "Using toolCallId: $callId for follow-up")
            
            // Send as "Tool Result" - continues the session
            sendMessage(
                userText = fullText, 
                isHiddenData = true, 
                toolCallId = callId
            )
        }
    }

    fun rejectRecord(messageId: String) {
        _uiState.update { state ->
            val newList = state.messages.toMutableList()
            val index = newList.indexOfLast { it.id == messageId }
            if (index != -1) {
                newList[index] = newList[index].copy(pendingToolCall = null, isActionResolved = true)
            }
            state.copy(messages = newList)
        }
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

    fun refreshMedicalSummary() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true) }
            val recovered = documentManager.reconcileVault()
            recovered.forEach { doc ->
                localIndexer.indexDocument(doc)
            }
            refreshUserData()
            _uiState.update { it.copy(isSyncing = false) }
        }
    }

    fun deleteDocument(document: HealthDocument) { viewModelScope.launch { documentManager.deleteDocument(document) } }
    
    fun updateDocumentLabel(documentId: String, newLabel: String) {
        viewModelScope.launch {
            documentManager.updateUserLabel(documentId, newLabel)
        }
    }
}

data class PermissionRequest(
    val id: String,
    val documentName: String,
    val reason: String
)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val sessions: List<ChatSessionEntity> = emptyList(),
    val activeSessionId: String? = null,
    val isSyncing: Boolean = false,
    val healthPermissionGranted: Boolean = false,
    val medicalSummary: String = "No medical records found.",
    val documents: List<HealthDocument> = emptyList(),
    val pendingApprovals: List<PermissionRequest> = emptyList(),
    val userName: String = "",
    val userWeight: String = "",
    val selectedImageUri: Uri? = null
)

