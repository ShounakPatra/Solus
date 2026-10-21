package com.shounak.localmeshai.utils

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.ui.graphics.vector.ImageVector
import java.util.Locale

enum class ErrorCategory(
    val categoryName: String,
    val icon: ImageVector
) {
    NoInternet("Offline", Icons.Default.WifiOff),
    ConnectionTimeout("Connection Interrupted", Icons.Default.Timer),
    AuthRequired("Access Token Needed", Icons.Default.Lock),
    NotFound("Not Found (404)", Icons.Default.SearchOff),
    RateLimited("Rate Limited (429)", Icons.Default.HourglassTop),
    ServerError("Server Error (5xx)", Icons.Default.CloudOff),
    StorageFull("Low Storage", Icons.Default.Storage),
    ChecksumCorrupted("Integrity Failed", Icons.Default.Warning),
    InvalidFormat("Invalid Format", Icons.Default.BrokenImage),
    HardwareIncompatible("Incompatible Device", Icons.Default.Memory),
    NativeCrash("Native Engine Crash", Icons.Default.BugReport),
    General("Error", Icons.Default.ErrorOutline)
}

data class ClassifiedError(
    val category: ErrorCategory,
    val headline: String,
    val message: String,
    val suggestion: String,
    val isOpenSettingsAction: Boolean = false,
    val isSendCrashReportAction: Boolean = false,
    val isRetryAction: Boolean = false
)

object ErrorClassifier {

    fun classify(rawError: String?): ClassifiedError? {
        if (rawError.isNullOrBlank()) return null
        val lower = rawError.lowercase(Locale.US)

        return when {
            // 1. Offline / No internet connection
            lower.contains("no internet connection") ||
                lower.contains("unable to resolve host") ||
                lower.contains("no address associated with hostname") ||
                lower.contains("network is unreachable") ||
                lower.contains("unknownhostexception") ||
                lower.contains("connectexception") -> {
                ClassifiedError(
                    category = ErrorCategory.NoInternet,
                    headline = "No Internet Connection",
                    message = "Your device is not connected to the internet. Downloads require an active network.",
                    suggestion = "Check your Wi-Fi or mobile data connection and try again.",
                    isRetryAction = true
                )
            }

            // 2. Timeout / interrupted network
            lower.contains("timed out") ||
                lower.contains("timeout") ||
                lower.contains("connection reset") ||
                lower.contains("broken pipe") ||
                lower.contains("unexpected end of stream") ||
                lower.contains("download interrupted") -> {
                ClassifiedError(
                    category = ErrorCategory.ConnectionTimeout,
                    headline = "Download Interrupted",
                    message = "The network connection dropped or timed out before completion.",
                    suggestion = "Tap Resume to continue downloading the model.",
                    isRetryAction = true
                )
            }

            // 3. Auth / Token needed (401 / 403 / Hugging Face token)
            lower.contains("hugging face") && (lower.contains("token") || lower.contains("denied access")) ||
                lower.contains("read token") ||
                lower.contains("http 401") ||
                lower.contains("http 403") ||
                lower.contains("token needed") -> {
                ClassifiedError(
                    category = ErrorCategory.AuthRequired,
                    headline = "Hugging Face Access Token Required",
                    message = rawError,
                    suggestion = "Enter your Hugging Face read access token in Settings (⚙️).",
                    isOpenSettingsAction = true
                )
            }

            // 4. Not Found (404)
            lower.contains("http 404") || lower.contains("not found at the configured url") -> {
                ClassifiedError(
                    category = ErrorCategory.NotFound,
                    headline = "Model File Not Found (404)",
                    message = rawError,
                    suggestion = "The remote repository file may have moved or been renamed."
                )
            }

            // 5. Rate limit (429)
            lower.contains("http 429") || lower.contains("rate limit") || lower.contains("too many requests") -> {
                ClassifiedError(
                    category = ErrorCategory.RateLimited,
                    headline = "Download Rate Limit Exceeded (429)",
                    message = "The model hosting server has temporarily restricted download requests.",
                    suggestion = "Please wait a few minutes before retrying."
                )
            }

            // 6. Server Error (5xx)
            lower.contains("http 500") || lower.contains("http 502") || lower.contains("http 503") || lower.contains("http 504") -> {
                ClassifiedError(
                    category = ErrorCategory.ServerError,
                    headline = "Remote Server Error (HTTP 5xx)",
                    message = rawError,
                    suggestion = "The model hosting service is temporarily unavailable. Please try again later."
                )
            }

            // 7. Low Storage / Space
            lower.contains("enospc") || lower.contains("no space left") || lower.contains("insufficient storage") || lower.contains("maximum safety limit (16 gb)") -> {
                ClassifiedError(
                    category = ErrorCategory.StorageFull,
                    headline = "Insufficient Storage Space",
                    message = rawError,
                    suggestion = "Free up internal storage space on your device and retry."
                )
            }

            // 8. Checksum verification failure / corruption
            lower.contains("sha-256") || lower.contains("checksum verification failed") -> {
                ClassifiedError(
                    category = ErrorCategory.ChecksumCorrupted,
                    headline = "File Integrity Check Failed",
                    message = rawError,
                    suggestion = "The file payload did not match the expected SHA-256 hash. Tap Retry to download a fresh copy.",
                    isRetryAction = true
                )
            }

            // 9. Format invalid / web page returned
            lower.contains("web page instead of a model") ||
                lower.contains("not look like a mediapipe") ||
                lower.contains("too small to be a valid model") ||
                lower.contains("traversal detected") -> {
                ClassifiedError(
                    category = ErrorCategory.InvalidFormat,
                    headline = "Invalid Model File Format",
                    message = rawError,
                    suggestion = "Verify that the model URL points directly to an Android-ready binary file."
                )
            }

            // 10. Native engine crash
            lower.contains("crashed during native initialization") ||
                lower.contains("blocked after it crashed") ||
                lower.contains("native crash") -> {
                ClassifiedError(
                    category = ErrorCategory.NativeCrash,
                    headline = "Native Initialization Crash",
                    message = rawError,
                    suggestion = "The model crashed during native GPU/CPU runtime init. You can report this issue on GitHub.",
                    isSendCrashReportAction = true
                )
            }

            // 11. Hardware incompatible / Device safety block
            lower.contains("blocked on") ||
                lower.contains("device safety profile") ||
                lower.contains("requires at least") ||
                lower.contains("ram") && lower.contains("gb") -> {
                ClassifiedError(
                    category = ErrorCategory.HardwareIncompatible,
                    headline = "Blocked by Device Profile",
                    message = rawError,
                    suggestion = "Your device hardware or RAM may not safely support this model size."
                )
            }

            // 12. Fallback general error
            else -> {
                ClassifiedError(
                    category = ErrorCategory.General,
                    headline = "Model Error",
                    message = rawError,
                    suggestion = "An error occurred while managing this model."
                )
            }
        }
    }
}
