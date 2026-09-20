package com.shounak.localmeshai.ai

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
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

interface LlamaTokenCallback {
    fun onToken(token: String)
    fun onComplete()
    fun onStop()
    fun onError(error: String)
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

data class LlamaModelMetadata(
    val desc: String = "",
    val architecture: String = "",
    val paramCount: Long = 0L,
    val trainContextLength: Int = 0,
    val vocabSize: Int = 0,
    val hasChatTemplate: Boolean = false
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
    val error: String = ""
)

/**
 * Abstraction layer for native llama.cpp JNI calls.
 * Enables deterministic lifecycle and concurrency regression testing on host JVM.
 */
internal interface LlamaNativeBridge {
    fun isAvailable(): Boolean
    fun getLastError(): String?
    fun inspectModel(modelPath: String): String?
    fun initModel(modelPath: String, nThreads: Int, nCtx: Int, nBatch: Int, nUbatch: Int): Long
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
    fun stop(handle: Long, generationId: Long)
    fun getMetadata(handle: Long): String?
    fun free(handle: Long)
}

internal object DefaultLlamaNativeBridge : LlamaNativeBridge {
    override fun isAvailable(): Boolean = LlamaCppEngine.isLibraryLoaded

    override fun getLastError(): String? {
        if (!isAvailable()) return "Native library libsolus_llama.so is not loaded"
        return LlamaCppEngine.nativeGetLastError()
    }

    override fun inspectModel(modelPath: String): String? {
        if (!isAvailable()) return null
        return LlamaCppEngine.nativeInspectModel(modelPath)
    }

