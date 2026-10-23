package com.shounak.localmeshai.ai

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Message
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.ProgressListener
import com.shounak.localmeshai.utils.ChatRuntimePolicy
import com.shounak.localmeshai.utils.InferenceBackend
import com.shounak.localmeshai.utils.LiteRtRuntimeCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.max

object NonGgufBenchmarkRunner {
    private const val TAG = "NonGgufBenchmarkRunner"

    private val WARM_PROMPTS = listOf(
        "Hi, what is today's weather like?",
        "Explain the theory of relativity briefly.",
        "What is the capital of France?",
        "Write a short haiku about summer."
    )

    suspend fun runBenchmark(
        context: Context,
        modelPath: String,
        modelId: String,
        onProgress: ((BenchmarkProgress) -> Unit)? = null
    ): BenchmarkResult = BenchmarkAdmission.run(modelId) { BenchmarkFrames.measure { runBenchmarkInternal(context = context, modelPath = modelPath, modelId = modelId, onProgress = onProgress) } }

    private suspend fun runBenchmarkInternal(
        context: Context,
        modelPath: String,
        modelId: String,
        onProgress: ((BenchmarkProgress) -> Unit)? = null
    ): BenchmarkResult = withContext(Dispatchers.IO) {
        val file = File(modelPath)
        require(file.exists()) { "Model file not found at $modelPath" }

        if (com.shounak.localmeshai.models.ModelFormat.fromFileName(file.name)?.isPortable == true) {
            check(!com.shounak.localmeshai.utils.InitCrashGuard.isModelBlocked(context, modelId)) {
                com.shounak.localmeshai.utils.InitCrashGuard.blockedModelMessage()
            }
            return@withContext PortableBenchmarkRunner.run(file, modelId, onProgress)
        }
        val isLiteRt = ChatRuntimePolicy.shouldUseLiteRtLmConversation(file.name, modelId)
        if (isLiteRt) {
            runLiteRtBenchmark(context, file, modelId, onProgress)
        } else {
            runMediaPipeBenchmark(context, file, onProgress)
        }
    }

