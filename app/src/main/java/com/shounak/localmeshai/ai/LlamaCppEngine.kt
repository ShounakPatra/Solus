package com.shounak.localmeshai.ai

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.shounak.localmeshai.models.ModelBundle
import com.shounak.localmeshai.utils.AppTrace
import com.shounak.localmeshai.utils.ModelSourceResolver
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

enum class LlamaGenerationResult {
    Completed,
    Stopped,
    Failed
}

enum class LlamaFailureCategory {
    None,
    PromptEmpty,
    UnsupportedTemplate,
    TokenizationError,
    PromptDecodeFailed,
    ContextCapacityExceeded,
    GenerationDecodeFailed,
    NumericalInstability,
    BackendExecution,
    OutOfMemory,
    UserStopped,
    Unknown;

    companion object {
        fun fromName(name: String?): LlamaFailureCategory =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Unknown
    }
}

class LlamaExecutionException(
    message: String,
    val category: LlamaFailureCategory = LlamaFailureCategory.Unknown,
    val code: Int = -1
) : RuntimeException(message)

interface LlamaTokenCallback {
    fun onFirstToken() {}
    fun onToken(token: String)
    fun onStructuredToken(answer: String, reasoning: String, isThinkingActive: Boolean) {}
    fun onComplete()
    fun onStop()
    fun onError(error: String)
    fun onMetrics(prefillDurationMs: Long, decodeDurationMs: Long, tokenCount: Int) {}
    fun onMetrics(
        prefillDurationMs: Long,
        decodeDurationMs: Long,
        tokenCount: Int,
        promptTokens: Int,
        reusedPromptTokens: Int,
        evaluatedPromptTokens: Int
    ) {
        onMetrics(prefillDurationMs, decodeDurationMs, tokenCount)
    }
}

data class LlamaGenerationMetrics(
    val prefillDurationMs: Long = 0L,
    val decodeDurationMs: Long = 0L,
    val tokenCount: Int = 0,
    val promptTokens: Int = 0,
    val reusedPromptTokens: Int = 0,
    val evaluatedPromptTokens: Int = 0
) {
    val reusedTokens: Int get() = reusedPromptTokens
    val evaluatedTokens: Int get() = evaluatedPromptTokens
    val tokensPerSecond: Double get() = if (decodeDurationMs > 0) tokenCount.toDouble() / (decodeDurationMs.toDouble() / 1000.0) else 0.0
}

data class LlamaSamplingParams(
    val temperature: Float = 0.7f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val minP: Float = 0.05f,
    val repeatPenalty: Float = 1.1f,
    val maxTokens: Int = 1024,
    val seed: Int = 0
)

sealed class MultimodalMediaInput {
    abstract val messageIndex: Int

    data class Image(
        val rgbBytes: ByteArray,
        val width: Int,
        val height: Int,
        override val messageIndex: Int = -1
    ) : MultimodalMediaInput() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Image
            if (!rgbBytes.contentEquals(other.rgbBytes)) return false
            if (width != other.width) return false
            if (height != other.height) return false
            if (messageIndex != other.messageIndex) return false
            return true
        }
        override fun hashCode(): Int {
            var result = rgbBytes.contentHashCode()
            result = 31 * result + width
            result = 31 * result + height
            result = 31 * result + messageIndex
            return result
        }
    }

    data class Audio(
        val samples: FloatArray,
        override val messageIndex: Int = -1
    ) : MultimodalMediaInput() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Audio
            if (!samples.contentEquals(other.samples)) return false
            if (messageIndex != other.messageIndex) return false
            return true
        }
        override fun hashCode(): Int {
            var result = samples.contentHashCode()
            result = 31 * result + messageIndex
            return result
        }
    }
}

data class LlamaModelMetadata(
    val desc: String = "",
    val architecture: String = "",
    val paramCount: Long = 0L,
    val trainContextLength: Int = 0,
    val vocabSize: Int = 0,
    val hasChatTemplate: Boolean = false,
    val backend: String = "CPU",
    val nGpuLayers: Int = 0,
    val contextSize: Int = 0,
    val batchSize: Int = 0,
    val microBatchSize: Int = 0,
    val actualComputeBackends: List<String> = emptyList(),
    val effectiveOffloadKqv: Boolean = false,
    val isCpuIsolated: Boolean = false,
    val gpuComputeBufferBytes: Long = 0L,
    val backendPolicyVersion: Int = 2,
    val supportsVision: Boolean = false,
    val supportsAudio: Boolean = false,
    val audioSampleRate: Int = -1,
    val hasDecoder: Boolean = true,
    val upstreamRevision: String = "a17e94d229fc7fed1567a799baebd11b771fe5af"
)

enum class LlamaBackend(val id: Int, val displayName: String) {
    CPU(0, "CPU"),
    VULKAN(1, "Vulkan GPU");

    companion object {
        fun fromId(id: Int): LlamaBackend = entries.firstOrNull { it.id == id } ?: CPU
        fun fromName(name: String): LlamaBackend = entries.firstOrNull {
            it.name.equals(name, ignoreCase = true) || it.displayName.equals(name, ignoreCase = true)
        } ?: CPU
    }
}

data class VulkanDeviceInfo(
    val available: Boolean = false,
    val deviceCount: Int = 0,
    val devices: List<VulkanDevice> = emptyList(),
    val driverIdentity: String = ""
)

data class VulkanDevice(
    val index: Int = 0,
    val name: String = "",
    val freeMemoryMb: Long = 0L,
    val totalMemoryMb: Long = 0L
)

data class LlamaModelInspectionResult(
    val valid: Boolean = false,
    val version: Int = 0,
    val architecture: String = "",
    val name: String = "",
    val desc: String = "",
    val tensorCount: Long = 0L,
    val trainContextLength: Int = 0,
    val embeddingLength: Int = 0,
    val layerCount: Int = 0,
    val hasChatTemplate: Boolean = false,
    val primaryQuantization: String = "",
    val isSupported: Boolean = false,
    val error: String = "",
    val splitNo: Int = -1,
    val splitCount: Int = -1,
    val isProjector: Boolean = false,
    val clipHasVisionEncoder: Boolean = false,
    val clipHasAudioEncoder: Boolean = false,
    val thinkingCapability: String = "Unknown"
)

/**
 * Abstraction layer for native llama.cpp JNI calls.
 * Enables deterministic lifecycle and concurrency regression testing on host JVM.
 */
internal interface LlamaNativeBridge {
    fun clearProbeReferences() {}
    fun resetAfterValidation(handle: Long) {}
    fun isAvailable(): Boolean
    fun getLastError(): String?
    fun inspectModel(modelPath: String): String?
    fun isVulkanAvailable(): Boolean = false
    fun getVulkanDeviceInfo(): String? = null
    fun getActiveBackend(handle: Long): String = "CPU"
    fun createOperationControl(): Long = 0L
    fun cancelOperationControl(controlId: Long) {}
    fun releaseOperationControl(controlId: Long) {}
    fun getOperationControlProgress(controlId: Long): Int = -1
    fun getOperationControlSnapshot(controlId: Long): String? = null
    fun initModel(
        modelPath: String,
        nThreads: Int,
        nThreadsBatch: Int = nThreads,
        nCtx: Int,
        nBatch: Int,
        nUbatch: Int,
        backend: Int = 0,
        nGpuLayers: Int = 0,
        operationControlId: Long = 0L
    ): Long
    fun initModelBundle(
        modelPaths: Array<String>,
        projectorPath: String?,
        nThreads: Int,
        nThreadsBatch: Int = nThreads,
        nCtx: Int,
        nBatch: Int,
        nUbatch: Int,
        backend: Int = 0,
        nGpuLayers: Int = 0,
        operationControlId: Long = 0L
    ): Long = initModel(modelPaths.firstOrNull() ?: "", nThreads, nThreadsBatch, nCtx, nBatch, nUbatch, backend, nGpuLayers, operationControlId)
    fun generateStream(
        handle: Long,
        generationId: Long,
        prompt: String,
        roles: Array<String>?,
        contents: Array<String>?,
        temperature: Float,
        topP: Float,
        topK: Int,
        minP: Float,
        repeatPenalty: Float,
        maxTokens: Int,
        seed: Int,
        callback: LlamaTokenCallback
    ): Int

