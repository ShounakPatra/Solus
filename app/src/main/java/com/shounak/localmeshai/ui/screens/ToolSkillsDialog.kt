@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.shounak.localmeshai.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.shounak.localmeshai.ai.tools.*
import com.shounak.localmeshai.utils.AppSettings
import com.shounak.localmeshai.utils.glassEffect
import com.shounak.localmeshai.ui.components.GlassActionButton
import com.shounak.localmeshai.ui.components.GlassContentDialog
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
internal fun ToolSkillsDialog(hazeState: HazeState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = remember(context) { AppSettings.getInstance(context) }
    val settings by app.settings.collectAsState()
    val custom = remember(settings.customToolSkills) { CustomSkillCodec.decode(settings.customToolSkills) }
    val definitions = remember(custom) { SkillCatalog.builtins + custom.map { it.definition() } }
    val entries = remember(definitions) { listOf(
        SkillDefinition("calculator", "calculator", "Evaluate arithmetic expressions accurately.") { _, _ -> "" },
        SkillDefinition("get_current_datetime", "current-date-time", "Read the device's local date, time and timezone.") { _, _ -> "" },
        SkillDefinition("search_documents", "document-search", "Search the local RAG document index. Also requires RAG to be enabled.") { _, _ -> "" }
    ) + definitions }
    var query by remember { mutableStateOf("") }
    var viewed by remember { mutableStateOf<SkillDefinition?>(null) }
    var editing by remember { mutableStateOf<CustomSkill?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleteMoods by remember { mutableStateOf(false) }
    var dataRevision by remember { mutableIntStateOf(0) }
    var permissionForAll by remember { mutableStateOf(false) }
    fun updateEnabled(ids: Set<String>, enableMaster: Boolean = false) {
        app.updateSettings { it.copy(enabledToolSkills = ids, enableToolCalling = it.enableToolCalling || enableMaster) }
    }
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (permissionForAll) {
            val ids = entries.map { it.id }.toSet() - if (granted) emptySet() else setOf("read_calendar_events")
            updateEnabled(ids, true)
        } else if (granted) updateEnabled(app.settings.value.enabledToolSkills + "read_calendar_events")
        if (!granted) Toast.makeText(context, "Calendar reading stays disabled without permission.", Toast.LENGTH_SHORT).show()
        permissionForAll = false
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.widthIn(max = 640.dp).fillMaxWidth().fillMaxHeight(0.94f).imePadding().padding(12.dp)
            .glassEffect(hazeState, RoundedCornerShape(24.dp), tintColor = MaterialTheme.colorScheme.surfaceContainer, borderAlpha = 0.4f)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Manage skills", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    GlassActionButton(hazeState = hazeState, onClick = onDismiss) { Text("Done") }
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Tool calling", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                                SkillGlassSwitch(settings.enableToolCalling, hazeState, onCheckedChange = { enabled -> app.updateSettings { it.copy(enableToolCalling = enabled) } })
                            }
                            Text("Changes apply to the next message in your current chat. Enable only the skills you need; each uses model context.", style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(query, onValueChange = { query = it }, label = { Text("Search skills") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        }
                    }
                    item {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            GlassActionButton(hazeState = hazeState, onClick = { creating = true; editing = null }) { Text("+ New skill") }
                            GlassActionButton(hazeState = hazeState, onClick = {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
                                    permissionForAll = true; calendarPermission.launch(Manifest.permission.READ_CALENDAR)
                                } else updateEnabled(entries.map { it.id }.toSet(), true)
                            }) { Text("Turn on all") }
                            GlassActionButton(hazeState = hazeState, onClick = { updateEnabled(emptySet()) }) { Text("Turn off all") }
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${entries.size} skills • Wikipedia uses internet; calendar and email actions open a review screen.", style = MaterialTheme.typography.labelSmall)
                            Text("Custom skills require a model and chat backend that support tool calling. Availability is not limited to Gemma models.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(entries.filter { it.title.contains(query, true) || it.description.contains(query, true) }, key = { it.id }) { entry ->
                        Box(Modifier.fillMaxWidth().glassEffect(hazeState, RoundedCornerShape(20.dp),
                            tintColor = MaterialTheme.colorScheme.surfaceContainerHigh, borderAlpha = 0.25f)) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(entry.title, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                                    SkillGlassSwitch(entry.id in settings.enabledToolSkills, hazeState, onCheckedChange = { enabled ->
                                        if (enabled && entry.id == "read_calendar_events" && ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
                                            permissionForAll = false; calendarPermission.launch(Manifest.permission.READ_CALENDAR)
                                        } else updateEnabled(if (enabled) settings.enabledToolSkills + entry.id else settings.enabledToolSkills - entry.id)
                                    })
                                }
                                Text(entry.description, style = MaterialTheme.typography.bodySmall)
                                Text(entry.access, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                GlassActionButton(hazeState = hazeState, onClick = { viewed = entry }) { Text("View") }
                            }
                        }
                    }
                }
            }
        }
    }
    viewed?.let { entry ->
        var refresh by remember(entry.id) { mutableIntStateOf(0) }
        val scope = rememberCoroutineScope()
        val privateData by produceState("", entry.id, refresh, dataRevision) {
            value = withContext(Dispatchers.IO) {
                when (entry.id) {
                    "mood_tracker" -> runCatching { JSONObject(ToolSkillStore.get(context).mood(JSONObject().put("operation", "history").put("days", 90))).toString(2) }.getOrDefault("Mood data could not be read.")
                    else -> ""
                }
            }
        }
        val reminders by produceState(org.json.JSONArray(), entry.id, refresh) {
            if (entry.id == "schedule_notification") value = withContext(Dispatchers.IO) { runCatching { ToolReminders.records(context) }.getOrDefault(org.json.JSONArray()) }
        }
        GlassContentDialog(hazeState = hazeState, onDismissRequest = { viewed = null }, title = { Text(entry.title) },
            text = {
                Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(entry.description); Text(entry.access, color = MaterialTheme.colorScheme.primary)
                    val schema = when (entry.id) {
                        "calculator" -> CalculatorTool().getToolDescriptionJsonString()
                        "get_current_datetime" -> CurrentDateTimeTool().getToolDescriptionJsonString()
                        "search_documents" -> DocumentSearchTool { "" }.getToolDescriptionJsonString()
                        else -> entry.schema()
                    }
                    Text(JSONObject(schema).toString(2), style = MaterialTheme.typography.labelSmall)
                    custom.firstOrNull { it.id == entry.id }?.let { Text(it.instructions) }
                    if (entry.id == "mood_tracker") {
                        Text("Recent mood logs (up to 30, from the last 90 days)"); Text(privateData, style = MaterialTheme.typography.bodySmall)
                        GlassActionButton(hazeState = hazeState, onClick = { deleteMoods = true }) { Text("Delete all mood logs") }
                    }
                    if (entry.id == "schedule_notification") {
                        Text("Saved reminders (restored reminders are paused)")
                        for (i in 0 until reminders.length()) {
                            val record = reminders.getJSONObject(i)
                            key(record.getString("id")) {
                            Text("${record.getString("title")} • ${record.getString("datetime")} • ${if (record.optBoolean("active")) "Active" else "Paused"}")
                            if (!record.optBoolean("active")) SkillActionButton("reminder", record) { refresh++ }
                            GlassActionButton(hazeState = hazeState, onClick = { scope.launch {
                                val failure = withContext(Dispatchers.IO) { runCatching { ToolReminders.cancel(context, record.getString("id")) }.exceptionOrNull() }
                                if (failure != null) Toast.makeText(context, failure.message ?: "Could not delete reminder", Toast.LENGTH_SHORT).show()
                                refresh++
                            } }) { Text("Delete reminder") }
                            }
                        }
                    }
                }
            }, confirmButton = { GlassActionButton(hazeState = hazeState, onClick = { viewed = null }) { Text("Close") } },
            dismissButton = {
                custom.firstOrNull { it.id == entry.id }?.let { skill ->
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassActionButton(hazeState = hazeState, onClick = { viewed = null; editing = skill; creating = true }) { Text("Edit") }
                        GlassActionButton(hazeState = hazeState, onClick = {
                            app.updateSettings { it.copy(customToolSkills = CustomSkillCodec.encode(custom.filter { value -> value.id != skill.id }), enabledToolSkills = it.enabledToolSkills - skill.id) }
                            viewed = null
                        }) { Text("Delete") }
                    }
                }
            })
    }
    if (deleteMoods) {
        val scope = rememberCoroutineScope()
        GlassContentDialog(hazeState = hazeState, onDismissRequest = { deleteMoods = false }, title = { Text("Delete all mood logs?") },
            text = { Text("This removes the mood logs stored in Solus. Existing chat messages are kept.") },
            confirmButton = { GlassActionButton(hazeState = hazeState, onClick = { scope.launch {
                val failure = withContext(Dispatchers.IO) { runCatching { ToolSkillStore.get(context).clearMoods() }.exceptionOrNull() }
                if (failure != null) Toast.makeText(context, failure.message ?: "Could not delete mood data", Toast.LENGTH_SHORT).show()
                dataRevision++; deleteMoods = false
            } }) { Text("Delete") } }, dismissButton = { GlassActionButton(hazeState = hazeState, onClick = { deleteMoods = false }) { Text("Cancel") } })
    }
    if (creating) {
        var title by remember(editing) { mutableStateOf(editing?.title.orEmpty()) }
        var description by remember(editing) { mutableStateOf(editing?.description.orEmpty()) }
        var instructions by remember(editing) { mutableStateOf(editing?.instructions.orEmpty()) }
        var error by remember { mutableStateOf<String?>(null) }
        GlassContentDialog(hazeState = hazeState, onDismissRequest = { creating = false }, title = { Text("Custom prompt skill") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Describe when the model should call this skill and the instructions it should follow. Custom skills cannot execute scripts or add device permissions.", style = MaterialTheme.typography.bodySmall)
                Text("Saving enables tool calling and this skill for the next message. Your model must support tool calling.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(title, { title = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(description, { description = it }, label = { Text("When to use") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(instructions, { instructions = it }, label = { Text("Instructions") }, modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 8)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } }, confirmButton = { GlassActionButton(hazeState = hazeState, onClick = {
                try {
                    val id = editing?.id ?: "custom_" + java.util.UUID.randomUUID().toString().replace("-", "").take(20)
                    val skill = CustomSkill(id, title.trim(), description.trim(), instructions.trim())
                    app.updateSettings { it.copy(customToolSkills = CustomSkillCodec.encode(custom.filter { old -> old.id != id } + skill), enabledToolSkills = it.enabledToolSkills + id, enableToolCalling = true) }
                    creating = false
                } catch (failure: Exception) { error = failure.message ?: "Invalid skill" }
            }) { Text("Save & enable") } }, dismissButton = { GlassActionButton(hazeState = hazeState, onClick = { creating = false }) { Text("Cancel") } })
    }
}

@Composable
private fun SkillGlassSwitch(checked: Boolean, hazeState: HazeState, onCheckedChange: (Boolean) -> Unit) {
    Switch(checked, onCheckedChange,
        modifier = Modifier.glassEffect(hazeState, RoundedCornerShape(50),
            tintColor = MaterialTheme.colorScheme.primary.copy(alpha = if (checked) 0.16f else 0.04f), borderAlpha = 0.3f),
        colors = SwitchDefaults.colors(
            checkedThumbColor = MaterialTheme.colorScheme.primary,
            checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
            checkedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
            uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.2f),
            uncheckedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
        ))
}