    @OptIn(ExperimentalApi::class)
    private suspend fun runLiteRtBenchmark(
        context: Context,
        file: File,
        modelId: String,
        onProgress: ((BenchmarkProgress) -> Unit)?
    ): BenchmarkResult {
        val frameMonitor = kotlinx.coroutines.currentCoroutineContext()[BenchmarkFrames]!!
        val totalSteps = 1 + WARM_PROMPTS.size
        var currentStep = 0

        fun reportProgress(phase: String, detail: String = "") {
            currentStep++
            frameMonitor.phase(phase)
            onProgress?.invoke(BenchmarkProgress(phase, currentStep, totalSteps, detail))
        }

        reportProgress("Starting LiteRT Benchmark", "Initializing cold model load")

        val runtimeCacheDir = LiteRtRuntimeCache.prepare(context, modelId, InferenceBackend.LITERT_CPU, file.absolutePath)
        var coldLoadFtMs = 0.0
        val warmTtftList = mutableListOf<Double>()
        val decodeTokPerSecList = mutableListOf<Double>()
        val frameTimes = frameMonitor.cpu
        var maxRenderStall = 0L

        try {
            val coldStart = SystemClock.elapsedRealtime()

            var coldEngine: Engine? = null
            try {
                coldEngine = Engine(
                    EngineConfig(
                        modelPath = file.absolutePath,
                        backend = Backend.CPU(),
                        maxNumTokens = 2048,
                        cacheDir = runtimeCacheDir.absolutePath
                    )
                )
                coldEngine.initialize()
                val conv = coldEngine.createConversation(ConversationConfig())
                val adapter = LiteRtStreamAdapter(
                    requestId = "bench-cold",
                    onFirstToken = {
                        if (coldLoadFtMs == 0.0) {
                            coldLoadFtMs = (SystemClock.elapsedRealtime() - coldStart).toDouble()
                        }
                    }
                )
                conv.sendMessageAsync(Message.user(WARM_PROMPTS[0]), adapter)
                adapter.awaitDone()
                if (coldLoadFtMs == 0.0) {
                    coldLoadFtMs = (SystemClock.elapsedRealtime() - coldStart).toDouble()
                }
            } finally {
                try { coldEngine?.close() } catch (_: Throwable) {}
            }

            var warmEngine: Engine? = null
            try {
                warmEngine = Engine(
                    EngineConfig(
                        modelPath = file.absolutePath,
                        backend = Backend.CPU(),
                        maxNumTokens = 2048,
                        cacheDir = runtimeCacheDir.absolutePath
                    )
                )
                warmEngine.initialize()
                val conv = warmEngine.createConversation(ConversationConfig())

                for ((index, prompt) in WARM_PROMPTS.withIndex()) {
                    reportProgress("Benchmarking LiteRT (Warm ${index + 1}/${WARM_PROMPTS.size})", "Prompt: ${prompt.take(25)}...")
                    val startGen = SystemClock.elapsedRealtime()
                    var ttft = 0.0
                    var firstTokenTime = 0L
                    var lastTokenTime = startGen

                    val adapter = LiteRtStreamAdapter(
                        requestId = "bench-warm-$index",
                        onFirstToken = {
                            val now = SystemClock.elapsedRealtime()
                            ttft = (now - startGen).toDouble()
                            firstTokenTime = now
                            lastTokenTime = now
                        },
                        onDelta = { _ ->
                            val now = SystemClock.elapsedRealtime()
                            lastTokenTime = now
                        }
                    )

                    conv.sendMessageAsync(Message.user(prompt), adapter)
                    val snapshot = adapter.awaitDone()
                    val genEnd = SystemClock.elapsedRealtime()

                    if (ttft > 0.0) warmTtftList.add(ttft)
                    val decodeDuration = if (firstTokenTime > 0L) genEnd - firstTokenTime else genEnd - startGen
                    val tokens = if (adapter.tokenCount > 0) adapter.tokenCount else maxOf(1, Math.round(snapshot.answerText.length / 3.8).toInt())
                    if (decodeDuration > 0 && tokens > 0) {
                        val tokPerSec = (tokens.toDouble() * 1000.0) / decodeDuration.toDouble()
                        decodeTokPerSecList.add(tokPerSec)
                    }
                }
            } finally {
                try { warmEngine?.close() } catch (_: Throwable) {}
            }
        } finally {
            LiteRtRuntimeCache.release(context, runtimeCacheDir)
        }

        val warmTtftMedian = if (warmTtftList.isNotEmpty()) {
            val sorted = warmTtftList.sorted()
            sorted[sorted.size / 2]
        } else 0.0

        val decodeMedian = if (decodeTokPerSecList.isNotEmpty()) {
            val sorted = decodeTokPerSecList.sorted()
            sorted[sorted.size / 2]
        } else 0.0

        val frameP95 = if (frameTimes.isNotEmpty()) {
            val sorted = synchronized(frameTimes) { frameTimes.toList().sorted() }
            val p95Index = ((sorted.size - 1) * 0.95).toInt().coerceIn(0, sorted.size - 1)
            sorted[p95Index]
        } else 0.0

        return BenchmarkResult(
            qualifiesVulkan = false,
            summary = "LiteRT-LM benchmark completed on CPU (${String.format(Locale.US, "%.1f", decodeMedian)} t/s)",
            cpuWarmTtftMedianMs = warmTtftMedian,
            vulkanWarmTtftMedianMs = 0.0,
            warmTtftImprovementPercent = 0.0,
            cpuColdLoadFtMs = coldLoadFtMs,
            vulkanColdLoadFtMs = 0.0,
            coldRegressionPercent = 0.0,
            cpuDecodeTokPerSec = decodeMedian,
            vulkanDecodeTokPerSec = 0.0,
            decodeRegressionPercent = 0.0,
            cpuFrameP95Ms = frameP95,
            vulkanFrameP95Ms = 0.0,
            frameP95RegressionPercent = 0.0,
            maxRenderStallMs = synchronized(frameTimes) { (frameTimes.maxOrNull() ?: 0.0).toLong() },
            uiFramesMeasured = frameMonitor.measured()
        )
    }

