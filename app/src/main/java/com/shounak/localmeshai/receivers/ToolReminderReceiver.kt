package com.shounak.localmeshai.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.shounak.localmeshai.ai.tools.ToolReminders
import kotlinx.coroutines.*

class ToolReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    "com.shounak.localmeshai.SKILL_REMINDER" -> intent.getStringExtra("id")?.let { ToolReminders.fire(context, it) }
                    Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED -> ToolReminders.reschedule(context)
                }
            } catch (failure: Exception) { android.util.Log.w("ToolReminders", "Reminder unavailable", failure) }
            finally { pending.finish() }
        }
    }
}
