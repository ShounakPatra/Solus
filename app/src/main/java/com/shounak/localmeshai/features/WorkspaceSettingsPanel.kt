@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.shounak.localmeshai.features

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import com.shounak.localmeshai.utils.LiquidGlassButton
import com.shounak.localmeshai.ui.screens.SettingsSwitchRow
import com.shounak.localmeshai.ui.screens.SettingsCategory
import dev.chrisbanes.haze.HazeState
import com.shounak.localmeshai.models.ModelInfo
import com.shounak.localmeshai.utils.CacheCleanupCoordinator
import com.shounak.localmeshai.utils.CleanupReason
import com.shounak.localmeshai.utils.ModelRuntimeCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun WorkspaceSettingsPanel(models: List<ModelInfo>, hazeState: HazeState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember(context) { WorkspacePreferences.get(context) }
    val revision by preferences.revision.collectAsState()
    val runtimeBusy by ModelRuntimeCoordinator.isRuntimeBusy.collectAsState()
    var working by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var storageOperation by remember { mutableStateOf(false) }
    var includeAttachments by remember { mutableStateOf(false) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var profileDialog by remember { mutableStateOf(false) }
    var storage by remember { mutableStateOf<StorageSnapshot?>(null) }
    fun runOperation(inStorage: Boolean = false, block: suspend () -> String) {
        if (working) return
        working = true
        storageOperation = inStorage
        status = null
        scope.launch {
            try { status = block() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { status = error.message ?: "Operation failed; existing data was preserved" }
            finally { working = false }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) runOperation { WorkspaceBackup(context).export(uri, includeAttachments) }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> restoreUri = uri }
    SettingsCategory(title = "🗂 Data", hazeState = hazeState) {
        Text("Back up chats, memories and settings. Model weights, access tokens and document indexes are excluded.", style = MaterialTheme.typography.bodySmall)
        Text("Backups contain private conversations. Choose a location you trust.", style = MaterialTheme.typography.bodySmall)
        SettingsSwitchRow(title = "Include available attachments", subtitle = "Add available photos, audio and documents to the backup",
            checked = includeAttachments, onCheckedChange = { includeAttachments = it }, enabled = !working)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        SettingsGlassAction("Export backup", Icons.Default.FileUpload, hazeState, enabled = !working && !runtimeBusy,
            onClick = { export.launch("Solus-backup.zip") })
        SettingsGlassAction("Restore backup", Icons.Default.FileDownload, hazeState, enabled = !working && !runtimeBusy,
            onClick = { restore.launch(arrayOf("application/zip", "application/octet-stream")) })
        if (runtimeBusy) Text("Finish the active model operation to export or restore.", style = MaterialTheme.typography.bodySmall)
        if (!storageOperation) {
            if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
    SettingsCategory(title = "🧠 Models", hazeState = hazeState) {
        Text("Save custom instructions, context limits and supported sampling settings for each model.", style = MaterialTheme.typography.bodySmall)
        SettingsGlassAction("Per-model profiles", Icons.Default.Tune, hazeState,
            enabled = !working && !runtimeBusy && models.isNotEmpty(), onClick = { profileDialog = true })
        if (runtimeBusy) Text("Finish the active model operation to change profiles.", style = MaterialTheme.typography.bodySmall)
    }
    SettingsCategory(title = "💾 Storage", hazeState = hazeState) {
        storage?.let { values ->
            listOf("Models" to values.models, "Chat attachments" to values.attachments, "Knowledge base" to values.knowledge,
                "Cache (including temporary attachments)" to values.cache, "Available on internal storage" to values.available).forEach { (label, bytes) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text(StorageDashboard.format(bytes), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        SettingsGlassAction("Refresh storage", Icons.Default.Refresh, hazeState, enabled = !working, onClick = {
            runOperation(inStorage = true) { storage = withContext(Dispatchers.IO) { StorageDashboard.inspect(context) }; "Storage usage refreshed" }
        })
        SettingsGlassAction("Clear unused cache", Icons.Default.DeleteSweep, hazeState, enabled = !working && !runtimeBusy, onClick = {
            runOperation(inStorage = true) {
                val result = withContext(Dispatchers.IO) { CacheCleanupCoordinator.getInstance(context).executeCleanupNow(CleanupReason.Explicit) }
                storage = withContext(Dispatchers.IO) { StorageDashboard.inspect(context) }
                "Removed ${result.deletedFilesCount} unused cache items. Active resources were preserved; ${result.failuresCount} cleanup failures."
            }
        })
        if (storageOperation) {
            if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
    SettingsCategory(title = "♿ Accessibility", hazeState = hazeState) {
        SettingsSwitchRow(title = "Reduce decorative motion", subtitle = "Limit decorative movement and animated transitions",
            checked = remember(revision) { preferences.reduceMotion }, onCheckedChange = preferences::setReduceMotion)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        SettingsSwitchRow(title = "Larger touch targets", subtitle = "Make supported buttons easier to tap",
            checked = remember(revision) { preferences.largeControls }, onCheckedChange = preferences::setLargeControls)
    }
    restoreUri?.let { uri ->
        GlassActionDialog(hazeState = hazeState, onDismiss = { restoreUri = null },
            title = "Restore this backup?", confirmLabel = "Restore", confirmEnabled = !runtimeBusy && !working,
            onConfirm = { restoreUri = null; runOperation { WorkspaceBackup(context).restore(uri) } }) {
            Text("Chats and saved memories will be added. Existing data will be preserved, and duplicate memories will be skipped. Portable app settings will be applied. Restoring the same backup again will skip already imported data.")
        }
    }
    if (profileDialog) ModelProfilesDialog(models, hazeState, onDismiss = { profileDialog = false })
}

@Composable
private fun ModelProfilesDialog(models: List<ModelInfo>, hazeState: HazeState, onDismiss: () -> Unit) {
    val preferences = WorkspacePreferences.get(LocalContext.current)
    var selectedId by remember { mutableStateOf(models.first().id) }
    val selected = models.firstOrNull { it.id == selectedId } ?: models.first()
    var menu by remember { mutableStateOf(false) }
    var system by remember(selectedId) { mutableStateOf(preferences.profile(selectedId).systemPrompt) }
    var context by remember(selectedId) { mutableStateOf(preferences.profile(selectedId).contextTokens?.toString().orEmpty()) }
    var temperature by remember(selectedId) { mutableStateOf(preferences.profile(selectedId).temperature?.toString().orEmpty()) }
    var topP by remember(selectedId) { mutableStateOf(preferences.profile(selectedId).topP?.toString().orEmpty()) }
    var topK by remember(selectedId) { mutableStateOf(preferences.profile(selectedId).topK?.toString().orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    val busy by ModelRuntimeCoordinator.isRuntimeBusy.collectAsState()
    val gguf = selected.bundle?.isGguf ?: selected.fileName.endsWith(".gguf", true)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Model profile") }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box {
                SettingsGlassAction(selected.name, Icons.Default.ExpandMore, hazeState, onClick = { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.heightIn(max = 260.dp)) {
                    models.sortedBy { it.name }.forEach { model -> DropdownMenuItem(text = { Text(model.name) }, onClick = { selectedId = model.id; menu = false; error = null }) }
                }
            }
            Text("Blank fields use model defaults. Saving reloads this model if it is currently selected. Runtime context limits and RAM/device safety checks still apply.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(system, { system = it }, label = { Text("Custom system instructions") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
            OutlinedTextField(context, { context = it }, label = { Text("Context limit (tokens)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (gguf) {
                OutlinedTextField(temperature, { temperature = it }, label = { Text("Temperature (0–2)") }, singleLine = true)
                OutlinedTextField(topP, { topP = it }, label = { Text("Top P (0.01–1)") }, singleLine = true)
                OutlinedTextField(topK, { topK = it }, label = { Text("Top K (1–200)") }, singleLine = true)
            } else Text("Sampling overrides are available for GGUF models. This runtime uses its own sampling defaults.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = {
        try {
            fun integer(value: String) = if (value.isBlank()) null else value.toIntOrNull() ?: error("Enter a whole number")
            fun decimal(value: String) = if (value.isBlank()) null else value.toFloatOrNull() ?: error("Enter a valid number")
            preferences.setProfile(selectedId, ModelProfile(system.trim(), integer(context), if (gguf) decimal(temperature) else null,
                if (gguf) decimal(topP) else null, if (gguf) integer(topK) else null))
            onDismiss()
        } catch (problem: Exception) { error = problem.message }
    }) { Text("Save profile") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun SettingsGlassAction(label: String, icon: ImageVector, hazeState: HazeState, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    LiquidGlassButton(onClick = onClick, hazeState = hazeState, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .border(0.9.dp, colors.primary.copy(alpha = 0.50f), shape),
        shape = shape, tintColor = colors.primary.copy(alpha = 0.20f),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = colors.primary)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = colors.primary)
    }
}
