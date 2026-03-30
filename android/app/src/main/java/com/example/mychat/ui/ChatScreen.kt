package com.example.mychat.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import coil3.compose.AsyncImage
import com.example.mychat.data.ChatMessage
import com.example.mychat.data.ChatRole
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onNavigateToVault: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var isClearing by remember { mutableStateOf(false) }
    var showFullScreenImage by remember { mutableStateOf<String?>(null) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    val healthPermissionLauncher = rememberLauncherForActivityResult(
        androidx.health.connect.client.PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        viewModel.onHealthPermissionResult()
    }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        viewModel.selectImage(uri)
    }

    // Full Screen Image Dialog
    if (showFullScreenImage != null) {
        Dialog(
            onDismissRequest = { showFullScreenImage = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f))) {
                AsyncImage(
                    model = showFullScreenImage,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    contentScale = ContentScale.Fit
                )
                IconButton(
                    onClick = { showFullScreenImage = null },
                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).background(Color.White.copy(alpha = 0.2f), CircleShape)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Heal Agent", 
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp), 
                    style = MaterialTheme.typography.headlineSmall, 
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary
                )
                
                NavigationDrawerItem(
                    label = { Text("Active Chat", fontWeight = FontWeight.Bold) },
                    selected = true,
                    onClick = { scope.launch { drawerState.close() } },
                    icon = { Icon(Icons.Default.ChatBubble, null) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                
                NavigationDrawerItem(
                    label = { Text("Health Data Vault") },
                    selected = false,
                    onClick = { 
                        scope.launch { drawerState.close(); onNavigateToVault() } 
                    },
                    icon = { Icon(Icons.Default.Storage, null) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp, horizontal = 24.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Recent Chats", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    IconButton(onClick = { viewModel.createNewSession() }) {
                        Icon(Icons.Default.Add, "New Chat", tint = MaterialTheme.colorScheme.primary)
                    }
                }

                Spacer(Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                ) {
                    items(uiState.sessions) { session ->
                        val isSelected = uiState.activeSessionId == session.id
                        NavigationDrawerItem(
                            label = { 
                                Text(
                                    session.title, 
                                    maxLines = 1, 
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                ) 
                            },
                            selected = isSelected,
                            onClick = { 
                                viewModel.loadSession(session.id)
                                scope.launch { drawerState.close() }
                            },
                            icon = { Icon(if (isSelected) Icons.Default.ChatBubble else Icons.Default.Chat, null, modifier = Modifier.size(18.dp)) },
                            badge = {
                                if (isSelected) {
                                    IconButton(onClick = { viewModel.deleteSession(session.id) }) {
                                        Icon(Icons.Default.DeleteOutline, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            },
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
                
                // User Profile & Settings at bottom
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Column(modifier = Modifier.padding(24.dp)) {
                        Text(uiState.userName.ifBlank { "Heal User" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(uiState.userWeight.ifBlank { "Weight: --" }, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("Heal 2.0", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge, letterSpacing = 1.sp) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Default.Menu, contentDescription = "Menu") }
                    },
                    actions = {
                        IconButton(onClick = { 
                            if (uiState.healthPermissionGranted) {
                                viewModel.syncHealthData()
                            } else {
                                healthPermissionLauncher.launch(
                                    setOf(
                                        androidx.health.connect.client.permission.HealthPermission.getReadPermission(androidx.health.connect.client.records.StepsRecord::class),
                                        androidx.health.connect.client.permission.HealthPermission.getReadPermission(androidx.health.connect.client.records.HeartRateRecord::class),
                                        androidx.health.connect.client.permission.HealthPermission.getReadPermission(androidx.health.connect.client.records.SleepSessionRecord::class),
                                        androidx.health.connect.client.permission.HealthPermission.getReadPermission(androidx.health.connect.client.records.OxygenSaturationRecord::class),
                                        androidx.health.connect.client.permission.HealthPermission.getReadPermission(androidx.health.connect.client.records.TotalCaloriesBurnedRecord::class),
                                        androidx.health.connect.client.permission.HealthPermission.getReadPermission(androidx.health.connect.client.records.ActiveCaloriesBurnedRecord::class)
                                    )
                                )
                            }
                        }) {
                            Icon(
                                Icons.Default.Favorite, 
                                contentDescription = "Health Connect", 
                                tint = if (uiState.healthPermissionGranted) Color(0xFFE91E63) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        FilledTonalIconButton(onClick = { viewModel.createNewSession() }) {
                            Icon(Icons.Default.Add, contentDescription = "New Chat")
                        }
                    }
                )
            },
            bottomBar = {
                ChatInput(
                    selectedImageUri = uiState.selectedImageUri,
                    onPickImage = { imagePicker.launch("image/*") },
                    onRemoveImage = { viewModel.selectImage(null) },
                    onPreviewClick = { showFullScreenImage = it.toString() }
                ) { text ->
                    viewModel.sendMessage(text)
                    scope.launch { if (uiState.messages.isNotEmpty()) listState.animateScrollToItem(uiState.messages.size - 1) }
                }
            }
        ) { paddingValues ->
            AnimatedVisibility(visible = !isClearing, exit = fadeOut(), enter = fadeIn()) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(paddingValues).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
                    items(uiState.messages, key = { it.id }) { MessageBubble(it, onImageClick = { uri -> showFullScreenImage = uri }, viewModel = viewModel) }
                }
            }
            LaunchedEffect(uiState.messages.size) { if (uiState.messages.isNotEmpty()) listState.animateScrollToItem(uiState.messages.size - 1) }
        }
    }
}

@Composable
fun MessageBubble(
    message: ChatMessage, 
    onImageClick: (String) -> Unit,
    viewModel: ChatViewModel
) {
    val isUser = message.role == ChatRole.USER
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val containerColor = when (message.role) {
        ChatRole.USER -> MaterialTheme.colorScheme.primary
        ChatRole.MODEL, ChatRole.ASSISTANT -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = when (message.role) {
        ChatRole.USER -> MaterialTheme.colorScheme.onPrimary
        ChatRole.MODEL, ChatRole.ASSISTANT -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onErrorContainer
    }

    Column(
        modifier = Modifier.fillMaxWidth().animateContentSize(spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow)),
        horizontalAlignment = alignment
    ) {
        if (!isUser && !message.reasoning.isNullOrBlank()) {
            ThinkingTrace(reasoning = message.reasoning)
            Spacer(modifier = Modifier.height(4.dp))
        }

        Box(
            modifier = Modifier
                .clip(
                    RoundedCornerShape(
                        topStart = 24.dp, 
                        topEnd = 24.dp, 
                        bottomStart = if (isUser) 24.dp else 4.dp, 
                        bottomEnd = if (isUser) 4.dp else 24.dp
                    )
                )
                .background(containerColor)
                .widthIn(max = 320.dp)
        ) {
            Column {
                if (message.imageUri != null) {
                    AsyncImage(
                        model = message.imageUri,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { onImageClick(message.imageUri) },
                        contentScale = ContentScale.Crop
                    )
                }
                
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    if (message.isPending && message.text.isEmpty() && message.reasoning.isNullOrEmpty()) {
                        WavyLoadingIndicator(color = contentColor)
                    } else {
                        Column {
                            MarkdownContent(text = message.text, contentColor = contentColor)
                            
                            // 2026 Inline Tool Approval UX
                            message.pendingToolCall?.let { tool ->
                                Spacer(modifier = Modifier.height(12.dp))
                                IntentApprovalCard(
                                    tool = tool,
                                    onApprove = { 
                                        android.util.Log.d("HealUI", "Approve clicked for tool: ${tool.name}, callId: ${tool.toolCallId}")
                                        try {
                                            val json = Json { ignoreUnknownKeys = true }
                                            val args = json.parseToJsonElement(tool.arguments) as JsonObject
                                            
                                            // Robust ID extraction
                                            var id = args["id"]?.jsonPrimitive?.content ?: ""
                                            if (id.isBlank()) {
                                                id = args.entries.find { it.key.contains("id", ignoreCase = true) }?.value?.jsonPrimitive?.content ?: ""
                                            }
                                            
                                            // Clean the ID (remove leading dashes, spaces, or extra quotes)
                                            id = id.trim().removePrefix("-").trim().replace("\"", "")
                                            
                                            android.util.Log.d("HealUI", "Extracted ID for approval: '$id'")
                                            viewModel.approveRecord(id, message.id)
                                        } catch (e: Exception) { 
                                            android.util.Log.e("HealUI", "Failed to parse tool arguments for approval", e)
                                        }
                                    },
                                    onReject = { 
                                        android.util.Log.d("HealUI", "Reject clicked for message: ${message.id}")
                                        viewModel.rejectRecord(message.id) 
                                    }
                                )
                            }
                            
                            if (message.isActionResolved && message.isPending) {
                                Column(
                                    modifier = Modifier.padding(top = 12.dp).fillMaxWidth().background(contentColor.copy(alpha = 0.05f), RoundedCornerShape(12.dp)).padding(12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.AutoAwesome, 
                                            null, 
                                            Modifier.size(18.dp), 
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "Analyzing clinical data...", 
                                            style = MaterialTheme.typography.labelMedium,
                                            color = contentColor.copy(alpha = 0.8f),
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    LinearProgressIndicator(
                                        modifier = Modifier.fillMaxWidth().height(2.dp).clip(CircleShape),
                                        color = MaterialTheme.colorScheme.primary,
                                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        Text(
            text = if (isUser) "You" else "Heal Agent", 
            style = MaterialTheme.typography.labelSmall, 
            color = MaterialTheme.colorScheme.onSurfaceVariant, 
            modifier = Modifier.padding(top = 4.dp, start = if (isUser) 0.dp else 8.dp, end = if (isUser) 8.dp else 0.dp)
        )
    }
}

@Composable
fun IntentApprovalCard(
    tool: com.example.mychat.data.ToolCallInfo,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
        ),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.FactCheck, 
                    null, 
                    Modifier.size(20.dp), 
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Clinical Data Access", 
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            
            Spacer(modifier = Modifier.height(10.dp))
            
            val reason = try {
                val json = Json { ignoreUnknownKeys = true }
                val args = json.parseToJsonElement(tool.arguments) as JsonObject
                args["reason"]?.jsonPrimitive?.content ?: "Access needed for detailed clinical analysis."
            } catch (e: Exception) { "Access requested to record." }
            
            Text(
                reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 16.sp
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onReject,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Deny", style = MaterialTheme.typography.labelMedium)
                }
                Button(
                    onClick = onApprove,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Approve", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
fun ThinkingTrace(reasoning: String) {
    var expanded by remember { mutableStateOf(false) }
    
    Card(
        onClick = { expanded = !expanded },
        modifier = Modifier.widthIn(max = 280.dp).padding(start = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(12.dp).animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Psychology, 
                    contentDescription = null, 
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Agent Thinking", 
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = reasoning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
fun WavyLoadingIndicator(color: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "wavy")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = intrinsicSizeAnimation(2000),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    Row(modifier = Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { index ->
            val offset = (phase + index * 0.5f) % (2f * Math.PI.toFloat())
            val y = Math.sin(offset.toDouble()).toFloat() * 4.dp.value
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .offset(y = y.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

private fun intrinsicSizeAnimation(duration: Int): DurationBasedAnimationSpec<Float> = 
    tween(duration, easing = LinearEasing)


@Composable
fun ChatInput(
    selectedImageUri: android.net.Uri?,
    onPickImage: () -> Unit,
    onRemoveImage: () -> Unit,
    onPreviewClick: (android.net.Uri) -> Unit,
    onSendMessage: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current

    Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).navigationBarsPadding().imePadding()) {
        AnimatedVisibility(
            visible = selectedImageUri != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Box(modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                        .clickable { selectedImageUri?.let { onPreviewClick(it) } }
                ) {
                    AsyncImage(
                        model = selectedImageUri,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-8).dp).size(22.dp).clickable { onRemoveImage() },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.error,
                    shadowElevation = 2.dp
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.White, modifier = Modifier.padding(4.dp))
                }
            }
        }

        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPickImage) {
                Icon(Icons.Default.AttachFile, contentDescription = "Attach", tint = MaterialTheme.colorScheme.primary)
            }
            TextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Message Heal 2.0...") },
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(24.dp)),
                colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                maxLines = 4
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = { if (text.isNotBlank() || selectedImageUri != null) { onSendMessage(text); text = ""; keyboardController?.hide() } },
                modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}
