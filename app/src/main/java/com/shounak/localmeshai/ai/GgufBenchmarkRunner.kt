package com.shounak.localmeshai.ai

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.shounak.localmeshai.BuildConfig
import com.shounak.localmeshai.models.ModelBundle
import com.shounak.localmeshai.utils.AppTrace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.max

data class BenchmarkProgress(
    val phase: String,
    val currentStep: Int,
    val totalSteps: Int,
    val detail: String = ""
)

data class BenchmarkResult(
    val qualifiesVulkan: Boolean,
    val summary: String,
    val cpuWarmTtftMedianMs: Double,
    val vulkanWarmTtftMedianMs: Double,
    val warmTtftImprovementPercent: Double,
    val cpuColdLoadFtMs: Double,
    val vulkanColdLoadFtMs: Double,
    val coldRegressionPercent: Double,
    val cpuDecodeTokPerSec: Double,
    val vulkanDecodeTokPerSec: Double,
    val decodeRegressionPercent: Double,
    val cpuFrameP95Ms: Double,
    val vulkanFrameP95Ms: Double,
    val frameP95RegressionPercent: Double,
    val maxRenderStallMs: Long,
    val receipt: GgufPerformanceReceipt? = null
)

object GgufBenchmarkRunner {
    private const val TAG = "GgufBenchmarkRunner"

    private const val COLD_SAMPLES = 5
    private const val WARM_SAMPLES = 20

    private val FRESH_PROMPTS = listOf(
        "Hi, what is today's weather like?",
        "Explain the theory of relativity briefly.",
        "What is the capital of France?",
        "Write a short haiku about summer."
    )

    private val LONG_PROMPTS = listOf(
        "The quick brown fox jumps over the lazy dog. In computer science, artificial intelligence is intelligence demonstrated by machines, as opposed to intelligence displayed by humans and animals. Please summarize this concept in one concise sentence."
    )

