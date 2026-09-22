package com.shounak.localmeshai.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.shounak.localmeshai.utils.AppUpdateManager

/**
 * BroadcastReceiver triggered by Android OS when a new version of Solus is installed over an existing one.
 * Cleans up any downloaded APK installer files left in the cache.
 */
class UpdatePackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.d("UpdateReceiver", "Solus package replaced. Deleting downloaded APK installer files.")
            AppUpdateManager.deleteDownloadedApks(context)
        }
    }
}