    @Suppress("DEPRECATION")
    private suspend fun runMediaPipeBenchmark(
        context: Context,
        file: File,
        onProgress: ((BenchmarkProgress) -> Unit)?
    ): BenchmarkResult {
        val frameMonitor = kotlinx.coroutines.currentCoroutineContext()[BenchmarkFrames]!!
        val totalSteps = 1 + WARM_PROMPTS.size
        var currentStep = 0

        fun reportProgress(phase: String, detail: String = "") {
            currentStep++
            frameMonitor.phase(phase)
            onProgress?.invoke(BenchmarkProgress(phase, currentStep, totalSteps, detail))
        }

        reportProgress("Starting MediaPipe Benchmark", "Initializing cold model load")

        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(file.absolutePath)
            .setMaxTokens(2048)
            .setPreferredBackend(LlmInference.Backend.CPU)
            .build()

        val coldStart = SystemClock.elapsedRealtime()
        var coldLoadFtMs = 0.0

        var coldInference: LlmInference? = null
        try {
            coldInference = LlmInference.createFromOptions(context, options)
            val session = LlmInferenceSession.createFromOptions(
                coldInference,
                LlmInferenceSession.LlmInferenceSessionOptions.builder().build()
            )
            session.addQueryChunk(WARM_PROMPTS[0])
            val future = session.generateResponseAsync(
                ProgressListener<String> { _, _ ->
                    if (coldLoadFtMs == 0.0) {
                        coldLoadFtMs = (SystemClock.elapsedRealtime() - coldStart).toDouble()
                    }
                }
            )
            future.get()
            if (coldLoadFtMs == 0.0) {
                coldLoadFtMs = (SystemClock.elapsedRealtime() - coldStart).toDouble()
            }
            session.close()
        } finally {
            try { coldInference?.close() } catch (_: Throwable) {}
        }

        val warmTtftList = mutableListOf<Double>()
        val decodeTokPerSecList = mutableListOf<Double>()
        val frameTimes = frameMonitor.cpu
        var maxRenderStall = 0L

        var warmInference: LlmInference? = null
        try {
            warmInference = LlmInference.createFromOptions(context, options)
            for ((index, prompt) in WARM_PROMPTS.withIndex()) {
                reportProgress("Benchmarking MediaPipe (Warm ${index + 1}/${WARM_PROMPTS.size})", "Prompt: ${prompt.take(25)}...")
                val session = LlmInferenceSession.createFromOptions(
                    warmInference,
                    LlmInferenceSession.LlmInferenceSessionOptions.builder().build()
                )
                val startGen = SystemClock.elapsedRealtime()
                var ttft = 0.0
                var firstTokenTime = 0L
                var lastTokenTime = startGen
                var tokenCount = 0

                session.addQueryChunk(prompt)
                val future = session.generateResponseAsync(
                    ProgressListener<String> { partial, _ ->
                        if (partial.isNullOrEmpty()) return@ProgressListener
                        tokenCount++
                        val now = SystemClock.elapsedRealtime()
                        if (firstTokenTime == 0L) {
                            firstTokenTime = now
                            ttft = (now - startGen).toDouble()
                        }
                        lastTokenTime = now
                    }
                )
                val resp = future.get()
                val genEnd = SystemClock.elapsedRealtime()

                if (ttft > 0.0) warmTtftList.add(ttft)
                val decodeDuration = if (firstTokenTime > 0L) genEnd - firstTokenTime else genEnd - startGen
                val tokens = if (tokenCount > 0) tokenCount else maxOf(1, Math.round(resp.length / 3.8).toInt())
                if (decodeDuration > 0 && tokens > 0) {
                    val tokPerSec = (tokens.toDouble() * 1000.0) / decodeDuration.toDouble()
                    decodeTokPerSecList.add(tokPerSec)
                }
                session.close()
            }
        } finally {
            try { warmInference?.close() } catch (_: Throwable) {}
        }

        val warmTtftMedian = if (warmTtftList.isNotEmpty()) {
            val sorted = warmTtftList.sorted()
            sorted[sorted.size / 2]
        } else 0.0

        val decodeMedian = if (decodeTokPerSecList.isNotEmpty()) {
            val sorted = decodeTokPerSecList.sorted()
            sorted[sorted.size / 2]
        } else 0.0

        val frameP95 = if (frameTimes.isNotEmpty()) {
            val sorted = synchronized(frameTimes) { frameTimes.toList().sorted() }
            val p95Index = ((sorted.size - 1) * 0.95).toInt().coerceIn(0, sorted.size - 1)
            sorted[p95Index]
        } else 0.0

        return BenchmarkResult(
            qualifiesVulkan = false,
            summary = "MediaPipe benchmark completed on CPU (${String.format(Locale.US, "%.1f", decodeMedian)} t/s)",
            cpuWarmTtftMedianMs = warmTtftMedian,
            vulkanWarmTtftMedianMs = 0.0,
            warmTtftImprovementPercent = 0.0,
            cpuColdLoadFtMs = coldLoadFtMs,
            vulkanColdLoadFtMs = 0.0,
            coldRegressionPercent = 0.0,
            cpuDecodeTokPerSec = decodeMedian,
            vulkanDecodeTokPerSec = 0.0,
            decodeRegressionPercent = 0.0,
            cpuFrameP95Ms = frameP95,
            vulkanFrameP95Ms = 0.0,
            frameP95RegressionPercent = 0.0,
            maxRenderStallMs = synchronized(frameTimes) { (frameTimes.maxOrNull() ?: 0.0).toLong() },
            uiFramesMeasured = frameMonitor.measured()
        )
    }
}