    override fun initModel(modelPath: String, nThreads: Int, nCtx: Int, nBatch: Int, nUbatch: Int): Long {
        if (!isAvailable()) return 0L
        return LlamaCppEngine.nativeInitModel(modelPath, nThreads, nCtx, nBatch, nUbatch)
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
    ): Int {
        if (!isAvailable()) return -1
        return LlamaCppEngine.nativeGenerateStream(
            handle, generationId, prompt, roles, contents,
            temperature, topP, topK, minP, repeatPenalty, maxTokens, seed, callback
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
    private val context: Context? = null,
    internal val nativeBridge: LlamaNativeBridge = DefaultLlamaNativeBridge
) {
    // Secondary public constructor for standard production use
    constructor(context: Context?) : this(context, DefaultLlamaNativeBridge)
    constructor() : this(null, DefaultLlamaNativeBridge)

    // Visible for testing only
    internal enum class EngineState {
        UNINITIALIZED,
        INITIALIZING,
        OPEN,
        CLOSING,
        CLOSED
    }

    /** Monitor guarding lifecycle state transitions and active lease counts. */
    private val lifecycleLock = Any()

    /** Raw native pointer. Only valid when engineState == OPEN. Zero otherwise. */
    @Volatile private var nativeHandle: Long = 0L

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
            if (!file.exists()) {
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
                    error = json.optString("error", "")
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
                    error = extractString("error")
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

        @JvmStatic external fun nativeGetLastError(): String?
        @JvmStatic external fun nativeInspectModel(modelPath: String): String?
        @JvmStatic external fun nativeInitModel(
            modelPath: String, nThreads: Int, nCtx: Int, nBatch: Int, nUbatch: Int
        ): Long
        @JvmStatic external fun nativeGenerateStream(
            handle: Long, generationId: Long, prompt: String,
            roles: Array<String>?, contents: Array<String>?,
            temperature: Float, topP: Float, topK: Int, minP: Float,
            repeatPenalty: Float, maxTokens: Int, seed: Int,
            callback: LlamaTokenCallback
        ): Int
        @JvmStatic external fun nativeStop(handle: Long, generationId: Long)
        @JvmStatic external fun nativeGetMetadata(handle: Long): String?
        @JvmStatic external fun nativeFree(handle: Long)
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
        contextWindow: Int = 2048,
        nBatch: Int = 256,
        nUbatch: Int = 128,
        /**
         * Expected GGUF file size in bytes for artifact integrity validation. 0L = no check.
         * Logs a warning (does not block init) if the actual file size is outside ±5% of this value.
         */
        expectedFileSizeBytes: Long = 0L
    ) {
        val file = File(modelPath)
        if (!file.exists()) throw IllegalArgumentException("GGUF model file not found at $modelPath")

        // Artifact size validation: warn if file size doesn't match expected quantization variant
        val sizeWarning = validateGgufArtifactSize(modelPath, expectedFileSizeBytes)
        if (sizeWarning != null) {
            safeLogE("[ARTIFACT_VALIDATION] $sizeWarning")
            // We do NOT abort initialization here — the model may still work if the size
            // difference is due to a re-upload or metadata change. The warning is logged
            // to Logcat and is visible in the crash forensics workflow.
        } else if (expectedFileSizeBytes > 0L) {
            safeLogI("[ARTIFACT_VALIDATION] File size OK: ${file.length()} bytes (expected $expectedFileSizeBytes ±5%)")
        }

        // Step 1: Ensure any active or closing model is completely closed
        close()

        val myEpoch: Long
        synchronized(lifecycleLock) {
            while (engineState == EngineState.CLOSING || engineState == EngineState.INITIALIZING) {
                try {
                    lifecycleLock.jvmWait(50)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IllegalStateException("Initialization interrupted while waiting for previous state to resolve", e)
                }
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
            handle = nativeBridge.initModel(file.absolutePath, threads, contextWindow, nBatch, nUbatch)
            if (handle == 0L) {
                val nativeErr = nativeBridge.getLastError()?.takeIf { it.isNotBlank() } ?: "unknown native loader failure"
                throw IllegalStateException("GGUF initialization failed: $nativeErr (path: $modelPath)")
            }
        } catch (t: Throwable) {
            initError = t
        }

        // Step 3: Publish handle ONLY if this initialization epoch was NOT cancelled/superseded
        var handleToFree = 0L
        var shouldTransitionToClosed = false

        synchronized(lifecycleLock) {
            if (engineState == EngineState.INITIALIZING && myEpoch == initEpoch.get() && initError == null && handle != 0L) {
                // Success: publish handle and mark OPEN
                nativeHandle = handle
                engineState = EngineState.OPEN
                lifecycleLock.jvmNotifyAll()
                safeLogI("LlamaCppEngine initialized: ${file.name} (ctx=$contextWindow, threads=$threads, batch=$nBatch/$nUbatch)")
                return
            }

            // Initialization was cancelled/superseded, close() intervened, or init failed.
            // Discard the handle so it is never published as OPEN.
            if (handle != 0L) {
                handleToFree = handle
            }
            if (engineState == EngineState.INITIALIZING || engineState == EngineState.CLOSING) {
                shouldTransitionToClosed = true
            }
        }

        // Free the obsolete native handle FIRST, before publishing CLOSED!
        if (handleToFree != 0L) {
            try {
                nativeBridge.free(handleToFree)
            } catch (t: Throwable) {
                safeLogW("Error freeing discarded model handle: ${t.message}")
            }
        }

        // ONLY AFTER native resources are completely freed, transition to CLOSED and notify all waiters
        if (shouldTransitionToClosed) {
            synchronized(lifecycleLock) {
                if (engineState == EngineState.INITIALIZING || engineState == EngineState.CLOSING) {
                    engineState = EngineState.CLOSED
                    lifecycleLock.jvmNotifyAll()
                }
            }
        }

        initError?.let { throw it }
    }

    /**
     * Streams token generation from the GGUF model.
     *
     * Acquires an operation lease before calling JNI. The lease prevents
     * nativeFree from executing until this function completes.
     */
    suspend fun generateStream(
        prompt: String,
        messages: List<Pair<String, String>>? = null,
        samplingParams: LlamaSamplingParams = LlamaSamplingParams(),
        onToken: (String) -> Unit
    ): LlamaGenerationResult = withContext(Dispatchers.IO) {
        val genId = generationIdCounter.incrementAndGet()
        var leaseHandle = 0L

        synchronized(lifecycleLock) {
            leaseHandle = acquireOperationLeaseUnlocked()
            if (leaseHandle == 0L) {
                return@withContext LlamaGenerationResult.Failed
            }
            activeGenerationId = genId
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

        var generationError: String? = null
        var wasStopped = false

        val callback = object : LlamaTokenCallback {
            override fun onToken(token: String) {
                if (token.isNotEmpty() && !wasStopped) onToken(token)
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
                generationError = error
            }
        }

        try {
            val res = nativeBridge.generateStream(
                handle = leaseHandle,
                generationId = genId,
                prompt = prompt,
                roles = rolesArray,
                contents = contentsArray,
                temperature = samplingParams.temperature,
                topP = samplingParams.topP,
                topK = samplingParams.topK,
                minP = samplingParams.minP,
                repeatPenalty = samplingParams.repeatPenalty,
                maxTokens = samplingParams.maxTokens,
                seed = samplingParams.seed,
                callback = callback
            )
            if (res == 1 || wasStopped) return@withContext LlamaGenerationResult.Stopped
            generationError?.let { throw IllegalStateException("Llama.cpp generation error: $it") }
            return@withContext LlamaGenerationResult.Completed
        } finally {
            synchronized(lifecycleLock) {
                if (activeGenerationId == genId) activeGenerationId = 0L
                releaseOperationLeaseUnlocked()
            }
        }
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
            LlamaModelMetadata(
                desc = json.optString("desc", ""),
                architecture = json.optString("architecture", ""),
                paramCount = json.optLong("n_params", 0L),
                trainContextLength = json.optInt("n_ctx_train", 0),
                vocabSize = json.optInt("n_vocab", 0),
                hasChatTemplate = json.optBoolean("has_chat_template", false)
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
                hasChatTemplate = extractBoolean("has_chat_template")
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
            // 1. If another thread is actively closing, wait until it completes to CLOSED
            while (engineState == EngineState.CLOSING) {
                try {
                    lifecycleLock.jvmWait(50)
                } catch (_: InterruptedException) {
                    interruptedInWait = true
                }
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
        try {
            if (nativeBridge.isAvailable() && handleToFree != 0L) {
                nativeBridge.free(handleToFree)
            }
        } catch (t: Throwable) {
            safeLogW("nativeFree error: ${t.message}")
        } finally {
            synchronized(lifecycleLock) {
                engineState = EngineState.CLOSED
                lifecycleLock.jvmNotifyAll()
            }
        }
    }

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private fun Any.jvmWait(timeoutMs: Long) { (this as java.lang.Object).wait(timeoutMs) }

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private fun Any.jvmNotifyAll() { (this as java.lang.Object).notifyAll() }
}