    fun generateStream(
        handle: Long,
        generationId: Long,
        prompt: String,
        roles: Array<String>?,
        contents: Array<String>?,
        temperature: Float,
        topP: Float,
        topK: Int,
        minP: Float,
        repeatPenalty: Float,
        maxTokens: Int,
        seed: Int,
        enableThinking: Boolean,
        callback: LlamaTokenCallback
    ): Int = generateStream(handle, generationId, prompt, roles, contents, temperature, topP, topK, minP, repeatPenalty, maxTokens, seed, callback)
    fun generateMultimodalStream(
        handle: Long,
        generationId: Long,
        prompt: String,
        roles: Array<String>?,
        contents: Array<String>?,
        mediaTypes: IntArray?,
        mediaMessageIndices: IntArray? = null,
        imageWidths: IntArray?,
        imageHeights: IntArray?,
        imageBytes: Array<ByteArray>?,
        audioFloats: Array<FloatArray>?,
        temperature: Float,
        topP: Float,
        topK: Int,
        minP: Float,
        repeatPenalty: Float,
        maxTokens: Int,
        seed: Int,
        enableThinking: Boolean = true,
        callback: LlamaTokenCallback
    ): Int = generateStream(handle, generationId, prompt, roles, contents, temperature, topP, topK, minP, repeatPenalty, maxTokens, seed, enableThinking, callback)
    fun stop(handle: Long, generationId: Long)
    fun getMetadata(handle: Long): String?
    fun free(handle: Long)
    fun getLastFailureCategory(): String? = null
    fun getLastFailureCode(): Int = 0
    fun probeReference(
        handle: Long,
        promptIndex: Int,
        roles: Array<String>?,
        contents: Array<String>?,
        nPositions: Int,
        operationControlId: Long = 0L
    ): String? = null
    fun probeCompare(
        handle: Long,
        promptIndex: Int,
        roles: Array<String>?,
        contents: Array<String>?,
        nPositions: Int,
        operationControlId: Long = 0L
    ): String? = null
}

internal object DefaultLlamaNativeBridge : LlamaNativeBridge {
    override fun clearProbeReferences() {
        if (isAvailable()) LlamaCppEngine.nativeClearProbeReferences()
    }
    override fun resetAfterValidation(handle: Long) {
        if (isAvailable()) LlamaCppEngine.nativeResetAfterValidation(handle)
    }
    override fun isAvailable(): Boolean = LlamaCppEngine.isLibraryLoaded

    override fun getLastError(): String? {
        if (!isAvailable()) return "Native library libsolus_llama.so is not loaded"
        return LlamaCppEngine.nativeGetLastError()
    }

    override fun inspectModel(modelPath: String): String? {
        if (!isAvailable()) return null
        return LlamaCppEngine.nativeInspectModel(modelPath)
    }

    override fun isVulkanAvailable(): Boolean {
        if (!isAvailable()) return false
        return LlamaCppEngine.nativeIsVulkanAvailable()
    }

    override fun getVulkanDeviceInfo(): String? {
        if (!isAvailable()) return null
        return LlamaCppEngine.nativeGetVulkanDeviceInfo()
    }

    override fun getActiveBackend(handle: Long): String {
        if (!isAvailable() || handle == 0L) return "CPU"
        return LlamaCppEngine.nativeGetActiveBackend(handle) ?: "CPU"
    }

    override fun createOperationControl(): Long =
        if (isAvailable()) LlamaCppEngine.nativeCreateOperationControl() else 0L

    override fun cancelOperationControl(controlId: Long) {
        if (isAvailable()) LlamaCppEngine.nativeCancelOperationControl(controlId)
    }

    override fun releaseOperationControl(controlId: Long) {
        if (isAvailable()) LlamaCppEngine.nativeReleaseOperationControl(controlId)
    }

    override fun getOperationControlProgress(controlId: Long): Int =
        if (isAvailable()) LlamaCppEngine.nativeGetOperationControlProgress(controlId) else -1

    override fun getOperationControlSnapshot(controlId: Long): String? =
        if (isAvailable()) LlamaCppEngine.nativeGetOperationControlSnapshot(controlId) else null

    override fun initModel(
        modelPath: String,
        nThreads: Int,
        nThreadsBatch: Int,
        nCtx: Int,
        nBatch: Int,
        nUbatch: Int,
        backend: Int,
        nGpuLayers: Int,
        operationControlId: Long
    ): Long {
        if (!isAvailable()) return 0L
        return LlamaCppEngine.nativeInitModel(
            modelPath, nThreads, nThreadsBatch, nCtx, nBatch, nUbatch, backend, nGpuLayers, operationControlId
        )
    }

    override fun initModelBundle(
        modelPaths: Array<String>,
        projectorPath: String?,
        nThreads: Int,
        nThreadsBatch: Int,
        nCtx: Int,
        nBatch: Int,
        nUbatch: Int,
        backend: Int,
        nGpuLayers: Int,
        operationControlId: Long
    ): Long {
        if (!isAvailable()) return 0L
        return LlamaCppEngine.nativeInitModelBundle(
            modelPaths, projectorPath, nThreads, nThreadsBatch, nCtx, nBatch, nUbatch, backend, nGpuLayers, operationControlId
        )
    }

    override fun generateStream(
        handle: Long,
        generationId: Long,
        prompt: String,
        roles: Array<String>?,
        contents: Array<String>?,
        temperature: Float,
        topP: Float,
        topK: Int,
        minP: Float,
        repeatPenalty: Float,
        maxTokens: Int,
        seed: Int,
        callback: LlamaTokenCallback
    ): Int = generateStream(
        handle, generationId, prompt, roles, contents,
        temperature, topP, topK, minP, repeatPenalty, maxTokens, seed, true, callback
    )

    override fun generateStream(
        handle: Long,
        generationId: Long,
        prompt: String,
        roles: Array<String>?,
        contents: Array<String>?,
        temperature: Float,
        topP: Float,
        topK: Int,
        minP: Float,
        repeatPenalty: Float,
        maxTokens: Int,
        seed: Int,
        enableThinking: Boolean,
        callback: LlamaTokenCallback
    ): Int {
        if (!isAvailable()) return -1
        return LlamaCppEngine.nativeGenerateStream(
            handle, generationId, prompt, roles, contents,
            temperature, topP, topK, minP, repeatPenalty, maxTokens, seed, enableThinking, callback
        )
    }

    override fun generateMultimodalStream(
        handle: Long,
        generationId: Long,
        prompt: String,
        roles: Array<String>?,
        contents: Array<String>?,
        mediaTypes: IntArray?,
        mediaMessageIndices: IntArray?,
        imageWidths: IntArray?,
        imageHeights: IntArray?,
        imageBytes: Array<ByteArray>?,
        audioFloats: Array<FloatArray>?,
        temperature: Float,
        topP: Float,
        topK: Int,
        minP: Float,
        repeatPenalty: Float,
        maxTokens: Int,
        seed: Int,
        enableThinking: Boolean,
        callback: LlamaTokenCallback
    ): Int {
        if (!isAvailable()) return -1
        return LlamaCppEngine.nativeGenerateMultimodalStream(
            handle, generationId, prompt, roles, contents,
            mediaTypes, mediaMessageIndices, imageWidths, imageHeights, imageBytes, audioFloats,
            temperature, topP, topK, minP, repeatPenalty, maxTokens, seed, enableThinking, callback
        )
    }

    override fun stop(handle: Long, generationId: Long) {
        if (isAvailable()) LlamaCppEngine.nativeStop(handle, generationId)
    }

    override fun getMetadata(handle: Long): String? {
        if (!isAvailable()) return null
        return LlamaCppEngine.nativeGetMetadata(handle)
    }

    override fun free(handle: Long) {
        if (isAvailable()) LlamaCppEngine.nativeFree(handle)
    }

    override fun getLastFailureCategory(): String? {
        if (!isAvailable()) return null
        return LlamaCppEngine.nativeGetLastFailureCategory()
    }

    override fun getLastFailureCode(): Int {
        if (!isAvailable()) return 0
        return LlamaCppEngine.nativeGetLastFailureCode()
    }

    override fun probeReference(
        handle: Long,
        promptIndex: Int,
        roles: Array<String>?,
        contents: Array<String>?,
        nPositions: Int,
        operationControlId: Long
    ): String? {
        if (!isAvailable()) return null
        return LlamaCppEngine.nativeProbeReference(handle, promptIndex, roles, contents, nPositions, operationControlId)
    }

    override fun probeCompare(
        handle: Long,
        promptIndex: Int,
        roles: Array<String>?,
        contents: Array<String>?,
        nPositions: Int,
        operationControlId: Long
    ): String? {
        if (!isAvailable()) return null
        return LlamaCppEngine.nativeProbeCompare(handle, promptIndex, roles, contents, nPositions, operationControlId)
    }
}

