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
                Spacer(Modifier.height(12.dp))
                Text("Heal 2.0 Menu", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                NavigationDrawerItem(
                    label = { Text("Chat") },
                    selected = true,
                    onClick = { scope.launch { drawerState.close() } },
                    icon = { Icon(Icons.Default.Chat, contentDescription = null) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    label = { Text("Health Data Vault") },
                    selected = false,
                    onClick = { scope.launch { drawerState.close(); onNavigateToVault() } },
                    icon = { Icon(Icons.Default.HealthAndSafety, contentDescription = null) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
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
                        IconButton(onClick = { /* Health Permission Flow */ }) {
                            Icon(Icons.Default.Favorite, contentDescription = "Health Connect", tint = MaterialTheme.colorScheme.primary)
                        }
                        FilledTonalIconButton(onClick = { scope.launch { isClearing = true; delay(300); viewModel.clearChat(); isClearing = false } }) {
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
                    items(uiState.messages, key = { it.id }) { MessageBubble(it, onImageClick = { uri -> showFullScreenImage = uri }) }
                }
            }
            LaunchedEffect(uiState.messages.size) { if (uiState.messages.isNotEmpty()) listState.animateScrollToItem(uiState.messages.size - 1) }
        }
    }
}

@Composable
fun MessageBubble(message: ChatMessage, onImageClick: (String) -> Unit) {
    val isUser = message.role == ChatRole.USER
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val containerColor = when (message.role) {
        ChatRole.USER -> MaterialTheme.colorScheme.primary
        ChatRole.MODEL -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = when (message.role) {
        ChatRole.USER -> MaterialTheme.colorScheme.onPrimary
        ChatRole.MODEL -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onErrorContainer
    }

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
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
                .widthIn(max = 300.dp)
        ) {
            Column {
                // Sent Image Display
                if (message.imageUri != null) {
                    AsyncImage(
                        model = message.imageUri,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onImageClick(message.imageUri) },
                        contentScale = ContentScale.Crop
                    )
                }
                
                // Message Text
                Box(modifier = Modifier.padding(16.dp)) {
                    if (message.isPending && message.text.isEmpty()) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = contentColor)
                    } else {
                        MarkdownContent(text = message.text, contentColor = contentColor)
                    }
                }
            }
        }
        Text(text = if (isUser) "You" else "Heal 2.0", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}

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
        // Attachment Preview Bar (Square Chip Style)
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
