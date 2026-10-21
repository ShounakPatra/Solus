package com.shounak.localmeshai.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.shounak.localmeshai.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

object AppUpdateManager {
    private const val GITHUB_REPO_OWNER = "ShounakPatra"
    private const val GITHUB_REPO_NAME = "Solus"
    private const val RELEASES_API_URL =
        "https://api.github.com/repos/$GITHUB_REPO_OWNER/$GITHUB_REPO_NAME/releases/latest"
    const val GITHUB_REPO_URL = "https://github.com/$GITHUB_REPO_OWNER/$GITHUB_REPO_NAME"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    data class UpdateInfo(
        val latestVersion: String,
        val releaseNotes: String,
        val htmlUrl: String,
        val downloadUrl: String?
    )

    sealed class UpdateCheckResult {
        data class UpdateAvailable(val updateInfo: UpdateInfo) : UpdateCheckResult()
        data class UpToDate(val currentVersion: String) : UpdateCheckResult()
        data class Error(val message: String) : UpdateCheckResult()
    }

    suspend fun checkForUpdates(currentVersionName: String, context: Context? = null): UpdateCheckResult = withContext(Dispatchers.IO) {
        if (context != null && !NetworkUtils.isConnected(context)) {
            return@withContext UpdateCheckResult.Error("No internet connection. Please connect to Wi-Fi or mobile data to check for updates.")
        }
        try {
            val request = Request.Builder()
                .url(RELEASES_API_URL)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "Solus-Android-App")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    if (response.code == 404) {
                        return@withContext UpdateCheckResult.UpToDate(currentVersionName)
                    }
                    if (response.code == 403 || response.code == 429) {
                        return@withContext UpdateCheckResult.Error("GitHub API rate limit reached. Please try checking again in a few minutes.")
                    }
                    return@withContext UpdateCheckResult.Error("GitHub server returned HTTP ${response.code}")
                }

                val body = response.body.string()
                if (body.isBlank()) return@withContext UpdateCheckResult.Error("Empty response from server")

                val json = JSONObject(body)
                val rawTagName = json.optString("tag_name", "").trim()
                val latestVersion = rawTagName.removePrefix("v").removePrefix("V")
                val releaseNotes = json.optString("body", "No release notes provided.")
                val htmlUrl = json.optString("html_url", GITHUB_REPO_URL)

