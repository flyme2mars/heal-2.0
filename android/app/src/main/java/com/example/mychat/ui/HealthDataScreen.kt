package com.example.mychat.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
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
                        headlineContent = { Text(doc.name, fontWeight = FontWeight.SemiBold) },
                        supportingContent = { 
                            Column {
                                doc.summary?.let { 
                                    Text(
                                        it, 
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 2
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
                        modifier = Modifier.fillMaxWidth()
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