/**
 * Wraps the native llama.cpp/GGUF inference engine.
 *
 * # Native handle lifetime and lifecycle guarantees
 *
 * State Machine:
 *   UNINITIALIZED ──► INITIALIZING ──► OPEN ──► CLOSING ──► CLOSED
 *                         │                        ▲
 *                         └──(cancelled/failed)────┘
 *
 * Structural invariants:
 *   1. nativeFree() CANNOT execute while any operation holds a valid lease on the handle.
 *   2. Once state becomes CLOSING or INITIALIZING, no new operation lease can be acquired.
 *   3. initialize() transitions to INITIALIZING with an [initEpoch]. If close() is requested
 *      while native model loading is in flight, close() invalidates the epoch and waits;
 *      when native loading completes, the obsolete handle is immediately freed and never published.
 *   4. InterruptedException during close wait NEVER frees a handle while active leases exist.
 */
class LlamaCppEngine @VisibleForTesting internal constructor(
    context: Context? = null,
    internal val nativeBridge: LlamaNativeBridge = DefaultLlamaNativeBridge
) {
    private val context: Context? = context?.applicationContext

    // Secondary public constructor for standard production use
    constructor(context: Context?) : this(context, DefaultLlamaNativeBridge)
    constructor() : this(null, DefaultLlamaNativeBridge)

    // Visible for testing only
    internal enum class EngineState {
        UNINITIALIZED,
        INITIALIZING,
        OPEN,
        CLOSING,
        CLOSED,
        CLOSE_FAILED
    }

    /** Monitor guarding lifecycle state transitions and active lease counts. */
    private val lifecycleLock = Any()
    private val nativeOperations = java.util.concurrent.locks.ReentrantLock()

    /** Raw native pointer. Only valid when engineState == OPEN. Zero otherwise. */
    @Volatile private var nativeHandle: Long = 0L

    /** Retained unresolved resources when close or cleanup fails. */
    @Volatile private var failedHandle: Long = 0L
    @Volatile private var failedBundleLease: AutoCloseable? = null
    @Volatile private var closeFailure: Throwable? = null

    val failedCloseError: Throwable?
        get() = synchronized(lifecycleLock) { closeFailure }

    /** Current lifecycle phase. Read/written only under lifecycleLock. */
    private var engineState = EngineState.UNINITIALIZED

    /**
     * Monotonically increasing initialization epoch counter.
     * Used to detect whether an in-flight nativeInitModel was superseded or cancelled.
     */
    private val initEpoch = AtomicLong(0L)

    /**
     * Count of ALL active native operations (generate + stop + getMetadata).
     * close() waits for this to reach 0 before freeing native resources.
     * Must only be accessed while holding lifecycleLock.
     */
    private var activeLeaseCount = 0

    val isInitialized: Boolean
        get() = synchronized(lifecycleLock) {
            engineState == EngineState.OPEN && nativeHandle != 0L
        }

    @Volatile private var _activeBackend: LlamaBackend = LlamaBackend.CPU

    val activeBackend: LlamaBackend
        get() = synchronized(lifecycleLock) { _activeBackend }

    @Volatile private var _actualConfiguration: ModelActualConfiguration? = null

    val actualConfiguration: ModelActualConfiguration?
        get() = synchronized(lifecycleLock) { _actualConfiguration }

    @Volatile private var activeBundleLease: AutoCloseable? = null

    // ── Testable seams ────────────────────────────────────────────────────────────────────────────

    @VisibleForTesting
    internal val testEngineStateName: String
        get() = synchronized(lifecycleLock) { engineState.name }

    @VisibleForTesting
    internal val testActiveLeaseCount: Int
        get() = synchronized(lifecycleLock) { activeLeaseCount }

    @VisibleForTesting
    internal val testNativeHandle: Long
        get() = synchronized(lifecycleLock) { nativeHandle }

    @VisibleForTesting
    internal val testFailedHandle: Long
        get() = synchronized(lifecycleLock) { failedHandle }

    @VisibleForTesting
    internal val testFailedBundleLease: AutoCloseable?
        get() = synchronized(lifecycleLock) { failedBundleLease }

    @VisibleForTesting
    internal val testInitEpoch: Long
        get() = initEpoch.get()

    // ── Generation ID tracking ────────────────────────────────────────────────────────────────────

    private val generationIdCounter = AtomicLong(1L)

    @VisibleForTesting
    internal val testGenerationIdCounter: Long
        get() = generationIdCounter.get()

    /** ID of the generation currently holding a lease, or 0 if none. Under lifecycleLock. */
    private var activeGenerationId = 0L

    // ── Lifecycle lease primitives ────────────────────────────────────────────────────────────────

    /**
     * Acquires a native operation lease. MUST be called while holding [lifecycleLock].
     *
     * Returns the native handle if the lease was successfully acquired (engine is OPEN),
     * 0L otherwise (engine is INITIALIZING, CLOSING, CLOSED, or UNINITIALIZED).
     */
    private fun acquireOperationLeaseUnlocked(): Long {
        if (engineState == EngineState.CLOSE_FAILED) {
            throw IllegalStateException("Engine is in unrecoverable CLOSE_FAILED state (handle: $failedHandle); app restart required.", closeFailure)
        }
        if (engineState != EngineState.OPEN || nativeHandle == 0L) return 0L
        activeLeaseCount++
        return nativeHandle
    }

    /**
     * Releases a native operation lease. MUST be called while holding [lifecycleLock].
     * MUST be called exactly once per successful [acquireOperationLeaseUnlocked] call.
     */
    private fun releaseOperationLeaseUnlocked() {
        activeLeaseCount--
        lifecycleLock.jvmNotifyAll()
    }

    // ── Companion (static inspection, library loading) ────────────────────────────────────────────

    companion object {
        private const val TAG = "LlamaCppEngine"
        var isLibraryLoaded = false
            private set

        init {
            try {
                System.loadLibrary("solus_llama")
                isLibraryLoaded = true
                safeLogI("Successfully loaded native libsolus_llama.so")
            } catch (t: Throwable) {
                // Native library unavailable in host JVM unit tests or on unsupported ABI
                safeLogW("Native libsolus_llama.so not loaded: ${t.message}")
            }
        }

        private fun safeLogI(msg: String) { runCatching { Log.i(TAG, msg) } }
        private fun safeLogW(msg: String) { runCatching { Log.w(TAG, msg) } }
        private fun safeLogE(msg: String, t: Throwable? = null) {
            runCatching { if (t != null) Log.e(TAG, msg, t) else Log.e(TAG, msg) }
        }

        fun getLastError(): String? = DefaultLlamaNativeBridge.getLastError()

        fun inspectModel(modelPath: String): LlamaModelInspectionResult {
            val file = File(modelPath)
            if (!modelPath.startsWith("/proc/self/fd/") && !file.exists()) {
                return LlamaModelInspectionResult(valid = false, error = "File does not exist: $modelPath")
            }
            if (!isLibraryLoaded) {
                return LlamaModelInspectionResult(
                    valid = false,
                    error = "Native library libsolus_llama.so is not available on this device ABI"
                )
            }
            return try {
                val jsonStr = DefaultLlamaNativeBridge.inspectModel(file.absolutePath)
                    ?: return LlamaModelInspectionResult(
                        valid = false,
                        error = getLastError() ?: "Failed to inspect GGUF header"
                    )
                parseInspectionJson(jsonStr)
            } catch (e: Throwable) {
                safeLogE("GGUF inspection failed for $modelPath", e)
                LlamaModelInspectionResult(
                    valid = false,
                    error = "GGUF inspection error: ${e.localizedMessage ?: e.message}"
                )
            }
        }

        internal fun parseInspectionJson(jsonStr: String): LlamaModelInspectionResult {
            return runCatching {
                val json = JSONObject(jsonStr)
                LlamaModelInspectionResult(
                    valid = json.optBoolean("valid", false),
                    version = json.optInt("version", 0),
                    architecture = json.optString("architecture", ""),
                    name = json.optString("name", ""),
                    desc = json.optString("desc", ""),
                    tensorCount = json.optLong("n_tensors", 0L),
                    trainContextLength = json.optInt("n_ctx_train", 0),
                    embeddingLength = json.optInt("n_embd", 0),
                    layerCount = json.optInt("n_layer", 0),
                    hasChatTemplate = json.optBoolean("has_chat_template", false),
                    primaryQuantization = json.optString("primary_quant", ""),
                    isSupported = json.optBoolean("is_supported", false),
                    error = json.optString("error", ""),
                    splitNo = json.optInt("split_no", -1),
                    splitCount = json.optInt("split_count", -1),
                    isProjector = json.optBoolean("is_projector", false),
                    clipHasVisionEncoder = json.optBoolean("clip_has_vision_encoder", false),
                    clipHasAudioEncoder = json.optBoolean("clip_has_audio_encoder", false),
                    thinkingCapability = json.optString("thinking_capability", "Unknown")
                )
            }.getOrElse {
                fun extractString(key: String): String =
                    Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(jsonStr)?.groupValues?.get(1) ?: ""
                fun extractLong(key: String): Long =
                    Regex("\"$key\"\\s*:\\s*(\\d+)").find(jsonStr)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                fun extractInt(key: String, default: Int = 0): Int =
                    Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(jsonStr)?.groupValues?.get(1)?.toIntOrNull() ?: default
                fun extractBoolean(key: String): Boolean =
                    Regex("\"$key\"\\s*:\\s*(true|false)").find(jsonStr)?.groupValues?.get(1)?.toBooleanStrictOrNull() ?: false

                LlamaModelInspectionResult(
                    valid = extractBoolean("valid"),
                    version = extractInt("version"),
                    architecture = extractString("architecture"),
                    name = extractString("name"),
                    desc = extractString("desc"),
                    tensorCount = extractLong("n_tensors"),
                    trainContextLength = extractInt("n_ctx_train"),
                    embeddingLength = extractInt("n_embd"),
                    layerCount = extractInt("n_layer"),
                    hasChatTemplate = extractBoolean("has_chat_template"),
                    primaryQuantization = extractString("primary_quant"),
                    isSupported = extractBoolean("is_supported"),
                    error = extractString("error"),
                    splitNo = extractInt("split_no", -1),
                    splitCount = extractInt("split_count", -1),
                    isProjector = extractBoolean("is_projector"),
                    clipHasVisionEncoder = extractBoolean("clip_has_vision_encoder"),
                    clipHasAudioEncoder = extractBoolean("clip_has_audio_encoder"),
                    thinkingCapability = extractString("thinking_capability").ifBlank { "Unknown" }
                )
            }
        }

        fun canLoadModel(modelPath: String): String? {
            if (!File(modelPath).exists()) return "File does not exist: $modelPath"
            if (!isLibraryLoaded) return null
            val inspection = inspectModel(modelPath)
            if (!inspection.valid) return inspection.error.ifBlank { "Invalid or corrupt GGUF file" }
            if (!inspection.isSupported) return inspection.error.ifBlank { "GGUF model architecture or tensor type is unsupported" }
            return null
        }

        /**
         * Validates that a downloaded GGUF file's size matches the expected size within ±5%.
         *
         * Returns null if validation passes (or expectedBytes == 0L, meaning no check).
         * Returns a non-null warning string if the size is out of range.
         *
         * This catches the most common silent error: the user downloads a different quantization
         * variant (e.g. Q4_0 instead of Q4_K_M), which causes a completely different codepath
         * in ggml-quants.c and may crash or produce garbage output.
         */
        fun validateGgufArtifactSize(modelPath: String, expectedBytes: Long): String? {
            if (expectedBytes <= 0L) return null // No check configured for this model
            val file = File(modelPath)
            if (!file.exists()) return null // File-not-found is handled elsewhere
            val actualBytes = file.length()
            if (actualBytes == 0L) return "GGUF file is empty (0 bytes): $modelPath"
            val tolerance = expectedBytes / 20L // 5% of expected
            val lower = expectedBytes - tolerance
            val upper = expectedBytes + tolerance
            if (actualBytes < lower || actualBytes > upper) {
                val actualMb = actualBytes / (1024.0 * 1024.0)
                val expectedMb = expectedBytes / (1024.0 * 1024.0)
                return String.format(
                    java.util.Locale.US,
                    "GGUF artifact size mismatch: expected ~%.1f MB (%.0f B) ±5%%, " +
                    "actual %.1f MB (%d B). " +
                    "A wrong quantization variant may have been downloaded. " +
                    "Delete the file and re-download to fix this.",
                    expectedMb, expectedBytes.toDouble(), actualMb, actualBytes
                )
            }
            return null
        }

        @JvmStatic external fun nativeRenderPortableChat(template: String, messages: String, bosToken: String, eosToken: String, thinking: Boolean): String
        @JvmStatic external fun nativeSupportsTools(handle: Long): Boolean
        @JvmStatic external fun nativeSetToolRequest(handle: Long, request: String?)
        @JvmStatic external fun nativeGetToolResponse(handle: Long): String?
        @JvmStatic external fun nativeGetLastError(): String?
        @JvmStatic external fun nativeInspectModel(modelPath: String): String?
        @JvmStatic external fun nativeIsVulkanAvailable(): Boolean
        @JvmStatic external fun nativeGetVulkanDeviceInfo(): String?
        @JvmStatic external fun nativeGetActiveBackend(handle: Long): String?
        @JvmStatic external fun nativeCreateOperationControl(): Long
        @JvmStatic external fun nativeCancelOperationControl(controlId: Long)
        @JvmStatic external fun nativeReleaseOperationControl(controlId: Long)
        @JvmStatic external fun nativeGetOperationControlProgress(controlId: Long): Int
        @JvmStatic external fun nativeGetOperationControlSnapshot(controlId: Long): String?
        @JvmStatic external fun nativeInitModel(
            modelPath: String, nThreads: Int, nThreadsBatch: Int, nCtx: Int, nBatch: Int, nUbatch: Int,
            backend: Int, nGpuLayers: Int, operationControlId: Long
        ): Long
        @JvmStatic external fun nativeInitModelBundle(
            modelPaths: Array<String>, projectorPath: String?, nThreads: Int, nThreadsBatch: Int, nCtx: Int, nBatch: Int, nUbatch: Int,
            backend: Int, nGpuLayers: Int, operationControlId: Long
        ): Long
        @JvmStatic external fun nativeGenerateStream(
            handle: Long, generationId: Long, prompt: String,
            roles: Array<String>?, contents: Array<String>?,
            temperature: Float, topP: Float, topK: Int, minP: Float,
            repeatPenalty: Float, maxTokens: Int, seed: Int,
            enableThinking: Boolean,
            callback: LlamaTokenCallback
        ): Int
        @JvmStatic external fun nativeGenerateMultimodalStream(
            handle: Long, generationId: Long, prompt: String,
            roles: Array<String>?, contents: Array<String>?,
            mediaTypes: IntArray?, mediaMessageIndices: IntArray?,
            imageWidths: IntArray?, imageHeights: IntArray?,
            imageBytes: Array<ByteArray>?, audioFloats: Array<FloatArray>?,
            temperature: Float, topP: Float, topK: Int, minP: Float,
            repeatPenalty: Float, maxTokens: Int, seed: Int,
            enableThinking: Boolean,
            callback: LlamaTokenCallback
        ): Int
        @JvmStatic external fun nativeStop(handle: Long, generationId: Long)
        @JvmStatic external fun nativeGetMetadata(handle: Long): String?
        @JvmStatic external fun nativeResetAfterValidation(handle: Long)
        @JvmStatic external fun nativeClearProbeReferences()
        @JvmStatic external fun nativeAllocateTensorBuffer(bytes: Long): java.nio.ByteBuffer
        @JvmStatic external fun nativeFreeTensorBuffer(buffer: java.nio.ByteBuffer)
        @JvmStatic external fun nativeWarmup(handle: Long): Boolean
        @JvmStatic external fun nativeFree(handle: Long)
        @JvmStatic external fun nativeGetLastFailureCategory(): String?
        @JvmStatic external fun nativeGetLastFailureCode(): Int
        @JvmStatic external fun nativeProbeReference(
            handle: Long, promptIndex: Int, roles: Array<String>?, contents: Array<String>?, nPositions: Int,
            operationControlId: Long
        ): String?
        @JvmStatic external fun nativeProbeCompare(
            handle: Long, promptIndex: Int, roles: Array<String>?, contents: Array<String>?, nPositions: Int,
            operationControlId: Long
        ): String?

        fun isVulkanAvailable(): Boolean = DefaultLlamaNativeBridge.isVulkanAvailable()

        fun getVulkanDeviceInfo(): VulkanDeviceInfo? {
            val jsonStr = DefaultLlamaNativeBridge.getVulkanDeviceInfo() ?: return null
            return try {
                val json = JSONObject(jsonStr)
                val avail = json.optBoolean("available", false)
                val count = json.optInt("device_count", 0)
                val devArr = json.optJSONArray("devices")
                val devList = mutableListOf<VulkanDevice>()
                if (devArr != null) {
                    for (i in 0 until devArr.length()) {
                        val d = devArr.getJSONObject(i)
                        devList.add(
                            VulkanDevice(
                                index = d.optInt("index", i),
                                name = d.optString("name", "Unknown GPU"),
                                freeMemoryMb = d.optLong("free_memory_mb", 0L),
                                totalMemoryMb = d.optLong("total_memory_mb", 0L)
                            )
                        )
                    }
                }
                VulkanDeviceInfo(available = avail, deviceCount = count, devices = devList,
                    driverIdentity = json.optString("driver_identity", ""))
            } catch (e: Exception) {
                null
            }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────────────────────────

    /**
     * Loads a GGUF model and initialises the native context.
     *
     * Invariants enforced:
     * - Coordinated via [lifecycleLock] without holding any lock around the native loading step.
     * - Any prior model (OPEN, CLOSING, or INITIALIZING) is fully closed and freed before
     *   the new model is loaded.
     * - Uses [initEpoch] to guarantee that if close() is called while native loading is in flight,
     *   the resulting handle is immediately freed and never published as OPEN.
     */
    fun initialize(
        modelPath: String,
        threads: Int = 4,
        prefillThreads: Int = threads,
        contextWindow: Int = 2048,
        nBatch: Int = 256,
        nUbatch: Int = 128,
        backend: LlamaBackend = LlamaBackend.CPU,
        nGpuLayers: Int = 99,
        expectedFileSizeBytes: Long = 0L,
        operationControlId: Long = 0L
    ) {
        initialize(
            modelPaths = listOf(modelPath),
            projectorPath = null,
            lease = null,
            threads = threads,
            prefillThreads = prefillThreads,
            contextWindow = contextWindow,
            nBatch = nBatch,
            nUbatch = nUbatch,
            backend = backend,
            nGpuLayers = nGpuLayers,
            expectedFileSizeBytes = expectedFileSizeBytes,
            operationControlId = operationControlId
        )
    }

    fun initializeBundle(
        context: Context,
        bundle: ModelBundle,
        threads: Int = 4,
        prefillThreads: Int = threads,
        contextWindow: Int = 2048,
        nBatch: Int = 256,
        nUbatch: Int = 128,
        backend: LlamaBackend = LlamaBackend.CPU,
        nGpuLayers: Int = 99,
        expectedFileSizeBytes: Long = 0L,
        operationControlId: Long = 0L
    ) {
        val lease = ModelSourceResolver.resolve(context, bundle)
        try {
            initialize(
                modelPaths = lease.paths,
                projectorPath = lease.projectorPath,
                lease = lease,
                threads = threads,
                prefillThreads = prefillThreads,
                contextWindow = contextWindow,
                nBatch = nBatch,
                nUbatch = nUbatch,
                backend = backend,
                nGpuLayers = nGpuLayers,
                expectedFileSizeBytes = expectedFileSizeBytes,
                operationControlId = operationControlId
            )
        } catch (e: Throwable) {
            if (activeBundleLease !== lease && failedBundleLease !== lease) {
                runCatching { lease.close() }
            }
            throw e
        }
    }

    fun initialize(
        modelPaths: List<String>,
        projectorPath: String? = null,
        lease: AutoCloseable? = null,
        threads: Int = 4,
        prefillThreads: Int = threads,
        contextWindow: Int = 2048,
        nBatch: Int = 256,
        nUbatch: Int = 128,
        backend: LlamaBackend = LlamaBackend.CPU,
        nGpuLayers: Int = 99,
        expectedFileSizeBytes: Long = 0L,
        operationControlId: Long = 0L
    ) {
        if (modelPaths.isEmpty()) throw IllegalArgumentException("No model paths provided for GGUF initialization")

        if (modelPaths.size == 1 && expectedFileSizeBytes > 0L) {
            val singleFile = File(modelPaths[0])
            if (singleFile.exists()) {
                val sizeWarning = validateGgufArtifactSize(modelPaths[0], expectedFileSizeBytes)
                if (sizeWarning != null) {
                    safeLogE("[ARTIFACT_VALIDATION] $sizeWarning")
                } else {
                    safeLogI("[ARTIFACT_VALIDATION] File size OK: ${singleFile.length()} bytes (expected $expectedFileSizeBytes ±5%)")
                }
            }
        }

        synchronized(lifecycleLock) {
            if (engineState == EngineState.CLOSE_FAILED) {
                throw IllegalStateException("Engine is in unrecoverable CLOSE_FAILED state (handle: $failedHandle); restart required.", closeFailure)
            }
        }

        // Step 1: Ensure any active or closing model is completely closed
        close()

        val myEpoch: Long
        synchronized(lifecycleLock) {
            if (engineState == EngineState.CLOSE_FAILED) {
                throw IllegalStateException("Engine transitioned to CLOSE_FAILED state during shutdown; restart required.", closeFailure)
            }
            while (engineState == EngineState.CLOSING || engineState == EngineState.INITIALIZING) {
                try {
                    lifecycleLock.jvmWait(50)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IllegalStateException("Initialization interrupted while waiting for previous state to resolve", e)
                }
            }
            if (engineState == EngineState.CLOSE_FAILED) {
                throw IllegalStateException("Engine transitioned to CLOSE_FAILED state; restart required.", closeFailure)
            }
            engineState = EngineState.INITIALIZING
            myEpoch = initEpoch.incrementAndGet()
            lifecycleLock.jvmNotifyAll()
        }

        // Step 2: Native model load outside lifecycleLock so inference/UI is not blocked
        var handle = 0L
        var initError: Throwable? = null
        try {
            if (!nativeBridge.isAvailable()) {
                throw IllegalStateException("Native library libsolus_llama.so is not available on this device/ABI")
            }
            handle = AppTrace.section("cpu_gpu_loading") {
                nativeBridge.initModelBundle(
                    modelPaths = modelPaths.toTypedArray(),
                    projectorPath = projectorPath,
                    nThreads = threads,
                    nThreadsBatch = prefillThreads,
                    nCtx = contextWindow,
                    nBatch = nBatch,
                    nUbatch = nUbatch,
                    backend = backend.id,
                    nGpuLayers = if (backend == LlamaBackend.VULKAN) nGpuLayers else 0,
                    operationControlId = operationControlId
                )
            }
            if (handle == 0L) {
                val cat = nativeBridge.getLastFailureCategory()
                val code = nativeBridge.getLastFailureCode()
                val nativeErr = nativeBridge.getLastError()?.takeIf { it.isNotBlank() } ?: "unknown native loader failure"
                if (cat == "UserStopped" || nativeErr.contains("cancelled", ignoreCase = true) || nativeErr.contains("user", ignoreCase = true)) {
                    throw CancellationException("Model loading cancelled: $nativeErr")
                }
                val suffix = if (!cat.isNullOrBlank()) " [$cat:$code]" else ""
                throw IllegalStateException("GGUF initialization failed$suffix: $nativeErr")
            }
        } catch (t: Throwable) {
            initError = t
        }

        // Step 3: Read metadata and construct configuration before publishing
        var metadataError: Throwable? = null
        var loadedBackend = backend
        var loadedConfig: ModelActualConfiguration? = null
        if (handle != 0L && initError == null) {
            try {
                val metaJson = nativeBridge.getMetadata(handle)
                val metadata = if (!metaJson.isNullOrBlank()) parseMetadataJson(metaJson) else null
                val backendStr = metadata?.backend ?: nativeBridge.getActiveBackend(handle)
                loadedBackend = LlamaBackend.fromName(backendStr)
                loadedConfig = ModelActualConfiguration(
                    runtimeKind = "Gguf",
                    backend = loadedBackend.displayName,
                    threads = threads,
                    prefillThreads = prefillThreads,
                    contextWindow = metadata?.contextSize?.takeIf { it > 0 } ?: contextWindow,
                    batchSize = metadata?.batchSize?.takeIf { it > 0 } ?: nBatch,
                    ubatchSize = metadata?.microBatchSize?.takeIf { it > 0 } ?: nUbatch,
                    gpuLayers = if (loadedBackend == LlamaBackend.VULKAN) (metadata?.nGpuLayers ?: nGpuLayers) else 0,
                    contextLength = metadata?.contextSize?.takeIf { it > 0 } ?: contextWindow,
                    actualComputeBackends = metadata?.actualComputeBackends ?: emptyList(),
                    effectiveOffloadKqv = metadata?.effectiveOffloadKqv ?: false,
                    isCpuIsolated = metadata?.isCpuIsolated ?: false,
                    gpuComputeBufferBytes = metadata?.gpuComputeBufferBytes ?: 0L,
                    backendPolicyVersion = metadata?.backendPolicyVersion ?: 1
                )
            } catch (t: Throwable) {
                metadataError = t
            }
        }

        // Step 4: Publish handle ONLY if this initialization epoch was NOT cancelled/superseded and no error occurred
        val hasFailed = initError != null || metadataError != null
        synchronized(lifecycleLock) {
            if (!hasFailed && engineState == EngineState.INITIALIZING && myEpoch == initEpoch.get() && handle != 0L) {
                nativeHandle = handle
                activeBundleLease = lease
                _activeBackend = loadedBackend
                _actualConfiguration = loadedConfig
                engineState = EngineState.OPEN
                lifecycleLock.jvmNotifyAll()
                safeLogI("LlamaCppEngine initialized: ${modelPaths.size} part(s) on ${_activeBackend.displayName} (ctx=${_actualConfiguration?.contextWindow}, threads=$threads, prefillThreads=$prefillThreads, batch=${_actualConfiguration?.batchSize}/${_actualConfiguration?.ubatchSize}, gpu_layers=${_actualConfiguration?.gpuLayers}, projector=${projectorPath != null})")
                return
            }
        }

        // Step 5: Unified cleanup path for all post-load failure / cancellation / superseded outcomes
        val effectiveFailure = initError ?: metadataError ?: IllegalStateException("Initialization cancelled or superseded")
        if (handle != 0L) {
            var freeOk = true
            var freeThrowable: Throwable? = null
            try {
                nativeBridge.free(handle)
            } catch (t: Throwable) {
                freeOk = false
                freeThrowable = t
                safeLogW("Error freeing discarded model handle $handle: ${t.message}")
            }

            if (freeOk) {
                runCatching { lease?.close() }
                synchronized(lifecycleLock) {
                    if (engineState == EngineState.INITIALIZING || engineState == EngineState.CLOSING) {
                        engineState = EngineState.CLOSED
                    }
                    lifecycleLock.jvmNotifyAll()
                }
            } else {
                safeLogE("Retaining lease and handle $handle because native free failed for discarded model; transitioning to CLOSE_FAILED", freeThrowable)
                synchronized(lifecycleLock) {
                    failedHandle = handle
                    failedBundleLease = lease
                    closeFailure = freeThrowable ?: IllegalStateException("Free discarded model handle failed")
                    engineState = EngineState.CLOSE_FAILED
                    lifecycleLock.jvmNotifyAll()
                }
                throw IllegalStateException("Failed to free discarded native model handle ($handle). Engine in CLOSE_FAILED state; restart required.", freeThrowable)
            }
        } else {
            runCatching { lease?.close() }
            synchronized(lifecycleLock) {
                if (engineState == EngineState.INITIALIZING || engineState == EngineState.CLOSING) {
                    engineState = EngineState.CLOSED
                }
                lifecycleLock.jvmNotifyAll()
            }
        }

        throw effectiveFailure
    }

    /**
     * Streams token generation from the GGUF model with optional multimodal media inputs (images/audio).
     *
     * Acquires an operation lease before calling JNI. The lease prevents
     * nativeFree from executing until this function completes.
     */
    suspend fun generateMultimodalStream(
        prompt: String,
        messages: List<Pair<String, String>>? = null,
        mediaInputs: List<MultimodalMediaInput>? = null,
        samplingParams: LlamaSamplingParams = LlamaSamplingParams(),
        enableThinking: Boolean = true,
        onMetrics: ((LlamaGenerationMetrics) -> Unit)? = null,
        onFirstToken: (() -> Unit)? = null,
        onStructuredToken: ((answer: String, reasoning: String, isThinkingActive: Boolean) -> Unit)? = null,
        toolRequestJson: String? = null,
        onToolResponse: ((String) -> Unit)? = null,
        onToken: (String) -> Unit
    ): LlamaGenerationResult = withContext(Dispatchers.IO) {
        val genId = generationIdCounter.incrementAndGet()
        var leaseHandle = 0L

        synchronized(lifecycleLock) {
            leaseHandle = acquireOperationLeaseUnlocked()
            if (leaseHandle == 0L) {
                return@withContext LlamaGenerationResult.Failed
            }
        }

        if (!nativeBridge.isAvailable()) {
            synchronized(lifecycleLock) {
                if (activeGenerationId == genId) activeGenerationId = 0L
                releaseOperationLeaseUnlocked()
            }
            throw IllegalStateException("Native llama.cpp library is not loaded")
        }

        val rolesArray = messages?.map { it.first }?.toTypedArray()
        val contentsArray = messages?.map { it.second }?.toTypedArray()

        val mediaTypes = mediaInputs?.map { if (it is MultimodalMediaInput.Image) 0 else 1 }?.toIntArray()
        val mediaMessageIndices = mediaInputs?.map { it.messageIndex }?.toIntArray()
        val imageWidths = mediaInputs?.map { if (it is MultimodalMediaInput.Image) it.width else 0 }?.toIntArray()
        val imageHeights = mediaInputs?.map { if (it is MultimodalMediaInput.Image) it.height else 0 }?.toIntArray()
        val imageBytes = mediaInputs?.map { if (it is MultimodalMediaInput.Image) it.rgbBytes else ByteArray(0) }?.toTypedArray()
        val audioFloats = mediaInputs?.map { if (it is MultimodalMediaInput.Audio) it.samples else FloatArray(0) }?.toTypedArray()

        var generationError: String? = null
        var wasStopped = false

        val callback = object : LlamaTokenCallback {
            override fun onFirstToken() {
                if (!wasStopped && activeGenerationId == genId) onFirstToken?.invoke()
            }
            override fun onToken(token: String) {
                if (token.isNotEmpty() && !wasStopped && activeGenerationId == genId) onToken(token)
            }
            override fun onStructuredToken(answer: String, reasoning: String, isThinkingActive: Boolean) {
                if (!wasStopped && activeGenerationId == genId) {
                    onStructuredToken?.invoke(answer, reasoning, isThinkingActive)
                }
            }
            override fun onComplete() {
                safeLogI("GGUF generation completed (genId=$genId)")
            }
            override fun onStop() {
                wasStopped = true
                safeLogI("GGUF generation stopped (genId=$genId)")
            }
            override fun onError(error: String) {
                safeLogE("GGUF generation error (genId=$genId): $error")
                if (activeGenerationId == genId) generationError = error
            }
            override fun onMetrics(prefillDurationMs: Long, decodeDurationMs: Long, tokenCount: Int) {
                if (activeGenerationId == genId) {
                    onMetrics?.invoke(LlamaGenerationMetrics(prefillDurationMs, decodeDurationMs, tokenCount))
                }
            }
            override fun onMetrics(
                prefillDurationMs: Long,
                decodeDurationMs: Long,
                tokenCount: Int,
                promptTokens: Int,
                reusedPromptTokens: Int,
                evaluatedPromptTokens: Int
            ) {
                if (activeGenerationId == genId) {
                    onMetrics?.invoke(
                        LlamaGenerationMetrics(
                            prefillDurationMs = prefillDurationMs,
                            decodeDurationMs = decodeDurationMs,
                            tokenCount = tokenCount,
                            promptTokens = promptTokens,
                            reusedPromptTokens = reusedPromptTokens,
                            evaluatedPromptTokens = evaluatedPromptTokens
                        )
                    )
                }
            }
        }

        nativeOperations.lock()
        try {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            synchronized(lifecycleLock) { activeGenerationId = genId }
            if (toolRequestJson != null) {
                check(nativeBridge === DefaultLlamaNativeBridge && nativeSupportsTools(leaseHandle)) { "Template does not support tools" }
                nativeSetToolRequest(leaseHandle, toolRequestJson)
            }
            val res = nativeBridge.generateMultimodalStream(
                handle = leaseHandle,
                generationId = genId,
                prompt = prompt,
                roles = rolesArray,
                contents = contentsArray,
                mediaTypes = mediaTypes,
                mediaMessageIndices = mediaMessageIndices,
                imageWidths = imageWidths,
                imageHeights = imageHeights,
                imageBytes = imageBytes,
                audioFloats = audioFloats,
                temperature = samplingParams.temperature,
                topP = samplingParams.topP,
                topK = samplingParams.topK,
                minP = samplingParams.minP,
                repeatPenalty = samplingParams.repeatPenalty,
                maxTokens = samplingParams.maxTokens,
                seed = samplingParams.seed,
                enableThinking = enableThinking,
                callback = callback
            )
            if (res < 0 || generationError != null) {
                val failureCatName = nativeBridge.getLastFailureCategory()
                val category = LlamaFailureCategory.fromName(failureCatName)
                val code = if (res < 0) res else nativeBridge.getLastFailureCode()
                val errMessage = generationError
                    ?: "Llama.cpp native generation failed with code $res (category: $category)"
                throw LlamaExecutionException(errMessage, category, code)
            }
            if (res == 1 || wasStopped) return@withContext LlamaGenerationResult.Stopped
            if (toolRequestJson != null) {
                val response = nativeGetToolResponse(leaseHandle)
                check(!response.isNullOrBlank()) { "Tool response could not be parsed" }
                onToolResponse?.invoke(response)
            }
            return@withContext LlamaGenerationResult.Completed
        } finally {
            if (toolRequestJson != null && nativeBridge === DefaultLlamaNativeBridge) nativeSetToolRequest(leaseHandle, null)
            nativeOperations.unlock()
            synchronized(lifecycleLock) {
                if (activeGenerationId == genId) activeGenerationId = 0L
                releaseOperationLeaseUnlocked()
            }
        }
    }

    /**
     * Streams text token generation from the GGUF model.
     */
    suspend fun generateStream(
        prompt: String,
        messages: List<Pair<String, String>>? = null,
        samplingParams: LlamaSamplingParams = LlamaSamplingParams(),
        enableThinking: Boolean = true,
        onMetrics: ((LlamaGenerationMetrics) -> Unit)? = null,
        onFirstToken: (() -> Unit)? = null,
        onStructuredToken: ((answer: String, reasoning: String, isThinkingActive: Boolean) -> Unit)? = null,
        onToken: (String) -> Unit
    ): LlamaGenerationResult = generateMultimodalStream(
        prompt = prompt,
        messages = messages,
        mediaInputs = null,
        samplingParams = samplingParams,
        enableThinking = enableThinking,
        onMetrics = onMetrics,
        onFirstToken = onFirstToken,
        onStructuredToken = onStructuredToken,
        onToken = onToken
    )

    /**
     * Runs reference probe on current model context to record token probabilities.
     */
    fun supportsToolCalling(): Boolean {
        if (nativeBridge !== DefaultLlamaNativeBridge) return false
        val handle = synchronized(lifecycleLock) { acquireOperationLeaseUnlocked() }
        if (handle == 0L) return false
        nativeOperations.lock()
        return try { nativeSupportsTools(handle) } finally {
            nativeOperations.unlock()
            synchronized(lifecycleLock) { releaseOperationLeaseUnlocked() }
        }
    }

    fun resetAfterValidation() {
        val handle = synchronized(lifecycleLock) { acquireOperationLeaseUnlocked() }
        check(handle != 0L) { "Engine is not loaded" }
        nativeOperations.lock()
        try { nativeBridge.resetAfterValidation(handle) }
        finally {
            nativeOperations.unlock()
            synchronized(lifecycleLock) { releaseOperationLeaseUnlocked() }
        }
    }

    fun warmUp(): Boolean {
        if (nativeBridge !== DefaultLlamaNativeBridge) return false
        val handle = synchronized(lifecycleLock) { acquireOperationLeaseUnlocked() }
        check(handle != 0L) { "Engine is not loaded" }
        nativeOperations.lock()
        return try { nativeWarmup(handle) } finally {
            nativeOperations.unlock()
            synchronized(lifecycleLock) { releaseOperationLeaseUnlocked() }
        }
    }

    fun probeReference(promptIndex: Int, messages: List<Pair<String, String>>, nPositions: Int = 8, operationControlId: Long = 0L): String? {
        var leaseHandle = 0L
        synchronized(lifecycleLock) {
            leaseHandle = acquireOperationLeaseUnlocked()
            if (leaseHandle == 0L) return null
        }
        nativeOperations.lock()
        return try {
            val rolesArray = messages.map { it.first }.toTypedArray()
            val contentsArray = messages.map { it.second }.toTypedArray()
            nativeBridge.probeReference(leaseHandle, promptIndex, rolesArray, contentsArray, nPositions, operationControlId)
        } finally {
            nativeOperations.unlock()
            synchronized(lifecycleLock) { releaseOperationLeaseUnlocked() }
        }
    }

    /**
     * Compares probe token probabilities on current model context against reference run.
     */
    fun probeCompare(promptIndex: Int, messages: List<Pair<String, String>>, nPositions: Int = 8, operationControlId: Long = 0L): String? {
        var leaseHandle = 0L
        synchronized(lifecycleLock) {
            leaseHandle = acquireOperationLeaseUnlocked()
            if (leaseHandle == 0L) return null
        }
        nativeOperations.lock()
        return try {
            val rolesArray = messages.map { it.first }.toTypedArray()
            val contentsArray = messages.map { it.second }.toTypedArray()
            nativeBridge.probeCompare(leaseHandle, promptIndex, rolesArray, contentsArray, nPositions, operationControlId)
        } finally {
            nativeOperations.unlock()
            synchronized(lifecycleLock) { releaseOperationLeaseUnlocked() }
        }
    }

    data class NativeOperationSnapshot(
        val stage: String = "",
        val progressPermille: Int = 0,
        val completedUnits: Long = 0L,
        val totalUnits: Long = 0L,
        val isCancelled: Boolean = false
    )

    internal class NativeOperationToken(val id: Long, private val bridge: LlamaNativeBridge) : AutoCloseable {
        private val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        fun cancel() {
            if (cancelled.compareAndSet(false, true)) {
                if (id != 0L) bridge.cancelOperationControl(id)
            }
        }
        fun isCancelled(): Boolean = cancelled.get()
        fun getProgress(): Int = if (id != 0L) bridge.getOperationControlProgress(id) else -1
        fun getSnapshot(): NativeOperationSnapshot? {
            if (id == 0L) return null
            val jsonStr = bridge.getOperationControlSnapshot(id) ?: return null
            return try {
                val obj = JSONObject(jsonStr)
                NativeOperationSnapshot(
                    stage = obj.optString("stage", ""),
                    progressPermille = obj.optInt("progress_permille", 0),
                    completedUnits = obj.optLong("completed_units", 0L),
                    totalUnits = obj.optLong("total_units", 0L),
                    isCancelled = obj.optBoolean("is_cancelled", false)
                )
            } catch (_: Throwable) {
                null
            }
        }
        override fun close() {
            if (id != 0L) bridge.releaseOperationControl(id)
        }
    }

    internal fun createOperationToken(): NativeOperationToken {
        val id = nativeBridge.createOperationControl()
        return NativeOperationToken(id, nativeBridge)
    }

    /**
     * Reads model metadata from the loaded GGUF model.
     *
     * Acquires an operation lease before calling JNI. Safe against concurrent close().
     * Returns null if the engine is not OPEN.
     */
    fun getMetadata(): LlamaModelMetadata? {
        var leaseHandle = 0L
        synchronized(lifecycleLock) {
            leaseHandle = acquireOperationLeaseUnlocked()
            if (leaseHandle == 0L) return null
        }
        return try {
            if (!nativeBridge.isAvailable()) return null
            val jsonStr = nativeBridge.getMetadata(leaseHandle) ?: return null
            parseMetadataJson(jsonStr)
        } catch (e: Exception) {
            safeLogW("Failed to parse model metadata: ${e.message}")
            null
        } finally {
            synchronized(lifecycleLock) { releaseOperationLeaseUnlocked() }
        }
    }

    private fun parseMetadataJson(jsonStr: String): LlamaModelMetadata {
        return runCatching {
            val json = JSONObject(jsonStr)
            val backends = mutableListOf<String>()
            val backendsArr = json.optJSONArray("actual_compute_backends")
            if (backendsArr != null) {
                for (i in 0 until backendsArr.length()) {
                    backends.add(backendsArr.optString(i))
                }
            }
            LlamaModelMetadata(
                desc = json.optString("desc", ""),
                architecture = json.optString("architecture", ""),
                paramCount = json.optLong("n_params", 0L),
                trainContextLength = json.optInt("n_ctx_train", 0),
                vocabSize = json.optInt("n_vocab", 0),
                hasChatTemplate = json.optBoolean("has_chat_template", false),
                backend = json.optString("backend", "CPU"),
                nGpuLayers = json.optInt("n_gpu_layers", 0),
                contextSize = json.optInt("n_ctx", 0),
                batchSize = json.optInt("n_batch", 0),
                microBatchSize = json.optInt("n_ubatch", 0),
                actualComputeBackends = backends,
                effectiveOffloadKqv = json.optBoolean("effective_offload_kqv", false),
                isCpuIsolated = json.optBoolean("is_cpu_isolated", false),
                gpuComputeBufferBytes = json.optLong("gpu_compute_buffer_bytes", 0L),
                backendPolicyVersion = json.optInt("backend_policy_version", 1),
                supportsVision = json.optBoolean("supports_vision", false),
                supportsAudio = json.optBoolean("supports_audio", false),
                audioSampleRate = json.optInt("audio_sample_rate", -1),
                hasDecoder = json.optBoolean("has_decoder", true),
                upstreamRevision = json.optString("upstream_revision", "8bb3cf0d216a5ae48360179f2931daf284be4d01")
            )
        }.getOrElse {
            fun extractString(key: String): String =
                Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(jsonStr)?.groupValues?.get(1) ?: ""
            fun extractLong(key: String): Long =
                Regex("\"$key\"\\s*:\\s*(\\d+)").find(jsonStr)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            fun extractInt(key: String): Int =
                Regex("\"$key\"\\s*:\\s*(\\d+)").find(jsonStr)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            fun extractBoolean(key: String): Boolean =
                Regex("\"$key\"\\s*:\\s*(true|false)").find(jsonStr)?.groupValues?.get(1)?.toBooleanStrictOrNull() ?: false

            LlamaModelMetadata(
                desc = extractString("desc"),
                architecture = extractString("architecture"),
                paramCount = extractLong("n_params"),
                trainContextLength = extractInt("n_ctx_train"),
                vocabSize = extractInt("n_vocab"),
                hasChatTemplate = extractBoolean("has_chat_template"),
                backend = extractString("backend").ifBlank { "CPU" },
                nGpuLayers = extractInt("n_gpu_layers"),
                contextSize = extractInt("n_ctx"),
                batchSize = extractInt("n_batch"),
                microBatchSize = extractInt("n_ubatch"),
                effectiveOffloadKqv = extractBoolean("effective_offload_kqv"),
                isCpuIsolated = extractBoolean("is_cpu_isolated"),
                gpuComputeBufferBytes = extractLong("gpu_compute_buffer_bytes"),
                backendPolicyVersion = extractInt("backend_policy_version").let { if (it == 0) 1 else it },
                supportsVision = extractBoolean("supports_vision"),
                supportsAudio = extractBoolean("supports_audio"),
                audioSampleRate = extractInt("audio_sample_rate").let { if (it == 0) -1 else it },
                hasDecoder = if (jsonStr.contains("has_decoder")) extractBoolean("has_decoder") else true,
                upstreamRevision = extractString("upstream_revision").ifBlank { "8bb3cf0d216a5ae48360179f2931daf284be4d01" }
            )
        }
    }

    /**
     * Signals the active generation to stop.
     *
     * Acquires an operation lease before calling JNI. Safe against concurrent close().
     * If the engine is not OPEN, this is a safe no-op.
     */
    fun stop() {
        var leaseHandle = 0L
        var leaseGenId = 0L
        synchronized(lifecycleLock) {
            leaseHandle = acquireOperationLeaseUnlocked()
            if (leaseHandle == 0L) return
            leaseGenId = activeGenerationId
        }
        try {
            if (nativeBridge.isAvailable()) nativeBridge.stop(leaseHandle, leaseGenId)
        } finally {
            synchronized(lifecycleLock) { releaseOperationLeaseUnlocked() }
        }
    }

    /** Convenience wrapper for coroutine callers. */
    suspend fun closeAsync() = withContext(Dispatchers.IO) { close() }

    /**
     * Closes the engine and frees native resources.
     *
     * State machine transitions:
     * - If INITIALIZING: invalidates initEpoch, marks CLOSING, waits for in-flight init to exit.
     * - If OPEN: marks CLOSING, zeroes nativeHandle, stops decode, waits for active leases == 0, frees model, marks CLOSED.
     * - If CLOSING: waits until engineState == CLOSED.
     * - If UNINITIALIZED / CLOSED: no-op, returns immediately.
     */
    fun close() {
        val handleToFree: Long
        var interruptedInWait = false

        synchronized(lifecycleLock) {
            if (engineState == EngineState.CLOSE_FAILED) {
                throw IllegalStateException("Engine is in unrecoverable CLOSE_FAILED state (handle: $failedHandle); restart required.", closeFailure)
            }

            // 1. If another thread is actively closing, wait until it completes to CLOSED or CLOSE_FAILED
            while (engineState == EngineState.CLOSING) {
                try {
                    lifecycleLock.jvmWait(50)
                } catch (_: InterruptedException) {
                    interruptedInWait = true
                }
            }

            if (engineState == EngineState.CLOSE_FAILED) {
                throw IllegalStateException("Engine transitioned to CLOSE_FAILED state; restart required.", closeFailure)
            }

            // 2. If an initialization is in progress, cancel it by advancing initEpoch, mark CLOSING, and wait
            if (engineState == EngineState.INITIALIZING) {
                initEpoch.incrementAndGet() // invalidate epoch so initializer discards its handle
                engineState = EngineState.CLOSING
                lifecycleLock.jvmNotifyAll()

                while (engineState == EngineState.CLOSING) {
                    try {
                        lifecycleLock.jvmWait(50)
                    } catch (_: InterruptedException) {
                        interruptedInWait = true
                    }
                }
                if (interruptedInWait) Thread.currentThread().interrupt()
                if (engineState == EngineState.CLOSE_FAILED) {
                    throw IllegalStateException("Engine transitioned to CLOSE_FAILED state during initialization shutdown; restart required.", closeFailure)
                }
                return
            }

            if (engineState == EngineState.UNINITIALIZED || engineState == EngineState.CLOSED) {
                if (interruptedInWait) Thread.currentThread().interrupt()
                return
            }

            // Exactly one thread wins the transition from OPEN to CLOSING
            engineState = EngineState.CLOSING
            handleToFree = nativeHandle
            nativeHandle = 0L
            _activeBackend = LlamaBackend.CPU
            _actualConfiguration = null
            activeGenerationId = 0L
            initEpoch.incrementAndGet()
        }

        // 1. Signal cooperative abort to in-flight decodes
        if (nativeBridge.isAvailable() && handleToFree != 0L) {
            try { nativeBridge.stop(handleToFree, 0L) } catch (_: Throwable) {}
        }

        // 2. Wait until all active operation leases have been released
        try {
            synchronized(lifecycleLock) {
                while (activeLeaseCount > 0) {
                    try {
                        lifecycleLock.jvmWait(50)
                    } catch (_: InterruptedException) {
                        interruptedInWait = true
                    }
                }
            }
        } finally {
            if (interruptedInWait) {
                Thread.currentThread().interrupt()
            }
        }

        // 3. Guaranteed activeLeaseCount == 0: free native resources
        var freeSucceeded = false
        var freeThrowable: Throwable? = null
        try {
            if (nativeBridge.isAvailable() && handleToFree != 0L) {
                nativeBridge.free(handleToFree)
            }
            freeSucceeded = true
        } catch (t: Throwable) {
            freeThrowable = t
            safeLogW("nativeFree error: ${t.message}")
        } finally {
            if (freeSucceeded) {
                val leaseToClose = activeBundleLease
                activeBundleLease = null
                runCatching { leaseToClose?.close() }
                synchronized(lifecycleLock) {
                    engineState = EngineState.CLOSED
                    lifecycleLock.jvmNotifyAll()
                }
            } else {
                safeLogE("Retaining unresolved native handle $handleToFree and active bundle lease; transitioning to CLOSE_FAILED", freeThrowable)
                synchronized(lifecycleLock) {
                    failedHandle = handleToFree
                    failedBundleLease = activeBundleLease
                    activeBundleLease = null
                    closeFailure = freeThrowable ?: IllegalStateException("nativeFree failed for handle $handleToFree")
                    engineState = EngineState.CLOSE_FAILED
                    lifecycleLock.jvmNotifyAll()
                }
                throw IllegalStateException("Failed to free native resources for handle $handleToFree. Engine in CLOSE_FAILED state; app restart required.", freeThrowable)
            }
        }
    }

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private fun Any.jvmWait(timeoutMs: Long) { (this as java.lang.Object).wait(timeoutMs) }

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private fun Any.jvmNotifyAll() { (this as java.lang.Object).notifyAll() }
}
