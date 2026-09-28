package com.shounak.localmeshai.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.shounak.localmeshai.R
import kotlinx.coroutines.Dispatchers
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
        onProgress: ((Float) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        if (!NetworkUtils.isConnected(context)) {
            return@withContext Result.failure(IOException("No internet connection. Please connect to Wi-Fi or mobile data to download updates."))
        }
        try {
            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val cleanVersion = version.trim().removePrefix("v").removePrefix("V")
            val targetApk = File(updatesDir, "Solus-v$cleanVersion.apk")
            val tempApk = File(updatesDir, "Solus-v$cleanVersion.apk.tmp")

            // If already downloaded and valid, return it directly
            if (targetApk.exists() && targetApk.length() > 0L) {
                val pkgInfo = context.packageManager.getPackageArchiveInfo(targetApk.absolutePath, 0)
                if (pkgInfo != null) {
                    onProgress?.invoke(1f)
                    return@withContext Result.success(targetApk)
                } else {
                    targetApk.delete()
                }
            }

            if (tempApk.exists()) {
                tempApk.delete()
            }

            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "Solus-Android-App")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("Server returned HTTP ${response.code}"))
                }
                val body = response.body ?: return@withContext Result.failure(IOException("Empty response body"))
                val totalBytes = body.contentLength()
                var downloadedBytes = 0L

                body.byteStream().use { input ->
                    tempApk.outputStream().use { output ->
                        val buffer = ByteArray(8 * 1024)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
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

                if (targetApk.exists()) targetApk.delete()
                if (!tempApk.renameTo(targetApk)) {
                    tempApk.copyTo(targetApk, overwrite = true)
                    tempApk.delete()
                }

                // Verify valid APK file
                val pkgInfo = context.packageManager.getPackageArchiveInfo(targetApk.absolutePath, 0)
                if (pkgInfo == null) {
                    targetApk.delete()
                    return@withContext Result.failure(IOException("Downloaded file is not a valid APK package"))
                }

                onProgress?.invoke(1f)
                Result.success(targetApk)
            }
        } catch (e: Exception) {
            Log.e("AppUpdateManager", "Failed to download update APK", e)
            Result.failure(e)
        }
    }

    /**
     * Launches the system Package Installer to install the downloaded APK.
     */
    fun installApk(context: Context, apkFile: File): Boolean {
        return try {
            if (!apkFile.exists()) {
                Log.w("AppUpdateManager", "APK file does not exist: ${apkFile.absolutePath}")
                return false
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

    /**
     * Deletes any downloaded APK installer files and partial download temp files.
     */
    fun deleteDownloadedApks(context: Context) {
        try {
            deleteApksInDirectory(File(context.cacheDir, "updates"))
            deleteApksInDirectory(context.cacheDir)
        } catch (e: Exception) {
            Log.e("AppUpdateManager", "Error deleting downloaded APKs", e)
        }
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

    /**
     * Sends a high-priority heads-up notification alerting the user that a new release is available
     * on the GitHub repository (https://github.com/shounakpatra/solus).
     */
    fun sendUpdateAvailableNotification(context: Context, updateInfo: UpdateInfo) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                UPDATE_CHANNEL_ID,
                "App Updates",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High priority notifications when new Solus releases are available on GitHub."
                enableLights(true)
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val targetUrl = updateInfo.htmlUrl.ifBlank { GITHUB_REPO_URL }
        val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            UPDATE_NOTIFICATION_ID,
            viewIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val appIntent = Intent(context, com.shounak.localmeshai.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val appPendingIntent = PendingIntent.getActivity(
            context,
            UPDATE_NOTIFICATION_ID + 1,
            appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = "Update Available: Solus v${updateInfo.latestVersion}"
        val content = "A new release is available on GitHub ($GITHUB_REPO_OWNER/$GITHUB_REPO_NAME). Tap to view."
        val bigText = "A new release (v${updateInfo.latestVersion}) is available on GitHub ($GITHUB_REPO_OWNER/$GITHUB_REPO_NAME).\n\n${updateInfo.releaseNotes.take(300)}"

        val notification = NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_view, "View on GitHub", pendingIntent)
            .addAction(android.R.drawable.ic_dialog_info, "Open App", appPendingIntent)
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
