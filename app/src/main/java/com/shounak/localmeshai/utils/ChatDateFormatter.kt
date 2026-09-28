package com.shounak.localmeshai.utils

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object ChatDateFormatter {

    /**
     * Formats a chat session timestamp into a human-readable string displaying when the user
     * last chatted with a model.
     *
     * Examples:
     * - Today: "Today, Sep 26 • 2:45 PM"
     * - Yesterday: "Yesterday, Sep 25 • 4:15 PM"
     * - Earlier dates: "Sep 20, 2026 • 11:30 AM"
     * - Unknown / legacy non-positive timestamps: "Earlier"
     */
    fun formatChatDate(timestamp: Long, nowMillis: Long = System.currentTimeMillis()): String {
        if (timestamp <= 0L) return "Earlier"

        val calNow = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val calTarget = Calendar.getInstance().apply {
            timeInMillis = timestamp
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val diffDays = ((calNow.timeInMillis - calTarget.timeInMillis) / (24L * 60L * 60L * 1000L)).toInt()

        val timeFormat = SimpleDateFormat("h:mm a", Locale.US)
        val timeStr = timeFormat.format(Date(timestamp))

        return when {
            diffDays <= 0 -> {
                val dayFormat = SimpleDateFormat("MMM d", Locale.US)
                "Today, ${dayFormat.format(Date(timestamp))} • $timeStr"
            }
            diffDays == 1 -> {
                val dayFormat = SimpleDateFormat("MMM d", Locale.US)
                "Yesterday, ${dayFormat.format(Date(timestamp))} • $timeStr"
            }
            else -> {
                val fullFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)
                "${fullFormat.format(Date(timestamp))} • $timeStr"
            }
        }
    }
}
