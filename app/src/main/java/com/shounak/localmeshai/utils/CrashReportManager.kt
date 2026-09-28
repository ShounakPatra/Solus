package com.shounak.localmeshai.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import com.shounak.localmeshai.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CrashReport(
    val timestamp: Long,
    val title: String,
    val details: String,
    val isNativeCrash: Boolean = false
) {
    val formattedDate: String
        get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}

object CrashReportManager {
    private const val TAG = "CrashReportManager"
    const val GITHUB_REPO_OWNER = "ShounakPatra"
    const val GITHUB_REPO_NAME = "Solus"
    const val GITHUB_ISSUES_URL = "https://github.com/$GITHUB_REPO_OWNER/$GITHUB_REPO_NAME/issues/new"
    private const val MAX_SAVED_REPORTS = 10

    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private var isInstalled = false

    fun install(context: Context) {
        if (isInstalled) return
        val appContext = context.applicationContext
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                recordJvmCrash(appContext, thread, throwable)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to record uncaught exception", e)
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
        isInstalled = true
    }

    fun recordNativeCrash(context: Context, modelId: String, details: String) {
        val report = buildReportString(
            context = context,
            title = "Native Engine Crash: $modelId",
            stackTrace = details,
            extraContext = "Native initialization crash detected by InitCrashGuard."
        )
        saveReport(context, report, isNative = true)
    }

    private fun recordJvmCrash(context: Context, thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val stackTrace = sw.toString()
        val title = "${throwable.javaClass.simpleName}: ${throwable.message ?: "Uncaught Exception"}"
        val report = buildReportString(
            context = context,
            title = title,
            stackTrace = stackTrace,
            extraContext = "Thread: ${thread.name}"
        )
        saveReport(context, report, isNative = false)
    }

    private const val PREFS_NAME = "solus_crash_reports"
    private const val KEY_HAS_UNPROMPTED_CRASH = "has_unprompted_crash"
    private const val KEY_PENDING_CRASH_FILE = "pending_crash_file"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun buildReportString(
        context: Context,
        title: String,
        stackTrace: String,
        extraContext: String
    ): String {
        val totalRam = String.format(Locale.US, "%.1f GB", DeviceUtils.getTotalRamGB(context))
        val availRam = "${DeviceUtils.getAvailableRamMb(context)} MB"
        val chip = DeviceUtils.currentDeviceChipLabel()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date())
        val freeStorageMb = runCatching { context.filesDir.freeSpace / (1024L * 1024L) }.getOrDefault(-1L)
        val tempCelsius = DeviceUtils.getBatteryTemperatureCelsius(context)?.let { "$it °C" } ?: "N/A"

