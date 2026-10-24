package com.shounak.localmeshai.ai.tools

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.shounak.localmeshai.MainActivity
import com.shounak.localmeshai.R
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

internal object ToolReminders {
    private const val CHANNEL = "tool_reminders"
    private fun prefs(context: Context) = context.getSharedPreferences("tool_reminders", Context.MODE_PRIVATE)
    @Synchronized fun records(context: Context): JSONArray = JSONArray(prefs(context).getString("records", "[]") ?: "[]")
    @Suppress("UseKtx") // Preserve the checked commit result for durable reminders.
    private fun write(context: Context, array: JSONArray) { check(prefs(context).edit().putString("records", array.toString()).commit()) { "Could not save reminders" } }
    fun validate(record: JSONObject) {
        val id = record.getString("id"); require(UUID.fromString(id).toString() == id)
        SkillCatalog.text(record, "title", 120); SkillCatalog.text(record, "message", 500)
        SkillTime.parse(SkillCatalog.text(record, "datetime", 100))
        if (record.has("anchor")) SkillTime.parse(SkillCatalog.text(record, "anchor", 100))
        require(record.getString("repeat") in setOf("once", "daily"))
    }
    @Synchronized fun confirm(context: Context, draft: JSONObject): String {
        validate(draft)
        require(NotificationManagerCompat.from(context).areNotificationsEnabled()) { "Enable Solus notifications first" }
        if (Build.VERSION.SDK_INT >= 33) require(ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) { "Allow notification permission first" }
        val at = SkillTime.next(SkillTime.parse(if (draft.getString("repeat") == "daily") draft.optString("anchor", draft.getString("datetime")) else draft.getString("datetime")), draft.getString("repeat") == "daily", Instant.now())
        require(at.toInstant() > Instant.now()) { "This reminder time has passed; ask for a new reminder" }
        require(at.year <= java.time.Year.now().value + 5) { "Choose a reminder within five years" }
        val existing = records(context)
        val id = draft.getString("id")
        val list = (0 until existing.length()).map { existing.getJSONObject(it) }.filter { it.getString("id") != id }
        require(list.size < 64) { "At most 64 reminders are supported" }
        val record = JSONObject(draft.toString()).put("datetime", at.toString()).put("active", true)
        write(context, JSONArray(list + record))
        try { schedule(context, record) } catch (failure: Exception) { record.put("active", false); write(context, JSONArray(list + record)); throw failure }
        return "Reminder scheduled. Android may delay delivery to save battery."
    }
    @Synchronized fun cancel(context: Context, id: String) {
        val old = records(context)
        write(context, JSONArray((0 until old.length()).map { old.getJSONObject(it) }.filter { it.getString("id") != id }))
        context.getSystemService(AlarmManager::class.java).cancel(pending(context, id))
        context.getSystemService(NotificationManager::class.java).cancel("skill:$id", 0)
    }
    @Synchronized fun importPaused(context: Context, imported: JSONArray) {
        require(imported.length() <= 64)
        val old = records(context)
        val list = (0 until old.length()).map { old.getJSONObject(it) }.toMutableList()
        for (index in 0 until imported.length()) {
            val record = imported.getJSONObject(index); validate(record)
            if (list.none { it.getString("id") == record.getString("id") } && list.size < 64) list += JSONObject(record.toString()).put("active", false)
        }
        write(context, JSONArray(list))
    }
    @Synchronized fun reschedule(context: Context) {
        val array = records(context)
        for (i in 0 until array.length()) {
            val record = array.getJSONObject(i)
            if (record.optBoolean("active")) runCatching { schedule(context, record) }
        }
    }
    @Synchronized fun fire(context: Context, id: String) {
        val array = records(context)
        val record = (0 until array.length()).map { array.getJSONObject(it) }.firstOrNull { it.getString("id") == id && it.optBoolean("active") } ?: return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Skill reminders", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, id.hashCode(), Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            manager.notify("skill:$id", 0, NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(record.getString("title")).setContentText(record.getString("message"))
                .setStyle(NotificationCompat.BigTextStyle().bigText(record.getString("message"))).setContentIntent(open).setAutoCancel(true).build())
        }
        if (record.getString("repeat") == "daily") {
            val next = SkillTime.next(SkillTime.parse(record.optString("anchor", record.getString("datetime"))), true, Instant.now())
            record.put("datetime", next.toString()); schedule(context, record)
        } else record.put("active", false)
        write(context, array)
    }
    private fun pending(context: Context, id: String) = PendingIntent.getBroadcast(context, id.hashCode(),
        Intent(context, com.shounak.localmeshai.receivers.ToolReminderReceiver::class.java).setAction("com.shounak.localmeshai.SKILL_REMINDER")
            .setData("solus-reminder:$id".toUri()).putExtra("id", id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private fun schedule(context: Context, record: JSONObject) {
        val at = SkillTime.next(SkillTime.parse(if (record.getString("repeat") == "daily") record.optString("anchor", record.getString("datetime")) else record.getString("datetime")), record.getString("repeat") == "daily", Instant.now())
        val time = maxOf(at.toInstant().toEpochMilli(), System.currentTimeMillis() + 1000)
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pending(context, record.getString("id")))
    }
}
