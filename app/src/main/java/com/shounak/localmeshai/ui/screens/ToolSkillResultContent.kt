package com.shounak.localmeshai.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.shounak.localmeshai.ai.tools.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
internal fun ToolSkillResultContent(use: ToolUse) {
    val data = remember(use) { runCatching { JSONObject(use.result) }.getOrNull() } ?: return
    val kind = data.optString("solus_tool_action")
    val expected = when (use.name) { "send_email" -> "email"; "create_calendar_event" -> "calendar"; "interactive_map" -> "map"; "schedule_notification" -> "reminder"; "mood_tracker" -> "delete_mood"; else -> "" }
    if (expected.isNotEmpty() && kind == expected) SkillActionButton(kind, data)
    val media = data.optString("solus_skill_media")
    if ((use.name == "qr_code" && media == "qr_code") || (use.name == "learn_something_new" && media == "learning_card")) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val bitmap by produceState<android.graphics.Bitmap?>(null, use.result) {
            value = withContext(Dispatchers.Default) { runCatching { SkillArtwork.render(data) }.getOrNull() }
        }
        bitmap?.let { image ->
            Image(image.asImageBitmap(), contentDescription = if (media == "qr_code") "Generated QR code" else "Learning card",
                modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp))
            OutlinedButton(onClick = {
                scope.launch {
                    try {
                        val file = withContext(Dispatchers.IO) {
                            val folder = java.io.File(context.cacheDir, "skill-media").apply { mkdirs() }
                            java.io.File(folder, java.util.UUID.randomUUID().toString() + ".png").also { file ->
                                file.outputStream().use { check(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
                            }
                        }
                        com.shounak.localmeshai.utils.AttachmentViewerUtils.openFileInExternalViewer(context, file, "image/png")
                    } catch (failure: Exception) { Toast.makeText(context, failure.message ?: "Could not open image", Toast.LENGTH_SHORT).show() }
                }
            }) { Text("Open / save image") }
        }
    }
}

@Composable
internal fun SkillActionButton(kind: String, data: JSONObject, onDone: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember(data.toString()) { mutableStateOf<String?>(null) }
    var busy by remember(data.toString()) { mutableStateOf(false) }
    var confirmDeletion by remember(data.toString()) { mutableStateOf(false) }
    if (confirmDeletion) AlertDialog(onDismissRequest = { confirmDeletion = false },
        title = { Text("Delete mood log?") }, text = { Text("Delete the mood log for ${data.optString("date")}? This cannot be undone.") },
        confirmButton = { TextButton(onClick = {
            confirmDeletion = false; busy = true
            scope.launch {
                status = withContext(Dispatchers.IO) {
                    runCatching { ToolSkillStore.get(context).confirmMoodDeletion(data) }.getOrElse { it.message ?: "Could not delete mood log" }
                }
                busy = false; onDone()
            }
        }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDeletion = false }) { Text("Cancel") } })
    fun confirmReminder() {
        if (busy) return
        busy = true
        scope.launch {
            status = withContext(Dispatchers.IO) {
                runCatching { ToolReminders.confirm(context, data) }.getOrElse { it.message ?: "Could not schedule reminder" }
            }
            busy = false; onDone()
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) confirmReminder() else status = "Notification permission was not granted."
    }
    OutlinedButton(enabled = !busy, onClick = {
        try {
            if (kind == "delete_mood") {
                confirmDeletion = true
            } else if (kind == "reminder") {
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else confirmReminder()
            } else {
                val intent = when (kind) {
                    "calendar" -> Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).apply {
                        putExtra(CalendarContract.Events.TITLE, SkillCatalog.text(data, "title", 200))
                        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, SkillTime.parse(data.getString("start")).toInstant().toEpochMilli())
                        putExtra(CalendarContract.EXTRA_EVENT_END_TIME, SkillTime.parse(data.getString("end")).toInstant().toEpochMilli())
                        putExtra(CalendarContract.Events.DESCRIPTION, data.optString("description").take(2000))
                        putExtra(CalendarContract.Events.EVENT_LOCATION, data.optString("location").take(500))
                    }
                    "email" -> Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", SkillCatalog.text(data, "to", 254), null)).apply {
                        putExtra(Intent.EXTRA_SUBJECT, SkillCatalog.text(data, "subject", 200, true))
                        putExtra(Intent.EXTRA_TEXT, SkillCatalog.text(data, "body", 4000, true))
                    }
                    "map" -> Intent(Intent.ACTION_VIEW, ("geo:0,0?q=" + Uri.encode(SkillCatalog.text(data, "location", 500))).toUri())
                    else -> error("Unsupported action")
                }
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                status = if (kind == "calendar") "Review and save in your calendar app." else if (kind == "email") "Review and send in your email app." else "Opened maps."
            }
        } catch (failure: Exception) { status = failure.message ?: "No app is available for this action." }
    }) {
        Text(when (kind) { "calendar" -> "Review calendar event"; "email" -> "Review email"; "map" -> "Open interactive map"; "reminder" -> "Confirm reminder"; "delete_mood" -> "Review mood deletion"; else -> "Review" })
    }
    status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}
