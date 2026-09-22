package com.shounak.localmeshai.utils

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
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

    suspend fun checkForUpdates(currentVersionName: String): UpdateCheckResult = withContext(Dispatchers.IO) {
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
                    return@withContext UpdateCheckResult.Error("GitHub API responded with code ${response.code}")
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
            UpdateCheckResult.Error(e.localizedMessage ?: "Failed to check for updates")
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
}
