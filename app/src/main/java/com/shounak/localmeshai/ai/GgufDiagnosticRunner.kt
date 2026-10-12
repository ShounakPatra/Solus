package com.shounak.localmeshai.ai

import android.content.Context
import android.util.Log
import com.shounak.localmeshai.models.ModelCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostic policy for selecting the backend during GGUF validation / diagnostics.
 */
enum class DiagnosticPolicy {
    AUTO,
    FORCED_CPU,
    FORCED_VULKAN
}

/**
 * Diagnostic result for a single prompt evaluation.
 */
data class GgufPromptDiagnosticResult(
    val prompt: String,
    val success: Boolean,
    val promptTokensCount: Int = 0,
    val promptDecodeDurationMs: Long = 0L,
    val timeToFirstTokenMs: Long = 0L,
    val generatedTokenCount: Int = 0,
    val totalGenerationDurationMs: Long = 0L,
    val tokensPerSecond: Double = 0.0,
    val generatedText: String = "",
    val errorMessage: String? = null
)

/**
 * Comprehensive diagnostic report for a GGUF model artifact and runtime execution.
 */
data class GgufDiagnosticReport(
    val timestamp: String,
    val modelPath: String,
    val fileName: String,
    val modelSha256: String = "",
    val backendUsed: String = "CPU",
    val backendPolicy: String = "AUTO",
    val fileSizeBytes: Long,
    val expectedSizeBytes: Long,
    val artifactSizeValidationPassed: Boolean,
    val artifactSizeWarning: String?,
    val inspectionValid: Boolean,
    val architecture: String,
    val primaryQuantization: String,
    val tensorCount: Long,
    val trainContextLength: Int,
    val isSupported: Boolean,
    val inspectionError: String?,
    val initSuccess: Boolean,
    val initDurationMs: Long,
    val threadsUsed: Int,
    val prefillThreadsUsed: Int = threadsUsed,
    val contextWindowUsed: Int,
    val nBatchUsed: Int,
    val nUbatchUsed: Int,
    val initError: String?,
    val promptResults: List<GgufPromptDiagnosticResult>,
    val closeSuccess: Boolean,
    val numericalAnomalyReport: String?,
    val numericalValidationPassed: Boolean = true,
    val responseQualityVerified: Boolean = false,
    val humanReviewedCoherence: Boolean = false,
    val overallSuccess: Boolean,
    val reportFilePath: String? = null
) {
    fun toMarkdown(): String {
        val sb = StringBuilder()
        sb.appendLine("# GGUF Diagnostic Report")
        sb.appendLine("**Timestamp:** $timestamp")
        sb.appendLine("**Model File:** `$fileName`")
        if (modelSha256.isNotBlank()) {
            sb.appendLine("**Model SHA-256:** `$modelSha256`")
        }
        sb.appendLine("**Backend:** `$backendUsed`")
        sb.appendLine("**Backend Policy:** `$backendPolicy`")
        sb.appendLine("**Response Quality Verified:** ${if (responseQualityVerified) "✅ Yes" else "⚠️ No"}")
        sb.appendLine("**File Size:** ${fileSizeBytes / (1024 * 1024)} MB ($fileSizeBytes bytes)")
        if (expectedSizeBytes > 0L) {
            sb.appendLine("**Expected Size:** ${expectedSizeBytes / (1024 * 1024)} MB ($expectedSizeBytes bytes)")
            sb.appendLine("**Size Check:** ${if (artifactSizeValidationPassed) "✅ PASSED (±5%)" else "⚠️ WARNING: $artifactSizeWarning"}")
        }
        sb.appendLine()
        sb.appendLine("## 1. GGUF Inspection")
        sb.appendLine("- **Valid GGUF:** ${if (inspectionValid) "✅ Yes" else "❌ No ($inspectionError)"}")
        sb.appendLine("- **Architecture:** `$architecture`")
        sb.appendLine("- **Primary Quant:** `$primaryQuantization`")
        sb.appendLine("- **Tensor Count:** $tensorCount")
        sb.appendLine("- **Train Context:** $trainContextLength")
        sb.appendLine("- **Supported in Runtime:** ${if (isSupported) "✅ Yes" else "❌ No"}")
        sb.appendLine()
        sb.appendLine("## 2. Runtime Context Initialization")
        sb.appendLine("- **Init Status:** ${if (initSuccess) "✅ Success (${initDurationMs}ms)" else "❌ Failed ($initError)"}")
        sb.appendLine("- **Decode Threads:** $threadsUsed")
        sb.appendLine("- **Prefill Threads:** $prefillThreadsUsed")
        sb.appendLine("- **Context Window:** $contextWindowUsed")
        sb.appendLine("- **Batch / UBatch:** $nBatchUsed / $nUbatchUsed")
        sb.appendLine()
        sb.appendLine("## 3. Standardized Test Matrix")
        promptResults.forEachIndexed { idx, res ->
            sb.appendLine("### Test ${idx + 1}: \"${res.prompt}\"")
            sb.appendLine("- **Status:** ${if (res.success) "✅ Completed" else "❌ Failed (${res.errorMessage})"}")
            if (res.success) {
                sb.appendLine("- **Prefill Duration:** ${res.promptDecodeDurationMs}ms")
                sb.appendLine("- **TTFT (Native First Token):** ${res.timeToFirstTokenMs}ms")
                sb.appendLine("- **Tokens Generated (Native):** ${res.generatedTokenCount} tokens (${String.format(Locale.US, "%.1f", res.tokensPerSecond)} tok/s)")
                sb.appendLine("- **Response Sample:** `${res.generatedText.take(120).replace("\n", " ")}`")
            }
            sb.appendLine()
        }
        sb.appendLine("## 4. Numerical Anomaly & Quality Forensics")
        sb.appendLine("- **Numerical Validation:** ${if (numericalValidationPassed) "✅ PASSED" else "❌ FAILED"}")
        sb.appendLine("- **Automated Response Quality:** ${if (responseQualityVerified) "✅ PASSED" else "⚠️ UNVERIFIED"}")
        sb.appendLine("- **Human-Reviewed Coherence:** ${if (humanReviewedCoherence) "✅ VERIFIED" else "⚠️ UNVERIFIED"}")
        if (!numericalAnomalyReport.isNullOrBlank()) {
            sb.appendLine("```")
            sb.appendLine(numericalAnomalyReport)
            sb.appendLine("```")
        }
        sb.appendLine()
        sb.appendLine("## 5. Summary")
        sb.appendLine("**Overall Verdict:** ${if (overallSuccess) "✅ HEALTHY — Model runs correctly" else "❌ FAILED — Inspect diagnostics above"}")
        return sb.toString()
    }
}

