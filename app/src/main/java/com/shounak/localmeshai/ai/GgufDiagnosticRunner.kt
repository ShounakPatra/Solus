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
    val contextWindowUsed: Int,
    val nBatchUsed: Int,
    val nUbatchUsed: Int,
    val initError: String?,
    val promptResults: List<GgufPromptDiagnosticResult>,
    val closeSuccess: Boolean,
    val numericalAnomalyReport: String?,
    val overallSuccess: Boolean,
    val reportFilePath: String? = null
) {
    fun toMarkdown(): String {
        val sb = StringBuilder()
        sb.appendLine("# GGUF Diagnostic Report")
        sb.appendLine("**Timestamp:** $timestamp")
        sb.appendLine("**Model File:** `$fileName`")
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
        sb.appendLine("- **Threads:** $threadsUsed")
        sb.appendLine("- **Context Window:** $contextWindowUsed")
        sb.appendLine("- **Batch / UBatch:** $nBatchUsed / $nUbatchUsed")
        sb.appendLine()
        sb.appendLine("## 3. Standardized Test Matrix")
        promptResults.forEachIndexed { idx, res ->
            sb.appendLine("### Test ${idx + 1}: \"${res.prompt}\"")
            sb.appendLine("- **Status:** ${if (res.success) "✅ Completed" else "❌ Failed (${res.errorMessage})"}")
            if (res.success) {
                sb.appendLine("- **TTFT (Time to first token):** ${res.timeToFirstTokenMs}ms")
                sb.appendLine("- **Tokens Generated:** ${res.generatedTokenCount} tokens (${String.format(Locale.US, "%.1f", res.tokensPerSecond)} tok/s)")
                sb.appendLine("- **Response Sample:** `${res.generatedText.take(120).replace("\n", " ")}`")
            }
            sb.appendLine()
        }
        if (!numericalAnomalyReport.isNullOrBlank()) {
            sb.appendLine("## 4. Numerical Anomaly Forensics")
            sb.appendLine("```")
            sb.appendLine(numericalAnomalyReport)
            sb.appendLine("```")
            sb.appendLine()
        }
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
        contextWindow: Int = 2048,
        nBatch: Int = 256,
        nUbatch: Int = 128,
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

        // Phase 3: Runtime Context Init
        val engine = if (customNativeBridge != null) {
            LlamaCppEngine(context, customNativeBridge)
        } else {
            LlamaCppEngine(context)
        }

        var initSuccess = false
        var initDurationMs = 0L
        var initError: String? = null

        val initStart = System.currentTimeMillis()
        try {
            engine.initialize(
                modelPath = modelPath,
                threads = threads,
                contextWindow = contextWindow,
                nBatch = nBatch,
                nUbatch = nUbatch,
                expectedFileSizeBytes = expectedBytes
            )
            initSuccess = engine.isInitialized
            initDurationMs = System.currentTimeMillis() - initStart
        } catch (t: Throwable) {
            initDurationMs = System.currentTimeMillis() - initStart
            initError = t.message ?: t.toString()
            Log.e(TAG, "GGUF diagnostic init failed for $fileName", t)
        }

        // Phase 4: Standard prompt matrix execution
        val promptResults = mutableListOf<GgufPromptDiagnosticResult>()
        if (initSuccess) {
            for (prompt in prompts) {
                val promptStart = System.currentTimeMillis()
                var firstTokenMs = 0L
                val tokens = mutableListOf<String>()

                try {
                    val result = engine.generateStream(
                        prompt = prompt,
                        samplingParams = LlamaSamplingParams(maxTokens = 32, temperature = 0.6f)
                    ) { token ->
                        if (tokens.isEmpty()) {
                            firstTokenMs = System.currentTimeMillis() - promptStart
                        }
                        tokens.add(token)
                    }

                    val totalDurationMs = System.currentTimeMillis() - promptStart
                    val tokCount = tokens.size
                    val tokPerSec = if (totalDurationMs > 0 && tokCount > 0) {
                        tokCount.toDouble() / (totalDurationMs.toDouble() / 1000.0)
                    } else 0.0

                    promptResults.add(
                        GgufPromptDiagnosticResult(
                            prompt = prompt,
                            success = (result == LlamaGenerationResult.Completed),
                            promptTokensCount = prompt.split(" ").size, // Approx
                            promptDecodeDurationMs = if (firstTokenMs > 0) firstTokenMs else totalDurationMs,
                            timeToFirstTokenMs = firstTokenMs,
                            generatedTokenCount = tokCount,
                            totalGenerationDurationMs = totalDurationMs,
                            tokensPerSecond = tokPerSec,
                            generatedText = tokens.joinToString(""),
                            errorMessage = if (result == LlamaGenerationResult.Failed) "Generation returned Failed" else null
                        )
                    )
                } catch (t: Throwable) {
                    val totalDurationMs = System.currentTimeMillis() - promptStart
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

        // Phase 5: Cleanup
        var closeSuccess = false
        try {
            engine.close()
            closeSuccess = !engine.isInitialized
        } catch (t: Throwable) {
            Log.w(TAG, "Error closing diagnostic engine: ${t.message}")
        }

        // Check for numerical anomalies recorded by native layer
        val anomalyReport = LlamaCppEngine.getLastError()?.takeIf { it.contains("[NUMERICAL_AUDIT]") }

        val overallSuccess = initSuccess && promptResults.isNotEmpty() && promptResults.all { it.success }

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
                    contextWindowUsed = contextWindow,
                    nBatchUsed = nBatch,
                    nUbatchUsed = nUbatch,
                    initError = initError,
                    promptResults = promptResults,
                    closeSuccess = closeSuccess,
                    numericalAnomalyReport = anomalyReport,
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
            contextWindowUsed = contextWindow,
            nBatchUsed = nBatch,
            nUbatchUsed = nUbatch,
            initError = initError,
            promptResults = promptResults,
            closeSuccess = closeSuccess,
            numericalAnomalyReport = anomalyReport,
            overallSuccess = overallSuccess,
            reportFilePath = savedPath
        )
    }
}
