package com.shounak.localmeshai.ai

import android.content.Context
import android.net.Uri
import android.os.Build
import com.shounak.localmeshai.BuildConfig
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.shounak.localmeshai.models.ArtifactSource
import com.shounak.localmeshai.models.ModelArtifact
import com.shounak.localmeshai.models.ModelBundle
import com.shounak.localmeshai.models.ModelCatalog
import com.shounak.localmeshai.utils.AppTrace
import com.shounak.localmeshai.utils.ModelDownloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Validates GPU computation once per model and configuration using versioned validation receipts.
 *
 * Runs 3 short, fixed prompts through CPU and Vulkan sequentially to minimize memory usage.
 * Compares next-token softmax probability distributions over 8 positions, feeding the CPU-selected tokens
 * to both backends. Requires finite logits and total-variation distance <= 0.05 at every position.
 *
 * Results are cached persistently against the model SHA-256, native build ID, device/driver identity,
 * requested configuration, and actual allocated configuration.
 */
internal object GgufGpuValidator {
    private const val TAG = "GgufGpuValidator"
    private const val PREFS_NAME = "gguf_gpu_validation_cache"
    const val PROTOCOL_VERSION = 5
    const val NATIVE_BUILD_ID = BuildConfig.NATIVE_BUILD_ID
    const val PROBE_POSITIONS = 8
    const val MAX_TV_DISTANCE_THRESHOLD = 0.05f

    val VALIDATION_PROMPTS = listOf(
        "Hi",
        "Hello.",
        "Explain 2 + 2 in one sentence."
    )

    enum class FailureKind { CpuReference, GpuDisagreement, Initialization, Transient }

    data class ValidationReceipt(
        val protocolVersion: Int = PROTOCOL_VERSION,
        val backendPolicyVersion: Int = 1,
        val isCpuIsolated: Boolean = true,
        val modelSha256: String,
        val nativeBuildId: String = NATIVE_BUILD_ID,
        val deviceIdentity: String,
        val requestedThreads: Int,
        val requestedPrefillThreads: Int = requestedThreads,
        val requestedContextWindow: Int,
        val requestedBatch: Int,
        val requestedUbatch: Int,
        val actualThreads: Int,
        val actualPrefillThreads: Int = actualThreads,
        val actualContextWindow: Int,
        val actualBatch: Int,
        val actualUbatch: Int,
        val status: String,
        val failReason: String? = null,
        val permanent: Boolean = false
    ) {
        fun toJson(): String {
            val obj = JSONObject()
            obj.put("protocol", protocolVersion)
            obj.put("backend_policy_version", backendPolicyVersion)
            obj.put("is_cpu_isolated", isCpuIsolated)
            obj.put("model_sha", modelSha256)
            obj.put("build_id", nativeBuildId)
            obj.put("device_id", deviceIdentity)
            obj.put("req_threads", requestedThreads)
            obj.put("req_prefill_th", requestedPrefillThreads)
            obj.put("req_ctx", requestedContextWindow)
            obj.put("req_batch", requestedBatch)
            obj.put("req_ubatch", requestedUbatch)
            obj.put("act_threads", actualThreads)
            obj.put("act_prefill_th", actualPrefillThreads)
            obj.put("act_ctx", actualContextWindow)
            obj.put("act_batch", actualBatch)
            obj.put("act_ubatch", actualUbatch)
            obj.put("status", status)
            if (failReason != null) obj.put("fail_reason", failReason)
            obj.put("permanent", permanent)
            return obj.toString()
        }

        companion object {
            fun fromJson(jsonStr: String): ValidationReceipt? {
                return runCatching {
                    val obj = JSONObject(jsonStr)
                    val protocol = obj.optInt("protocol", 0)
                    if (protocol != PROTOCOL_VERSION) return null // Stale or older protocol receipt -> treated as pending
                    val modelSha = obj.getString("model_sha")
                    if (modelSha.startsWith("local_gguf_") || modelSha.startsWith("content:") || modelSha.startsWith("file:")) return null
                    val reqTh = obj.getInt("req_threads")
                    val actTh = obj.getInt("act_threads")
                    ValidationReceipt(
                        protocolVersion = protocol,
                        backendPolicyVersion = obj.optInt("backend_policy_version", 1),
                        isCpuIsolated = obj.optBoolean("is_cpu_isolated", true),
                        modelSha256 = obj.getString("model_sha"),
                        nativeBuildId = obj.optString("build_id", NATIVE_BUILD_ID),
                        deviceIdentity = obj.optString("device_id", ""),
                        requestedThreads = reqTh,
                        requestedPrefillThreads = obj.optInt("req_prefill_th", reqTh),
                        requestedContextWindow = obj.getInt("req_ctx"),
                        requestedBatch = obj.getInt("req_batch"),
                        requestedUbatch = obj.getInt("req_ubatch"),
                        actualThreads = actTh,
                        actualPrefillThreads = obj.optInt("act_prefill_th", actTh),
                        actualContextWindow = obj.getInt("act_ctx"),
                        actualBatch = obj.getInt("act_batch"),
                        actualUbatch = obj.getInt("act_ubatch"),
                        status = obj.getString("status"),
                        failReason = if (obj.has("fail_reason")) obj.getString("fail_reason") else null,
                        permanent = obj.optBoolean("permanent", false)
                    )
                }.getOrNull()
            }
        }
    }

    sealed class ValidationResult {
        object Pass : ValidationResult()
        data class Fail(
            val reason: String,
            val kind: FailureKind = FailureKind.GpuDisagreement,
            val permanent: Boolean = kind == FailureKind.GpuDisagreement
        ) : ValidationResult()
        object Pending : ValidationResult()
    }

    data class ProbeOutcome(val success: Boolean, val error: String, val cancelled: Boolean)