/**
 * Executes controlled in-app and automated diagnostics on GGUF models.
 */
internal object GgufDiagnosticRunner {
    private const val TAG = "GgufDiagnosticRunner"

    /** Standard prompt sequence required by the testing specification. */
    val STANDARD_PROMPTS = listOf(
        "Hi",
        "Hello.",
        "Explain 2 + 2 in one sentence."
    )

    /**
     * Runs full diagnostics on a local GGUF file.
     */
    internal suspend fun runDiagnostics(
        context: Context?,
        modelPath: String,
        threads: Int = 4,
        prefillThreads: Int = threads,
        contextWindow: Int = 2048,
        nBatch: Int = 256,
        nUbatch: Int = 128,
        backend: LlamaBackend? = null,
        policy: DiagnosticPolicy = if (backend == LlamaBackend.VULKAN) DiagnosticPolicy.FORCED_VULKAN
            else if (backend == LlamaBackend.CPU) DiagnosticPolicy.FORCED_CPU
            else DiagnosticPolicy.AUTO,
        prompts: List<String> = STANDARD_PROMPTS,
        customNativeBridge: LlamaNativeBridge? = null
    ): GgufDiagnosticReport = withContext(Dispatchers.IO) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val file = File(modelPath)
        val fileSizeBytes = if (file.exists()) file.length() else 0L
        val fileName = file.name

        // Find expected size in catalog if known
        val catalogEntry = ModelCatalog.defaultModels.find {
            it.fileName.equals(fileName, ignoreCase = true) || it.id.equals(file.nameWithoutExtension, ignoreCase = true)
        }
        val expectedBytes = catalogEntry?.expectedFileSizeBytes ?: 0L
        val modelSha256 = GgufGpuValidator.resolveModelSha256(modelPath, catalogEntry?.id.orEmpty())

        // Phase 1: Artifact validation
        val sizeWarning = LlamaCppEngine.validateGgufArtifactSize(modelPath, expectedBytes)
        val sizePassed = sizeWarning == null

        // Phase 2: Inspection
        val inspection = if (customNativeBridge != null) {
            val jsonStr = customNativeBridge.inspectModel(file.absolutePath)
            if (jsonStr != null) {
                LlamaCppEngine.parseInspectionJson(jsonStr)
            } else {
                LlamaModelInspectionResult(valid = false, error = "Inspection returned null")
            }
        } else {
            LlamaCppEngine.inspectModel(modelPath)
        }

        // Phase 3: Resolve backend according to policy
        val resolvedBackend = when (policy) {
            DiagnosticPolicy.FORCED_CPU -> LlamaBackend.CPU
            DiagnosticPolicy.FORCED_VULKAN -> LlamaBackend.VULKAN
            DiagnosticPolicy.AUTO -> {
                val decision = GgufBackendPolicy.selectBackend(
                    context = context,
                    modelSha256 = modelSha256,
                    threads = threads,
                    contextWindow = contextWindow,
                    nBatch = nBatch,
                    nUbatch = nUbatch,
                    prefillThreads = prefillThreads
                )
                decision.selectedBackend
            }
        }

