package com.shounak.localmeshai.ai

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.google.ai.edge.litertlm.*
import com.shounak.localmeshai.BuildConfig
import com.shounak.localmeshai.models.ModelInfo
import com.shounak.localmeshai.models.ModelFormat
import com.shounak.localmeshai.utils.*
import kotlinx.coroutines.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/** Explicit, sequential measurements; Auto consumes only a receipt for this artifact/device/config. */
internal object RuntimeSpeedTuner {
    internal fun liteRtContextTokens(customTokens: Int?, modelTokens: Int?): Int = (customTokens ?: modelTokens ?: 4096).coerceIn(2048, 8192)
    private val prompts = listOf("Say hello.", "What is 2 + 2?", "Name a color.")
    private data class Sample(val threads: Int, val medianMs: Double, val answers: List<String>)

    internal fun fastest(samples: Map<Int, List<Double>>): Int? = samples.filterValues { values ->
        values.isNotEmpty() && values.all { it.isFinite() && it > 0 }
    }.minByOrNull { (_, values) -> values.sorted()[values.size / 2] }?.key

    internal fun qualifiesGpu(cpuMs: Double, gpuMs: Double, cpuAnswers: List<String>, gpuAnswers: List<String>): Boolean =
        cpuMs.isFinite() && gpuMs.isFinite() && cpuMs > 0 && gpuMs > 0 &&
            gpuAnswers.isNotEmpty() && gpuAnswers.all { it.isNotBlank() } && gpuAnswers == cpuAnswers && gpuMs < cpuMs * 0.90

    fun supports(model: ModelInfo?): Boolean = model != null && model.loadRestriction == null && model.localPath?.let { path ->
        val file = File(path)
        file.isFile && ModelFormat.fromFileName(file.name) in setOf(ModelFormat.GGUF, ModelFormat.LiteRtLm)
    } == true

