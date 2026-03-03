package com.example.mychat.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
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

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            // In a real app, we should get the actual name from the content resolver
            viewModel.uploadDocument(it, "MedicalRecord_${System.currentTimeMillis()}.pdf")
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshMedicalSummary()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Health Data Vault") },
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
            item {
                Text(
                    "Encrypted Medical Vault",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        MarkdownContent(
                            text = "All documents below are encrypted using **AES-256-GCM** and stored in the app's private sandbox. They are not accessible to other applications.",
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                Text(
                    "Recent Documents",
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
                        headlineContent = { Text(doc.name) },
                        supportingContent = { Text(dateFormatter.format(Date(doc.timestamp))) },
                        leadingContent = { 
                            Icon(
                                Icons.Default.Description, 
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            ) 
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    doc.type.uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                                IconButton(onClick = { viewModel.deleteDocument(doc) }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Delete Document",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            item {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Clinical Summary (ABDM Sync)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
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