        // Phase 4: Runtime Context Init
        val engine = if (customNativeBridge != null) {
            LlamaCppEngine(context, customNativeBridge)
        } else {
            LlamaCppEngine(context)
        }

        var initSuccess = false
        var initDurationMs = 0L
        var initError: String? = null

        val initStartNano = System.nanoTime()
        try {
            engine.initialize(
                modelPath = modelPath,
                threads = threads,
                prefillThreads = prefillThreads,
                contextWindow = contextWindow,
                nBatch = nBatch,
                nUbatch = nUbatch,
                backend = resolvedBackend,
                expectedFileSizeBytes = expectedBytes
            )
            initSuccess = engine.isInitialized
            initDurationMs = (System.nanoTime() - initStartNano) / 1_000_000L
        } catch (t: Throwable) {
            initDurationMs = (System.nanoTime() - initStartNano) / 1_000_000L
            initError = t.message ?: t.toString()
            Log.e(TAG, "GGUF diagnostic init failed for $fileName", t)
        }

        // Phase 5: Standard prompt matrix execution
        val promptResults = mutableListOf<GgufPromptDiagnosticResult>()
        if (initSuccess) {
            for (prompt in prompts) {
                val promptStartNano = System.nanoTime()
                var nativeFirstTokenMs = 0L
                var firstCallbackMs = 0L
                val tokens = mutableListOf<String>()
                var generationMetrics: LlamaGenerationMetrics? = null

                try {
                    val messages = listOf("user" to prompt)
                    val result = engine.generateStream(
                        prompt = prompt,
                        messages = messages,
                        samplingParams = LlamaSamplingParams(maxTokens = 32, temperature = 0.6f),
                        onMetrics = { m -> generationMetrics = m },
                        onFirstToken = {
                            if (nativeFirstTokenMs == 0L) {
                                nativeFirstTokenMs = (System.nanoTime() - promptStartNano) / 1_000_000L
                            }
                        }
                    ) { token ->
                        if (tokens.isEmpty() && firstCallbackMs == 0L) {
                            firstCallbackMs = (System.nanoTime() - promptStartNano) / 1_000_000L
                        }
                        tokens.add(token)
                    }

                    val totalDurationMs = (System.nanoTime() - promptStartNano) / 1_000_000L
                    val tokCount = generationMetrics?.tokenCount ?: tokens.size
                    val decodeMs = generationMetrics?.decodeDurationMs ?: run {
                        val start = if (nativeFirstTokenMs > 0L) nativeFirstTokenMs else firstCallbackMs
                        if (start > 0L && totalDurationMs >= start) totalDurationMs - start else totalDurationMs
                    }
                    val promptDecodeMs = generationMetrics?.prefillDurationMs ?: (if (nativeFirstTokenMs > 0L) nativeFirstTokenMs else firstCallbackMs)
                    val tokPerSec = if (decodeMs > 0L && tokCount > 0) {
                        tokCount.toDouble() / (decodeMs.toDouble() / 1000.0)
                    } else if (totalDurationMs > 0L && tokCount > 0) {
                        tokCount.toDouble() / (totalDurationMs.toDouble() / 1000.0)
                    } else 0.0

                    promptResults.add(
                        GgufPromptDiagnosticResult(
                            prompt = prompt,
                            success = (result == LlamaGenerationResult.Completed),
                            promptTokensCount = 0, // Left unknown; do not substitute word count
                            promptDecodeDurationMs = promptDecodeMs,
                            timeToFirstTokenMs = if (nativeFirstTokenMs > 0L) nativeFirstTokenMs else firstCallbackMs,
                            generatedTokenCount = tokCount,
                            totalGenerationDurationMs = totalDurationMs,
                            tokensPerSecond = tokPerSec,
                            generatedText = tokens.joinToString(""),
                            errorMessage = if (result == LlamaGenerationResult.Failed) "Generation returned Failed" else null
                        )
                    )
                } catch (e: LlamaExecutionException) {
                    val totalDurationMs = (System.nanoTime() - promptStartNano) / 1_000_000L
                    promptResults.add(
                        GgufPromptDiagnosticResult(
                            prompt = prompt,
                            success = false,
                            totalGenerationDurationMs = totalDurationMs,
                            errorMessage = "${e.category}: ${e.message}"
                        )
                    )
                } catch (t: Throwable) {
                    val totalDurationMs = (System.nanoTime() - promptStartNano) / 1_000_000L
                    promptResults.add(
                        GgufPromptDiagnosticResult(
                            prompt = prompt,
                            success = false,
                            totalGenerationDurationMs = totalDurationMs,
                            errorMessage = t.message ?: t.toString()
                        )
                    )
                }
            }
        }