        return buildString {
            appendLine("### Solus Crash Report")
            appendLine("- **Date/Time:** $dateFormat")
            appendLine("- **Solus Version:** ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
            appendLine("- **Device:** ${Build.MANUFACTURER} ${Build.MODEL} (${Build.PRODUCT})")
            appendLine("- **Brand / Hardware:** ${Build.BRAND} / ${Build.HARDWARE}")
            appendLine("- **Android OS:** Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("- **Chipset:** $chip")
            appendLine("- **Supported ABIs:** ${Build.SUPPORTED_ABIS.joinToString(", ")}")
            appendLine("- **Memory:** Total $totalRam, Available $availRam (LowRAM: ${DeviceUtils.isLowRamDevice(context)})")
            appendLine("- **Storage Available:** ${if (freeStorageMb >= 0) "$freeStorageMb MB" else "Unknown"}")
            appendLine("- **Battery Temp:** $tempCelsius")
            appendLine("- **Context:** $extraContext")
            appendLine()
            appendLine("### Error Summary")
            appendLine(title)
            appendLine()
            appendLine("### Stack Trace / Details")
            appendLine("```")
            appendLine(stackTrace.trim())
            appendLine("```")
        }
    }

    private fun getReportsDir(context: Context): File {
        return File(context.filesDir, "crash_reports").apply { mkdirs() }
    }

    private fun saveReport(context: Context, content: String, isNative: Boolean): File? {
        return try {
            val dir = getReportsDir(context)
            val prefix = if (isNative) "native_crash_" else "jvm_crash_"
            val file = File(dir, "$prefix${System.currentTimeMillis()}.txt")
            file.writeText(content)

            // Flag for user prompt on next app launch
            prefs(context).edit()
                .putBoolean(KEY_HAS_UNPROMPTED_CRASH, true)
                .putString(KEY_PENDING_CRASH_FILE, file.absolutePath)
                .commit()

            val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".txt") }
                ?.sortedByDescending { it.lastModified() }
                .orEmpty()
            if (files.size > MAX_SAVED_REPORTS) {
                files.drop(MAX_SAVED_REPORTS).forEach { it.delete() }
            }
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save crash report to disk", e)
            null
        }
    }

    fun hasPendingCrashPrompt(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_HAS_UNPROMPTED_CRASH, false)
    }

    fun getPendingCrashReportForPrompt(context: Context): CrashReport? {
        if (!hasPendingCrashPrompt(context)) return null
        val filePath = prefs(context).getString(KEY_PENDING_CRASH_FILE, null)
        if (filePath != null) {
            val file = File(filePath)
            if (file.exists() && file.isFile) {
                val report = parseReportFile(file)
                if (report != null) return report
            }
        }
        return getAllReports(context).firstOrNull()
    }

    fun clearPendingCrashPrompt(context: Context) {
        prefs(context).edit()
            .remove(KEY_HAS_UNPROMPTED_CRASH)
            .remove(KEY_PENDING_CRASH_FILE)
            .apply()
    }

    private fun parseReportFile(file: File): CrashReport? {
        return runCatching {
            val text = file.readText()
            val lines = text.lines()
            val errorSummaryIdx = lines.indexOfFirst { it.startsWith("### Error Summary") }
            val titleLine = if (errorSummaryIdx >= 0 && errorSummaryIdx + 1 < lines.size) {
                lines[errorSummaryIdx + 1].trim().ifBlank { file.name }
            } else {
                file.name
            }
            CrashReport(
                timestamp = file.lastModified(),
                title = titleLine,
                details = text,
                isNativeCrash = file.name.startsWith("native_crash_")
            )
        }.getOrNull()
    }

    fun getAllReports(context: Context): List<CrashReport> {
        return try {
            val dir = getReportsDir(context)
            dir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".txt") }
                ?.sortedByDescending { it.lastModified() }
                ?.mapNotNull { parseReportFile(it) }
                .orEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load crash reports", e)
            emptyList()
        }
    }

    fun clearAllReports(context: Context) {
        try {
            val dir = getReportsDir(context)
            dir.listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear crash reports", e)
        }
    }

    fun sendToGitHub(context: Context, reportText: String, titleHint: String? = null) {
        // 1. Copy full crash report to system clipboard
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText("Solus Crash Report", reportText)
            clipboard?.setPrimaryClip(clip)
            Toast.makeText(context, "Full crash report copied to clipboard!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.w(TAG, "Could not copy crash report to clipboard", e)
        }

        // 2. Open GitHub issue with pre-filled title and markdown body
        try {
            val issueTitle = titleHint?.take(80) ?: "[Crash Report] Solus Issue"
            val safeBody = if (reportText.length > 2000) {
                reportText.take(1800) + "\n\n... *(Report truncated for URL length. Full report has been copied to your clipboard — please paste it here)*"
            } else {
                reportText
            }
            val encodedTitle = URLEncoder.encode(issueTitle, "UTF-8")
            val encodedBody = URLEncoder.encode(safeBody, "UTF-8")
            val issueUrl = "$GITHUB_ISSUES_URL?title=$encodedTitle&body=$encodedBody"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(issueUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open GitHub issues page", e)
            Toast.makeText(context, "Unable to open browser. Crash report was copied to clipboard.", Toast.LENGTH_LONG).show()
        }
    }

    fun openNewIssue(context: Context, title: String = "[Issue / Crash Report]", body: String = "") {
        sendToGitHub(context, body, title)
    }
}
