@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.shounak.localmeshai.features

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.HistoryToggleOff
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Delete
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.shounak.localmeshai.utils.glassEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import com.shounak.localmeshai.utils.LiquidGlassButton
import com.shounak.localmeshai.utils.GlassDispersionCard
import com.shounak.localmeshai.ui.components.GlassActionButton
import com.shounak.localmeshai.ui.components.GlassContentDialog
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shounak.localmeshai.rag.RagManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun TemporaryChatToggle(temporary: Boolean, busy: Boolean, hazeState: HazeState, onTemporaryChange: (Boolean) -> Unit) {
    val accent = if (temporary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val shape = RoundedCornerShape(24.dp)
    Row(modifier = Modifier.heightIn(min = 48.dp)
        .clip(shape)
        .hazeEffect(state = hazeState, style = HazeStyle(
            backgroundColor = MaterialTheme.colorScheme.surface,
            blurRadius = 24.dp,
            tint = HazeTint(MaterialTheme.colorScheme.primary.copy(alpha = if (temporary) 0.18f else 0.07f)),
            noiseFactor = 0.04f))
        .border(0.8.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.42f), shape)
        .clickable(enabled = !busy, role = Role.Switch) { onTemporaryChange(!temporary) }
        .semantics { stateDescription = if (temporary) "On" else "Off" }
        .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center) {
        Icon(Icons.Default.HistoryToggleOff, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (temporary) "Temporary chat on" else "Temporary chat off", color = accent, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun ContextUsageIndicator(budget: ContextBudget) {
    var showDetails by remember { mutableStateOf(false) }
    Text("Context ~${budget.estimatedTokens} / ${budget.limit}",
        modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Show estimated context usage") { showDetails = true },
        fontSize = 11.sp, maxLines = 1,
        color = if (budget.warning) MaterialTheme.colorScheme.error else Color(0xFF94A3B8))
    if (showDetails) AlertDialog(onDismissRequest = { showDetails = false }, title = { Text("Estimated context usage") },
        text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("~${budget.estimatedTokens} / ${budget.limit} tokens")
            Text("Includes the displayed conversation, draft and custom instructions. Attachments, memory, retrieved passages and generated answers consume additional context. This is an estimate; runtime limits can reduce the configured context.", style = MaterialTheme.typography.bodySmall)
            if (budget.omittedMessages > 0) Text("When history is rebuilt, ${budget.omittedMessages} older messages are omitted. Stateful runtimes can retain more history until reset.", style = MaterialTheme.typography.bodySmall)
            if (budget.warning) Text("Estimated usage is near or above 80% of the configured limit.", color = MaterialTheme.colorScheme.error)
        } }, confirmButton = { TextButton(onClick = { showDetails = false }) { Text("Close") } })
}

@Composable
fun HistoryEntryActions(kind: String, id: String, defaultTitle: String, hazeState: HazeState, onDelete: () -> Unit) {
    val preferences = WorkspacePreferences.get(LocalContext.current)
    val revision by preferences.revision.collectAsState()
    var rename by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val pinned = remember(revision) { preferences.pinned(kind, id) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Box {
        LiquidGlassButton(onClick = { menu = true }, hazeState = hazeState, modifier = Modifier.size(34.dp),
            shape = RoundedCornerShape(17.dp), contentPadding = PaddingValues(0.dp)) {
            Icon(Icons.Default.MoreVert, contentDescription = "Conversation actions for $defaultTitle", modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false },
            modifier = Modifier.widthIn(min = 180.dp).glassEffect(hazeState = hazeState, shape = RoundedCornerShape(16.dp),
                tintColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.80f), borderAlpha = 0.45f),
            shape = RoundedCornerShape(16.dp), containerColor = Color.Transparent, tonalElevation = 0.dp, shadowElevation = 0.dp) {
            DropdownMenuItem(leadingIcon = { Icon(Icons.Default.PushPin, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }, text = { Text(if (pinned) "Unpin" else "Pin") }, onClick = { menu = false; preferences.togglePin(kind, id) })
            DropdownMenuItem(leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }, text = { Text("Rename") }, onClick = { menu = false; title = preferences.title(kind, id, defaultTitle); error = null; rename = true })
            DropdownMenuItem(leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) }, text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; confirmDelete = true })
        }
    }
    if (confirmDelete) GlassActionDialog(hazeState = hazeState,
        onDismiss = { confirmDelete = false }, title = "Delete conversation?", confirmLabel = "Delete", destructive = true,
        onConfirm = { confirmDelete = false; onDelete() }) {
        Text(preferences.title(kind, id, defaultTitle))
    }
    if (rename) GlassActionDialog(hazeState = hazeState,
        onDismiss = { rename = false }, title = "Rename conversation", confirmLabel = "Save",
        onConfirm = {
            try { preferences.rename(kind, id, title); rename = false } catch (problem: Exception) { error = problem.message }
        }) {
        Column { OutlinedTextField(title, { title = it }, label = { Text("Conversation title") }, modifier = Modifier.fillMaxWidth(), supportingText = { Text("${title.length}/120") })
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) } }
    }
}

