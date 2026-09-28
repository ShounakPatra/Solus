package com.shounak.localmeshai.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.shounak.localmeshai.rag.RagManager
import com.shounak.localmeshai.rag.store.RagDocumentSummary
import com.shounak.localmeshai.utils.AppSettings
import com.shounak.localmeshai.utils.glassEffect
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RagKnowledgeBaseDialog(
    ragManager: RagManager,
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val appSettings = remember { AppSettings.getInstance(context) }
    val settingsData by appSettings.settings.collectAsState()

    var documents by remember { mutableStateOf(ragManager.indexedDocuments) }
    var totalChunks by remember { mutableIntStateOf(ragManager.totalIndexedChunks) }
    var isIngesting by remember { mutableStateOf(false) }
    var ingestingDocName by remember { mutableStateOf("") }
    var showAddTextDialog by remember { mutableStateOf(false) }
    var showClearConfirmation by remember { mutableStateOf(false) }
    var docToDelete by remember { mutableStateOf<RagDocumentSummary?>(null) }
    var showSettingsSection by remember { mutableStateOf(false) }

    fun refresh() {
        documents = ragManager.indexedDocuments
        totalChunks = ragManager.totalIndexedChunks
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val fileName = getFileNameFromUri(context, uri) ?: "document"
        val mimeType = context.contentResolver.getType(uri) ?: ""
        isIngesting = true
        ingestingDocName = fileName
        scope.launch {
            try {
                val result = ragManager.ingestDocument(uri, fileName, mimeType)
                refresh()
                if (result.success) {
                    Toast.makeText(context, "Indexed ${result.chunkCount} chunks into RAG", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Indexing failed: ${result.message}", Toast.LENGTH_LONG).show()
                }
            } catch (t: Throwable) {
                Toast.makeText(context, "Error: ${t.message}", Toast.LENGTH_SHORT).show()
            } finally {
                isIngesting = false
                ingestingDocName = ""
            }
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val colors = MaterialTheme.colorScheme
        val dialogShape = RoundedCornerShape(24.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.88f)
                .glassEffect(
                    hazeState = remember { HazeState() },
                    shape = dialogShape,
                    blurRadius = 20.dp,
                    tintColor = colors.surfaceContainer,
                    borderAlpha = 0.35f
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "📚 RAG Knowledge Base",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface
                        )
                        Text(
                            text = "Semantically indexed documents available to chat models",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = onDismissRequest,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = colors.onSurfaceVariant
                        )
                    }
                }

                // Stats Chips and Tune Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SuggestionChip(
                            onClick = {},
                            label = { Text("${documents.size} docs", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall) },
                            shape = RoundedCornerShape(10.dp),
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = colors.primaryContainer.copy(alpha = 0.40f),
                                labelColor = colors.primary
                            ),
                            border = SuggestionChipDefaults.suggestionChipBorder(
                                enabled = true,
                                borderColor = colors.primary.copy(alpha = 0.35f),
                                borderWidth = 0.8.dp
                            )
                        )
                        SuggestionChip(
                            onClick = {},
                            label = { Text("$totalChunks chunks", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall) },
                            shape = RoundedCornerShape(10.dp),
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = colors.surfaceContainerHighest,
                                labelColor = colors.onSurfaceVariant
                            ),
                            border = SuggestionChipDefaults.suggestionChipBorder(
                                enabled = true,
                                borderColor = colors.outlineVariant.copy(alpha = 0.40f),
                                borderWidth = 0.8.dp
                            )
                        )
                    }
                    IconButton(
                        onClick = { showSettingsSection = !showSettingsSection },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "RAG Tuning Settings",
                            tint = if (showSettingsSection) colors.primary else colors.onSurfaceVariant
                        )
                    }
                }

                // Tuning Settings Panel (Collapsible)
                if (showSettingsSection) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = colors.surfaceContainerLow,
                        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.40f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Retrieval Parameters",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = colors.onSurface
                            )
                            // Top-K Slider
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Top Chunks (Top-K):", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                    Text("${settingsData.ragTopK} chunks", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = colors.primary)
                                }
                                Slider(
                                    value = settingsData.ragTopK.toFloat(),
                                    onValueChange = { appSettings.updateSettings { s -> s.copy(ragTopK = it.toInt()) } },
                                    valueRange = 1f..5f,
                                    steps = 3,
                                    modifier = Modifier.fillMaxWidth().height(28.dp)
                                )
                            }
                            // Min Similarity Slider
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Min Relevance Threshold:", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                    Text("${(settingsData.ragMinSimilarity * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = colors.primary)
                                }
                                Slider(
                                    value = settingsData.ragMinSimilarity.coerceIn(0.25f, 0.65f),
                                    onValueChange = { appSettings.updateSettings { s -> s.copy(ragMinSimilarity = it) } },
                                    valueRange = 0.25f..0.65f,
                                    steps = 7,
                                    modifier = Modifier.fillMaxWidth().height(28.dp)
                                )
                            }
                        }
                    }
                }

                // Ingestion Loading Bar
                if (isIngesting) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = colors.primaryContainer.copy(alpha = 0.40f),
                        border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = colors.primary
                            )
                            Text(
                                text = "Indexing \"$ingestingDocName\" into vector store...",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onPrimaryContainer
                            )
                        }
                    }
                }

                // Action Buttons: Index File, Add Text
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { filePicker.launch(arrayOf("*/*")) },
                        enabled = !isIngesting,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Index File", style = MaterialTheme.typography.labelMedium)
                    }
                    OutlinedButton(
                        onClick = { showAddTextDialog = true },
                        enabled = !isIngesting,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Add Text / Note", style = MaterialTheme.typography.labelMedium)
                    }
                }

                HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.30f))

                // Documents List
                if (documents.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = colors.onSurfaceVariant.copy(alpha = 0.40f)
                            )
                            Text(
                                text = "No documents indexed",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.onSurfaceVariant
                            )
                            Text(
                                text = "Index PDFs, DOCX, TXT, or notes to give chat models\naccurate context grounded in your files.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant.copy(alpha = 0.75f),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(documents, key = { it.documentId }) { doc ->
                            RagDocumentRow(
                                document = doc,
                                onDelete = { docToDelete = doc }
                            )
                        }
                    }
                }

                // Footer: Clear All
                if (documents.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { showClearConfirmation = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.error),
                        border = BorderStroke(0.8.dp, colors.error.copy(alpha = 0.40f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Clear All Documents", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }

    // Add Raw Text Dialog
    if (showAddTextDialog) {
        var textTitle by remember { mutableStateOf("") }
        var textContent by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddTextDialog = false },
            title = { Text("Add Document / Note to RAG") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = textTitle,
                        onValueChange = { textTitle = it },
                        label = { Text("Title / Name") },
                        placeholder = { Text("e.g. Project Specifications.txt") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = textContent,
                        onValueChange = { textContent = it },
                        label = { Text("Document Content") },
                        placeholder = { Text("Paste document text, notes, guidelines, or code...") },
                        modifier = Modifier.fillMaxWidth().height(160.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = textTitle.ifBlank { "Untitled Note.txt" }
                        val content = textContent.trim()
                        if (content.isNotBlank()) {
                            scope.launch {
                                isIngesting = true
                                ingestingDocName = name
                                val res = ragManager.ingestText(name, content)
                                isIngesting = false
                                ingestingDocName = ""
                                refresh()
                                if (res.success) {
                                    Toast.makeText(context, "Indexed ${res.chunkCount} chunks", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Failed: ${res.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        showAddTextDialog = false
                    },
                    enabled = textContent.isNotBlank()
                ) {
                    Text("Index Text")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddTextDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete Single Document Confirmation
    docToDelete?.let { doc ->
        AlertDialog(
            onDismissRequest = { docToDelete = null },
            title = { Text("Delete Document?") },
            text = { Text("Remove \"${doc.documentName}\" and its ${doc.chunkCount} indexed vector chunks from the knowledge base?") },
            confirmButton = {
                Button(
                    onClick = {
                        ragManager.removeDocument(doc.documentId)
                        refresh()
                        docToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { docToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Clear All Confirmation
    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text("Clear RAG Knowledge Base?") },
            text = { Text("This will permanently remove all ${documents.size} documents and $totalChunks vector chunks from the on-device knowledge base.") },
            confirmButton = {
                Button(
                    onClick = {
                        ragManager.clearKnowledgeBase()
                        refresh()
                        showClearConfirmation = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun RagDocumentRow(
    document: RagDocumentSummary,
    onDelete: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val dateStr = remember(document.addedAt) {
        try {
            SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault()).format(Date(document.addedAt))
        } catch (_: Throwable) {
            ""
        }
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = colors.surfaceContainerLow,
        border = BorderStroke(0.8.dp, colors.outlineVariant.copy(alpha = 0.40f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = colors.primaryContainer.copy(alpha = 0.50f),
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                            contentDescription = null,
                            tint = colors.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = document.documentName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                        maxLines = 1
                    )
                    Text(
                        text = "${document.chunkCount} chunks • ${document.totalChars} chars" + if (dateStr.isNotBlank()) " • $dateStr" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant.copy(alpha = 0.80f),
                        maxLines = 1
                    )
                }
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete document",
                    tint = colors.error.copy(alpha = 0.75f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

private fun getFileNameFromUri(context: android.content.Context, uri: Uri): String? {
    var name: String? = null
    val cursor = context.contentResolver.query(uri, null, null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1) {
                name = it.getString(nameIndex)
            }
        }
    }
    return name ?: uri.lastPathSegment
}
