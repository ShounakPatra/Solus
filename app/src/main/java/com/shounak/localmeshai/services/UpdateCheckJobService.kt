package com.shounak.localmeshai.services

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log
import com.shounak.localmeshai.BuildConfig
import com.shounak.localmeshai.utils.AppSettings
import com.shounak.localmeshai.utils.AppUpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Android JobService that periodically queries GitHub releases for Solus updates in the background.
 * Operates when network connectivity is available, even when Solus is completely closed.
 * Sends high-priority heads-up update notifications when newer releases are discovered.
 */
class UpdateCheckJobService : JobService() {
    private var jobScope: CoroutineScope? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        Log.d(TAG, "UpdateCheckJobService triggered in background")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        jobScope = scope

        scope.launch {
            try {
                val appSettings = AppSettings.getInstance(applicationContext)
                if (!appSettings.settings.value.autoCheckUpdates) {
                    Log.d(TAG, "autoCheckUpdates is disabled in settings; cancelling scheduled job.")
                    AppUpdateManager.cancelBackgroundUpdateCheck(applicationContext)
                    jobFinished(params, false)
                    return@launch
                }

                val result = AppUpdateManager.checkForUpdates(
                    currentVersionName = BuildConfig.VERSION_NAME,
                    context = applicationContext
                )

                when (result) {
                    is AppUpdateManager.UpdateCheckResult.UpdateAvailable -> {
                        Log.i(TAG, "Background update check found new version: ${result.updateInfo.latestVersion}")
                        AppUpdateManager.sendUpdateAvailableNotification(
                            context = applicationContext,
                            updateInfo = result.updateInfo
                        )
                    }
                    is AppUpdateManager.UpdateCheckResult.UpToDate -> {
                        Log.d(TAG, "Background update check: app is up-to-date (${result.currentVersion})")
                        AppUpdateManager.onAppUpToDate(applicationContext)
                    }
                    is AppUpdateManager.UpdateCheckResult.Error -> {
                        Log.w(TAG, "Background update check encountered non-fatal error: ${result.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected failure during background update check", e)
            } finally {
                jobFinished(params, false)
            }
        }

        return true // Asynchronous work running
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        Log.d(TAG, "UpdateCheckJobService stopped by system")
        jobScope?.cancel()
        jobScope = null
        return true // Reschedule if aborted prematurely by system
    }

    companion object {
        private const val TAG = "UpdateCheckJobService"
    }
}