    private fun parseProbeResponse(jsonStr: String?): ProbeOutcome {
        if (jsonStr.isNullOrBlank()) return ProbeOutcome(false, "Empty probe response", false)
        return runCatching {
            val json = JSONObject(jsonStr)
            val success = json.optBoolean("success", false)
            val cancelled = json.optBoolean("cancelled", false) ||
                json.optString("error").contains("User stopped", ignoreCase = true) ||
                json.optString("error").contains("cancelled", ignoreCase = true)
            val error = json.optString("error", "")
            ProbeOutcome(success, error, cancelled)
        }.getOrElse {
            val success = Regex("\"success\"\\s*:\\s*(true|false)").find(jsonStr)?.groupValues?.get(1)?.toBooleanStrictOrNull() ?: false
            val cancelled = jsonStr.contains("\"cancelled\"\\s*:\\s*true".toRegex()) ||
                jsonStr.contains("User stopped", ignoreCase = true) ||
                jsonStr.contains("cancelled", ignoreCase = true)
            val error = Regex("\"error\"\\s*:\\s*\"([^\"]*)\"").find(jsonStr)?.groupValues?.get(1) ?: ""
            ProbeOutcome(success, error, cancelled)
        }
    }

    private fun safeLogI(msg: String) { runCatching { Log.i(TAG, msg) } }
    private fun safeLogW(msg: String) { runCatching { Log.w(TAG, msg) } }
    private fun safeLogE(msg: String, t: Throwable? = null) {
        runCatching { if (t != null) Log.e(TAG, msg, t) else Log.e(TAG, msg) }
    }

    fun getDeviceIdentity(): String {
        val gpuIdentity = runCatching {
            LlamaCppEngine.getVulkanDeviceInfo()?.let { info ->
                info.driverIdentity + info.devices.joinToString(";") { it.name }
            }
        }.getOrNull().orEmpty()
        return "${Build.MANUFACTURER}_${Build.MODEL}_${Build.HARDWARE}_${Build.FINGERPRINT}_${Build.DISPLAY}_$gpuIdentity".replace(" ", "_")
    }

    /**
     * Builds the unique cache key for validation results.
     */
    fun buildCacheKey(
        modelSha256: String,
        threads: Int,
        contextWindow: Int,
        nBatch: Int,
        nUbatch: Int,
        prefillThreads: Int = threads,
        companionSummary: String = ""
    ): String {
        val deviceIdentity = getDeviceIdentity()
        val companionPart = if (companionSummary.isNotBlank()) "_comp$companionSummary" else ""
        val configStr = "v${PROTOCOL_VERSION}_bp1_vk99_tv05_p8_th${threads}_pth${prefillThreads}_ctx${contextWindow}_b${nBatch}_ub${nUbatch}$companionPart"
        return "$modelSha256:$NATIVE_BUILD_ID:${BuildConfig.BUILD_TYPE}:$deviceIdentity:$configStr"
    }

    @VisibleForTesting
    internal val testMemoryCache = java.util.concurrent.ConcurrentHashMap<String, ValidationResult>()

    @VisibleForTesting
    internal val testReceiptCache = java.util.concurrent.ConcurrentHashMap<String, ValidationReceipt>()

    /**
     * Checks the cached validation state for a model and configuration.
     */
    fun getCachedResult(
        context: Context?,
        cacheKey: String
    ): ValidationResult {
        testMemoryCache[cacheKey]?.let { return it }
        val receipt = getReceipt(context, cacheKey) ?: return ValidationResult.Pending
        return when (receipt.status) {
            "PASS" -> ValidationResult.Pass
            "FAIL" -> if (receipt.permanent) ValidationResult.Fail(receipt.failReason ?: "GPU disagreement", FailureKind.GpuDisagreement, true)
                      else ValidationResult.Pending
            else -> ValidationResult.Pending
        }
    }

    fun getReceipt(context: Context?, cacheKey: String): ValidationReceipt? {
        testReceiptCache[cacheKey]?.let { return it }
        if (context == null) return null
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val value = prefs.getString(cacheKey, null) ?: return null
        return ValidationReceipt.fromJson(value)
    }

    /**
     * Saves a validation result into persistent preferences.
     */
    fun setCachedResult(
        context: Context?,
        cacheKey: String,
        result: ValidationResult
    ) {
        if (result is ValidationResult.Pending || (result is ValidationResult.Fail && !result.permanent)) {
            testMemoryCache.remove(cacheKey)
            testReceiptCache.remove(cacheKey)
        } else {
            testMemoryCache[cacheKey] = result
        }

        if (context == null) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        when (result) {
            is ValidationResult.Pass -> {
                val receipt = ValidationReceipt(
                    protocolVersion = PROTOCOL_VERSION,
                    modelSha256 = cacheKey.substringBefore(":"),
                    nativeBuildId = NATIVE_BUILD_ID,
                    deviceIdentity = getDeviceIdentity(),
                    requestedThreads = 4,
                    requestedPrefillThreads = 4,
                    requestedContextWindow = 2048,
                    requestedBatch = 256,
                    requestedUbatch = 128,
                    actualThreads = 4,
                    actualPrefillThreads = 4,
                    actualContextWindow = 2048,
                    actualBatch = 256,
                    actualUbatch = 128,
                    status = "PASS",
                    permanent = true
                )
                testReceiptCache[cacheKey] = receipt
                prefs.edit().putString(cacheKey, receipt.toJson()).apply()
            }
            is ValidationResult.Fail -> {
                if (result.permanent) {
                    val receipt = ValidationReceipt(
                        protocolVersion = PROTOCOL_VERSION,
                        modelSha256 = cacheKey.substringBefore(":"),
                        nativeBuildId = NATIVE_BUILD_ID,
                        deviceIdentity = getDeviceIdentity(),
                        requestedThreads = 4,
                        requestedPrefillThreads = 4,
                        requestedContextWindow = 2048,
                        requestedBatch = 256,
                        requestedUbatch = 128,
                        actualThreads = 4,
                        actualPrefillThreads = 4,
                        actualContextWindow = 2048,
                        actualBatch = 256,
                        actualUbatch = 128,
                        status = "FAIL",
                        failReason = result.reason,
                        permanent = true
                    )
                    testReceiptCache[cacheKey] = receipt
                    prefs.edit().putString(cacheKey, receipt.toJson()).apply()
                } else {
                    testReceiptCache.remove(cacheKey)
                    prefs.edit().remove(cacheKey).apply()
                }
            }
            is ValidationResult.Pending -> {
                testReceiptCache.remove(cacheKey)
                prefs.edit().remove(cacheKey).apply()
            }
        }
    }