    suspend fun runBenchmark(
        context: Context,
        modelPath: String,
        modelSha256: String,
        threads: Int = 4,
        contextWindow: Int = 2048,
        nBatch: Int = 256,
        nUbatch: Int = 128,
        prefillThreads: Int = threads,
        expectedFileSizeBytes: Long = 0L,
        onProgress: ((BenchmarkProgress) -> Unit)? = null
    ): BenchmarkResult = withContext(Dispatchers.IO) {
        val file = File(modelPath)
        require(file.exists()) { "Model file not found at $modelPath" }

        val totalSteps = (COLD_SAMPLES + WARM_SAMPLES) * 2 + 10 // CPU + Validation + Vulkan
        var currentStep = 0

        fun reportProgress(phase: String, detail: String = "") {
            currentStep++
            onProgress?.invoke(BenchmarkProgress(phase, currentStep, totalSteps, detail))
        }

        reportProgress("Starting Benchmark", "Initializing CPU benchmark pass")

        // ── Phase 1: CPU Benchmark ────────────────────────────────────────────────────────
        val cpuColdTimes = mutableListOf<Double>()
        val cpuWarmTtft = mutableListOf<Double>()
        val cpuDecodeTokPerSec = mutableListOf<Double>()
        val cpuFrameTimes = mutableListOf<Double>()
        var maxRenderStallCpu = 0L

        // 1A: CPU Cold Samples (Cold load + first token)
        for (i in 1..COLD_SAMPLES) {
            ensureActive()
            reportProgress("Benchmarking CPU (Cold $i/$COLD_SAMPLES)", "Testing cold model initialization")
            val prompt = FRESH_PROMPTS[(i - 1) % FRESH_PROMPTS.size]
            val engine = LlamaCppEngine(context)
            val startLoad = SystemClock.elapsedRealtime()
            var firstTokenMs = 0.0

            try {
                engine.initialize(
                    modelPath = file.absolutePath,
                    threads = threads,
                    prefillThreads = prefillThreads,
                    contextWindow = contextWindow,
                    nBatch = nBatch,
                    nUbatch = nUbatch,
                    backend = LlamaBackend.CPU,
                    nGpuLayers = 0,
                    expectedFileSizeBytes = expectedFileSizeBytes
                )
                val loadDuration = SystemClock.elapsedRealtime() - startLoad
                var ftRecorded = false
                engine.generateStream(
                    prompt = prompt,
                    samplingParams = LlamaSamplingParams(maxTokens = 8),
                    onFirstToken = {
                        firstTokenMs = (SystemClock.elapsedRealtime() - startLoad).toDouble()
                        ftRecorded = true
                    }
                ) {}
                if (!ftRecorded) {
                    firstTokenMs = (SystemClock.elapsedRealtime() - startLoad).toDouble()
                }
                cpuColdTimes.add(firstTokenMs)
            } finally {
                engine.close()
            }
        }

        // 1B: CPU Warm Samples
        reportProgress("Benchmarking CPU (Warm)", "Loading sustained CPU context")
        val cpuEngine = LlamaCppEngine(context)
        try {
            cpuEngine.initialize(
                modelPath = file.absolutePath,
                threads = threads,
                prefillThreads = prefillThreads,
                contextWindow = contextWindow,
                nBatch = nBatch,
                nUbatch = nUbatch,
                backend = LlamaBackend.CPU,
                nGpuLayers = 0,
                expectedFileSizeBytes = expectedFileSizeBytes
            )

            for (i in 1..WARM_SAMPLES) {
                ensureActive()
                val isLong = (i % 5 == 0)
                val prompt = if (isLong) LONG_PROMPTS[0] else FRESH_PROMPTS[(i - 1) % FRESH_PROMPTS.size]
                val maxTok = if (i % 4 == 0) 32 else 16
                reportProgress("Benchmarking CPU (Warm $i/$WARM_SAMPLES)", "Prompt: ${prompt.take(25)}...")

                val startGen = SystemClock.elapsedRealtime()
                var ttft = 0.0
                var lastTokenTime = startGen

                cpuEngine.generateStream(
                    prompt = prompt,
                    samplingParams = LlamaSamplingParams(maxTokens = maxTok),
                    onFirstToken = {
                        ttft = (SystemClock.elapsedRealtime() - startGen).toDouble()
                        lastTokenTime = SystemClock.elapsedRealtime()
                    },
                    onMetrics = { m ->
                        if (m.decodeDurationMs > 0 && m.tokenCount > 0) {
                            val tokPerSec = (m.tokenCount.toDouble() * 1000.0) / m.decodeDurationMs.toDouble()
                            cpuDecodeTokPerSec.add(tokPerSec)
                        }
                    }
                ) {
                    val now = SystemClock.elapsedRealtime()
                    val frameDelta = (now - lastTokenTime).toDouble()
                    cpuFrameTimes.add(frameDelta)
                    if (frameDelta > 100.0) {
                        maxRenderStallCpu = max(maxRenderStallCpu, frameDelta.toLong())
                    }
                    lastTokenTime = now
                }
                if (ttft > 0.0) cpuWarmTtft.add(ttft)
            }
        } finally {
            cpuEngine.close()
        }

        // ── Phase 2: Numerical Validation ─────────────────────────────────────────────────
        reportProgress("Verifying Numerical Correctness", "Running TV distance validation against CPU reference")
        val validationResult = GgufGpuValidator.validate(
            context = context,
            modelPath = file.absolutePath,
            modelSha256 = modelSha256,
            threads = threads,
            prefillThreads = prefillThreads,
            contextWindow = contextWindow,
            nBatch = nBatch,
            nUbatch = nUbatch,
            expectedFileSizeBytes = expectedFileSizeBytes
        )

        if (validationResult !is GgufGpuValidator.ValidationResult.Pass) {
            val reason = if (validationResult is GgufGpuValidator.ValidationResult.Fail) validationResult.reason else "Validation incomplete"
            Log.w(TAG, "Numerical validation failed: $reason. Vulkan disqualified.")
            return@withContext BenchmarkResult(
                qualifiesVulkan = false,
                summary = "Vulkan disqualified: Numerical validation failed ($reason). Defaulting to true CPU.",
                cpuWarmTtftMedianMs = median(cpuWarmTtft),
                vulkanWarmTtftMedianMs = 0.0,
                warmTtftImprovementPercent = 0.0,
                cpuColdLoadFtMs = median(cpuColdTimes),
                vulkanColdLoadFtMs = 0.0,
                coldRegressionPercent = 0.0,
                cpuDecodeTokPerSec = median(cpuDecodeTokPerSec),
                vulkanDecodeTokPerSec = 0.0,
                decodeRegressionPercent = 0.0,
                cpuFrameP95Ms = percentile(cpuFrameTimes, 95.0),
                vulkanFrameP95Ms = 0.0,
                frameP95RegressionPercent = 0.0,
                maxRenderStallMs = maxRenderStallCpu
            )
        }

        // ── Phase 3: Vulkan GPU Benchmark ─────────────────────────────────────────────────
        val vkColdTimes = mutableListOf<Double>()
        val vkWarmTtft = mutableListOf<Double>()
        val vkDecodeTokPerSec = mutableListOf<Double>()
        val vkFrameTimes = mutableListOf<Double>()
        var maxRenderStallVk = 0L
        var vkAnomalyOrFailure = false

        // 3A: Vulkan Cold Samples
        for (i in 1..COLD_SAMPLES) {
            ensureActive()
            reportProgress("Benchmarking Vulkan (Cold $i/$COLD_SAMPLES)", "Testing cold GPU initialization")
            val prompt = FRESH_PROMPTS[(i - 1) % FRESH_PROMPTS.size]
            val engine = LlamaCppEngine(context)
            val startLoad = SystemClock.elapsedRealtime()
            var firstTokenMs = 0.0

            try {
                engine.initialize(
                    modelPath = file.absolutePath,
                    threads = threads,
                    prefillThreads = prefillThreads,
                    contextWindow = contextWindow,
                    nBatch = nBatch,
                    nUbatch = nUbatch,
                    backend = LlamaBackend.VULKAN,
                    nGpuLayers = 99,
                    expectedFileSizeBytes = expectedFileSizeBytes
                )
                var ftRecorded = false
                engine.generateStream(
                    prompt = prompt,
                    samplingParams = LlamaSamplingParams(maxTokens = 8),
                    onFirstToken = {
                        firstTokenMs = (SystemClock.elapsedRealtime() - startLoad).toDouble()
                        ftRecorded = true
                    }
                ) {}
                if (!ftRecorded) {
                    firstTokenMs = (SystemClock.elapsedRealtime() - startLoad).toDouble()
                }
                vkColdTimes.add(firstTokenMs)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                vkAnomalyOrFailure = true
                Log.e(TAG, "Vulkan cold sample failed", t)
            } finally {
                engine.close()
            }
        }

        // 3B: Vulkan Warm Samples
        reportProgress("Benchmarking Vulkan (Warm)", "Loading sustained GPU context")
        val vkEngine = LlamaCppEngine(context)
        try {
            vkEngine.initialize(
                modelPath = file.absolutePath,
                threads = threads,
                prefillThreads = prefillThreads,
                contextWindow = contextWindow,
                nBatch = nBatch,
                nUbatch = nUbatch,
                backend = LlamaBackend.VULKAN,
                nGpuLayers = 99,
                expectedFileSizeBytes = expectedFileSizeBytes
            )

            for (i in 1..WARM_SAMPLES) {
                ensureActive()
                val isLong = (i % 5 == 0)
                val prompt = if (isLong) LONG_PROMPTS[0] else FRESH_PROMPTS[(i - 1) % FRESH_PROMPTS.size]
                val maxTok = if (i % 4 == 0) 32 else 16
                reportProgress("Benchmarking Vulkan (Warm $i/$WARM_SAMPLES)", "Prompt: ${prompt.take(25)}...")

                val startGen = SystemClock.elapsedRealtime()
                var ttft = 0.0
                var lastTokenTime = startGen

                try {
                    vkEngine.generateStream(
                        prompt = prompt,
                        samplingParams = LlamaSamplingParams(maxTokens = maxTok),
                        onFirstToken = {
                            ttft = (SystemClock.elapsedRealtime() - startGen).toDouble()
                            lastTokenTime = SystemClock.elapsedRealtime()
                        },
                        onMetrics = { m ->
                            if (m.decodeDurationMs > 0 && m.tokenCount > 0) {
                                val tokPerSec = (m.tokenCount.toDouble() * 1000.0) / m.decodeDurationMs.toDouble()
                                vkDecodeTokPerSec.add(tokPerSec)
                            }
                        }
                    ) {
                        val now = SystemClock.elapsedRealtime()
                        val frameDelta = (now - lastTokenTime).toDouble()
                        vkFrameTimes.add(frameDelta)
                        if (frameDelta > 100.0) {
                            maxRenderStallVk = max(maxRenderStallVk, frameDelta.toLong())
                        }
                        lastTokenTime = now
                    }
                    if (ttft > 0.0) vkWarmTtft.add(ttft)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    vkAnomalyOrFailure = true
                    Log.e(TAG, "Vulkan warm sample failed", t)
                }
            }
        } finally {
            vkEngine.close()
        }

        // ── Phase 4: Compute Metrics and Qualification ────────────────────────────────────
        reportProgress("Evaluating Results", "Computing statistics and qualification criteria")

        val cpuWarmMedian = median(cpuWarmTtft)
        val vkWarmMedian = median(vkWarmTtft)
        val warmImprovementFraction = if (cpuWarmMedian > 0) (cpuWarmMedian - vkWarmMedian) / cpuWarmMedian else 0.0

        val cpuColdMedian = median(cpuColdTimes)
        val vkColdMedian = median(vkColdTimes)
        val coldRegressionFraction = if (cpuColdMedian > 0) max(0.0, (vkColdMedian - cpuColdMedian) / cpuColdMedian) else 0.0

        val cpuDecodeMedian = median(cpuDecodeTokPerSec)
        val vkDecodeMedian = median(vkDecodeTokPerSec)
        val decodeRegressionFraction = if (cpuDecodeMedian > 0) max(0.0, (cpuDecodeMedian - vkDecodeMedian) / cpuDecodeMedian) else 0.0

        val cpuFrameP95 = percentile(cpuFrameTimes, 95.0)
        val vkFrameP95 = percentile(vkFrameTimes, 95.0)
        val frameP95RegressionFraction = if (cpuFrameP95 > 0) max(0.0, (vkFrameP95 - cpuFrameP95) / cpuFrameP95) else 0.0

        val maxStall = max(maxRenderStallCpu, maxRenderStallVk)

        // Strict qualification rules:
        // 1. Numerical pass
        // 2. Median warm TTFT improves by at least 10%
        // 3. Cold load+FT latency doesn't regress by > 5%
        // 4. Decode throughput doesn't regress by > 5%
        // 5. Frame p95 doesn't regress by > 5%
        // 6. No rendering stall > 100ms
        // 7. No failures or numerical anomalies
        val qualifies = !vkAnomalyOrFailure &&
            warmImprovementFraction >= 0.10 &&
            coldRegressionFraction <= 0.05 &&
            decodeRegressionFraction <= 0.05 &&
            frameP95RegressionFraction <= 0.05 &&
            maxStall <= 100L

        val configKey = GgufPerformanceStore.buildCacheKey(
            modelSha256 = modelSha256,
            threads = threads,
            contextWindow = contextWindow,
            nBatch = nBatch,
            nUbatch = nUbatch,
            prefillThreads = prefillThreads
        )

        val receipt = GgufPerformanceReceipt(
            modelSha256 = modelSha256,
            nativeBuildId = BuildConfig.NATIVE_BUILD_ID,
            buildType = BuildConfig.BUILD_TYPE,
            deviceIdentity = GgufGpuValidator.getDeviceIdentity(),
            backendPolicyVersion = 1,
            configurationKey = configKey,
            coldSampleCount = COLD_SAMPLES,
            warmSampleCount = WARM_SAMPLES,
            cpuWarmTtftMedianMs = cpuWarmMedian,
            vulkanWarmTtftMedianMs = vkWarmMedian,
            warmTtftImprovementFraction = warmImprovementFraction,
            cpuColdLoadFirstTokenMs = cpuColdMedian,
            vulkanColdLoadFirstTokenMs = vkColdMedian,
            coldRegressionFraction = coldRegressionFraction,
            cpuSustainedDecodeTokensPerSec = cpuDecodeMedian,
            vulkanSustainedDecodeTokensPerSec = vkDecodeMedian,
            decodeRegressionFraction = decodeRegressionFraction,
            cpuFrameP95Ms = cpuFrameP95,
            vulkanFrameP95Ms = vkFrameP95,
            frameP95RegressionFraction = frameP95RegressionFraction,
            maxInferenceRenderStallMs = maxStall,
            hasNumericalAnomalyOrFailure = vkAnomalyOrFailure,
            qualifiesVulkan = qualifies,
            isComplete = true
        )

        GgufPerformanceStore.saveReceipt(context, configKey, receipt)

        val summary = if (qualifies) {
            "Vulkan qualified! Warm TTFT improved by ${(warmImprovementFraction * 100).toInt()}% (CPU: ${cpuWarmMedian.toInt()}ms vs GPU: ${vkWarmMedian.toInt()}ms). Auto will now select Vulkan."
        } else {
            val failReasons = mutableListOf<String>()
            if (warmImprovementFraction < 0.10) failReasons.add("Warm TTFT did not improve by >= 10% (was ${(warmImprovementFraction * 100).toInt()}%)")
            if (coldRegressionFraction > 0.05) failReasons.add("Cold startup regressed by ${(coldRegressionFraction * 100).toInt()}%")
            if (decodeRegressionFraction > 0.05) failReasons.add("Decode speed regressed by ${(decodeRegressionFraction * 100).toInt()}%")
            if (frameP95RegressionFraction > 0.05) failReasons.add("Frame p95 regressed by ${(frameP95RegressionFraction * 100).toInt()}%")
            if (maxStall > 100L) failReasons.add("Render stall exceeded 100ms ($maxStall ms)")
            if (vkAnomalyOrFailure) failReasons.add("Encountered numerical anomaly or failure")
            "CPU preferred. Reasons: ${failReasons.joinToString("; ")}. Auto will retain true CPU."
        }

        reportProgress("Completed", summary)

        BenchmarkResult(
            qualifiesVulkan = qualifies,
            summary = summary,
            cpuWarmTtftMedianMs = cpuWarmMedian,
            vulkanWarmTtftMedianMs = vkWarmMedian,
            warmTtftImprovementPercent = warmImprovementFraction * 100.0,
            cpuColdLoadFtMs = cpuColdMedian,
            vulkanColdLoadFtMs = vkColdMedian,
            coldRegressionPercent = coldRegressionFraction * 100.0,
            cpuDecodeTokPerSec = cpuDecodeMedian,
            vulkanDecodeTokPerSec = vkDecodeMedian,
            decodeRegressionPercent = decodeRegressionFraction * 100.0,
            cpuFrameP95Ms = cpuFrameP95,
            vulkanFrameP95Ms = vkFrameP95,
            frameP95RegressionPercent = frameP95RegressionFraction * 100.0,
            maxRenderStallMs = maxStall,
            receipt = receipt
        )
    }

