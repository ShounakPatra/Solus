package com.shounak.localmeshai.models

import androidx.compose.runtime.Immutable

private val ContextWindowInFileName = Regex("""ekv(\d+)""", RegexOption.IGNORE_CASE)

enum class ModelType(val label: String) {
    Text("Text"),
    Vision("Image / Audio")
}

enum class ModelStatus(val label: String) {
    NotDownloaded("Not downloaded"),
    Downloading("Downloading"),
    Paused("Paused"),
    Available("Available"),
    Failed("Failed"),
    Blocked("Blocked"),
    NeedsConversion("Future build"),
    ComingSoon("Coming soon")
}

enum class ModelPackage {
    SingleFile,
    ZipDirectory
}

@Immutable
data class ModelInfo(
    val id: String,
    val name: String,
    val size: String,
    val status: ModelStatus,
    val type: ModelType,
    val fileName: String,
    val packageType: ModelPackage = ModelPackage.SingleFile,
    val description: String,
    val backend: String,
    val deviceTarget: String,
    val url: String? = null,
    val modelPageUrl: String? = null,
    val requiresHuggingFaceToken: Boolean = false,
    val isRecommended: Boolean = false,
    val supportsAudioInput: Boolean = false,
    val supportsThinkingMode: Boolean = false,
    val contextWindowTokens: Int? = null,
    val progress: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val bytesPerSecond: Long = 0L,
    val localPath: String? = null,
    val errorMessage: String? = null
) {
    val hasDownloadUrl: Boolean
        get() = !url.isNullOrBlank()

    val isFutureBuild: Boolean
        get() = status == ModelStatus.NeedsConversion || status == ModelStatus.ComingSoon

    val isFuturePlaceholder: Boolean
        get() = isFutureBuild && !hasDownloadUrl

    /** Size string to show in the UI. For unavailable models the raw estimate
     *  is meaningless, so we show "Unknown" instead of a misleading "~X GB (est.)".
     */
    val displaySize: String
        get() = if (isFuturePlaceholder) "Unknown" else size

    val contextLengthLabel: String
        get() = effectiveContextWindowTokens?.let { "Context ${it.withThousandsSeparator()}" } ?: "Context TBD"

    val effectiveContextWindowTokens: Int?
        get() = contextWindowTokens ?: ContextWindowInFileName.find(fileName)?.groupValues?.get(1)?.toIntOrNull()

    val etaString: String
        get() {
            if (status != ModelStatus.Downloading || bytesPerSecond <= 0L || totalBytes <= 0L || downloadedBytes >= totalBytes) {
                return ""
            }
            val remainingBytes = totalBytes - downloadedBytes
            val secondsLeft = (remainingBytes / bytesPerSecond).coerceAtLeast(1L)
            val hours = secondsLeft / 3600L
            val minutes = (secondsLeft % 3600L) / 60L
            val seconds = secondsLeft % 60L
            return when {
                hours > 0L -> String.format(java.util.Locale.US, "%dh %dm", hours, minutes)
                minutes > 0L -> String.format(java.util.Locale.US, "%dm %ds", minutes, seconds)
                else -> String.format(java.util.Locale.US, "%ds", seconds)
            }
        }
}

private fun Int.withThousandsSeparator(): String {
    return toString()
        .reversed()
        .chunked(3)
        .joinToString(",")
        .reversed()
}
