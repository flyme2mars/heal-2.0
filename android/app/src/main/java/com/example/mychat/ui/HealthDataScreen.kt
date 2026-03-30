package com.example.mychat.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthDataScreen(
    onBack: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val dateFormatter = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }

    var isEditing by remember { mutableStateOf(false) }
    var nameInput by remember { mutableStateOf("") }
    var weightInput by remember { mutableStateOf("") }
    var selectedDocument by remember { mutableStateOf<com.example.mychat.data.HealthDocument?>(null) }
    var isRenaming by remember { mutableStateOf(false) }
    var labelInput by remember { mutableStateOf("") }

    // Detail Dialog
    if (selectedDocument != null) {
        val doc = selectedDocument!!
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { selectedDocument = null; isRenaming = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isRenaming) {
                            OutlinedTextField(
                                value = labelInput,
                                onValueChange = { labelInput = it },
                                modifier = Modifier.weight(1f),
                                label = { Text("Record Label") },
                                trailingIcon = {
                                    IconButton(onClick = { 
                                        viewModel.updateDocumentLabel(doc.id, labelInput)
                                        isRenaming = false 
                                        selectedDocument = doc.copy(userLabel = labelInput)
                                    }) {
                                        Icon(Icons.Default.Check, null)
                                    }
                                }
                            )
                        } else {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    doc.userLabel ?: doc.name,
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "Original: ${doc.name}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { 
                                labelInput = doc.userLabel ?: doc.name
                                isRenaming = true 
                            }) {
                                Icon(Icons.Default.Edit, contentDescription = "Rename", modifier = Modifier.size(20.dp))
                            }
                        }
                        IconButton(onClick = { selectedDocument = null; isRenaming = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        AssistChip(
                            onClick = { },
                            label = { Text(doc.recordType ?: "General") },
                            leadingIcon = { Icon(Icons.Default.Category, null, Modifier.size(18.dp)) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        AssistChip(
                            onClick = { },
                            label = { Text(doc.recordDate ?: "No Date") },
                            leadingIcon = { Icon(Icons.Default.Event, null, Modifier.size(18.dp)) }
                        )
                    }
                    
                    if (doc.tags.isNotEmpty()) {
                        Row(
                            modifier = Modifier.padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            doc.tags.forEach { tag ->
                                SuggestionChip(
                                    onClick = { },
                                    label = { Text(tag, style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Text("AI Analysis Summary", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        doc.summary ?: "No summary available.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(16.dp))

                    Text("Extracted Content", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Column {
                            val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
                            IconButton(
                                onClick = { 
                                    doc.fullText?.let { 
                                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(it))
                                        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.align(Alignment.End).size(24.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp))
                            }
                            LazyColumn {
                                item {
                                    Text(
                                        doc.fullText ?: "Full text not available or still processing.",
                                        style = MaterialTheme.typography.bodySmall,
                                        lineHeight = 22.sp,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Button(
                        onClick = { selectedDocument = null },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("Done")
                    }
                }
            }
        }
    }

    // Sync inputs with state when not editing
    LaunchedEffect(uiState.userName, uiState.userWeight, isEditing) {
        if (!isEditing) {
            nameInput = uiState.userName
            weightInput = uiState.userWeight
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            viewModel.uploadDocument(it, "MedicalRecord_${System.currentTimeMillis()}.pdf")
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshMedicalSummary()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Health Data Vault", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (uiState.isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    IconButton(onClick = { filePickerLauncher.launch(arrayOf("application/pdf", "image/*")) }) {
                        Icon(Icons.Default.FileUpload, contentDescription = "Upload Document")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Personal Health Passport Header
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Personal Profile",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.Bold
                            )
                            TextButton(
                                onClick = { 
                                    if (isEditing) {
                                        val names = nameInput.trim().split(" ")
                                        viewModel.updateProfile(names.firstOrNull() ?: "", names.lastOrNull() ?: "", "other")
                                        weightInput.toDoubleOrNull()?.let { viewModel.updateWeight(it) }
                                        Toast.makeText(context, "Profile Saved", Toast.LENGTH_SHORT).show()
                                    }
                                    isEditing = !isEditing 
                                },
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
                            ) {
                                Icon(
                                    imageVector = if (isEditing) Icons.Default.Check else Icons.Default.Edit,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(if (isEditing) "Save" else "Edit")
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        AnimatedContent(targetState = isEditing, label = "ProfileEdit") { editing ->
                            if (editing) {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    OutlinedTextField(
                                        value = nameInput,
                                        onValueChange = { nameInput = it },
                                        placeholder = { Text("Enter full name") },
                                        label = { Text("Name") },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedContainerColor = Color.White.copy(alpha = 0.1f),
                                            unfocusedContainerColor = Color.White.copy(alpha = 0.1f)
                                        )
                                    )
                                    OutlinedTextField(
                                        value = weightInput,
                                        onValueChange = { weightInput = it },
                                        placeholder = { Text("Enter weight in kg") },
                                        label = { Text("Weight (kg)") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedContainerColor = Color.White.copy(alpha = 0.1f),
                                            unfocusedContainerColor = Color.White.copy(alpha = 0.1f)
                                        )
                                    )
                                }
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ProfileItem(Icons.Default.Person, "Name", uiState.userName.ifEmpty { "Not set" })
                                    ProfileItem(Icons.Default.Scale, "Weight", if (uiState.userWeight.isEmpty()) "Not set" else "${uiState.userWeight} kg")
                                }
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    "Medical Records",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            if (uiState.documents.isEmpty()) {
                item {
                    Text(
                        "No documents uploaded yet. Use the upload button at the top to add your medical reports.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(uiState.documents) { doc ->
                    ListItem(
                        headlineContent = { Text(doc.userLabel ?: doc.name, fontWeight = FontWeight.SemiBold) },
                        supportingContent = { 
                            Column {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    doc.tags.take(2).forEach { tag ->
                                        Surface(
                                            color = MaterialTheme.colorScheme.tertiaryContainer,
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                tag, 
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onTertiaryContainer
                                            )
                                        }
                                    }
                                }
                                doc.summary?.let { 
                                    Text(
                                        it, 
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    ) 
                                }
                                Text(
                                    dateFormatter.format(Date(doc.timestamp)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                        },
                        leadingContent = { 
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.secondaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Description, 
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        },
                        trailingContent = {
                            IconButton(onClick = { viewModel.deleteDocument(doc) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Delete Document",
                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                                )
                            }
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = Color.Transparent
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedDocument = doc }
                    )
                }
            }

            item {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Clinical Vault Summary",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        MarkdownContent(
                            text = uiState.medicalSummary,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ProfileItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f))
            Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.SemiBold)
        }
    }
}
