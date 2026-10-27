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

enum class ModelValidationState { Pending, Checking, Verified, Failed }

enum class LoadRestrictionCategory {
    DeviceCompatibility,
    PreviousCrash,
    RuntimeIncompatibility
}

@Immutable
data class LoadRestriction(
    val reason: String,
    val category: LoadRestrictionCategory
) {
    val canTryAnyway: Boolean
        get() = category in setOf(LoadRestrictionCategory.DeviceCompatibility, LoadRestrictionCategory.PreviousCrash)
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
    val sha256: String? = null,
    /**
     * Expected file size in bytes for GGUF artifact validation. 0 = no size check.
     * Used to detect if a wrong variant (e.g. Q4_0 instead of Q4_K_M) was downloaded.
     * Tolerance is ±5% to accommodate minor re-uploads to HuggingFace.
     */
    val expectedFileSizeBytes: Long = 0L,
    val progress: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val bytesPerSecond: Long = 0L,
    val localPath: String? = null,
    val errorMessage: String? = null,
    val downloadedAt: Long = 0L,
    val bundle: ModelBundle? = null,
    val downloadSpec: ModelDownloadSpec? = null,
    val supportsVisionInput: Boolean = false,
    val loadRestriction: LoadRestriction? = null,
    val downloadStartedAt: Long = 0L
) {
    /** Old persisted descriptions counted only selected weights, before companion discovery. */
    val displayDescription: String
        get() {
            val repoDescription = description.startsWith("Hugging Face model from ")
            if (!repoDescription) return description
            val base = description.replace(Regex("\\s*\\(\\d+ files?\\)$"), "")
            val artifacts = bundle?.allArtifacts ?: return "$base (selected model files; companions fetched during download)"
            val weights = artifacts.count { ModelFormat.fromFileName(it.displayName) != null }
            val companions = artifacts.size - weights
            return "$base ($weights model file${if (weights == 1) "" else "s"}, $companions companion file${if (companions == 1) "" else "s"})"
        }

    val hasMissingFiles: Boolean
        get() = com.shounak.localmeshai.utils.PortableCompanionFiles.isMissing(errorMessage)

    val failureLabel: String
        get() = when {
            hasMissingFiles -> "Missing files"
            downloadedBytes > 0L && progress >= 1f -> "Model validation failed"
            else -> "Download Failed"
        }

    val taskLabel: String get() = when (bundle?.capabilities?.task) {
        ModelTask.VisionLanguage -> "Image questions"
        ModelTask.ImageClassification -> "Image classification"
        ModelTask.SpeechRecognition -> "Speech transcription"
        ModelTask.ImageGeneration -> "Image generation"
        ModelTask.Unknown -> "Unrecognized task"
        else -> type.label
    }

    val canTryAnyway: Boolean
        get() = loadRestriction?.canTryAnyway == true

    val isCrashBlocked: Boolean
        get() = loadRestriction?.category == LoadRestrictionCategory.PreviousCrash

    val hasDownloadUrl: Boolean
        get() = !url.isNullOrBlank()

    val isCustom: Boolean
        get() = id.startsWith("custom_") || id.startsWith("local_gguf_")

    val isStorageImport: Boolean
        get() = bundle?.mainArtifacts?.any { it.source is ArtifactSource.PersistedUri } == true

    val isSplit: Boolean
        get() = bundle?.isSplit == true

    val isMultimodal: Boolean
        get() = supportsVision || supportsAudio

    val supportsVision: Boolean
        get() {
            if (bundle != null) return bundle.capabilities.supportsVision
            if (downloadSpec != null) return downloadSpec.capabilities.supportsVision
            return supportsVisionInput || (type == ModelType.Vision)
        }

    val supportsAudio: Boolean
        get() {
            if (bundle != null) return bundle.capabilities.supportsAudio
            if (downloadSpec != null) return downloadSpec.capabilities.supportsAudio
            return supportsAudioInput
        }

    val thinkingCapability: ThinkingCapability
        get() {
            if (bundle != null && bundle.capabilities.thinking != ThinkingCapability.Unknown) {
                return bundle.capabilities.thinking
            }
            if (downloadSpec != null && downloadSpec.capabilities.thinking != ThinkingCapability.Unknown) {
                return downloadSpec.capabilities.thinking
            }
            if (supportsThinkingMode) {
                return ThinkingCapability.ModelControlled
            }
            return ThinkingCapability.Unknown
        }

    val isFutureBuild: Boolean
        get() = status == ModelStatus.NeedsConversion || status == ModelStatus.ComingSoon

    val isFuturePlaceholder: Boolean
        get() = isFutureBuild && !hasDownloadUrl

    /** Size string to show in the UI. For unavailable models the raw estimate
     *  is meaningless, so we show "Unknown" instead of a misleading "~X GB (est.)".
     */
    val displaySize: String
        get() = if (isFuturePlaceholder) "Unknown" else size

    /** Derive presentation from artifact metadata, including entries saved with the old GGUF label. */
    val displayBackend: String
        get() {
            val format = bundle?.format ?: downloadSpec?.effectiveFormat ?: ModelFormat.fromFileName(fileName)
            return if (format != null && (backend.isBlank() || (format != ModelFormat.GGUF && backend.contains("GGUF", true)))) format.backendLabel else backend
        }

    val contextLengthLabel: String
        get() = effectiveContextWindowTokens?.let { "Context ${it.withThousandsSeparator()}" } ?: "Context TBD"

    val effectiveContextWindowTokens: Int?
        get() = contextWindowTokens
            ?: bundle?.contextLength?.takeIf { it > 0 }
            ?: bundle?.capabilities?.contextLength?.takeIf { it > 0 }
            ?: ContextWindowInFileName.find(fileName)?.groupValues?.get(1)?.toIntOrNull()

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