                var apkDownloadUrl: String? = null
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkDownloadUrl = asset.optString("browser_download_url").ifBlank { null }
                            break
                        }
                    }
                }

                if (isVersionNewer(latestVersion, currentVersionName)) {
                    UpdateCheckResult.UpdateAvailable(
                        UpdateInfo(
                            latestVersion = latestVersion,
                            releaseNotes = releaseNotes,
                            htmlUrl = htmlUrl,
                            downloadUrl = apkDownloadUrl
                        )
                    )
                } else {
                    UpdateCheckResult.UpToDate(currentVersionName)
                }
            }
        } catch (e: Exception) {
            val message = if (NetworkUtils.isOfflineException(e) || (context != null && !NetworkUtils.isConnected(context))) {
                "No internet connection. Please connect to Wi-Fi or mobile data to check for updates."
            } else if (NetworkUtils.isNetworkException(e)) {
                "Network connection lost while checking for updates. Please try again."
            } else {
                e.localizedMessage ?: "Failed to check for updates"
            }
            UpdateCheckResult.Error(message)
        }
    }

    fun isVersionNewer(latestVersion: String, currentVersion: String): Boolean {
        val cleanLatest = latestVersion.trim().removePrefix("v").removePrefix("V")
        val cleanCurrent = currentVersion.trim().removePrefix("v").removePrefix("V")

        if (cleanLatest.isBlank()) return false
        if (cleanCurrent.isBlank()) return true

        val latestParts = cleanLatest.split('.').mapNotNull { it.takeWhile { char -> char.isDigit() }.toIntOrNull() }
        val currentParts = cleanCurrent.split('.').mapNotNull { it.takeWhile { char -> char.isDigit() }.toIntOrNull() }

        val maxLen = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    /**
     * Downloads an APK from the given URL into cacheDir/updates/Solus-v[version].apk.
     * Validates the downloaded APK file integrity using PackageManager before completing.
     */
    suspend fun downloadApk(
        context: Context,
        downloadUrl: String,
        version: String,
        verifier: UpdateArtifactVerifier = UpdateArtifactVerifier(context),
        onProgress: ((Float) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        if (!NetworkUtils.isConnected(context)) {
            return@withContext Result.failure(IOException("No internet connection. Please connect to Wi-Fi or mobile data to download updates."))
        }
        if (!downloadUrl.startsWith("https://", ignoreCase = true)) {
            return@withContext Result.failure(SecurityException("Insecure update URL rejected: only HTTPS is permitted."))
        }

        val updatesDir = getUpdatesDir(context)
        val cleanVersion = version.trim().removePrefix("v").removePrefix("V")
        val sanitizedVersion = cleanVersion.filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
        if (sanitizedVersion.isBlank() || sanitizedVersion.contains("..")) {
            return@withContext Result.failure(IllegalArgumentException("Invalid update version: $version"))
        }

        val targetApk = File(updatesDir, "Solus-v$sanitizedVersion.apk")
        val tempApk = File(updatesDir, "Solus-v$sanitizedVersion.apk.tmp")
        val canonicalUpdatesDir = updatesDir.canonicalFile
        if (!targetApk.canonicalFile.startsWith(canonicalUpdatesDir) || !tempApk.canonicalFile.startsWith(canonicalUpdatesDir)) {
            return@withContext Result.failure(SecurityException("Target APK path escapes updates directory"))
        }

        // If already downloaded and valid according to verifier, return it directly
        if (targetApk.exists() && targetApk.length() > 0L) {
            when (val verification = verifier.verify(targetApk)) {
                is VerificationResult.Success -> {
                    onProgress?.invoke(1f)
                    return@withContext Result.success(targetApk)
                }
                is VerificationResult.Failure -> {
                    targetApk.delete()
                }
            }
        }

        if (tempApk.exists()) {
            tempApk.delete()
        }

        val request = Request.Builder()
            .url(downloadUrl)
            .header("User-Agent", "Solus-Android-App")
            .build()

        val call = httpClient.newCall(request)
        val cancellationHandle = coroutineContext[Job]?.abortTransportOnCancellation {
            call.cancel()
        }

        try {
            call.execute().use { response ->
                coroutineContext.ensureActive()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("Server returned HTTP ${response.code}"))
                }
                if (response.request.url.scheme != "https") {
                    return@withContext Result.failure(SecurityException("Update download redirected to non-HTTPS URL."))
                }
                val body = response.body
                val totalBytes = body.contentLength()
                var downloadedBytes = 0L

                body.byteStream().use { input ->
                    tempApk.outputStream().use { output ->
                        val buffer = ByteArray(8 * 1024)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            kotlin.coroutines.coroutineContext.ensureActive()
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            if (totalBytes > 0L) {
                                val progress = (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                                onProgress?.invoke(progress)
                            }
                        }
                        output.flush()
                    }
                }

                coroutineContext.ensureActive()
                if (targetApk.exists()) targetApk.delete()
                if (!tempApk.renameTo(targetApk)) {
                    tempApk.copyTo(targetApk, overwrite = true)
                    tempApk.delete()
                }

                // Verify valid APK package and identity independently
                when (val verification = verifier.verify(targetApk)) {
                    is VerificationResult.Success -> {
                        onProgress?.invoke(1f)
                        Result.success(targetApk)
                    }
                    is VerificationResult.Failure -> {
                        targetApk.delete()
                        Result.failure(SecurityException(verification.reason))
                    }
                }
            }
        } catch (e: CancellationException) {
            call.cancel()
            tempApk.delete()
            throw e
        } catch (e: Exception) {
            tempApk.delete()
            Log.e("AppUpdateManager", "Failed to download update APK", e)
            Result.failure(e)
        } finally {
            cancellationHandle?.dispose()
            call.cancel()
        }
    }

    /**
     * Launches the system Package Installer to install the downloaded APK after verifying identity.
     */
    fun installApk(
        context: Context,
        apkFile: File,
        verifier: UpdateArtifactVerifier = UpdateArtifactVerifier(context)
    ): Boolean {
        return try {
            if (!apkFile.exists() || apkFile.length() == 0L) {
                Log.w("AppUpdateManager", "APK file does not exist or is empty: ${apkFile.absolutePath}")
                return false
            }

            // Verify before installing
            when (val verification = verifier.verify(apkFile)) {
                is VerificationResult.Success -> {
                    // Passed verification
                }
                is VerificationResult.Failure -> {
                    Log.e("AppUpdateManager", "Installation blocked: ${verification.reason}")
                    apkFile.delete()
                    return false
                }
            }

            val appContext = context.applicationContext
            val contentUri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            appContext.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e("AppUpdateManager", "Failed to launch package installer", e)
            false
        }
    }

    fun getUpdatesDir(context: Context): File {
        return File(context.filesDir, "updates").apply { mkdirs() }
    }

    /**
     * Centralized update artifact cleanup policy.
     * Cleans up installer APKs and partial temp downloads when updates are obsolete:
     * - After package replacement (new version is already installed)
     * - When the app is confirmed up to date (no newer version needed)
     * - Or when explicitly requested.
     */
    fun cleanupUpdateArtifacts(context: Context) {
        try {
            val durableUpdates = getUpdatesDir(context)
            deleteApksInDirectory(durableUpdates)
            val legacyCacheUpdates = File(context.cacheDir, "updates")
            if (legacyCacheUpdates.exists()) {
                deleteApksInDirectory(legacyCacheUpdates)
            }
            deleteApksInDirectory(context.cacheDir)
        } catch (e: Exception) {
            Log.e("AppUpdateManager", "Error cleaning up update artifacts", e)
        }
    }

    /**
     * Deletes any downloaded APK installer files and partial download temp files.
     */
    fun deleteDownloadedApks(context: Context) {
        cleanupUpdateArtifacts(context)
    }

    fun onPackageReplaced(context: Context) {
        cleanupUpdateArtifacts(context)
        cancelUpdateNotification(context)
        val appSettings = AppSettings.getInstance(context)
        if (appSettings.settings.value.autoCheckUpdates) {
            scheduleBackgroundUpdateCheck(context, force = true)
        }
    }

    fun onAppUpToDate(context: Context) {
        cleanupUpdateArtifacts(context)
        cancelUpdateNotification(context)
    }

    internal fun deleteApksInDirectory(dir: File) {
        if (dir.exists() && dir.isDirectory) {
            dir.listFiles()?.forEach { file ->
                if (file.name.endsWith(".apk", ignoreCase = true) ||
                    file.name.endsWith(".tmp", ignoreCase = true)
                ) {
                    try {
                        file.delete()
                    } catch (_: Exception) {}
                }
            }
        }
    }

    const val UPDATE_CHANNEL_ID = "app_updates_high"
    const val UPDATE_NOTIFICATION_ID = 9001
    const val BACKGROUND_UPDATE_JOB_ID = 9002
    const val UPDATE_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L // 6 hours
    const val UPDATE_CHECK_FLEX_MS = 30 * 60 * 1000L // 30 minutes flex
    const val EXTRA_OPEN_SETTINGS_ABOUT = "extra_open_settings_about"
    const val EXTRA_UPDATE_VERSION = "extra_update_version"
    const val EXTRA_UPDATE_NOTES = "extra_update_notes"
    const val EXTRA_UPDATE_HTML_URL = "extra_update_html_url"
    const val EXTRA_UPDATE_DOWNLOAD_URL = "extra_update_download_url"

    /**
     * Ensures the notification channel for high-priority app updates exists.
     */
    fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                UPDATE_CHANNEL_ID,
                "App Updates",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High priority notifications when new Solus releases are available."
                enableLights(true)
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Schedules periodic background update checks using Android JobScheduler.
     * Checks will run even when Solus is completely closed, provided network is connected.
     * Persisted across device reboots when RECEIVE_BOOT_COMPLETED is available.
     *
     * @param context Application context
     * @param force If true, cancels and reschedules even if already pending.
     */
    fun scheduleBackgroundUpdateCheck(context: Context, force: Boolean = false): Boolean {
        val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
            ?: return false

        ensureNotificationChannel(context)

        if (!force && jobScheduler.getPendingJob(BACKGROUND_UPDATE_JOB_ID) != null) {
            Log.d("AppUpdateManager", "Background update check job already scheduled.")
            return true
        }

        val componentName = ComponentName(context, "com.shounak.localmeshai.services.UpdateCheckJobService")
        val builder = JobInfo.Builder(BACKGROUND_UPDATE_JOB_ID, componentName)
            .setPeriodic(UPDATE_CHECK_INTERVAL_MS, UPDATE_CHECK_FLEX_MS)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)

        try {
            builder.setPersisted(true)
        } catch (e: Exception) {
            Log.w("AppUpdateManager", "Could not set job persisted: ${e.message}")
        }

        val result = jobScheduler.schedule(builder.build())
        val success = (result == JobScheduler.RESULT_SUCCESS)
        if (success) {
            Log.d("AppUpdateManager", "Background update check job successfully scheduled.")
        } else {
            Log.w("AppUpdateManager", "Failed to schedule background update check job.")
        }
        return success
    }

    /**
     * Cancels any scheduled background update checks.
     */
    fun cancelBackgroundUpdateCheck(context: Context) {
        val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
        try {
            jobScheduler.cancel(BACKGROUND_UPDATE_JOB_ID)
            Log.d("AppUpdateManager", "Background update check job cancelled.")
        } catch (e: Exception) {
            Log.w("AppUpdateManager", "Failed to cancel update check job", e)
        }
    }

    /**
     * Checks if the background update check job is currently scheduled.
     */
    fun isBackgroundUpdateCheckScheduled(context: Context): Boolean {
        val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return false
        return jobScheduler.getPendingJob(BACKGROUND_UPDATE_JOB_ID) != null
    }

    /**
     * Sends a high-priority heads-up notification alerting the user that a new release is available.
     * Tapping the notification brings the user directly to the About Solus & App Updates section in Settings.
     */
    fun sendUpdateAvailableNotification(context: Context, updateInfo: UpdateInfo) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        ensureNotificationChannel(context)

        val appIntent = Intent(context, com.shounak.localmeshai.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_SETTINGS_ABOUT, true)
            putExtra(EXTRA_UPDATE_VERSION, updateInfo.latestVersion)
            putExtra(EXTRA_UPDATE_NOTES, updateInfo.releaseNotes)
            putExtra(EXTRA_UPDATE_HTML_URL, updateInfo.htmlUrl)
            putExtra(EXTRA_UPDATE_DOWNLOAD_URL, updateInfo.downloadUrl)
        }
        val appPendingIntent = PendingIntent.getActivity(
            context,
            UPDATE_NOTIFICATION_ID,
            appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val targetUrl = updateInfo.htmlUrl.ifBlank { GITHUB_REPO_URL }
        val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val viewPendingIntent = PendingIntent.getActivity(
            context,
            UPDATE_NOTIFICATION_ID + 1,
            viewIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = "Update Available: Solus v${updateInfo.latestVersion}"
        val content = "A new release is available. Tap to view and install in Settings."
        val notificationSummary = UpdateNotesFormatter.formatForNotification(updateInfo.releaseNotes)
        val bigText = "A new release (v${updateInfo.latestVersion}) is available.\n\n$notificationSummary"

        val notification = NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setContentIntent(appPendingIntent)
            .addAction(android.R.drawable.ic_menu_preferences, "Open Settings", appPendingIntent)
            .addAction(android.R.drawable.ic_menu_view, "View on GitHub", viewPendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        try {
            notificationManager.notify(UPDATE_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w("AppUpdateManager", "Failed to post update notification: missing permission", e)
        } catch (e: Exception) {
            Log.w("AppUpdateManager", "Failed to post update notification", e)
        }
    }

    /**
     * Cancels any pending update notification if the app is already up to date.
     */
    fun cancelUpdateNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        try {
            notificationManager.cancel(UPDATE_NOTIFICATION_ID)
        } catch (_: Exception) {}
    }
}