    fun saveReceipt(context: Context?, cacheKey: String, receipt: ValidationReceipt) {
        testReceiptCache[cacheKey] = receipt
        if (receipt.status == "PASS") {
            testMemoryCache[cacheKey] = ValidationResult.Pass
        } else if (receipt.status == "FAIL" && receipt.permanent) {
            testMemoryCache[cacheKey] = ValidationResult.Fail(receipt.failReason ?: "GPU disagreement", FailureKind.GpuDisagreement, true)
        } else {
            testMemoryCache.remove(cacheKey)
        }

        if (context == null) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(cacheKey, receipt.toJson()).apply()
    }

    /**
     * Resolves the SHA-256 hash for a local model file (using catalog entry if available, or file hash).
     */
    private val verifiedArtifacts = java.util.concurrent.ConcurrentHashMap<String, String>()

    private data class ArtifactStat(
        val identity: String,
        val size: Long,
        val lastModified: Long,
        val inodeCtime: String
    ) {
        fun stamp(): String = "$identity|$size|$lastModified|$inodeCtime"
    }

    private fun getArtifactStat(context: Context, artifact: ModelArtifact): ArtifactStat {
        return when (val source = artifact.source) {
            is ArtifactSource.AppManaged -> {
                val file = File(source.path)
                require(file.isFile && file.length() > 0L) { "Model artifact is missing or empty: ${file.name}" }
                val stat = runCatching { android.system.Os.stat(file.absolutePath).let { "${it.st_ino}|${it.st_ctime}" } }.getOrDefault("")
                ArtifactStat(file.canonicalPath, file.length(), file.lastModified(), stat)
            }
            is ArtifactSource.PersistedUri -> {
                val uri = Uri.parse(source.uriString)
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: throw java.io.IOException("Cannot open descriptor for URI: ${source.uriString}")
                pfd.use { descriptor ->
                    val stat = android.system.Os.fstat(descriptor.fileDescriptor)
                    require(stat.st_size > 0L) { "Model artifact URI is empty: ${artifact.displayName}" }
                    ArtifactStat(source.uriString, stat.st_size, stat.st_mtime, "${stat.st_ino}|${stat.st_ctime}")
                }
            }
        }
    }