        // Capture actual runtime diagnostics BEFORE closing the engine
        val backendUsed = engine.activeBackend.displayName
        val activeBackend = engine.activeBackend
        val anomalyReport = LlamaCppEngine.getLastError()?.takeIf { it.contains("[NUMERICAL_AUDIT]") }

        val validationResult = if (activeBackend == LlamaBackend.VULKAN) {
            val cacheKey = GgufGpuValidator.buildCacheKey(modelSha256, threads, contextWindow, nBatch, nUbatch, prefillThreads)
            GgufGpuValidator.getCachedResult(context, cacheKey)
        } else {
            GgufGpuValidator.ValidationResult.Pass
        }

        val overallSuccess = initSuccess && promptResults.isNotEmpty() && promptResults.all { it.success }
        val numericalValidationPassed = anomalyReport == null &&
            (activeBackend != LlamaBackend.VULKAN || validationResult is GgufGpuValidator.ValidationResult.Pass)
        val responseQualityVerified = overallSuccess &&
            promptResults.all { it.generatedText.isNotBlank() } &&
            numericalValidationPassed

        // Phase 6: Cleanup
        var closeSuccess = false
        try {
            engine.close()
            closeSuccess = !engine.isInitialized
        } catch (t: Throwable) {
            Log.w(TAG, "Error closing diagnostic engine: ${t.message}")
        }

        // Save report to disk if context is provided
        var savedPath: String? = null
        if (context != null) {
            try {
                val diagDir = File(context.filesDir, "diagnostics").apply { mkdirs() }
                val reportFile = File(diagDir, "gguf_diag_${System.currentTimeMillis()}.md")
                val report = GgufDiagnosticReport(
                    timestamp = timestamp,
                    modelPath = modelPath,
                    fileName = fileName,
                    modelSha256 = modelSha256,
                    backendUsed = backendUsed,
                    backendPolicy = policy.name,
                    fileSizeBytes = fileSizeBytes,
                    expectedSizeBytes = expectedBytes,
                    artifactSizeValidationPassed = sizePassed,
                    artifactSizeWarning = sizeWarning,
                    inspectionValid = inspection.valid,
                    architecture = inspection.architecture,
                    primaryQuantization = inspection.primaryQuantization,
                    tensorCount = inspection.tensorCount,
                    trainContextLength = inspection.trainContextLength,
                    isSupported = inspection.isSupported,
                    inspectionError = inspection.error,
                    initSuccess = initSuccess,
                    initDurationMs = initDurationMs,
                    threadsUsed = threads,
                    prefillThreadsUsed = prefillThreads,
                    contextWindowUsed = contextWindow,
                    nBatchUsed = nBatch,
                    nUbatchUsed = nUbatch,
                    initError = initError,
                    promptResults = promptResults,
                    closeSuccess = closeSuccess,
                    numericalAnomalyReport = anomalyReport,
                    numericalValidationPassed = numericalValidationPassed,
                    responseQualityVerified = responseQualityVerified,
                    humanReviewedCoherence = false,
                    overallSuccess = overallSuccess,
                    reportFilePath = reportFile.absolutePath
                )
                reportFile.writeText(report.toMarkdown())
                savedPath = reportFile.absolutePath
                Log.i(TAG, "Saved GGUF diagnostic report to $savedPath")
            } catch (e: Throwable) {
                Log.w(TAG, "Could not save diagnostic report file: ${e.message}")
            }
        }

        GgufDiagnosticReport(
            timestamp = timestamp,
            modelPath = modelPath,
            fileName = fileName,
            modelSha256 = modelSha256,
            backendUsed = backendUsed,
            backendPolicy = policy.name,
            fileSizeBytes = fileSizeBytes,
            expectedSizeBytes = expectedBytes,
            artifactSizeValidationPassed = sizePassed,
            artifactSizeWarning = sizeWarning,
            inspectionValid = inspection.valid,
            architecture = inspection.architecture,
            primaryQuantization = inspection.primaryQuantization,
            tensorCount = inspection.tensorCount,
            trainContextLength = inspection.trainContextLength,
            isSupported = inspection.isSupported,
            inspectionError = inspection.error,
            initSuccess = initSuccess,
            initDurationMs = initDurationMs,
            threadsUsed = threads,
            prefillThreadsUsed = prefillThreads,
            contextWindowUsed = contextWindow,
            nBatchUsed = nBatch,
            nUbatchUsed = nUbatch,
            initError = initError,
            promptResults = promptResults,
            closeSuccess = closeSuccess,
            numericalAnomalyReport = anomalyReport,
            numericalValidationPassed = numericalValidationPassed,
            responseQualityVerified = responseQualityVerified,
            humanReviewedCoherence = false,
            overallSuccess = overallSuccess,
            reportFilePath = savedPath
        )
    }
}
