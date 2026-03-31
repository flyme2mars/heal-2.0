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
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date

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
        chatDao.getAllNonEmptySessions()
    ) { state: ChatUiState, docs: List<HealthDocument>, sessions: List<ChatSessionEntity> ->
        // Handle edge case where active session is not in the list of non-empty sessions
        // This is fine for the drawer list, but we should always show the current session's data
        state.copy(
            documents = docs,
            sessions = sessions
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChatUiState())

    init {
        refreshUserData()
        checkHealthPermissions()
        viewModelScope.launch {
            documentManager.reconcileVault()
            initializeSession()
        }
    }

    private suspend fun initializeSession() {
        val lastActiveId = _uiState.value.activeSessionId
        val sessions = chatDao.getMessagesForSessionList(lastActiveId ?: "").isNotEmpty()
        if (lastActiveId == null || !sessions) {
            val all = chatDao.getMessagesForSessionList("").isNotEmpty() // This is just a check
            // If we have an existing non-empty session, load it
            // Else just stay with the empty current one or create one
            createNewSession()
        }
    }

    fun createNewSession() {
        viewModelScope.launch {
            val currentId = _uiState.value.activeSessionId
            if (currentId != null) {
                val count = chatDao.getMessageCount(currentId)
                if (count == 0) {
                    // Current session is already empty, just keep using it
                    return@launch
                }
            }

            // Cleanup any other empty sessions hanging around
            chatDao.deleteEmptySessions(currentId ?: "")

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
            }.filter { it.role != ChatRole.TOOL && it.role != ChatRole.SYSTEM }
            _uiState.update { it.copy(messages = messages) }
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            chatDao.deleteSession(sessionId)
            val currentId = _uiState.value.activeSessionId
            if (currentId == sessionId) {
                // If we deleted the active one, we need to pick a new one or create a fresh one
                val remaining = chatDao.getMessagesForSessionList("").isNotEmpty() // Logic placeholder
                // Easiest is to just call createNewSession which will find or make one
                _uiState.update { it.copy(activeSessionId = null) }
                createNewSession()
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
                    healthManager.fetchHealthSummary()
                    _uiState.update { it.copy(healthPermissionGranted = true) }
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
            val dbRole = when {
                toolCallId != null -> "tool"
                isHiddenData -> "system"
                else -> "user"
            }
            
            val messageToPersist = ChatMessageEntity(
                sessionId = sessionId,
                role = dbRole,
                content = userText,
                timestamp = System.currentTimeMillis(),
                toolCallId = toolCallId
            )
            val currentMessageDbId = chatDao.insertMessage(messageToPersist)

            // Dynamic Naming: If this is the first real user message, update the session title
            if (!isHiddenData && chatDao.getMessageCount(sessionId) == 1) {
                val title = if (userText.length > 30) userText.take(27) + "..." else userText
                chatDao.updateSessionTitle(sessionId, title, System.currentTimeMillis())
            }

            val modelMessageId = java.util.UUID.randomUUID().toString()
            val targetId: String

            if (!isHiddenData) {
                val modelMessage = ChatMessage(id = modelMessageId, text = "", role = ChatRole.MODEL, isPending = true)
                _uiState.update { it.copy(messages = it.messages + modelMessage) }
                targetId = modelMessageId
            } else {
                val lastModel = _uiState.value.messages.findLast { it.role == ChatRole.MODEL || it.role == ChatRole.ASSISTANT }
                if (lastModel != null) {
                    targetId = lastModel.id
                    _uiState.update { state ->
                        val newList = state.messages.toMutableList()
                        val idx = newList.indexOfLast { it.id == targetId }
                        if (idx != -1) {
                            newList[idx] = newList[idx].copy(isPending = true, isActionResolved = true, pendingToolCall = null)
                        }
                        state.copy(messages = newList)
                    }
                } else {
                    val modelMessage = ChatMessage(id = modelMessageId, text = "", role = ChatRole.MODEL, isPending = true)
                    _uiState.update { it.copy(messages = it.messages + modelMessage) }
                    targetId = modelMessageId
                }
            }

            try {
                val healthSummary = if (healthManager.hasAllPermissions()) healthManager.fetchHealthSummary() else "Permissions not granted."
                val medicalSummary = medicalRecordManager.getMedicalSummary()
                val memorySnapshot = documentManager.getMemorySnapshot()
                
                val allHistory = chatDao.getMessagesForSessionList(sessionId)
                val history = allHistory.filter { it.id != currentMessageDbId }.takeLast(20).map { 
                    val map = mutableMapOf("role" to it.role, "content" to it.content)
                    if (it.role.lowercase() == "assistant" && !it.toolCallsJson.isNullOrBlank()) {
                        map["toolCalls"] = it.toolCallsJson
                    }
                    if (it.role.lowercase() == "tool" && !it.toolCallId.isNullOrBlank()) {
                        map["toolCallId"] = it.toolCallId
                    }
                    map.toMap()
                }

                val docMap = uiState.value.documents.joinToString("\n") { 
                    "DOCUMENT [ID: ${it.id}] | Label: ${it.userLabel ?: it.name} | Type: ${it.recordType ?: "Unknown"} | Tags: ${it.tags.joinToString(", ")} | Date: ${it.recordDate ?: "No Date"} | AI Summary: ${it.summary}"
                }

                val attachments = mutableListOf<ChatAttachment>()
                imageUri?.let { uri -> uriToBase64(uri)?.let { base64 -> attachments.add(ChatAttachment(url = base64)) } }

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
                val currentToolCalls = mutableListOf<ToolCallInfo>()

                networkClient.streamChat(backendUrl, requestBody, null).collect { event ->
                    when (event) {
                        is HealEvent.TextDelta -> appendToMessage(targetId, event.text, true)
                        is HealEvent.ReasoningDelta -> appendToMessage(targetId, null, true, reasoningDelta = event.text)
                        is HealEvent.ToolCall -> {
                            val parts = event.arguments.split("|||")
                            val args = parts[0]
                            val signature = parts.getOrNull(1)
                            
                            val info = ToolCallInfo(event.id, event.name, args, signature)
                            currentToolCalls.add(info)
                            
                            _uiState.update { state ->
                                val newList = state.messages.toMutableList()
                                val index = newList.indexOfLast { it.id == targetId }
                                if (index != -1) {
                                    newList[index] = newList[index].copy(isPending = false, pendingToolCall = info, toolCallId = event.id)
                                }
                                state.copy(messages = newList)
                            }
                        }
                        is HealEvent.Error -> updateMessage(targetId, "Error: ${event.message}", false, ChatRole.ERROR)
                        is HealEvent.StreamEnd -> {
                            val finalState = _uiState.value.messages.find { it.id == targetId }
                            val finalFullText = finalState?.text ?: ""
                            val finalFullReasoning = finalState?.reasoning ?: ""
                            updateMessage(targetId, finalFullText, false, reasoning = finalFullReasoning)
                            
                            val toolCallsJson = if (currentToolCalls.isNotEmpty()) json.encodeToString(currentToolCalls) else null
                            chatDao.insertMessage(ChatMessageEntity(
                                sessionId = sessionId,
                                role = "assistant",
                                content = finalFullText,
                                reasoning = finalFullReasoning,
                                timestamp = System.currentTimeMillis(),
                                toolCallsJson = toolCallsJson
                            ))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Request failed", e)
                updateMessage(targetId, "Failed to connect to Heal Agent.", false, ChatRole.ERROR)
            }
        }
    }

    fun approveRecord(requestId: String, messageId: String) {
        viewModelScope.launch {
            val doc = uiState.value.documents.find { it.id == requestId } ?: return@launch
            val fullText = doc.fullText ?: documentManager.readDocumentText(doc.name)
            val callId = _uiState.value.messages.find { it.id == messageId }?.pendingToolCall?.toolCallId
            appendToMessage(messageId, "\n\n---\n", true)
            sendMessage(userText = fullText, isHiddenData = true, toolCallId = callId)
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

    private fun appendToMessage(id: String, textDelta: String?, isPending: Boolean, reasoningDelta: String? = null) {
        _uiState.update { state ->
            val newList = state.messages.toMutableList()
            val index = newList.indexOfLast { it.id == id }
            if (index != -1) {
                val current = newList[index]
                newList[index] = current.copy(
                    text = current.text + (textDelta ?: ""),
                    reasoning = (current.reasoning ?: "") + (reasoningDelta ?: ""),
                    isPending = isPending
                )
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

    fun refreshMedicalSummary() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true) }
            val recovered = documentManager.reconcileVault()
            recovered.forEach { localIndexer.indexDocument(it) }
            refreshUserData()
            _uiState.update { it.copy(isSyncing = false) }
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

    fun deleteDocument(document: HealthDocument) { viewModelScope.launch { documentManager.deleteDocument(document) } }
    fun updateDocumentLabel(id: String, label: String) { viewModelScope.launch { documentManager.updateUserLabel(id, label) } }
}

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val sessions: List<ChatSessionEntity> = emptyList(),
    val activeSessionId: String? = null,
    val isSyncing: Boolean = false,
    val healthPermissionGranted: Boolean = false,
    val medicalSummary: String = "No medical records found.",
    val documents: List<HealthDocument> = emptyList(),
    val userName: String = "",
    val userWeight: String = "",
    val selectedImageUri: Uri? = null
)