    private fun hashArtifact(
        context: Context,
        artifact: ModelArtifact,
        isCancelled: (() -> Boolean)?,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)?
    ): String {
        val stampBefore = getArtifactStat(context, artifact)
        val stampKey = "fp_v1:${stampBefore.stamp()}"
        val receiptKey = MessageDigest.getInstance("SHA-256")
            .digest(stampKey.toByteArray())
            .joinToString("") { "%02x".format(it) }

        val receipts = context.getSharedPreferences("verified_gguf_artifacts_v3", Context.MODE_PRIVATE)
        val cached = verifiedArtifacts[stampKey] ?: receipts.getString(receiptKey, null)
        if (cached != null) {
            onProgress?.invoke(stampBefore.size, stampBefore.size)
            return cached
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val stream = when (val source = artifact.source) {
            is ArtifactSource.AppManaged -> FileInputStream(File(source.path))
            is ArtifactSource.PersistedUri -> context.contentResolver.openInputStream(Uri.parse(source.uriString))
                ?: throw java.io.IOException("Cannot open input stream for ${source.uriString}")
        }

        var bytesReadTotal = 0L
        val totalBytes = stampBefore.size
        stream.use { input ->
            val buffer = ByteArray(65536)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                if (isCancelled?.invoke() == true) {
                    throw CancellationException("Artifact hashing cancelled")
                }
                digest.update(buffer, 0, read)
                bytesReadTotal += read
                onProgress?.invoke(bytesReadTotal, totalBytes)
            }
        }

        val stampAfter = getArtifactStat(context, artifact)
        require(stampBefore.stamp() == stampAfter.stamp()) {
            "Model artifact changed while verification was running: ${artifact.displayName}"
        }

        val hashHex = digest.digest().joinToString("") { "%02x".format(it) }
        verifiedArtifacts[stampKey] = hashHex
        receipts.edit().putString(receiptKey, hashHex).apply()
        return hashHex
    }

    fun resolveArtifactFingerprint(
        context: Context,
        bundle: ModelBundle,
        isCancelled: (() -> Boolean)? = null,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
    ): String {
        require(bundle.mainArtifacts.isNotEmpty()) { "Bundle contains no main artifacts" }
        val allArtifacts = bundle.mainArtifacts + listOfNotNull(bundle.projectorArtifact)

        val hashes = mutableMapOf<ModelArtifact, String>()
        for (artifact in allArtifacts) {
            if (isCancelled?.invoke() == true) {
                throw CancellationException("Artifact verification cancelled")
            }
            hashes[artifact] = hashArtifact(context, artifact, isCancelled, onProgress)
        }

        // For single-file main artifact without projector, use standard SHA-256 directly
        if (bundle.mainArtifacts.size == 1 && bundle.projectorArtifact == null) {
            val singleHash = hashes[bundle.mainArtifacts[0]]!!
            val isCustomOrStorage = bundle.isStorageImport || bundle.id.startsWith("custom_") || bundle.id.startsWith("storage_")
            val expected = if (!isCustomOrStorage && bundle.id.isNotBlank()) {
                ModelCatalog.defaultModels.firstOrNull { it.id.equals(bundle.id, true) }?.sha256.orEmpty()
            } else ""
            require(expected.isBlank() || singleHash.equals(expected, true)) {
                "Model checksum does not match the catalog. Download the model again."
            }
            return singleHash
        }

        // For sharded bundles or models with projector, create a composite fingerprint
        val composite = buildString {
            append("bundle_v1:")
            bundle.mainArtifacts.forEachIndexed { idx, shard ->
                append("shard$idx:${shard.displayName}:${hashes[shard]};")
            }
            bundle.projectorArtifact?.let { proj ->
                append("proj:${proj.displayName}:${hashes[proj]};")
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(composite.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    fun resolveModelSha256(
        modelPath: String,
        modelId: String = "",
        context: Context? = null,
        isCancelled: (() -> Boolean)? = null,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
    ): String {
        val file = File(modelPath)
        require(file.isFile && file.length() > 0L) { "Model artifact is missing or empty" }
        fun identity(): String {
            val stat = runCatching { android.system.Os.stat(file.absolutePath).let { "${it.st_ino}|${it.st_ctime}" } }.getOrDefault("")
            return "${file.canonicalPath}|${file.length()}|${file.lastModified()}|$stat"
        }
        val stamp = identity()
        val receiptKey = java.security.MessageDigest.getInstance("SHA-256").digest(stamp.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val receipts = context?.getSharedPreferences("verified_gguf_artifacts_v3", Context.MODE_PRIVATE)
        val actual = verifiedArtifacts[stamp] ?: receipts?.getString(receiptKey, null)
            ?: AppTrace.section("hashing") {
                ModelDownloader.computeSha256(file, isCancelled = isCancelled, onProgress = onProgress).also {
                    require(file.length() > 0 && stamp == identity()) {
                        "Model artifact changed while verification was running"
                    }
                    verifiedArtifacts[stamp] = it
                    receipts?.edit()?.putString(receiptKey, it)?.apply()
                }
            }
        val isCustomOrStorage = modelId.startsWith("custom_") || modelId.startsWith("storage_")
        val expected = if (!isCustomOrStorage && modelId.isNotBlank()) {
            ModelCatalog.defaultModels.firstOrNull { it.id.equals(modelId, true) }?.sha256.orEmpty()
        } else ""
        require(expected.isBlank() || actual.equals(expected, true)) {
            "Model checksum does not match the catalog. Download the model again."
        }
        return actual
    }

    /**
     * Validates GPU computation against CPU reference.
     * Sequential execution guarantees that memory usage does not double.
     *
     * If cancelled: leaves validation pending and rethrows CancellationException.
     */
    suspend fun validate(
        context: Context?,
        modelPath: String,
        modelSha256: String,
        threads: Int = 4,
        prefillThreads: Int = threads,
        contextWindow: Int = 2048,
        nBatch: Int = 256,
        nUbatch: Int = 128,
        expectedFileSizeBytes: Long = 0L,
        customNativeBridge: LlamaNativeBridge? = null,
        onValidatedEngine: ((LlamaCppEngine) -> Unit)? = null,
        operationControlId: Long = 0L,
        onProgress: ((stage: ModelLoadStage, completed: Long?, total: Long?) -> Unit)? = null,
        companionSummary: String = ""
    ): ValidationResult = withContext(Dispatchers.IO) {
        val requestedCacheKey = buildCacheKey(modelSha256, threads, contextWindow, nBatch, nUbatch, prefillThreads, companionSummary)

        // Step 1: Check memory cache and candidate receipt
        testMemoryCache[requestedCacheKey]?.let { cached ->
            if (cached is ValidationResult.Pass && onValidatedEngine == null) {
                safeLogI("Using memory-cached validation pass for key: $requestedCacheKey")
                return@withContext cached
            }
            if (cached is ValidationResult.Fail && cached.permanent) {
                return@withContext cached
            }
        }

        val candidateReceipt = getReceipt(context, requestedCacheKey)
        if (candidateReceipt != null) {
            if (candidateReceipt.status == "FAIL" && candidateReceipt.permanent) {
                safeLogI("Permanent failure receipt found for key: $requestedCacheKey")
                return@withContext ValidationResult.Fail(
                    candidateReceipt.failReason ?: "GPU disagreement",
                    FailureKind.GpuDisagreement,
                    permanent = true
                )
            }

            if (candidateReceipt.status == "PASS") {
                if (onValidatedEngine == null) {
                    safeLogI("Using candidate receipt PASS (no retained engine requested) for key: $requestedCacheKey")
                    return@withContext ValidationResult.Pass
                }

                // Load candidate GPU engine once, read actual configuration, trust only if complete configuration matches
                val candidateEngine = if (customNativeBridge != null) {
                    LlamaCppEngine(context, customNativeBridge)
                } else {
                    LlamaCppEngine(context)
                }

                var candidateMatched = false
                try {
                    onProgress?.invoke(ModelLoadStage.ContextPreparation("Vulkan"), null, null)
                    candidateEngine.initialize(
                        modelPath = modelPath,
                        threads = threads,
                        prefillThreads = prefillThreads,
                        contextWindow = contextWindow,
                        nBatch = nBatch,
                        nUbatch = nUbatch,
                        backend = LlamaBackend.VULKAN,
                        expectedFileSizeBytes = expectedFileSizeBytes,
                        operationControlId = operationControlId
                    )

                    if (candidateEngine.isInitialized && candidateEngine.activeBackend == LlamaBackend.VULKAN) {
                        val meta = candidateEngine.getMetadata()
                        val actualCtx = meta?.contextSize?.takeIf { it > 0 } ?: contextWindow
                        val actualB = meta?.batchSize?.takeIf { it > 0 } ?: nBatch
                        val actualUb = meta?.microBatchSize?.takeIf { it > 0 } ?: nUbatch
                        val actualConfig = candidateEngine.actualConfiguration

                        if (actualCtx == candidateReceipt.actualContextWindow &&
                            actualB == candidateReceipt.actualBatch &&
                            actualUb == candidateReceipt.actualUbatch &&
                            (actualConfig == null || (actualConfig.threads == candidateReceipt.actualThreads && actualConfig.prefillThreads == candidateReceipt.actualPrefillThreads))
                        ) {
                            candidateMatched = true
                            candidateEngine.resetAfterValidation()
                            onValidatedEngine(candidateEngine)
                            safeLogI("Candidate GPU engine matched validation receipt; reused directly")
                            return@withContext ValidationResult.Pass
                        } else {
                            safeLogW("Candidate GPU engine allocated settings ($actualCtx, $actualB, $actualUb) differed from receipt ($candidateReceipt); sequential validation required")
                        }
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    safeLogW("Candidate GPU engine load check failed; falling back to sequential validation: ${t.message}")
                } finally {
                    if (!candidateMatched) {
                        candidateEngine.close()
                    }
                }
            }
        }

        safeLogI("Starting sequential CPU/Vulkan validation for model: $modelPath (key: $requestedCacheKey)")

        var gpuPhase = false
        val totalProbeUnits = (VALIDATION_PROMPTS.size * PROBE_POSITIONS).toLong()

        AppTrace.beginSection("validation")
        try {
            // Step 2: Run CPU reference pass
            ensureActive()
            val cpuEngine = if (customNativeBridge != null) {
                LlamaCppEngine(context, customNativeBridge)
            } else {
                LlamaCppEngine(context)
            }

            try {
                onProgress?.invoke(ModelLoadStage.ContextPreparation("CPU"), null, null)
                cpuEngine.initialize(
                    modelPath = modelPath,
                    threads = threads,
                    prefillThreads = prefillThreads,
                    contextWindow = contextWindow,
                    nBatch = nBatch,
                    nUbatch = nUbatch,
                    backend = LlamaBackend.CPU,
                    expectedFileSizeBytes = expectedFileSizeBytes,
                    operationControlId = operationControlId
                )
                if (!cpuEngine.isInitialized) {
                    val fail = ValidationResult.Fail("CPU reference initialization failed", FailureKind.CpuReference, permanent = false)
                    return@withContext fail
                }

                val cpuMeta = cpuEngine.getMetadata()
                if (cpuMeta == null || !cpuMeta.isCpuIsolated) {
                    safeLogE("CPU reference engine rejected: CPU isolation not verified (meta=$cpuMeta)")
                    val fail = ValidationResult.Fail("CPU reference probe rejected: CPU context is not verified CPU-isolated", FailureKind.CpuReference, permanent = false)
                    return@withContext fail
                }

                for ((idx, prompt) in VALIDATION_PROMPTS.withIndex()) {
                    ensureActive()
                    onProgress?.invoke(
                        ModelLoadStage.CpuReference(promptIndex = idx, position = 0),
                        (idx * PROBE_POSITIONS).toLong(),
                        totalProbeUnits
                    )
                    val messages = listOf("user" to prompt)
                    val resJson = cpuEngine.probeReference(idx, messages, PROBE_POSITIONS, operationControlId)
                    val outcome = parseProbeResponse(resJson)
                    if (outcome.cancelled) {
                        throw CancellationException("CPU reference probe cancelled by user")
                    }
                    if (!outcome.success) {
                        val fail = ValidationResult.Fail(
                            "CPU reference probe failed on prompt $idx: ${outcome.error.ifBlank { "Unknown CPU probe error" }}",
                            FailureKind.CpuReference,
                            permanent = false
                        )
                        return@withContext fail
                    }
                }
            } finally {
                cpuEngine.close()
            }

            gpuPhase = true
            // Step 3: Run Vulkan comparison pass
            ensureActive()
            val gpuEngine = if (customNativeBridge != null) {
                LlamaCppEngine(context, customNativeBridge)
            } else {
                LlamaCppEngine(context)
            }

            var transferred = false
            try {
                onProgress?.invoke(ModelLoadStage.ContextPreparation("Vulkan"), null, null)
                gpuEngine.initialize(
                    modelPath = modelPath,
                    threads = threads,
                    prefillThreads = prefillThreads,
                    contextWindow = contextWindow,
                    nBatch = nBatch,
                    nUbatch = nUbatch,
                    backend = LlamaBackend.VULKAN,
                    expectedFileSizeBytes = expectedFileSizeBytes,
                    operationControlId = operationControlId
                )
                if (!gpuEngine.isInitialized || gpuEngine.activeBackend != LlamaBackend.VULKAN) {
                    val fail = ValidationResult.Fail("Vulkan GPU initialization failed or fell back to CPU", FailureKind.Initialization, permanent = false)
                    return@withContext fail
                }

                val actualMeta = gpuEngine.getMetadata()
                val actualCtx = actualMeta?.contextSize?.takeIf { it > 0 } ?: contextWindow
                val actualB = actualMeta?.batchSize?.takeIf { it > 0 } ?: nBatch
                val actualUb = actualMeta?.microBatchSize?.takeIf { it > 0 } ?: nUbatch
                val actualConfig = gpuEngine.actualConfiguration
                val actualTh = actualConfig?.threads ?: threads
                val actualPth = actualConfig?.prefillThreads ?: prefillThreads

                for ((idx, prompt) in VALIDATION_PROMPTS.withIndex()) {
                    ensureActive()
                    onProgress?.invoke(
                        ModelLoadStage.GpuProbe(promptIndex = idx, position = 0),
                        (idx * PROBE_POSITIONS).toLong(),
                        totalProbeUnits
                    )
                    val messages = listOf("user" to prompt)
                    val resJson = gpuEngine.probeCompare(idx, messages, PROBE_POSITIONS, operationControlId)
                    val outcome = parseProbeResponse(resJson)
                    if (outcome.cancelled) {
                        throw CancellationException("Vulkan probe comparison cancelled by user")
                    }
                    if (!outcome.success) {
                        val numerical = outcome.error.contains("variation", true) ||
                            outcome.error.contains("TV", true) ||
                            outcome.error.contains("non-finite", true) ||
                            outcome.error.contains("numerical", true)
                        val fail = ValidationResult.Fail(
                            "GPU probe failed on prompt $idx (\"$prompt\"): ${outcome.error.ifBlank { "Unknown GPU probe failure" }}",
                            if (numerical) FailureKind.GpuDisagreement else FailureKind.Transient,
                            permanent = numerical
                        )
                        if (numerical) {
                            val failReceipt = ValidationReceipt(
                                protocolVersion = PROTOCOL_VERSION,
                                modelSha256 = modelSha256,
                                nativeBuildId = NATIVE_BUILD_ID,
                                deviceIdentity = getDeviceIdentity(),
                                requestedThreads = threads,
                                requestedPrefillThreads = prefillThreads,
                                requestedContextWindow = contextWindow,
                                requestedBatch = nBatch,
                                requestedUbatch = nUbatch,
                                actualThreads = actualTh,
                                actualPrefillThreads = actualPth,
                                actualContextWindow = actualCtx,
                                actualBatch = actualB,
                                actualUbatch = actualUb,
                                status = "FAIL",
                                failReason = fail.reason,
                                permanent = true
                            )
                            saveReceipt(context, requestedCacheKey, failReceipt)
                        }
                        return@withContext fail
                    }
                }

                ensureActive()
                gpuEngine.resetAfterValidation()

                val passReceipt = ValidationReceipt(
                    protocolVersion = PROTOCOL_VERSION,
                    modelSha256 = modelSha256,
                    nativeBuildId = NATIVE_BUILD_ID,
                    deviceIdentity = getDeviceIdentity(),
                    requestedThreads = threads,
                    requestedPrefillThreads = prefillThreads,
                    requestedContextWindow = contextWindow,
                    requestedBatch = nBatch,
                    requestedUbatch = nUbatch,
                    actualThreads = actualTh,
                    actualPrefillThreads = actualPth,
                    actualContextWindow = actualCtx,
                    actualBatch = actualB,
                    actualUbatch = actualUb,
                    status = "PASS",
                    permanent = true
                )
                saveReceipt(context, requestedCacheKey, passReceipt)

                val actualCacheKey = buildCacheKey(modelSha256, actualTh, actualCtx, actualB, actualUb, actualPth, companionSummary = companionSummary)
                if (actualCacheKey != requestedCacheKey) {
                    saveReceipt(context, actualCacheKey, passReceipt)
                }

                if (onValidatedEngine != null) {
                    onValidatedEngine(gpuEngine)
                    transferred = true
                }
                safeLogI("GPU validation PASSED for model $modelPath (actual ctx=$actualCtx, b=$actualB, ub=$actualUb)")
                ValidationResult.Pass
            } finally {
                if (!transferred) gpuEngine.close()
            }

        } catch (e: CancellationException) {
            safeLogI("GPU validation was cancelled; leaving validation pending")
            throw e
        } catch (t: Throwable) {
            val fail = ValidationResult.Fail("Unexpected validation error: ${t.message ?: t.toString()}", if (gpuPhase) FailureKind.Transient else FailureKind.CpuReference, permanent = false)
            safeLogE("GPU validation failed unexpectedly", t)
            return@withContext fail
        } finally {
            (customNativeBridge ?: DefaultLlamaNativeBridge).clearProbeReferences()
            AppTrace.endSection()
        }
    }

    suspend fun validate(
        context: Context,
        bundle: ModelBundle,
        threads: Int = 4,
        prefillThreads: Int = threads,
        contextWindow: Int = 2048,
        nBatch: Int = 256,
        nUbatch: Int = 128,
        expectedFileSizeBytes: Long = 0L,
        customNativeBridge: LlamaNativeBridge? = null,
        onValidatedEngine: ((LlamaCppEngine) -> Unit)? = null,
        operationControlId: Long = 0L,
        onProgress: ((stage: ModelLoadStage, completed: Long?, total: Long?) -> Unit)? = null,
        companionSummary: String = ""
    ): ValidationResult = withContext(Dispatchers.IO) {
        val modelSha256 = resolveArtifactFingerprint(
            context = context,
            bundle = bundle,
            onProgress = { bytesRead, totalBytes ->
                onProgress?.invoke(ModelLoadStage.VerifyingFile, bytesRead, totalBytes)
            }
        )
        val effectiveCompanionSummary = if (companionSummary.isNotBlank()) {
            companionSummary
        } else {
            "${bundle.mainArtifacts.size}_parts_${bundle.projectorArtifact?.displayName.orEmpty()}"
        }

        val requestedCacheKey = buildCacheKey(
            modelSha256, threads, contextWindow, nBatch, nUbatch, prefillThreads, effectiveCompanionSummary
        )

        // Step 1: Check memory cache and candidate receipt
        testMemoryCache[requestedCacheKey]?.let { cached ->
            if (cached is ValidationResult.Pass && onValidatedEngine == null) {
                safeLogI("Using memory-cached validation pass for key: $requestedCacheKey")
                return@withContext cached
            }
            if (cached is ValidationResult.Fail && cached.permanent) {
                return@withContext cached
            }
        }

        val candidateReceipt = getReceipt(context, requestedCacheKey)
        if (candidateReceipt != null) {
            if (candidateReceipt.status == "FAIL" && candidateReceipt.permanent) {
                safeLogI("Permanent failure receipt found for key: $requestedCacheKey")
                return@withContext ValidationResult.Fail(
                    candidateReceipt.failReason ?: "GPU disagreement",
                    FailureKind.GpuDisagreement,
                    permanent = true
                )
            }

            if (candidateReceipt.status == "PASS") {
                if (onValidatedEngine == null) {
                    safeLogI("Using candidate receipt PASS (no retained engine requested) for key: $requestedCacheKey")
                    return@withContext ValidationResult.Pass
                }

                val candidateEngine = if (customNativeBridge != null) {
                    LlamaCppEngine(context, customNativeBridge)
                } else {
                    LlamaCppEngine(context)
                }

                var candidateMatched = false
                try {
                    onProgress?.invoke(ModelLoadStage.ContextPreparation("Vulkan"), null, null)
                    candidateEngine.initializeBundle(
                        context = context,
                        bundle = bundle,
                        threads = threads,
                        prefillThreads = prefillThreads,
                        contextWindow = contextWindow,
                        nBatch = nBatch,
                        nUbatch = nUbatch,
                        backend = LlamaBackend.VULKAN,
                        expectedFileSizeBytes = expectedFileSizeBytes,
                        operationControlId = operationControlId
                    )

                    if (candidateEngine.isInitialized && candidateEngine.activeBackend == LlamaBackend.VULKAN) {
                        val meta = candidateEngine.getMetadata()
                        val actualCtx = meta?.contextSize?.takeIf { it > 0 } ?: contextWindow
                        val actualB = meta?.batchSize?.takeIf { it > 0 } ?: nBatch
                        val actualUb = meta?.microBatchSize?.takeIf { it > 0 } ?: nUbatch
                        val actualConfig = candidateEngine.actualConfiguration

                        if (actualCtx == candidateReceipt.actualContextWindow &&
                            actualB == candidateReceipt.actualBatch &&
                            actualUb == candidateReceipt.actualUbatch &&
                            (actualConfig == null || (actualConfig.threads == candidateReceipt.actualThreads && actualConfig.prefillThreads == candidateReceipt.actualPrefillThreads))
                        ) {
                            candidateMatched = true
                            candidateEngine.resetAfterValidation()
                            onValidatedEngine(candidateEngine)
                            safeLogI("Candidate GPU engine matched validation receipt; reused directly")
                            return@withContext ValidationResult.Pass
                        }
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    safeLogW("Candidate GPU engine load check failed; falling back to sequential validation: ${t.message}")
                } finally {
                    if (!candidateMatched) {
                        candidateEngine.close()
                    }
                }
            }
        }

        safeLogI("Starting sequential CPU/Vulkan validation for bundle: ${bundle.id} (key: $requestedCacheKey)")

        var gpuPhase = false
        val totalProbeUnits = (VALIDATION_PROMPTS.size * PROBE_POSITIONS).toLong()

        AppTrace.beginSection("validation")
        try {
            ensureActive()
            val cpuEngine = if (customNativeBridge != null) {
                LlamaCppEngine(context, customNativeBridge)
            } else {
                LlamaCppEngine(context)
            }

            try {
                onProgress?.invoke(ModelLoadStage.ContextPreparation("CPU"), null, null)
                cpuEngine.initializeBundle(
                    context = context,
                    bundle = bundle,
                    threads = threads,
                    prefillThreads = prefillThreads,
                    contextWindow = contextWindow,
                    nBatch = nBatch,
                    nUbatch = nUbatch,
                    backend = LlamaBackend.CPU,
                    expectedFileSizeBytes = expectedFileSizeBytes,
                    operationControlId = operationControlId
                )
                if (!cpuEngine.isInitialized) {
                    val fail = ValidationResult.Fail("CPU reference initialization failed", FailureKind.CpuReference, permanent = false)
                    return@withContext fail
                }

                val cpuMeta = cpuEngine.getMetadata()
                if (cpuMeta == null || !cpuMeta.isCpuIsolated) {
                    safeLogE("CPU reference engine rejected: CPU isolation not verified (meta=$cpuMeta)")
                    val fail = ValidationResult.Fail("CPU reference probe rejected: CPU context is not verified CPU-isolated", FailureKind.CpuReference, permanent = false)
                    return@withContext fail
                }

                for ((idx, prompt) in VALIDATION_PROMPTS.withIndex()) {
                    ensureActive()
                    onProgress?.invoke(
                        ModelLoadStage.CpuReference(promptIndex = idx, position = 0),
                        (idx * PROBE_POSITIONS).toLong(),
                        totalProbeUnits
                    )
                    val messages = listOf("user" to prompt)
                    val resJson = cpuEngine.probeReference(idx, messages, PROBE_POSITIONS, operationControlId)
                    val outcome = parseProbeResponse(resJson)
                    if (outcome.cancelled) {
                        throw CancellationException("CPU reference probe cancelled by user")
                    }
                    if (!outcome.success) {
                        val fail = ValidationResult.Fail(
                            "CPU reference probe failed on prompt $idx: ${outcome.error.ifBlank { "Unknown CPU probe error" }}",
                            FailureKind.CpuReference,
                            permanent = false
                        )
                        return@withContext fail
                    }
                }
            } finally {
                cpuEngine.close()
            }

            gpuPhase = true
            ensureActive()
            val gpuEngine = if (customNativeBridge != null) {
                LlamaCppEngine(context, customNativeBridge)
            } else {
                LlamaCppEngine(context)
            }

            var transferred = false
            try {
                onProgress?.invoke(ModelLoadStage.ContextPreparation("Vulkan"), null, null)
                gpuEngine.initializeBundle(
                    context = context,
                    bundle = bundle,
                    threads = threads,
                    prefillThreads = prefillThreads,
                    contextWindow = contextWindow,
                    nBatch = nBatch,
                    nUbatch = nUbatch,
                    backend = LlamaBackend.VULKAN,
                    expectedFileSizeBytes = expectedFileSizeBytes,
                    operationControlId = operationControlId
                )
                if (!gpuEngine.isInitialized || gpuEngine.activeBackend != LlamaBackend.VULKAN) {
                    val fail = ValidationResult.Fail("Vulkan GPU initialization failed or fell back to CPU", FailureKind.Initialization, permanent = false)
                    return@withContext fail
                }

                val actualMeta = gpuEngine.getMetadata()
                val actualCtx = actualMeta?.contextSize?.takeIf { it > 0 } ?: contextWindow
                val actualB = actualMeta?.batchSize?.takeIf { it > 0 } ?: nBatch
                val actualUb = actualMeta?.microBatchSize?.takeIf { it > 0 } ?: nUbatch
                val actualConfig = gpuEngine.actualConfiguration
                val actualTh = actualConfig?.threads ?: threads
                val actualPth = actualConfig?.prefillThreads ?: prefillThreads

                for ((idx, prompt) in VALIDATION_PROMPTS.withIndex()) {
                    ensureActive()
                    onProgress?.invoke(
                        ModelLoadStage.GpuProbe(promptIndex = idx, position = 0),
                        (idx * PROBE_POSITIONS).toLong(),
                        totalProbeUnits
                    )
                    val messages = listOf("user" to prompt)
                    val resJson = gpuEngine.probeCompare(idx, messages, PROBE_POSITIONS, operationControlId)
                    val outcome = parseProbeResponse(resJson)
                    if (outcome.cancelled) {
                        throw CancellationException("Vulkan probe comparison cancelled by user")
                    }
                    if (!outcome.success) {
                        val numerical = outcome.error.contains("variation", true) ||
                            outcome.error.contains("TV", true) ||
                            outcome.error.contains("non-finite", true) ||
                            outcome.error.contains("numerical", true)
                        val fail = ValidationResult.Fail(
                            "GPU probe failed on prompt $idx (\"$prompt\"): ${outcome.error.ifBlank { "Unknown GPU probe failure" }}",
                            if (numerical) FailureKind.GpuDisagreement else FailureKind.Transient,
                            permanent = numerical
                        )
                        if (numerical) {
                            val failReceipt = ValidationReceipt(
                                protocolVersion = PROTOCOL_VERSION,
                                modelSha256 = modelSha256,
                                nativeBuildId = NATIVE_BUILD_ID,
                                deviceIdentity = getDeviceIdentity(),
                                requestedThreads = threads,
                                requestedPrefillThreads = prefillThreads,
                                requestedContextWindow = contextWindow,
                                requestedBatch = nBatch,
                                requestedUbatch = nUbatch,
                                actualThreads = actualTh,
                                actualPrefillThreads = actualPth,
                                actualContextWindow = actualCtx,
                                actualBatch = actualB,
                                actualUbatch = actualUb,
                                status = "FAIL",
                                failReason = fail.reason,
                                permanent = true
                            )
                            saveReceipt(context, requestedCacheKey, failReceipt)
                        }
                        return@withContext fail
                    }
                }

                ensureActive()
                gpuEngine.resetAfterValidation()

                val passReceipt = ValidationReceipt(
                    protocolVersion = PROTOCOL_VERSION,
                    modelSha256 = modelSha256,
                    nativeBuildId = NATIVE_BUILD_ID,
                    deviceIdentity = getDeviceIdentity(),
                    requestedThreads = threads,
                    requestedPrefillThreads = prefillThreads,
                    requestedContextWindow = contextWindow,
                    requestedBatch = nBatch,
                    requestedUbatch = nUbatch,
                    actualThreads = actualTh,
                    actualPrefillThreads = actualPth,
                    actualContextWindow = actualCtx,
                    actualBatch = actualB,
                    actualUbatch = actualUb,
                    status = "PASS",
                    permanent = true
                )
                saveReceipt(context, requestedCacheKey, passReceipt)

                val actualCacheKey = buildCacheKey(modelSha256, actualTh, actualCtx, actualB, actualUb, actualPth, companionSummary = effectiveCompanionSummary)
                if (actualCacheKey != requestedCacheKey) {
                    saveReceipt(context, actualCacheKey, passReceipt)
                }

                if (onValidatedEngine != null) {
                    onValidatedEngine(gpuEngine)
                    transferred = true
                }
                safeLogI("GPU validation PASSED for bundle ${bundle.id} (actual ctx=$actualCtx, b=$actualB, ub=$actualUb)")
                ValidationResult.Pass
            } finally {
                if (!transferred) gpuEngine.close()
            }
        } catch (e: CancellationException) {
            safeLogI("GPU validation was cancelled; leaving validation pending")
            throw e
        } catch (t: Throwable) {
            val fail = ValidationResult.Fail("Unexpected validation error: ${t.message ?: t.toString()}", if (gpuPhase) FailureKind.Transient else FailureKind.CpuReference, permanent = false)
            safeLogE("GPU validation failed unexpectedly", t)
            return@withContext fail
        } finally {
            (customNativeBridge ?: DefaultLlamaNativeBridge).clearProbeReferences()
            AppTrace.endSection()
        }
    }
}