    private fun key(id: String, file: File, tokens: Int, threads: Int): String {
        val identity = "speed-v1|$id|${file.canonicalPath}|${file.length()}|${file.lastModified()}|$tokens|$threads|${Build.FINGERPRINT}|${Build.HARDWARE}|${BuildConfig.LITERT_RUNTIME_VERSION}|${BuildConfig.NATIVE_BUILD_ID}"
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun liteRtBackend(context: Context, id: String, path: String, tokens: Int?): InferenceBackend {
        val receipt = key(id, File(path), liteRtContextTokens(tokens, null), RuntimeExecutionPolicy.resolve(context, id).cpuThreads)
        val prefs = context.getSharedPreferences("runtime_speed", Context.MODE_PRIVATE)
        return if (prefs.getString("litert_$receipt", null) == "GPU" &&
            !InitCrashGuard.isModelBlocked(context, "$id:speed:gpu:$receipt")) InferenceBackend.LITERT_GPU else InferenceBackend.LITERT_CPU
    }

    fun recordGpuFailure(context: Context, id: String, path: String, tokens: Int?) {
        val receipt = key(id, File(path), liteRtContextTokens(tokens, null), RuntimeExecutionPolicy.resolve(context, id).cpuThreads)
        context.getSharedPreferences("runtime_speed", Context.MODE_PRIVATE).edit().putString("litert_$receipt", "CPU").commit()
    }

    suspend fun run(context: Context, model: ModelInfo, progress: suspend (String) -> Unit): String = withContext(Dispatchers.IO) {
            require(supports(model)) { "Tune speed supports app-managed GGUF and LiteRT-LM files. Linked imports can use their bundle benchmark." }
            check(!InitCrashGuard.isModelBlocked(context, model.id)) { InitCrashGuard.blockedModelMessage() }
            val file = model.localPath?.let(::File)?.takeIf { it.isFile }
                ?: error("Tune speed needs an app-managed model file. Use the model's bundle benchmark for linked storage imports.")
            val format = ModelFormat.fromFileName(file.name)
            require(format == ModelFormat.GGUF || format == ModelFormat.LiteRtLm) { "Tune speed currently supports GGUF and LiteRT-LM models." }
            val policy = RuntimeExecutionPolicy.resolve(context, model.id)
            check(!policy.isPowerSaveMode) { "Turn off power saving before tuning speed." }
            val maximum = if (policy.isLowRam) 2 else minOf(Runtime.getRuntime().availableProcessors(), 6)
            val candidates = listOf(2, 4, 6).filter { it <= maximum }.ifEmpty { listOf(1) }
            val requestedContext = com.shounak.localmeshai.features.WorkspacePreferences.get(context).profile(model.id).contextTokens
            val tokens = if (format == ModelFormat.LiteRtLm) liteRtContextTokens(requestedContext, model.effectiveContextWindowTokens)
                else GgufExecutionProfile.resolve(context, model.name, model.size, requestedContext, "CPU").contextTokens
        BenchmarkAdmission.run(model.id) {
            val samples = candidates.map { threads ->
                ensureActive()
                progress("Testing CPU with $threads threads…")
                if (format == ModelFormat.LiteRtLm) probeLiteRt(context, model.id, file, threads, tokens, false)
                else probeGguf(context, model, file, threads, tokens)
            }
            val bestThreads = checkNotNull(fastest(samples.associate { it.threads to listOf(it.medianMs) })) { "No valid CPU measurements" }
            val best = samples.first { it.threads == bestThreads }
            var backend = "CPU"
            val receipt = key(model.id, file, tokens, best.threads)
            var gpuNote = ""
            if (format == ModelFormat.LiteRtLm) {
                ensureActive()
                progress("Validating and timing LiteRT GPU…")
                val gpuId = "${model.id}:speed:gpu:$receipt"
                if (!InitCrashGuard.isModelBlocked(context, gpuId)) {
                    try {
                        val admission = DeviceUtils.canInitializeLiteRtLm(context, model.id, model.name, model.size,
                            isVision = false, fileName = file.name, backendLabel = "LiteRT-LM", modelFilePath = file.absolutePath)
                        check(admission.first) { admission.second }
                        val gpu = probeLiteRt(context, gpuId, file, best.threads, tokens, true)
                        if (qualifiesGpu(best.medianMs, gpu.medianMs, best.answers, gpu.answers)) backend = "GPU"
                        else gpuNote = "; CPU retained: GPU was slower or its greedy probe outputs differed"
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { gpuNote = "; CPU retained: GPU probe failed (${failure.message?.take(120)})" }
                } else gpuNote = "; GPU probe previously crashed on this device"
            }
            ensureActive()
            val prefs = context.getSharedPreferences("runtime_speed", Context.MODE_PRIVATE)
            val editor = prefs.edit().putInt(RuntimeExecutionPolicy.threadPreferenceKey(model.id), best.threads)
            if (format == ModelFormat.LiteRtLm) editor.putString("litert_$receipt", backend)
            check(editor.commit()) { "Could not save speed measurements" }
            "CPU: ${best.threads} threads (${best.medianMs.toLong()} ms median first token). " +
                (if (format == ModelFormat.LiteRtLm) "Auto selects $backend for this model/configuration$gpuNote."
                else "GGUF Auto keeps its existing CPU/GPU validation policy; rerun its CPU/GPU benchmark after changing threads.")
        }
    }

    private suspend fun probeLiteRt(context: Context, guardId: String, file: File, threads: Int, tokens: Int, gpu: Boolean): Sample {
        val backend = if (gpu) Backend.GPU() else Backend.CPU(threads)
        val kind = if (gpu) InferenceBackend.LITERT_GPU else InferenceBackend.LITERT_CPU
        val cache = LiteRtRuntimeCache.prepare(context, guardId, kind, file.absolutePath)
        val times = mutableListOf<Double>()
        val answers = mutableListOf<String>()
        InitCrashGuard.markInitStarted(context, guardId)
        try {
            Engine(EngineConfig(modelPath = file.absolutePath, backend = backend, maxNumTokens = tokens, cacheDir = cache.absolutePath)).use { engine ->
                engine.initialize()
                RuntimeWarmup.liteRt(engine) { }
                for ((index, prompt) in prompts.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    engine.createConversation(ConversationConfig(maxOutputToken = 4,
                        samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
                        thinkingConfig = ThinkingConfig(enableThinking = false), extraContext = mapOf("enable_thinking" to false))).use { conversation ->
                        val start = SystemClock.elapsedRealtime()
                        val first = AtomicLong(0)
                        val adapter = LiteRtStreamAdapter("speed-$index", onFirstToken = { first.compareAndSet(0, SystemClock.elapsedRealtime()) })
                        val deadline = RuntimeWarmup.cancellationDeadline { runCatching { conversation.cancelProcess() } }
                        try {
                            conversation.sendMessageAsync(Message.user(prompt), adapter)
                            // Never close a conversation while its native callback is still using it.
                            val response = withContext(NonCancellable) { adapter.awaitDone() }
                            check(first.get() > 0 && response.answerText.isNotBlank()) { "Probe produced no answer token" }
                            times += (first.get() - start).coerceAtLeast(1).toDouble()
                            answers += response.answerText
                        } finally { deadline.close() }
                    }
                }
            }
            InitCrashGuard.markInitSucceeded(context, guardId)
            return Sample(threads, times.sorted()[times.size / 2], answers)
        } finally {
            InitCrashGuard.markInitFailed(context, guardId)
            LiteRtRuntimeCache.release(context, cache)
        }
    }

    private suspend fun probeGguf(context: Context, model: ModelInfo, file: File, threads: Int, tokens: Int): Sample {
        val engine = LlamaCppEngine(context)
        val times = mutableListOf<Double>()
        try {
            if (model.bundle != null) engine.initializeBundle(context, model.bundle, threads = threads, prefillThreads = threads, contextWindow = tokens, backend = LlamaBackend.CPU, nGpuLayers = 0)
            else engine.initialize(file.absolutePath, threads = threads, prefillThreads = threads, contextWindow = tokens, backend = LlamaBackend.CPU, nGpuLayers = 0)
            check(engine.warmUp()) { "GGUF warm-up failed" }
            for (prompt in prompts) {
                currentCoroutineContext().ensureActive()
                engine.resetAfterValidation()
                val start = SystemClock.elapsedRealtime()
                var first = 0L
                engine.generateStream(prompt, samplingParams = LlamaSamplingParams(maxTokens = 4, temperature = 0f),
                    enableThinking = false, onFirstToken = { first = SystemClock.elapsedRealtime() }) { }
                check(first > 0) { "Probe produced no token" }
                times += (first - start).coerceAtLeast(1).toDouble()
            }
            return Sample(threads, times.sorted()[times.size / 2], emptyList())
        } finally { engine.close() }
    }
}