@Composable
fun GlassActionDialog(
    hazeState: HazeState,
    onDismiss: () -> Unit,
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    destructive: Boolean = false,
    confirmEnabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.8f).dp
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        GlassDispersionCard(
            hazeState = hazeState,
            modifier = Modifier.widthIn(max = 540.dp).fillMaxWidth(0.92f),
            cornerRadius = 24.dp, blurRadius = 34.dp,
            tintColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            borderAlpha = 0.50f, contentPadding = PaddingValues(20.dp)
        ) {
            Column(Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                content()
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LiquidGlassButton(onClick = onDismiss, hazeState = hazeState, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Cancel", color = MaterialTheme.colorScheme.primary)
                    }
                    LiquidGlassButton(onClick = onConfirm, enabled = confirmEnabled, hazeState = hazeState, modifier = Modifier.heightIn(min = 48.dp),
                        tintColor = (if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary).copy(alpha = 0.20f)) {
                        Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
fun MessageRevisionActions(text: String, onEdit: ((String) -> Unit)?, onRegenerate: (() -> Unit)?, hazeState: HazeState) {
    var dialog by remember { mutableStateOf(false) }
    var edited by remember { mutableStateOf(text) }
    if (onEdit == null && onRegenerate == null) return
    LiquidGlassButton(onClick = { edited = text; dialog = true }, hazeState = hazeState,
        modifier = Modifier.size(34.dp), shape = RoundedCornerShape(17.dp),
        tintColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f), contentPadding = PaddingValues(0.dp)) {
        Icon(if (onEdit != null) Icons.Default.Edit else Icons.Default.Refresh,
            contentDescription = if (onEdit != null) "Edit prompt and regenerate" else "Regenerate answer",
            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
    }
    if (dialog) AlertDialog(onDismissRequest = { dialog = false },
        title = { Text(if (onEdit != null) "Edit prompt" else "Regenerate answer?") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("This replaces responses and messages after this prompt in the current conversation. Earlier messages remain.")
            if (onEdit != null) OutlinedTextField(edited, { edited = it }, label = { Text("Prompt") }, modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp), minLines = 3)
        } },
        confirmButton = { TextButton(enabled = onEdit == null || edited.isNotBlank(), onClick = { dialog = false; if (onEdit != null) onEdit(edited) else onRegenerate?.invoke() }) { Text("Regenerate") } },
        dismissButton = { TextButton(onClick = { dialog = false }) { Text("Cancel") } })
}

@Composable
fun SourcePassagesButton(passages: List<RagPassage>, hazeState: HazeState) {
    if (passages.isEmpty()) return
    var show by remember { mutableStateOf(false) }
    var selectedDocument by remember { mutableStateOf<String?>(null) }
    var fullText by remember { mutableStateOf<String?>(null) }
    val manager = RagManager.getInstance(LocalContext.current)
    LaunchedEffect(selectedDocument) {
        fullText = null
        selectedDocument?.let { id ->
            fullText = try { withContext(Dispatchers.IO) {
                val chunks = manager.vectorStore.getChunksForDocument(id, limit = 200).sortedBy { it.chunkIndex }
                val text = chunks.joinToString("\n\n") { it.text }
                text.take(500_000).ifBlank { "This document is no longer indexed. The original retrieved passages are still available." } +
                    if (chunks.size >= 200 || text.length > 500_000) "\n\nPreview limited for this large document." else ""
            } } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { "Could not open the indexed document: ${error.message}" }
        }
    }
    GlassActionButton(hazeState = hazeState, onClick = { selectedDocument = null; show = true }) { Text("Sources (${passages.map { it.documentId }.distinct().size})") }
    if (show) GlassContentDialog(hazeState = hazeState, onDismissRequest = { show = false },
        title = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (selectedDocument == null) "Answer sources" else "Indexed document")
            Text(if (selectedDocument == null) "${passages.size} retrieved passages" else passages.firstOrNull { it.documentId == selectedDocument }?.documentName.orEmpty(),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } },
        text = { key(selectedDocument) { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Retrieved passages provide context; they do not verify every claim in the answer.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (selectedDocument != null) {
                fullText?.let { SelectionContainer { Text(it) } } ?: CircularProgressIndicator()
            } else passages.forEach { passage ->
                Column(Modifier.fillMaxWidth().glassEffect(hazeState, RoundedCornerShape(16.dp),
                    tintColor = MaterialTheme.colorScheme.surfaceContainerHigh, borderAlpha = 0.25f).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(passage.documentName, style = MaterialTheme.typography.titleSmall)
                Text("Passage ${passage.chunkIndex + 1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                SelectionContainer { Text(passage.text, style = MaterialTheme.typography.bodyMedium) }
                GlassActionButton(hazeState = hazeState, onClick = { selectedDocument = passage.documentId }) { Text("Preview document") }
                }
            }
        } } }, confirmButton = { GlassActionButton(hazeState = hazeState, onClick = { show = false }) { Text("Close") } },
        dismissButton = { if (selectedDocument != null) GlassActionButton(hazeState = hazeState, onClick = { selectedDocument = null }) { Text("Back to passages") } })
}
