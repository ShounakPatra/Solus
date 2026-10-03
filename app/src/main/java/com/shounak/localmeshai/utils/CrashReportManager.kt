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
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
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
    private const val MAX_SAVED_REPORTS = 15

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

    /**
     * Captures system & app logcat lines around the crash while redacting any sensitive tokens.
     */
    fun captureCrashDebugLogs(maxLines: Int = 350): String {
        return try {
            val pid = android.os.Process.myPid()
            val process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-v", "time", "-t", maxLines.toString())
            )
            val lines = mutableListOf<String>()
            BufferedReader(InputStreamReader(process.inputStream)).useLines { seq ->
                seq.forEach { lines.add(it) }
            }
            process.waitFor()

            if (lines.isEmpty()) {
                return "No system logcat entries captured for process $pid."
            }

            val rawLog = lines.joinToString("\n")
            sanitizeLog(rawLog)
        } catch (e: Exception) {
            "Unable to capture logcat debug log: ${e.message}"
        }
    }

    /**
     * Sanitizes sensitive tokens, API keys, and personal credentials before saving or transmitting.
     */
    fun sanitizeLog(raw: String): String {
        return raw
            // Hugging Face tokens
            .replace(Regex("""hf_[A-Za-z0-9_]{20,}"""), "hf_***REDACTED***")
            // Google API keys
            .replace(Regex("""AIza[0-9A-Za-z-_]{35}"""), "AIza***REDACTED***")
            // Bearer authorization tokens
            .replace(Regex("""(?i)bearer\s+[A-Za-z0-9_\-\.]{20,}"""), "Bearer ***REDACTED***")
    }

    /**
     * Builds the complete crash debug report with device specifications, stack traces, and system logcat.
     */
    fun buildCompleteCrashReportString(
        context: Context,
        title: String,
        stackTrace: String,
        modelInfo: String? = null,
        extraContext: String? = null,
        isNative: Boolean = false,
        includeLogcat: Boolean = true
    ): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date())
        val deviceSpecs = DeviceUtils.formatFullDeviceDiagnostics(context)
        val debugLog = if (includeLogcat) captureCrashDebugLogs(350) else "Logcat capture bypassed."

        return buildString {
            appendLine("### Solus Crash Debug Report")
            appendLine("- **Report Date/Time:** $dateFormat")
            appendLine("- **Crash Classification:** ${if (isNative) "Native Runtime / Engine Failure" else "JVM / App Runtime Exception"}")
            if (!extraContext.isNullOrBlank()) {
                appendLine("- **Execution Context:** $extraContext")
            }
            if (!modelInfo.isNullOrBlank()) {
                appendLine("- **Active Model Diagnostics:** $modelInfo")
            }
            appendLine()
            appendLine("#### 📱 Complete Device Specifications & Hardware Info")
            appendLine(deviceSpecs.trim())
            appendLine()
            appendLine("#### 💥 Error Summary & Stack Trace")
            appendLine("```")
            appendLine(title)
            if (stackTrace.isNotBlank() && stackTrace != title) {
                appendLine()
                appendLine(stackTrace.trim())
            }
            appendLine("```")
            appendLine()
            appendLine("#### 📜 Full Crash Debug Log (System & Runtime Logcat)")
            appendLine("```")
            appendLine(debugLog.trim())
            appendLine("```")
        }
    }

    /** Legacy helper maintained for backward compatibility. */
    fun buildReportString(
        context: Context,
        title: String,
        stackTrace: String,
        extraContext: String
    ): String {
        return buildCompleteCrashReportString(
            context = context,
            title = title,
            stackTrace = stackTrace,
            extraContext = extraContext,
            isNative = false,
            includeLogcat = true
        )
    }

    fun recordNativeCrash(context: Context, modelId: String, details: String) {
        val report = buildCompleteCrashReportString(
            context = context,
            title = "Native Engine Crash: $modelId",
            stackTrace = details,
            modelInfo = "Model ID: $modelId",
            extraContext = "Native initialization crash detected by InitCrashGuard.",
            isNative = true,
            includeLogcat = true
        )
        saveReport(context, report, isNative = true)
    }

    private fun recordJvmCrash(context: Context, thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val stackTrace = sw.toString()
        val title = "${throwable.javaClass.simpleName}: ${throwable.message ?: "Uncaught Exception"}"
        @Suppress("DEPRECATION")
        val threadId = thread.id
        val report = buildCompleteCrashReportString(
            context = context,
            title = title,
            stackTrace = stackTrace,
            extraContext = "Crashed Thread: ${thread.name} (id: $threadId)",
            isNative = false,
            includeLogcat = true
        )
        saveReport(context, report, isNative = false)
    }

    /**
     * Records a model runtime crash or inference failure with complete device specs and logcat.
     */
    fun recordModelCrash(
        context: Context,
        modelId: String,
        modelName: String,
        errorMessage: String,
        throwable: Throwable? = null,
        runtimeInfo: String? = null
    ): CrashReport {
        val stackTrace = if (throwable != null) {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            sw.toString()
        } else {
            errorMessage
        }
        val title = "Model Runtime Crash: $modelName ($modelId)"
        val reportText = buildCompleteCrashReportString(
            context = context,
            title = title,
            stackTrace = stackTrace,
            modelInfo = "Model: $modelName | ID: $modelId${if (!runtimeInfo.isNullOrBlank()) " | $runtimeInfo" else ""}",
            extraContext = "Model execution / inference error",
            isNative = true,
            includeLogcat = true
        )
        saveReport(context, reportText, isNative = true)
        return CrashReport(
            timestamp = System.currentTimeMillis(),
            title = title,
            details = reportText,
            isNativeCrash = true
        )
    }

    private const val PREFS_NAME = "solus_crash_reports"
    private const val KEY_HAS_UNPROMPTED_CRASH = "has_unprompted_crash"
    private const val KEY_PENDING_CRASH_FILE = "pending_crash_file"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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
            val errorSummaryIdx = lines.indexOfFirst { it.startsWith("#### 💥 Error Summary") || it.startsWith("### Error Summary") }
            val titleLine = if (errorSummaryIdx >= 0 && errorSummaryIdx + 2 < lines.size) {
                lines[errorSummaryIdx + 2].trim().ifBlank { file.name }
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

    /**
     * Reports the crash to the GitHub repository:
     * 1. Copies the ENTIRE crash debug log and device specifications to the Android clipboard.
     * 2. Opens the repository's new issue page with prefilled title and formatted markdown body.
     */
    fun sendToGitHub(context: Context, reportText: String, titleHint: String? = null) {
        // 1. Copy the WHOLE crash debug log and device info to system clipboard
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText("Solus Crash Debug Report", reportText)
            clipboard?.setPrimaryClip(clip)
            Toast.makeText(context, "Full crash debug log & device info copied to clipboard!", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.w(TAG, "Could not copy crash report to clipboard", e)
        }

        // 2. Open GitHub issue with pre-filled title and structured markdown body
        try {
            val issueTitle = (titleHint ?: "[Crash Report] Solus Issue").take(100)

            // Safe budget for Android Intent URL query parameters (~4000 characters)
            val maxUrlLength = 4000
            val safeBody = if (reportText.length > maxUrlLength) {
                val banner = "> [!IMPORTANT]\n" +
                    "> 📋 **The complete crash debug log, system logcat, and complete device specs were copied to your clipboard.**\n" +
                    "> *Please paste (Ctrl+V / Long-press -> Paste) in this issue box below to ensure all details are included.*\n\n"
                val budget = maxUrlLength - banner.length - 200
                val snippet = reportText.take(budget.coerceAtLeast(600))
                banner + snippet + "\n\n... *(Log truncated for URL query limit. Paste clipboard here for the full log)*"
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
            Toast.makeText(context, "Unable to open browser. The complete crash report & device info were copied to your clipboard.", Toast.LENGTH_LONG).show()
        }
    }

    fun openNewIssue(context: Context, title: String = "[Issue / Crash Report]", body: String = "") {
        val fullBody = if (body.isNotBlank()) {
            body
        } else {
            val deviceSpecs = DeviceUtils.formatFullDeviceDiagnostics(context)
            "### Description\n(Describe your issue here)\n\n### Device Info\n$deviceSpecs"
        }
        sendToGitHub(context, fullBody, title)
    }

    /**
     * Shares the complete crash debug log and device specs using Android's system share sheet.
     */
    fun shareCrashReport(context: Context, report: CrashReport) {
        try {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, report.title)
                putExtra(Intent.EXTRA_TEXT, report.details)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(sendIntent, "Share Crash Debug Log & Device Info").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to share crash report", e)
        }
    }
}
