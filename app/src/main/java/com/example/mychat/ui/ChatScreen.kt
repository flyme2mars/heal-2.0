package com.example.mychat.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.draw.rotate
import androidx.compose.material.icons.filled.Sync
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.health.connect.client.PermissionController
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.mychat.data.ChatMessage
import com.example.mychat.data.ChatRole
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var isClearing by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val healthPermissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        Toast.makeText(context, "Permissions updated", Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "Remini",
                        fontWeight = FontWeight.ExtraBold,
                        style = MaterialTheme.typography.titleLarge,
                        letterSpacing = 1.sp
                    )
                },
                actions = {
                    IconButton(onClick = {
                        val status = viewModel.getHealthSdkStatus()
                        if (status == androidx.health.connect.client.HealthConnectClient.SDK_AVAILABLE) {
                            // Try the standard popup first
                            healthPermissionLauncher.launch(viewModel.healthPermissions)
                            
                            // Also provide a way to open settings directly if popup fails
                            Toast.makeText(context, "Opening Health Settings...", Toast.LENGTH_SHORT).show()
                            try {
                                context.startActivity(viewModel.getHealthSettingsIntent())
                            } catch (e: Exception) {
                                // Ignore if settings won't open
                            }
                        } else if (status == androidx.health.connect.client.HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                data = android.net.Uri.parse("market://details?id=com.google.android.apps.healthdata")
                                setPackage("com.android.vending")
                            }
                            context.startActivity(intent)
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Default.Favorite,
                            contentDescription = "Health Connect",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(
                        onClick = { viewModel.loadSampleMedicalData() },
                        enabled = !uiState.isSyncing
                    ) {
                        val infiniteTransition = rememberInfiniteTransition(label = "SyncRotation")
                        val rotation by infiniteTransition.animateFloat(
                            initialValue = 0f,
                            targetValue = 360f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1000, easing = LinearEasing)
                            ),
                            label = "SyncRotation"
                        )
                        
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = "Sync ABDM",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = if (uiState.isSyncing) Modifier.rotate(rotation) else Modifier
                        )
                    }
                    FilledTonalIconButton(
                        onClick = {
                            scope.launch {
                                isClearing = true
                                delay(300) // Let animation play
                                viewModel.clearChat()
                                isClearing = false
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "New Chat"
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            ChatInput { text ->
                viewModel.sendMessage(text)
                scope.launch {
                    if (uiState.messages.isNotEmpty()) {
                        listState.animateScrollToItem(uiState.messages.size - 1)
                    }
                }
            }
        }
    ) { paddingValues ->
        AnimatedVisibility(
            visible = !isClearing,
            exit = fadeOut() + slideOutVertically { -it },
            enter = fadeIn() + slideInVertically { it }
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                items(uiState.messages, key = { it.id }) { message ->
                    MessageBubble(message)
                }
            }
        }
        
        // Auto-scroll on new message
        LaunchedEffect(uiState.messages.size) {
            if (uiState.messages.isNotEmpty()) {
                listState.animateScrollToItem(uiState.messages.size - 1)
            }
        }
    }
}

@Composable
fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == ChatRole.USER
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val containerColor = when (message.role) {
        ChatRole.USER -> MaterialTheme.colorScheme.primary
        ChatRole.MODEL -> MaterialTheme.colorScheme.secondaryContainer
        ChatRole.ERROR -> MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = when (message.role) {
        ChatRole.USER -> MaterialTheme.colorScheme.onPrimary
        ChatRole.MODEL -> MaterialTheme.colorScheme.onSecondaryContainer
        ChatRole.ERROR -> MaterialTheme.colorScheme.onErrorContainer
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        AnimatedVisibility(
            visible = true,
            enter = fadeIn() + slideInVertically(
                initialOffsetY = { it / 2 },
                animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy)
            )
        ) {
            Box(
                modifier = Modifier
                    .clip(
                        RoundedCornerShape(
                            topStart = 20.dp,
                            topEnd = 20.dp,
                            bottomStart = if (isUser) 20.dp else 4.dp,
                            bottomEnd = if (isUser) 4.dp else 20.dp
                        )
                    )
                    .background(containerColor)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .widthIn(max = 300.dp)
            ) {
                if (message.isPending && message.text.isEmpty()) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = contentColor
                    )
                } else {
                    MarkdownContent(
                        text = message.text,
                        contentColor = contentColor
                    )
                }
            }
        }
        Text(
            text = if (isUser) "You" else "Remini",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp)
        )
    }
}

@Composable
fun ChatInput(onSendMessage: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Message Remini...") },
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp)),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                maxLines = 4
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = {
                    if (text.isNotBlank()) {
                        onSendMessage(text)
                        text = ""
                        keyboardController?.hide()
                    }
                },
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}
