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

    internal fun key(id: String, file: File, tokens: Int, threads: Int): String {
        val identity = "speed-v1|$id|${file.canonicalPath}|${file.length()}|${file.lastModified()}|$tokens|$threads|${Build.FINGERPRINT}|${Build.HARDWARE}|${BuildConfig.LITERT_RUNTIME_VERSION}|${BuildConfig.NATIVE_BUILD_ID}"
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /**
     * Artifact/device identity without context tokens or CPU threads. A GPU failure must veto every
     * receipt for the file: threads change with power-save/low-RAM and Vision may retry at a smaller
     * context, so a receipt-scoped flip can miss the receipt the next Auto load reads.
     */
    private fun gpuVetoKey(id: String, file: File): String {
        val identity = "gpu-veto-v1|$id|${file.canonicalPath}|${file.length()}|${file.lastModified()}|${Build.FINGERPRINT}|${Build.HARDWARE}|${BuildConfig.LITERT_RUNTIME_VERSION}|${BuildConfig.NATIVE_BUILD_ID}"
        return "litert_gpu_veto_" + MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    internal fun selectLiteRtBackend(receiptBackend: String?, gpuVetoed: Boolean, gpuProbeBlocked: Boolean): InferenceBackend =
        selectLiteRtBackend(
            receiptBackend = receiptBackend,
            gpuVetoed = gpuVetoed,
            gpuProbeBlocked = gpuProbeBlocked,
            npuSupported = false,
            npuBlocked = true
        )

    internal fun selectLiteRtBackend(
        receiptBackend: String?,
        gpuVetoed: Boolean,
        gpuProbeBlocked: Boolean,
        npuSupported: Boolean,
        npuBlocked: Boolean
    ): InferenceBackend = when {
        receiptBackend == "NPU" && npuSupported && !npuBlocked -> InferenceBackend.LITERT_NPU
        receiptBackend == "GPU" && !gpuVetoed && !gpuProbeBlocked -> InferenceBackend.LITERT_GPU
        receiptBackend == null && npuSupported && !npuBlocked -> InferenceBackend.LITERT_NPU
        else -> InferenceBackend.LITERT_CPU
    }

    fun liteRtBackend(
        context: Context,
        id: String,
        path: String,
        tokens: Int?,
        npuSupportedOverride: Boolean? = null
    ): InferenceBackend {
        val file = File(path)
        val receipt = key(id, file, liteRtContextTokens(tokens, null), RuntimeExecutionPolicy.resolve(context, id).cpuThreads)
        val prefs = context.getSharedPreferences("runtime_speed", Context.MODE_PRIVATE)
        val npuSupported = npuSupportedOverride ?: NpuCapabilityDetector.detect(context, id).litertLm.supported
        val npuBlocked = InitCrashGuard.isNpuBlocked(context, id)
        return selectLiteRtBackend(
            receiptBackend = prefs.getString("litert_$receipt", null),
            gpuVetoed = prefs.getBoolean(gpuVetoKey(id, file), false),
            gpuProbeBlocked = InitCrashGuard.isModelBlocked(context, "$id:speed:gpu:$receipt"),
            npuSupported = npuSupported,
            npuBlocked = npuBlocked
        )
    }

    /** Vetoes GPU for every receipt (any context size or thread count) of this model file; only an explicit re-tune lifts it. */
    fun recordGpuFailure(context: Context, id: String, path: String) {
        context.getSharedPreferences("runtime_speed", Context.MODE_PRIVATE).edit()
            .putBoolean(gpuVetoKey(id, File(path)), true).commit()
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
                val npuSnapshot = NpuCapabilityDetector.detect(context, model.id)
                if (npuSnapshot.litertLm.supported && !InitCrashGuard.isNpuBlocked(context, model.id)) {
                    ensureActive()
                    progress("Validating and timing ${npuSnapshot.primaryBadgeLabel}…")
                    try {
                        val npuSample = probeLiteRt(context, model.id, file, best.threads, tokens, backendOverride = InferenceBackend.LITERT_NPU)
                        if (qualifiesGpu(best.medianMs, npuSample.medianMs, best.answers, npuSample.answers)) {
                            backend = "NPU"
                            gpuNote = " (${npuSnapshot.primaryBadgeLabel} ${npuSample.medianMs.toLong()} ms)"
                        } else {
                            gpuNote = "; ${npuSnapshot.primaryBadgeLabel} tested (${npuSample.medianMs.toLong()} ms)"
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        gpuNote = "; ${npuSnapshot.primaryBadgeLabel} unavailable (${failure.message?.take(80)})"
                    }
                }
                if (backend != "NPU") {
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
                            else gpuNote += "; CPU retained: GPU was slower or its greedy probe outputs differed"
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { gpuNote += "; CPU retained: GPU probe failed (${failure.message?.take(120)})" }
                    } else gpuNote += "; GPU probe previously crashed on this device"
                }
            }
            ensureActive()
            val prefs = context.getSharedPreferences("runtime_speed", Context.MODE_PRIVATE)
            val editor = prefs.edit().putInt(RuntimeExecutionPolicy.threadPreferenceKey(model.id), best.threads)
            // A fresh explicit measurement supersedes an earlier GPU-failure veto for this file.
            if (format == ModelFormat.LiteRtLm) editor.putString("litert_$receipt", backend).remove(gpuVetoKey(model.id, file))
            check(editor.commit()) { "Could not save speed measurements" }
            "CPU: ${best.threads} threads (${best.medianMs.toLong()} ms median first token). " +
                (if (format == ModelFormat.LiteRtLm) "Auto selects $backend for this model/configuration$gpuNote."
                else "GGUF Auto keeps its existing CPU/GPU validation policy; rerun its CPU/GPU benchmark after changing threads.")
        }
    }

    private suspend fun probeLiteRt(
        context: Context,
        guardId: String,
        file: File,
        threads: Int,
        tokens: Int,
        gpu: Boolean = false,
        backendOverride: InferenceBackend? = null
    ): Sample {
        val kind = backendOverride ?: if (gpu) InferenceBackend.LITERT_GPU else InferenceBackend.LITERT_CPU
        val backend = when (kind) {
            InferenceBackend.LITERT_NPU -> Backend.NPU(context.applicationInfo.nativeLibraryDir.orEmpty())
            InferenceBackend.LITERT_GPU -> Backend.GPU()
            InferenceBackend.LITERT_CPU, InferenceBackend.MEDIAPIPE -> Backend.CPU(threads)
        }
        val cache = LiteRtRuntimeCache.prepare(context, guardId, kind, file.absolutePath)
        val times = mutableListOf<Double>()
        val answers = mutableListOf<String>()
        if (kind == InferenceBackend.LITERT_NPU) {
            InitCrashGuard.markNpuInitStarted(context, guardId)
        } else {
            InitCrashGuard.markInitStarted(context, guardId, if (gpu) InitCrashGuard.BACKEND_GPU else InitCrashGuard.BACKEND_CPU)
        }
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
            if (kind == InferenceBackend.LITERT_NPU) {
                InitCrashGuard.markNpuInitSucceeded(context, guardId)
            } else {
                InitCrashGuard.markInitSucceeded(context, guardId)
            }
            return Sample(threads, times.sorted()[times.size / 2], answers)
        } finally {
            if (kind == InferenceBackend.LITERT_NPU) {
                InitCrashGuard.markNpuInitFailed(context, guardId)
            } else {
                InitCrashGuard.markInitFailed(context, guardId)
            }
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