    suspend fun runBenchmark(
        context: Context,
        bundle: ModelBundle,
        threads: Int = 4,
        contextWindow: Int = 2048,
        nBatch: Int = 256,
        nUbatch: Int = 128,
        prefillThreads: Int = threads,
        expectedFileSizeBytes: Long = 0L,
        onProgress: ((BenchmarkProgress) -> Unit)? = null
    ): BenchmarkResult = withContext(Dispatchers.IO) {
        val totalSteps = (COLD_SAMPLES + WARM_SAMPLES) * 2 + 10 // CPU + Validation + Vulkan
        var currentStep = 0

        fun reportProgress(phase: String, detail: String = "") {
            currentStep++
            onProgress?.invoke(BenchmarkProgress(phase, currentStep, totalSteps, detail))
        }

        reportProgress("Starting Benchmark", "Verifying artifact fingerprint")
        val modelSha256 = GgufGpuValidator.resolveArtifactFingerprint(context, bundle)
        val companionSummary = "${bundle.mainArtifacts.size}_parts_${bundle.projectorArtifact?.displayName.orEmpty()}"

        reportProgress("Starting Benchmark", "Initializing CPU benchmark pass")

        // ── Phase 1: CPU Benchmark ────────────────────────────────────────────────────────
        val cpuColdTimes = mutableListOf<Double>()
        val cpuWarmTtft = mutableListOf<Double>()
        val cpuDecodeTokPerSec = mutableListOf<Double>()
        val cpuFrameTimes = mutableListOf<Double>()
        var maxRenderStallCpu = 0L

        // 1A: CPU Cold Samples (Cold load + first token)
        for (i in 1..COLD_SAMPLES) {
            ensureActive()
            reportProgress("Benchmarking CPU (Cold $i/$COLD_SAMPLES)", "Testing cold model initialization")
            val prompt = FRESH_PROMPTS[(i - 1) % FRESH_PROMPTS.size]
            val engine = LlamaCppEngine(context)
            val startLoad = SystemClock.elapsedRealtime()
            var firstTokenMs = 0.0

            try {
                engine.initializeBundle(
                    context = context,
                    bundle = bundle,
                    threads = threads,
                    prefillThreads = prefillThreads,
                    contextWindow = contextWindow,
                    nBatch = nBatch,
                    nUbatch = nUbatch,
                    backend = LlamaBackend.CPU,
                    nGpuLayers = 0,
                    expectedFileSizeBytes = expectedFileSizeBytes
                )
                var ftRecorded = false
                engine.generateStream(
                    prompt = prompt,
                    samplingParams = LlamaSamplingParams(maxTokens = 8),
                    onFirstToken = {
                        firstTokenMs = (SystemClock.elapsedRealtime() - startLoad).toDouble()
                        ftRecorded = true
                    }
                ) {}
                if (!ftRecorded) {
                    firstTokenMs = (SystemClock.elapsedRealtime() - startLoad).toDouble()
                }
                cpuColdTimes.add(firstTokenMs)
            } finally {
                engine.close()
            }
        }

        // 1B: CPU Warm Samples
        reportProgress("Benchmarking CPU (Warm)", "Loading sustained CPU context")
        val cpuEngine = LlamaCppEngine(context)
        try {
            cpuEngine.initializeBundle(
                context = context,
                bundle = bundle,
                threads = threads,
                prefillThreads = prefillThreads,
                contextWindow = contextWindow,
                nBatch = nBatch,
                nUbatch = nUbatch,
                backend = LlamaBackend.CPU,
                nGpuLayers = 0,
                expectedFileSizeBytes = expectedFileSizeBytes
            )

            for (i in 1..WARM_SAMPLES) {
                ensureActive()
                val isLong = (i % 5 == 0)
                val prompt = if (isLong) LONG_PROMPTS[0] else FRESH_PROMPTS[(i - 1) % FRESH_PROMPTS.size]
                val maxTok = if (i % 4 == 0) 32 else 16
                reportProgress("Benchmarking CPU (Warm $i/$WARM_SAMPLES)", "Prompt: ${prompt.take(25)}...")

                val startGen = SystemClock.elapsedRealtime()
                var ttft = 0.0
                var lastTokenTime = startGen

                cpuEngine.generateStream(
                    prompt = prompt,
                    samplingParams = LlamaSamplingParams(maxTokens = maxTok),
                    onFirstToken = {
                        ttft = (SystemClock.elapsedRealtime() - startGen).toDouble()
                        lastTokenTime = SystemClock.elapsedRealtime()
                    },
                    onMetrics = { m ->
                        if (m.decodeDurationMs > 0 && m.tokenCount > 0) {
                            val tokPerSec = (m.tokenCount.toDouble() * 1000.0) / m.decodeDurationMs.toDouble()
                            cpuDecodeTokPerSec.add(tokPerSec)
                        }
                    }
                ) {
                    val now = SystemClock.elapsedRealtime()
                    val frameDelta = (now - lastTokenTime).toDouble()
                    cpuFrameTimes.add(frameDelta)
                    if (frameDelta > 100.0) {
                        maxRenderStallCpu = max(maxRenderStallCpu, frameDelta.toLong())
                    }
                    lastTokenTime = now
                }
                if (ttft > 0.0) cpuWarmTtft.add(ttft)
            }
        } finally {
            cpuEngine.close()
        }

        // ── Phase 2: Numerical Validation ─────────────────────────────────────────────────
        reportProgress("Verifying Numerical Correctness", "Running TV distance validation against CPU reference")
        val validationResult = GgufGpuValidator.validate(
            context = context,
            bundle = bundle,
            threads = threads,
            prefillThreads = prefillThreads,
            contextWindow = contextWindow,
            nBatch = nBatch,
            nUbatch = nUbatch,
            expectedFileSizeBytes = expectedFileSizeBytes,
            companionSummary = companionSummary
        )

        if (validationResult !is GgufGpuValidator.ValidationResult.Pass) {
            val reason = if (validationResult is GgufGpuValidator.ValidationResult.Fail) validationResult.reason else "Validation incomplete"
            Log.w(TAG, "Numerical validation failed: $reason. Vulkan disqualified.")
            return@withContext BenchmarkResult(
                qualifiesVulkan = false,
                summary = "Vulkan disqualified: Numerical validation failed ($reason). Defaulting to true CPU.",
                cpuWarmTtftMedianMs = median(cpuWarmTtft),
                vulkanWarmTtftMedianMs = 0.0,
                warmTtftImprovementPercent = 0.0,
                cpuColdLoadFtMs = median(cpuColdTimes),
                vulkanColdLoadFtMs = 0.0,
                coldRegressionPercent = 0.0,
                cpuDecodeTokPerSec = median(cpuDecodeTokPerSec),
                vulkanDecodeTokPerSec = 0.0,
                decodeRegressionPercent = 0.0,
                cpuFrameP95Ms = percentile(cpuFrameTimes, 95.0),
                vulkanFrameP95Ms = 0.0,
                frameP95RegressionPercent = 0.0,
                maxRenderStallMs = maxRenderStallCpu
            )
        }

        // ── Phase 3: Vulkan GPU Benchmark ─────────────────────────────────────────────────
        val vkColdTimes = mutableListOf<Double>()
        val vkWarmTtft = mutableListOf<Double>()
        val vkDecodeTokPerSec = mutableListOf<Double>()
        val vkFrameTimes = mutableListOf<Double>()
        var maxRenderStallVk = 0L
        var vkAnomalyOrFailure = false

        // 3A: Vulkan Cold Samples
        for (i in 1..COLD_SAMPLES) {
            ensureActive()
            reportProgress("Benchmarking Vulkan (Cold $i/$COLD_SAMPLES)", "Testing cold GPU initialization")
            val prompt = FRESH_PROMPTS[(i - 1) % FRESH_PROMPTS.size]
            val engine = LlamaCppEngine(context)
            val startLoad = SystemClock.elapsedRealtime()
            var firstTokenMs = 0.0

            try {
                engine.initializeBundle(
                    context = context,
                    bundle = bundle,
                    threads = threads,
                    prefillThreads = prefillThreads,
                    contextWindow = contextWindow,
                    nBatch = nBatch,
                    nUbatch = nUbatch,
                    backend = LlamaBackend.VULKAN,
                    nGpuLayers = 99,
                    expectedFileSizeBytes = expectedFileSizeBytes
                )
                var ftRecorded = false
                engine.generateStream(
                    prompt = prompt,
                    samplingParams = LlamaSamplingParams(maxTokens = 8),
                    onFirstToken = {
                        firstTokenMs = (SystemClock.elapsedRealtime() - startLoad).toDouble()
                        ftRecorded = true
                    }
                ) {}
                if (!ftRecorded) {
                    firstTokenMs = (SystemClock.elapsedRealtime() - startLoad).toDouble()
                }
                vkColdTimes.add(firstTokenMs)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                vkAnomalyOrFailure = true
                Log.e(TAG, "Vulkan cold sample failed", t)
            } finally {
                engine.close()
            }
        }

        // 3B: Vulkan Warm Samples
        reportProgress("Benchmarking Vulkan (Warm)", "Loading sustained GPU context")
        val vkEngine = LlamaCppEngine(context)
        try {
            vkEngine.initializeBundle(
                context = context,
                bundle = bundle,
                threads = threads,
                prefillThreads = prefillThreads,
                contextWindow = contextWindow,
                nBatch = nBatch,
                nUbatch = nUbatch,
                backend = LlamaBackend.VULKAN,
                nGpuLayers = 99,
                expectedFileSizeBytes = expectedFileSizeBytes
            )

            for (i in 1..WARM_SAMPLES) {
                ensureActive()
                val isLong = (i % 5 == 0)
                val prompt = if (isLong) LONG_PROMPTS[0] else FRESH_PROMPTS[(i - 1) % FRESH_PROMPTS.size]
                val maxTok = if (i % 4 == 0) 32 else 16
                reportProgress("Benchmarking Vulkan (Warm $i/$WARM_SAMPLES)", "Prompt: ${prompt.take(25)}...")

                val startGen = SystemClock.elapsedRealtime()
                var ttft = 0.0
                var lastTokenTime = startGen

                try {
                    vkEngine.generateStream(
                        prompt = prompt,
                        samplingParams = LlamaSamplingParams(maxTokens = maxTok),
                        onFirstToken = {
                            ttft = (SystemClock.elapsedRealtime() - startGen).toDouble()
                            lastTokenTime = SystemClock.elapsedRealtime()
                        },
                        onMetrics = { m ->
                            if (m.decodeDurationMs > 0 && m.tokenCount > 0) {
                                val tokPerSec = (m.tokenCount.toDouble() * 1000.0) / m.decodeDurationMs.toDouble()
                                vkDecodeTokPerSec.add(tokPerSec)
                            }
                        }
                    ) {
                        val now = SystemClock.elapsedRealtime()
                        val frameDelta = (now - lastTokenTime).toDouble()
                        vkFrameTimes.add(frameDelta)
                        if (frameDelta > 100.0) {
                            maxRenderStallVk = max(maxRenderStallVk, frameDelta.toLong())
                        }
                        lastTokenTime = now
                    }
                    if (ttft > 0.0) vkWarmTtft.add(ttft)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    vkAnomalyOrFailure = true
                    Log.e(TAG, "Vulkan warm sample failed", t)
                }
            }
        } finally {
            vkEngine.close()
        }

        // ── Phase 4: Compute Metrics and Qualification ────────────────────────────────────
        reportProgress("Evaluating Results", "Computing statistics and qualification criteria")

        val cpuWarmMedian = median(cpuWarmTtft)
        val vkWarmMedian = median(vkWarmTtft)
        val warmImprovementFraction = if (cpuWarmMedian > 0) (cpuWarmMedian - vkWarmMedian) / cpuWarmMedian else 0.0

        val cpuColdMedian = median(cpuColdTimes)
        val vkColdMedian = median(vkColdTimes)
        val coldRegressionFraction = if (cpuColdMedian > 0) max(0.0, (vkColdMedian - cpuColdMedian) / cpuColdMedian) else 0.0

        val cpuDecodeMedian = median(cpuDecodeTokPerSec)
        val vkDecodeMedian = median(vkDecodeTokPerSec)
        val decodeRegressionFraction = if (cpuDecodeMedian > 0) max(0.0, (cpuDecodeMedian - vkDecodeMedian) / cpuDecodeMedian) else 0.0

        val cpuFrameP95 = percentile(cpuFrameTimes, 95.0)
        val vkFrameP95 = percentile(vkFrameTimes, 95.0)
        val frameP95RegressionFraction = if (cpuFrameP95 > 0) max(0.0, (vkFrameP95 - cpuFrameP95) / cpuFrameP95) else 0.0

        val maxStall = max(maxRenderStallCpu, maxRenderStallVk)

        val qualifies = !vkAnomalyOrFailure &&
            warmImprovementFraction >= 0.10 &&
            coldRegressionFraction <= 0.05 &&
            decodeRegressionFraction <= 0.05 &&
            frameP95RegressionFraction <= 0.05 &&
            maxStall <= 100L

        val configKey = GgufPerformanceStore.buildCacheKey(
            modelSha256 = modelSha256,
            threads = threads,
            contextWindow = contextWindow,
            nBatch = nBatch,
            nUbatch = nUbatch,
            prefillThreads = prefillThreads,
            companionSummary = companionSummary
        )

        val receipt = GgufPerformanceReceipt(
            modelSha256 = modelSha256,
            nativeBuildId = BuildConfig.NATIVE_BUILD_ID,
            buildType = BuildConfig.BUILD_TYPE,
            deviceIdentity = GgufGpuValidator.getDeviceIdentity(),
            backendPolicyVersion = 1,
            configurationKey = configKey,
            coldSampleCount = COLD_SAMPLES,
            warmSampleCount = WARM_SAMPLES,
            cpuWarmTtftMedianMs = cpuWarmMedian,
            vulkanWarmTtftMedianMs = vkWarmMedian,
            warmTtftImprovementFraction = warmImprovementFraction,
            cpuColdLoadFirstTokenMs = cpuColdMedian,
            vulkanColdLoadFirstTokenMs = vkColdMedian,
            coldRegressionFraction = coldRegressionFraction,
            cpuSustainedDecodeTokensPerSec = cpuDecodeMedian,
            vulkanSustainedDecodeTokensPerSec = vkDecodeMedian,
            decodeRegressionFraction = decodeRegressionFraction,
            cpuFrameP95Ms = cpuFrameP95,
            vulkanFrameP95Ms = vkFrameP95,
            frameP95RegressionFraction = frameP95RegressionFraction,
            maxInferenceRenderStallMs = maxStall,
            hasNumericalAnomalyOrFailure = vkAnomalyOrFailure,
            qualifiesVulkan = qualifies,
            isComplete = true
        )

        GgufPerformanceStore.saveReceipt(context, configKey, receipt)

        val summary = if (qualifies) {
            "Vulkan qualified! Warm TTFT improved by ${String.format(Locale.US, "%.1f", warmImprovementFraction * 100.0)}% without regressions. Auto mode will prefer Vulkan."
        } else {
            val failReasons = mutableListOf<String>()
            if (warmImprovementFraction < 0.10) failReasons.add("Warm TTFT gain < 10% (${String.format(Locale.US, "%.1f", warmImprovementFraction * 100.0)}%)")
            if (coldRegressionFraction > 0.05) failReasons.add("Cold load regressed > 5% (${String.format(Locale.US, "%.1f", coldRegressionFraction * 100.0)}%)")
            if (decodeRegressionFraction > 0.05) failReasons.add("Decode speed regressed > 5% (${String.format(Locale.US, "%.1f", decodeRegressionFraction * 100.0)}%)")
            if (frameP95RegressionFraction > 0.05) failReasons.add("Frame time p95 regressed > 5% (${String.format(Locale.US, "%.1f", frameP95RegressionFraction * 100.0)}%)")
            if (maxStall > 100L) failReasons.add("Render stall exceeded 100ms ($maxStall ms)")
            if (vkAnomalyOrFailure) failReasons.add("Encountered numerical anomaly or failure")
            "CPU preferred. Reasons: ${failReasons.joinToString("; ")}. Auto will retain true CPU."
        }

        reportProgress("Completed", summary)

        BenchmarkResult(
            qualifiesVulkan = qualifies,
            summary = summary,
            cpuWarmTtftMedianMs = cpuWarmMedian,
            vulkanWarmTtftMedianMs = vkWarmMedian,
            warmTtftImprovementPercent = warmImprovementFraction * 100.0,
            cpuColdLoadFtMs = cpuColdMedian,
            vulkanColdLoadFtMs = vkColdMedian,
            coldRegressionPercent = coldRegressionFraction * 100.0,
            cpuDecodeTokPerSec = cpuDecodeMedian,
            vulkanDecodeTokPerSec = vkDecodeMedian,
            decodeRegressionPercent = decodeRegressionFraction * 100.0,
            cpuFrameP95Ms = cpuFrameP95,
            vulkanFrameP95Ms = vkFrameP95,
            frameP95RegressionPercent = frameP95RegressionFraction * 100.0,
            maxRenderStallMs = maxStall,
            receipt = receipt
        )
    }

    private fun median(list: List<Double>): Double {
        if (list.isEmpty()) return 0.0
        val sorted = list.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        }
    }

    private fun percentile(list: List<Double>, p: Double): Double {
        if (list.isEmpty()) return 0.0
        val sorted = list.sorted()
        val index = ((p / 100.0) * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }
}
