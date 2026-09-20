package com.shounak.localmeshai.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
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
}
