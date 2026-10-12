package com.shounak.localmeshai.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.shounak.localmeshai.utils.AppSettings
import com.shounak.localmeshai.utils.AppUpdateManager

/**
 * BroadcastReceiver triggered when the device finishes booting.
 * Restores and verifies the periodic background update check job if automatic updates are enabled.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            Log.d("BootCompletedReceiver", "Device boot completed ($action). Verifying update check schedule.")
            val appSettings = AppSettings.getInstance(context)
            if (appSettings.settings.value.autoCheckUpdates) {
                AppUpdateManager.scheduleBackgroundUpdateCheck(context, force = true)
            }
        }
    }
}
