package com.shounak.localmeshai.ai

import android.graphics.Bitmap
import android.net.Uri
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import com.shounak.localmeshai.utils.ActiveInputFiles

/**
 * Progress states representing inference lifecycle steps separately from assistant text.
 */
sealed interface RequestProgress {
    object PreparingInput : RequestProgress
    object LoadingModel : RequestProgress
    object ValidatingBackend : RequestProgress
    object RecoveringOnCpu : RequestProgress
    object ProcessingPrompt : RequestProgress
    object UsingTools : RequestProgress
    object RetryingWithoutTools : RequestProgress
    object StoppingAfterTimeout : RequestProgress
    object Generating : RequestProgress
}

/**
 * Terminal result status for completed, stopped, or failed requests.
 */
enum class RequestTerminalStatus {
    Completed,
    Stopped,
    Failed
}

/**
 * Encapsulates an attachment with durable file references, extracted text or prepared media,
 * and explicit resource ownership. Shared between retrieval, inference, and history persistence.
 */
data class PreparedAttachment(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val mimeType: String = "",
    val fileUri: Uri? = null,
    val durableFile: File? = null,
    val extractedText: String? = null,
    val bitmap: Bitmap? = null,
    val audioFile: File? = null,
    val audioBytes: ByteArray? = null,
    val audioDurationMs: Long = 0L,
    val audioSampleRate: Int = 16_000,
    val ownsResources: Boolean = false,
    val temporaryFiles: List<File> = emptyList(),
    val resourceLease: AutoCloseable? = null,
    val extractionError: String? = null
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        resourceLease?.close()
        if (ownsResources) {
            runCatching {
                if (bitmap != null && !bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
            temporaryFiles.forEach { file -> runCatching { ActiveInputFiles.deleteIfUnreferenced(file) } }
        }
    }

    fun releaseTemporaryInputs() {
        resourceLease?.close()
        temporaryFiles.forEach { file -> runCatching { ActiveInputFiles.deleteIfUnreferenced(file) } }
    }

    /** Transfer the already extracted input into durable history storage without extracting again. */
    fun persistInto(
        attachmentStore: com.shounak.localmeshai.data.ChatAttachmentStore,
        chatKind: com.shounak.localmeshai.data.ChatKind,
        sessionId: String
    ): PreparedAttachment {
        val source = durableFile ?: temporaryFiles.firstOrNull() ?: return this
        if (durableFile != null && attachmentStore.isOwnedAttachment(durableFile, chatKind, sessionId)) return this
        val target = attachmentStore.storeAttachmentFromFile(
            chatKind = chatKind,
            sessionId = sessionId,
            attachmentId = id,
            sourceFile = source,
            targetFileName = name
        )
        // Separate durable preparation from temporary release: keep temporary files and lease until committed
        return copy(
            fileUri = runCatching { Uri.fromFile(target) }.getOrNull() ?: fileUri,
            durableFile = target
        )
    }

    /** Transfer the already extracted input into durable history storage without extracting again. */
    fun persistInto(directory: File): PreparedAttachment {
        val source = durableFile ?: temporaryFiles.firstOrNull() ?: return this
        if (durableFile != null) return this
        check(directory.mkdirs() || directory.isDirectory) { "Cannot create attachment storage" }
        val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").takeLast(160).ifBlank { "document" }
        val target = directory.resolve("${id}_$safeName")
        if (!source.renameTo(target)) source.copyTo(target, overwrite = false)
        return copy(
            fileUri = runCatching { Uri.fromFile(target) }.getOrNull() ?: fileUri,
            durableFile = target
        )
    }
}

/**
 * Monotonic timing and token metrics for a single request attempt.
 * Does not contain prompt or response text.
 */
data class InferenceMetrics(
    val requestId: String,
    val sessionId: String,
    val attempt: Int = 1,
    val ackLatencyMs: Long = 0L,
    val preparationTimeMs: Long = 0L,
    val queueTimeMs: Long = 0L,
    val loadingValidationTimeMs: Long = 0L,
    val firstTokenLatencyMs: Long? = null,
    val firstVisibleTextLatencyMs: Long? = null,
    val prefillDurationMs: Long? = null,
    val decodeDurationMs: Long? = null,
    val generatedTokens: Int? = null,
    val isTokenCountEstimated: Boolean = false,
    val decodeThroughputTokPerSec: Double? = null,
    val promptTokens: Int? = null,
    val reusedTokens: Int? = null,
    val evaluatedTokens: Int? = null
)

/**
 * Structured snapshot for in-flight and terminal response state.
 */
data class StructuredResponseSnapshot(
    val requestId: String = "",
    val attemptId: Int = 1,
    val revision: Int = 0,
    val answerText: String = "",
    val reasoningText: String = "",
    val isThinkingActive: Boolean = false,
    val channels: Map<String, String> = emptyMap()
)

/**
 * Categorised reasons when an inference attempt completes without visible answer text.
 */
sealed class EmptyResponseReason {
    object NoVisibleAnswer : EmptyResponseReason()
    object ReasoningOnly : EmptyResponseReason()
    object AttachmentUnavailable : EmptyResponseReason()

    override fun toString(): String = when (this) {
        is NoVisibleAnswer -> "NoVisibleAnswer"
        is ReasoningOnly -> "ReasoningOnly"
        is AttachmentUnavailable -> "AttachmentUnavailable"
    }
}

/**
 * Typed inference result returned across manager boundaries.
 */
data class InferenceExecutionResult(
    val text: String,
    val status: RequestTerminalStatus,
    val error: String? = null,
    val metrics: InferenceMetrics? = null,
    val reasoningText: String? = null,
    val emptyReason: EmptyResponseReason? = null,
    val requestId: String = "",
    val attemptId: Int = 1
)

