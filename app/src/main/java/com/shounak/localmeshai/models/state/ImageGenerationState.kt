package com.shounak.localmeshai.models.state

import androidx.compose.runtime.Immutable

/**
 * Memory policy decision for running local single-graph ONNX Text-to-Image generation.
 */
enum class T2iRamPolicy {
    /** Device has < 7.2 GiB reported total RAM; block local T2I with a clear warning badge. */
    UnsupportedLowRam,

    /** Device has 7.2 GiB .. 10.0 GiB RAM; unload active chat LLM before loading T2I session to prevent lmkd kill. */
    UnloadChatBeforeT2i,

    /** Device has > 10.0 GiB RAM; safe to run T2I, though T2I session still releases after completion/idle. */
    KeepChatLoaded
}

@Immutable
data class T2iDeviceEligibility(
    val totalRamGiB: Double,
    val availableRamMb: Long,
    val policy: T2iRamPolicy
) {
    val isSupported: Boolean
        get() = policy != T2iRamPolicy.UnsupportedLowRam

    val requiresChatUnload: Boolean
        get() = policy == T2iRamPolicy.UnloadChatBeforeT2i

    val warningMessage: String?
        get() = if (policy == T2iRamPolicy.UnsupportedLowRam) {
            "Requires >= 8 GB RAM (~7.2 GiB usable; detected ${String.format(java.util.Locale.US, "%.1f", totalRamGiB)} GiB)"
        } else null
}

/**
 * Live UI state for offline ONNX Text-to-Image generation in chat.
 */
@Immutable
sealed interface ImageGenerationState {
    data object Idle : ImageGenerationState
    data object CheckingRam : ImageGenerationState
    data object UnloadingChatModel : ImageGenerationState
    data class LoadingEngine(val modelName: String) : ImageGenerationState
    data class Generating(
        val prompt: String,
        val seed: Long,
        val width: Int,
        val height: Int,
        val elapsedMs: Long,
        val modelName: String
    ) : ImageGenerationState

    data class Completed(
        val imagePath: String,
        val seed: Long,
        val elapsedMs: Long,
        val modelName: String
    ) : ImageGenerationState

    data object Cancelled : ImageGenerationState
    data class Failed(val message: String) : ImageGenerationState

    val isActive: Boolean
        get() = this is CheckingRam || this is UnloadingChatModel || this is LoadingEngine || this is Generating

    val statusText: String?
        get() = when (this) {
            Idle -> null
            CheckingRam -> "Checking device memory…"
            UnloadingChatModel -> "Unloading chat model…"
            is LoadingEngine -> "Loading ${modelName}…"
            is Generating -> "Generating ${width}×${height} image…"
            is Completed -> "Generated in ${elapsedMs / 1000.0}s"
            Cancelled -> "Generation cancelled"
            is Failed -> message
        }
}

object T2iRamGate {
    const val MIN_TOTAL_RAM_GIB = 7.2
    const val AUTO_UNLOAD_CHAT_MAX_RAM_GIB = 10.0

    fun evaluate(totalRamGiB: Double, availableRamMb: Long = 0L): T2iDeviceEligibility {
        val policy = when {
            totalRamGiB < MIN_TOTAL_RAM_GIB -> T2iRamPolicy.UnsupportedLowRam
            totalRamGiB <= AUTO_UNLOAD_CHAT_MAX_RAM_GIB -> T2iRamPolicy.UnloadChatBeforeT2i
            else -> T2iRamPolicy.KeepChatLoaded
        }
        return T2iDeviceEligibility(
            totalRamGiB = totalRamGiB,
            availableRamMb = availableRamMb,
            policy = policy
        )
    }
}
