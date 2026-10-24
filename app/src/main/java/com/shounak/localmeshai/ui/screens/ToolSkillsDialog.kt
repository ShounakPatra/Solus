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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.shounak.localmeshai.ai.tools.*
import com.shounak.localmeshai.utils.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
internal fun ToolSkillsDialog(onDismiss: () -> Unit) {
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
        Surface(Modifier.fillMaxWidth().fillMaxHeight(0.94f).padding(12.dp), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Manage skills", style = MaterialTheme.typography.headlineSmall)
                    TextButton(onClick = onDismiss) { Text("Done") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Tool calling", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    Switch(settings.enableToolCalling, onCheckedChange = { enabled -> app.updateSettings { it.copy(enableToolCalling = enabled) } })
                }
                Text("Changes apply to the next message in your current chat. Enable only the skills you need; each uses model context.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(query, onValueChange = { query = it }, label = { Text("Search skills") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { creating = true; editing = null }) { Text("+ New") }
                    TextButton(onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
                            permissionForAll = true; calendarPermission.launch(Manifest.permission.READ_CALENDAR)
                        } else updateEnabled(entries.map { it.id }.toSet(), true)
                    }) { Text("Turn on all") }
                    TextButton(onClick = { updateEnabled(emptySet()) }) { Text("Turn off all") }
                }
                Text("${entries.size} skills • Wikipedia uses internet; calendar and email actions open a review screen.", style = MaterialTheme.typography.labelSmall)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(entries.filter { it.title.contains(query, true) || it.description.contains(query, true) }, key = { it.id }) { entry ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row {
                                    Text(entry.title, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                                    Switch(entry.id in settings.enabledToolSkills, onCheckedChange = { enabled ->
                                        if (enabled && entry.id == "read_calendar_events" && ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
                                            permissionForAll = false; calendarPermission.launch(Manifest.permission.READ_CALENDAR)
                                        } else updateEnabled(if (enabled) settings.enabledToolSkills + entry.id else settings.enabledToolSkills - entry.id)
                                    })
                                }
                                Text(entry.description, style = MaterialTheme.typography.bodySmall)
                                Text(entry.access, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                TextButton(onClick = { viewed = entry }) { Text("View") }
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
        AlertDialog(onDismissRequest = { viewed = null }, title = { Text(entry.title) },
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
                        TextButton(onClick = { deleteMoods = true }) { Text("Delete all mood logs") }
                    }
                    if (entry.id == "schedule_notification") {
                        Text("Saved reminders (restored reminders are paused)")
                        for (i in 0 until reminders.length()) {
                            val record = reminders.getJSONObject(i)
                            Text("${record.getString("title")} • ${record.getString("datetime")} • ${if (record.optBoolean("active")) "Active" else "Paused"}")
                            if (!record.optBoolean("active")) SkillActionButton("reminder", record) { refresh++ }
                            TextButton(onClick = { scope.launch {
                                val failure = withContext(Dispatchers.IO) { runCatching { ToolReminders.cancel(context, record.getString("id")) }.exceptionOrNull() }
                                if (failure != null) Toast.makeText(context, failure.message ?: "Could not delete reminder", Toast.LENGTH_SHORT).show()
                                refresh++
                            } }) { Text("Delete reminder") }
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = { viewed = null }) { Text("Close") } },
            dismissButton = {
                custom.firstOrNull { it.id == entry.id }?.let { skill ->
                    Row {
                        TextButton(onClick = { viewed = null; editing = skill; creating = true }) { Text("Edit") }
                        TextButton(onClick = {
                            app.updateSettings { it.copy(customToolSkills = CustomSkillCodec.encode(custom.filter { value -> value.id != skill.id }), enabledToolSkills = it.enabledToolSkills - skill.id) }
                            viewed = null
                        }) { Text("Delete") }
                    }
                }
            })
    }
    if (deleteMoods) {
        val scope = rememberCoroutineScope()
        AlertDialog(onDismissRequest = { deleteMoods = false }, title = { Text("Delete all mood logs?") },
            text = { Text("This removes the mood logs stored in Solus. Existing chat messages are kept.") },
            confirmButton = { TextButton(onClick = { scope.launch {
                val failure = withContext(Dispatchers.IO) { runCatching { ToolSkillStore.get(context).clearMoods() }.exceptionOrNull() }
                if (failure != null) Toast.makeText(context, failure.message ?: "Could not delete mood data", Toast.LENGTH_SHORT).show()
                dataRevision++; deleteMoods = false
            } }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { deleteMoods = false }) { Text("Cancel") } })
    }
    if (creating) {
        var title by remember(editing) { mutableStateOf(editing?.title.orEmpty()) }
        var description by remember(editing) { mutableStateOf(editing?.description.orEmpty()) }
        var instructions by remember(editing) { mutableStateOf(editing?.instructions.orEmpty()) }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { creating = false }, title = { Text("Custom prompt skill") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Describe when the model should call this skill and the instructions it should follow. Custom skills cannot execute scripts or add device permissions.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(title, { title = it }, label = { Text("Name") })
                OutlinedTextField(description, { description = it }, label = { Text("When to use") })
                OutlinedTextField(instructions, { instructions = it }, label = { Text("Instructions") }, minLines = 4, maxLines = 8)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } }, confirmButton = { TextButton(onClick = {
                try {
                    val id = editing?.id ?: "custom_" + java.util.UUID.randomUUID().toString().replace("-", "").take(20)
                    val skill = CustomSkill(id, title.trim(), description.trim(), instructions.trim())
                    app.updateSettings { it.copy(customToolSkills = CustomSkillCodec.encode(custom.filter { old -> old.id != id } + skill), enabledToolSkills = it.enabledToolSkills + id) }
                    creating = false
                } catch (failure: Exception) { error = failure.message ?: "Invalid skill" }
            }) { Text("Save") } }, dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } })
    }
}
