@file:Suppress("DEPRECATION")
package com.shounak.localmeshai.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Message
import com.shounak.localmeshai.utils.ThinkingModeConfig
import com.shounak.localmeshai.utils.ThinkingTextUtils
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.AudioModelOptions
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.ProgressListener
import com.shounak.localmeshai.ai.ShutdownResult
import com.shounak.localmeshai.utils.AppCacheCleaner
import com.shounak.localmeshai.utils.AppSettings
import com.shounak.localmeshai.utils.DeviceUtils
import com.shounak.localmeshai.utils.InferenceBackend
import com.shounak.localmeshai.utils.StreamingResponsePublisher
import com.shounak.localmeshai.utils.InferenceTiming
import com.shounak.localmeshai.utils.InitCrashGuard
import com.shounak.localmeshai.utils.LiteRtRuntimeCache
import com.shounak.localmeshai.utils.ModelOutputSanitizer
import com.shounak.localmeshai.utils.ModelDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CancellationException as FutureCancellationException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

data class VisionLabel(
    val label: String,
    val score: Float
)

class VisionInferenceManager(private val context: Context) {
    private var mediaPipeInference: LlmInference? = null
    private var liteRtEngine: Engine? = null
    private var liteRtConversation: Conversation? = null
    private var liteRtCacheDir: File? = null
    @Volatile private var runtime = RuntimeKind.None
    private var activeModelLease: AutoCloseable? = null
    val isReady: Boolean get() = when (runtime) {
        RuntimeKind.LiteRtLm -> liteRtConversation != null
        RuntimeKind.MediaPipeVision -> mediaPipeInference != null
        RuntimeKind.None -> false
    }
    @Volatile private var activeMediaPipeSession: LlmInferenceSession? = null
    @Volatile private var activeMediaPipeFuture: Future<String>? = null
    @Volatile private var activeMediaPipeCallbackDone: CountDownLatch? = null
    @Volatile private var stopRequested = false
    @Volatile private var activeLiteRtAdapter: LiteRtStreamAdapter? = null
    @Volatile private var lastLiteRtSnapshot: StructuredResponseSnapshot? = null
    var activeBackendDisplayName: String = ""
        private set
    /**
     * Set to true by [close] before it destroys any native resource.
     * [streamMediaPipeVision]'s finally block checks this flag before
     * calling session.close() — if [close] has already claimed and closed
     * the session, the finally block skips it, preventing a double-free
     * native crash (SIGSEGV/SIGABRT) when the user taps Stop then
     * immediately switches tabs or loads a new model.
     */
    @Volatile private var isBeingClosed = false
    @Volatile private var modelSupportsAudioInput = false
    @Volatile private var isDefaultThinkingModel = false
    @Volatile private var supportsThinkingMode = false

    companion object {
        private const val TAG = "VisionInferenceManager"
        private const val DEFAULT_IMAGE_PROMPT = "Describe the image"
        private const val MAX_RESPONSE_TOKENS = 2048
        private const val DEFAULT_VISION_TEMPERATURE = 0.7f

        private val THINK_BLOCK_REGEX =
            Regex("""<\s*(?:think|thought|reasoning)\s*>[\s\S]*?<\s*/\s*(?:think|thought|reasoning)\s*>""", RegexOption.IGNORE_CASE)
        private val THINK_UNCLOSED_REGEX =
            Regex("""<\s*(?:think|thought|reasoning)\s*>[\s\S]*$""", RegexOption.IGNORE_CASE)
        private val THINK_PREFILLED_CLOSE_REGEX =
            Regex("""^[\s\S]*?<\s*/\s*(?:think|thought|reasoning)\s*>""", RegexOption.IGNORE_CASE)
    }

    suspend fun initialize(
        modelPath: String,
        modelId: String = "",
        modelName: String = "",
        modelSize: String = "",
        supportsAudioInput: Boolean = false,
        supportsThinkingMode: Boolean = false,
        allowUnsafeOverride: Boolean = false,
        contextWindowTokens: Int? = null
    ) {
        val file = File(modelPath)
        if (!file.exists()) {
            throw IllegalArgumentException("Image model file not found.")
        }
        if (file.extension.equals("task", ignoreCase = true) && !ModelDownloader.isLikelyTaskBundle(file)) {
            throw IllegalArgumentException("Selected .task file does not look like a MediaPipe or LiteRT task bundle. Download a verified Android multimodal .task model from the Models tab.")
        }

        val effectiveId = modelId.ifBlank { file.nameWithoutExtension }
        close()
        com.shounak.localmeshai.data.StartupCoordinator.getInstance(context).awaitCrashGuardChecked()
        val modelLease = com.shounak.localmeshai.utils.ModelArtifactStorage.leaseArtifact(file)
        try {
            AppCacheCleaner.clearAppCache(context)
            this.supportsThinkingMode = supportsThinkingMode
            isDefaultThinkingModel = ThinkingModeConfig.isThinkingSupported(
                modelPath = modelPath,
                effectiveId = effectiveId,
                modelName = modelName,
                supportsThinkingFlag = supportsThinkingMode
            )
        val isGemmaAudio = modelId.contains("gemma4", ignoreCase = true) ||
            modelId.contains("gemma3n", ignoreCase = true) ||
            modelName.contains("gemma 4", ignoreCase = true) ||
            modelName.contains("gemma 3n", ignoreCase = true) ||
            file.name.contains("gemma-4", ignoreCase = true) ||
            file.name.contains("gemma-3n", ignoreCase = true) ||
            file.name.contains("gemma4", ignoreCase = true) ||
            file.name.contains("gemma3n", ignoreCase = true)

        val audioInputAvailable = (supportsAudioInput || isGemmaAudio) && (
            file.extension.equals("litertlm", ignoreCase = true) ||
                file.extension.equals("task", ignoreCase = true) ||
                file.containsAudioModelAssets()
            )
        modelSupportsAudioInput = audioInputAvailable
        isBeingClosed = false   // Reset after close() so initialize() starts clean
        when {
            file.extension.equals("litertlm", ignoreCase = true) -> {
                val effectiveId = modelId.ifBlank { file.nameWithoutExtension }
                val appSettings = AppSettings.getInstance(context)
                val pref = appSettings.settings.value.llamaBackendPreference.uppercase()
                val inferenceBackend = when (pref) {
                    "CPU" -> InferenceBackend.LITERT_CPU
                    "VULKAN", "GPU" -> InferenceBackend.LITERT_GPU
                    else -> {
                        if (allowUnsafeOverride) {
                            InferenceBackend.LITERT_GPU
                        } else {
                            DeviceUtils.selectBackendForModelFile(context, file.name)
                        }
                    }
                }
                val canRetryOnCpu = DeviceUtils.canUseGemma4LiteRtCpuFallback(
                    context = context,
                    modelId = effectiveId,
                    modelName = modelName.ifBlank { file.nameWithoutExtension },
                    modelSize = modelSize,
                    fileName = file.name,
                    isMultimodalLiteRt = true
                )
                if (InitCrashGuard.isModelBlocked(context, effectiveId) && canRetryOnCpu) {
                    InitCrashGuard.unblockModel(context, effectiveId)
                }
                if (InitCrashGuard.isModelBlocked(context, effectiveId)) {
                    throw IllegalStateException(InitCrashGuard.blockedModelMessage())
                }
                if (!allowUnsafeOverride && inferenceBackend != InferenceBackend.LITERT_CPU) {
                    val (allowed, reason) = DeviceUtils.canInitializeLiteRtLm(
                        context = context,
                        modelId = effectiveId,
                        modelName = modelName.ifBlank { file.nameWithoutExtension },
                        modelSize = modelSize,
                        isVision = true,
                        fileName = file.name,
                        backendLabel = "LiteRT-LM multimodal",
                        supportsAudioInput = audioInputAvailable
                    )
                    if (!allowed) {
                        throw IllegalStateException(reason)
                    }
                }
                initializeLiteRtLm(
                    modelPath = modelPath,
                    modelId = effectiveId,
                    inferenceBackend = inferenceBackend,
                    supportsAudioInput = audioInputAvailable,
                    contextWindowTokens = contextWindowTokens
                )
                activeBackendDisplayName = if (inferenceBackend == InferenceBackend.LITERT_GPU) "LiteRT-LM GPU" else "LiteRT-LM CPU"
            }
            file.extension.equals("task", ignoreCase = true) -> {
                initializeMediaPipeVision(modelPath, audioInputAvailable, contextWindowTokens)
                activeBackendDisplayName = "MediaPipe CPU"
            }
            else -> {
                throw IllegalArgumentException("Unsupported vision model format (${file.extension}). Solus multimodal models require LiteRT (.litertlm) or MediaPipe (.task) bundles.")
            }
        }
        activeModelLease = modelLease
        } catch (t: Throwable) {
            modelLease.close()
            throw t
        }
    }

    /**
     * Streaming ask. Emits partial text to [onUpdate] as the model produces it.
     * Returns a typed [InferenceExecutionResult] with metrics and terminal status.
     */
    suspend fun askStreaming(
        bitmap: Bitmap?,
        question: String,
        audioFile: File? = null,
        audioBytes: ByteArray? = null,
        thinkingMode: Boolean = false,
        preparedImageFile: File? = null,
        requestId: String = "",
        sessionId: String = "",
        timing: InferenceTiming? = null,
        isRetry: Boolean = false,
        onProgress: ((RequestProgress) -> Unit)? = null,
        onUpdate: (String) -> Unit
    ): InferenceExecutionResult = withContext(Dispatchers.IO) {
        stopRequested = false
        lastLiteRtSnapshot = null
        val prompt = question.trim()
        val responseController = InferenceResponseController(
            thinkingMode = thinkingMode,
            rawUserText = prompt,
            requestId = requestId,
            initialAttemptId = 1
        )
        val publisher = StreamingResponsePublisher(
            scope = this,
            transform = { rawSnapshot -> responseController.formatSnapshot(rawSnapshot) },
            finalTransform = { rawSnapshot -> responseController.formatSnapshot(rawSnapshot) },
            publish = onUpdate
        )
        val effectiveOnUpdate: (String) -> Unit = { delta ->
            if (delta.isNotEmpty()) {
                responseController.appendDelta(delta, reqId = requestId, attemptId = 1)
                publisher.appendDelta(delta)
            }
        }

        onProgress?.invoke(RequestProgress.ProcessingPrompt)

        var lastGeneratedTokens: Int? = null
        try {
            val rawResponse = when (runtime) {
                RuntimeKind.LiteRtLm -> streamLiteRtLmVision(
                    bitmap = bitmap,
                    prompt = prompt,
                    audioFile = audioFile,
                    audioBytes = audioBytes,
                    thinkingMode = thinkingMode,
                    preparedImageFile = preparedImageFile,
                    requestId = requestId,
                    timing = timing,
                    onProgress = onProgress,
                    onTokensGenerated = { lastGeneratedTokens = it },
                    responseSnapshot = publisher::rawText,
                    onUpdate = effectiveOnUpdate
                )
                RuntimeKind.MediaPipeVision -> streamMediaPipeVision(
                    bitmap = bitmap,
                    prompt = prompt,
                    audioFile = audioFile,
                    audioBytes = audioBytes,
                    timing = timing,
                    onProgress = onProgress,
                    onTokensGenerated = { lastGeneratedTokens = it },
                    responseSnapshot = publisher::rawText,
                    onUpdate = effectiveOnUpdate
                )
                RuntimeKind.None -> throw IllegalStateException("Choose and initialise a multimodal model first.")
            }
            responseController.finish()
            val rawReasoning = if (runtime == RuntimeKind.LiteRtLm) {
                lastLiteRtSnapshot?.reasoningText.orEmpty().ifBlank { responseController.getReasoningText() }
            } else {
                responseController.getReasoningText()
            }
            val cleanResponse = if (runtime == RuntimeKind.LiteRtLm) {
                ModelOutputSanitizer.clean(rawResponse)
            } else {
                responseController.getFinalAnswer()
            }
            val finalized = ResponseFinalizer.finalize(
                requestId = requestId,
                attemptId = 1,
                rawAnswer = cleanResponse,
                rawReasoning = rawReasoning,
                isStopped = stopRequested,
                acceptedAnswerFallback = responseController.acceptedAnswerText(),
                thinkingMode = thinkingMode
            )
            val finalFormatted = responseController.formatFinal(finalized.answerText, finalized.reasoningText)
            publisher.finishExplicit(finalFormatted)
            timing?.endGeneration()
            val totalTokens = lastGeneratedTokens ?: maxOf(1, Math.round((finalized.answerText.length + rawReasoning.length) / 3.8).toInt())
            val metrics = timing?.buildMetrics(
                requestId = requestId,
                sessionId = sessionId,
                attempt = 1,
                generatedTokens = totalTokens,
                isTokenCountEstimated = true
            )
            finalized.toExecutionResult(metrics)
        } catch (exception: kotlinx.coroutines.CancellationException) {
            responseController.finish()
            val acceptedAnswer = responseController.getFinalAnswer()
            val rawReasoning = responseController.getReasoningText()
            timing?.endGeneration()
            val finalized = ResponseFinalizer.finalize(
                requestId = requestId,
                attemptId = 1,
                rawAnswer = acceptedAnswer,
                rawReasoning = rawReasoning,
                isStopped = true,
                acceptedAnswerFallback = responseController.acceptedAnswerText(),
                thinkingMode = thinkingMode
            )
            val finalFormatted = responseController.formatFinal(finalized.answerText, finalized.reasoningText)
            publisher.finishExplicit(finalFormatted)
            val partialTokens = lastGeneratedTokens ?: maxOf(1, Math.round(finalFormatted.length / 3.8).toInt())
            val metrics = timing?.buildMetrics(
                requestId = requestId,
                sessionId = sessionId,
                attempt = 1,
                generatedTokens = partialTokens,
                isTokenCountEstimated = true
            )
            finalized.toExecutionResult(metrics)
        } catch (t: Throwable) {
            responseController.finish()
            val acceptedAnswer = responseController.getFinalAnswer()
            val rawReasoning = responseController.getReasoningText()
            timing?.endGeneration()
            val finalized = ResponseFinalizer.finalize(
                requestId = requestId,
                attemptId = 1,
                rawAnswer = acceptedAnswer,
                rawReasoning = rawReasoning,
                isStopped = stopRequested,
                sdkError = if (stopRequested) null else t,
                acceptedAnswerFallback = responseController.acceptedAnswerText(),
                thinkingMode = thinkingMode
            )
            val finalFormatted = responseController.formatFinal(finalized.answerText, finalized.reasoningText)
            publisher.finishExplicit(finalFormatted)
            val partialTokens = lastGeneratedTokens ?: maxOf(1, Math.round(finalFormatted.length / 3.8).toInt())
            val metrics = timing?.buildMetrics(
                requestId = requestId,
                sessionId = sessionId,
                attempt = 1,
                generatedTokens = partialTokens,
                isTokenCountEstimated = true
            )
            finalized.toExecutionResult(metrics)
        }
    }

    suspend fun classify(bitmap: Bitmap): List<VisionLabel> = withContext(Dispatchers.IO) {
        emptyList()
    }

    fun close(): ShutdownResult {
        val wasMediaPipeVision = runtime == RuntimeKind.MediaPipeVision
        isBeingClosed = true
        cancelGeneration()
        var closeError: Throwable? = null

        val sessionToClose = activeMediaPipeSession
        val callbackDone = activeMediaPipeCallbackDone
        activeMediaPipeSession = null    // null first so finally block skips it
        activeMediaPipeCallbackDone = null
        if (!wasMediaPipeVision && sessionToClose != null && waitForCallbackToReturn(callbackDone)) {
            try { sessionToClose.close() } catch (t: Throwable) { if (closeError == null) closeError = t }
        } else if (wasMediaPipeVision && sessionToClose != null) {
            Log.w(TAG, "Skipping MediaPipe vision session.close(); native close is unstable on this device")
        }
        if (wasMediaPipeVision) {
            Log.w(TAG, "Skipping MediaPipe vision inference.close(); native close is unstable on this device")
        } else {
            try { mediaPipeInference?.close() } catch (t: Throwable) { if (closeError == null) closeError = t }
        }
        mediaPipeInference = null

        try { liteRtConversation?.close() } catch (t: Throwable) { if (closeError == null) closeError = t }
        liteRtConversation = null
        try { liteRtEngine?.close() } catch (t: Throwable) { if (closeError == null) closeError = t }
        liteRtEngine = null
        LiteRtRuntimeCache.release(context, liteRtCacheDir)
        liteRtCacheDir = null

        // Release model file lease strictly AFTER native engines and sessions have cleanly closed
        try { activeModelLease?.close() } catch (t: Throwable) { if (closeError == null) closeError = t }
        activeModelLease = null

        runtime = RuntimeKind.None
        activeBackendDisplayName = ""
        isDefaultThinkingModel = false
        supportsThinkingMode = false
        isBeingClosed = false
        AppCacheCleaner.clearAppCache(context)

        return if (closeError != null) {
            ShutdownResult.Failed(closeError)
        } else {
            ShutdownResult.Success
        }
    }

    fun cancelGeneration() {
        stopRequested = true
        activeLiteRtAdapter?.cancel(liteRtConversation)
        runCatching { liteRtConversation?.cancelProcess() }
        val session = activeMediaPipeSession
        if (session != null) {
            runCatching { session.cancelGenerateResponseAsync() }
        }
        val future = activeMediaPipeFuture
        activeMediaPipeFuture = null
        runCatching { future?.cancel(true) }
        activeMediaPipeCallbackDone?.countDown()
    }

    fun resetConversation(
        thinkingMode: Boolean = false,
        initialMessages: List<Message>? = null
    ) {
        if (runtime != RuntimeKind.LiteRtLm) return
        val engine = liteRtEngine ?: return
        runCatching { liteRtConversation?.close() }.onFailure { t ->
            Log.w(TAG, "LiteRT-LM vision conversation close during reset failed", t)
        }
        val extraCtx = if (isDefaultThinkingModel) {
            ThinkingModeConfig.liteRtExtraContext(
                isDefaultThinkingModel = true,
                thinkingMode = thinkingMode
            )
        } else emptyMap()

        val config = if (!initialMessages.isNullOrEmpty() || extraCtx.isNotEmpty()) {
            ConversationConfig(
                initialMessages = initialMessages.orEmpty(),
                extraContext = extraCtx
            )
        } else null
        liteRtConversation = if (config != null) engine.createConversation(config) else engine.createConversation()
        stopRequested = false
    }

    private fun initializeLiteRtLm(
        modelPath: String,
        modelId: String,
        inferenceBackend: InferenceBackend,
        supportsAudioInput: Boolean = false,
        contextWindowTokens: Int? = null
    ) {
        InitCrashGuard.markInitStarted(context, modelId)
        var engine: Engine? = null
        try {
            val executionPolicy = RuntimeExecutionPolicy.resolve(context)
            val backend = when (inferenceBackend) {
                InferenceBackend.LITERT_GPU -> Backend.GPU()
                InferenceBackend.LITERT_CPU,
                InferenceBackend.MEDIAPIPE -> Backend.CPU(executionPolicy.cpuThreads)
            }
            val runtimeCacheDir = LiteRtRuntimeCache.prepare(context, modelId, inferenceBackend, modelPath)
            liteRtCacheDir = runtimeCacheDir

            val effectiveMaxTokens = (contextWindowTokens ?: 4096).coerceIn(2048, 8192)
            var audioBackendCandidate: Backend? = if (supportsAudioInput) backend else null
            var initialized = false
            var finalAudioSupported = supportsAudioInput

            // Try 1: with primary audioBackend matching the main backend (GPU or CPU)
            try {
                val eng = Engine(
                    EngineConfig(
                        modelPath = modelPath,
                        backend = backend,
                        visionBackend = backend,
                        audioBackend = audioBackendCandidate,
                        maxNumTokens = effectiveMaxTokens,
                        cacheDir = runtimeCacheDir.absolutePath
                    )
                )
                engine = eng
                eng.initialize()
                initialized = true
            } catch (t: Throwable) {
                Log.w(TAG, "LiteRT-LM init with audioBackend=$audioBackendCandidate and maxTokens=$effectiveMaxTokens failed: ${t.message}", t)
                runCatching { engine?.close() }
                engine = null
            }

            // Try 2: If primary audio backend was GPU and failed, retry with CPU audioBackend
            if (!initialized && supportsAudioInput && backend !is Backend.CPU) {
                try {
                    audioBackendCandidate = Backend.CPU(executionPolicy.cpuThreads)
                    val eng = Engine(
                        EngineConfig(
                            modelPath = modelPath,
                            backend = backend,
                            visionBackend = backend,
                            audioBackend = audioBackendCandidate,
                            maxNumTokens = effectiveMaxTokens,
                            cacheDir = runtimeCacheDir.absolutePath
                        )
                    )
                    engine = eng
                    eng.initialize()
                    initialized = true
                    Log.i(TAG, "LiteRT-LM audio backend fell back to CPU successfully")
                } catch (t: Throwable) {
                    Log.w(TAG, "LiteRT-LM init with CPU audioBackend failed: ${t.message}", t)
                    runCatching { engine?.close() }
                    engine = null
                }
            }

            // Try 3: Fall back to no-audio mode so model still runs for vision/text
            if (!initialized) {
                finalAudioSupported = false
                try {
                    val eng = Engine(
                        EngineConfig(
                            modelPath = modelPath,
                            backend = backend,
                            visionBackend = backend,
                            audioBackend = null,
                            maxNumTokens = effectiveMaxTokens,
                            cacheDir = runtimeCacheDir.absolutePath
                        )
                    )
                    engine = eng
                    eng.initialize()
                    initialized = true
                    Log.w(TAG, "LiteRT-LM initialized with audio disabled")
                } catch (t: Throwable) {
                    Log.w(TAG, "LiteRT-LM init with audio disabled failed: ${t.message}", t)
                    runCatching { engine?.close() }
                    engine = null
                }
            }

            // Try 4: If effectiveMaxTokens > 2048 and still failed, retry with safe 2048 tokens
            if (!initialized && effectiveMaxTokens > 2048) {
                finalAudioSupported = false
                try {
                    val eng = Engine(
                        EngineConfig(
                            modelPath = modelPath,
                            backend = backend,
                            visionBackend = backend,
                            audioBackend = null,
                            maxNumTokens = 2048,
                            cacheDir = runtimeCacheDir.absolutePath
                        )
                    )
                    engine = eng
                    eng.initialize()
                    initialized = true
                    Log.w(TAG, "LiteRT-LM initialized with safe 2048 tokens fallback")
                } catch (t: Throwable) {
                    Log.w(TAG, "LiteRT-LM init with safe 2048 tokens fallback failed: ${t.message}", t)
                    runCatching { engine?.close() }
                    engine = null
                }
            }

            modelSupportsAudioInput = finalAudioSupported
            InitCrashGuard.markInitCompleted(context)
            val readyEngine = engine ?: throw IllegalStateException("Failed to initialize LiteRT-LM engine")
            liteRtEngine = readyEngine
            val initialConfig = if (isDefaultThinkingModel) {
                ConversationConfig(
                    extraContext = ThinkingModeConfig.liteRtExtraContext(
                        isDefaultThinkingModel = true,
                        thinkingMode = false
                    )
                )
            } else null
            liteRtConversation = if (initialConfig != null) readyEngine.createConversation(initialConfig) else readyEngine.createConversation()
            runtime = RuntimeKind.LiteRtLm
            Log.i(TAG, "LiteRT-LM vision engine ready on $backend (audio: $finalAudioSupported): $modelPath")
        } catch (t: Throwable) {
            InitCrashGuard.markInitCompleted(context)
            runCatching { engine?.close() }
            LiteRtRuntimeCache.clear(context, liteRtCacheDir)
            liteRtCacheDir = null
            throw t
        }
    }

    private fun initializeMediaPipeVision(
        modelPath: String,
        supportsAudioInput: Boolean,
        contextWindowTokens: Int? = null
    ) {
        val targetTokens = (contextWindowTokens ?: MAX_RESPONSE_TOKENS).coerceIn(1024, 4096)
        val optionsBuilder = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(targetTokens)
            .setMaxTopK(40)
            .setMaxNumImages(1)
            .setPreferredBackend(LlmInference.Backend.CPU)
        if (supportsAudioInput) {
            runCatching {
                optionsBuilder.setAudioModelOptions(
                    AudioModelOptions.builder()
                        .setMaxAudioSequenceLength(300)
                        .build()
                )
            }.onFailure { Log.w(TAG, "AudioModelOptions not applied: ${it.message}") }
        }
        val options = optionsBuilder.build()
        try {
            mediaPipeInference = LlmInference.createFromOptions(context, options)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to initialize MediaPipe with $targetTokens tokens (audio=$supportsAudioInput): ${t.message}, retrying safe fallback", t)
            val fallbackOptions = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(1024)
                .setMaxTopK(40)
                .setMaxNumImages(1)
                .setPreferredBackend(LlmInference.Backend.CPU)
                .build()
            mediaPipeInference = LlmInference.createFromOptions(context, fallbackOptions)
            modelSupportsAudioInput = false
        }
        runtime = RuntimeKind.MediaPipeVision
    }


    @OptIn(ExperimentalApi::class)
    private suspend fun streamLiteRtLmVision(
        bitmap: Bitmap?,
        prompt: String,
        audioFile: File?,
        audioBytes: ByteArray?,
        thinkingMode: Boolean,
        preparedImageFile: File?,
        requestId: String = "",
        timing: InferenceTiming? = null,
        onProgress: ((RequestProgress) -> Unit)? = null,
        onTokensGenerated: ((Int) -> Unit)? = null,
        responseSnapshot: () -> String,
        onUpdate: (String) -> Unit
    ): String {
        require(bitmap?.isRecycled != true) { "Image was recycled before analysis" }
        if (preparedImageFile != null) {
            require(preparedImageFile.exists() && preparedImageFile.isFile) {
                "Attached image file does not exist: ${preparedImageFile.absolutePath}"
            }
        }
        val rawBytes = audioBytes
        val hasAudioBytes = rawBytes?.isNotEmpty() == true
        val hasAudioInput = audioFile != null || hasAudioBytes
        require(!hasAudioInput || modelSupportsAudioInput) { "This model does not support audio input." }
        val conversation = liteRtConversation ?: throw IllegalStateException("Image model is not initialised.")
        val reusableImageFile = preparedImageFile
        val imageFile = reusableImageFile ?: bitmap?.writeToCacheFile()
        val effectiveAudioFile = audioFile
        var firstChunkRecorded = false
        val requestContext = ThinkingModeConfig.liteRtExtraContext(
            isDefaultThinkingModel = isDefaultThinkingModel,
            thinkingMode = thinkingMode
        )
        Log.i(
            TAG,
            "LiteRT-LM vision request mode=${if (thinkingMode) "thinking" else "direct"} " +
                "defaultThinking=$isDefaultThinkingModel contextKeys=${requestContext.keys}"
        )
        try {
            val audioContent: Content? = when {
                rawBytes != null && rawBytes.isNotEmpty() -> Content.AudioBytes(rawBytes)
                effectiveAudioFile != null -> Content.AudioFile(effectiveAudioFile.absolutePath)
                else -> null
            }
            val imageContent: Content? = imageFile?.let { Content.ImageFile(it.absolutePath) }
            val effectivePromptText = if (prompt.isNotBlank()) prompt else if (imageContent != null) DEFAULT_IMAGE_PROMPT else ""
            val textContent = if (effectivePromptText.isNotBlank()) Content.Text(effectivePromptText) else null
            val message = when {
                imageContent != null && audioContent != null && textContent != null -> Message.of(imageContent, audioContent, textContent)
                imageContent != null && audioContent != null -> Message.of(imageContent, audioContent)
                imageContent != null && textContent != null -> Message.of(imageContent, textContent)
                imageContent != null -> Message.of(imageContent, Content.Text(DEFAULT_IMAGE_PROMPT))
                audioContent != null && textContent != null -> Message.of(audioContent, textContent)
                audioContent != null -> Message.of(audioContent)
                textContent != null -> Message.of(textContent)
                else -> Message.of(Content.Text(""))
            }

            val adapter = LiteRtStreamAdapter(
                requestId = requestId,
                attemptId = 1,
                thinkingMode = thinkingMode,
                onFirstToken = {
                    timing?.recordFirstToken()
                    if (!firstChunkRecorded) {
                        firstChunkRecorded = true
                        onProgress?.invoke(RequestProgress.Generating)
                    }
                },
                onDelta = { delta ->
                    if (stopRequested) return@LiteRtStreamAdapter
                    if (!firstChunkRecorded && delta.isNotEmpty()) {
                        firstChunkRecorded = true
                        timing?.recordFirstToken()
                        onProgress?.invoke(RequestProgress.Generating)
                    }
                    timing?.recordChunk(delta)
                    runCatching { onUpdate(delta) }
                }
            )
            activeLiteRtAdapter = adapter
            try {
                conversation.sendMessageAsync(message, adapter, requestContext)
                val finalSnapshot = adapter.awaitDone()
                lastLiteRtSnapshot = finalSnapshot
                val answerText = finalSnapshot.answerText
                val rawTokens = if (adapter.callbackCount > 0) {
                    adapter.callbackCount
                } else {
                    val combinedText = answerText + finalSnapshot.reasoningText
                    maxOf(1, Math.round(combinedText.length / 3.8).toInt())
                }
                onTokensGenerated?.invoke(rawTokens)
                return answerText
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                adapter.cancel(conversation)
                lastLiteRtSnapshot = adapter.currentSnapshot()
                val partialText = adapter.currentSnapshot().answerText + adapter.currentSnapshot().reasoningText
                val rawTokens = if (adapter.tokenCount > 0) adapter.tokenCount else maxOf(1, Math.round(partialText.length / 3.8).toInt())
                onTokensGenerated?.invoke(rawTokens)
                throw cancelled
            } catch (t: Throwable) {
                lastLiteRtSnapshot = adapter.currentSnapshot()
                val partialText = adapter.currentSnapshot().answerText + adapter.currentSnapshot().reasoningText
                val rawTokens = if (adapter.tokenCount > 0) adapter.tokenCount else maxOf(1, Math.round(partialText.length / 3.8).toInt())
                onTokensGenerated?.invoke(rawTokens)
                throw t
            } finally {
                if (activeLiteRtAdapter === adapter) {
                    activeLiteRtAdapter = null
                }
            }
        } finally {
            if (reusableImageFile == null) runCatching { imageFile?.delete() }
        }
    }

    private fun streamMediaPipeVision(
        bitmap: Bitmap?,
        prompt: String,
        audioFile: File?,
        audioBytes: ByteArray?,
        timing: InferenceTiming? = null,
        onProgress: ((RequestProgress) -> Unit)? = null,
        onTokensGenerated: ((Int) -> Unit)? = null,
        responseSnapshot: () -> String,
        onUpdate: (String) -> Unit
    ): String {
        require(bitmap?.isRecycled != true) { "Image was recycled before analysis" }
        val hasAudioBytes = audioBytes?.isNotEmpty() == true
        val hasAudioInput = audioFile != null || hasAudioBytes
        require(!hasAudioInput || modelSupportsAudioInput) { "This model does not support audio input." }
        val inference = mediaPipeInference ?: throw IllegalStateException("Image model is not initialised.")
        val mpImage = try {
            bitmap?.let { BitmapImageBuilder(it).build() }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to build MPImage", t)
            throw IllegalArgumentException("Failed to process image", t)
        }
        val mediaPipeAudioBytes = try {
            if (hasAudioBytes) audioBytes else audioFile?.readBytes()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to read audio attachment", t)
            throw IllegalArgumentException("Failed to read audio", t)
        }
        return try {
            val graphOptions = GraphOptions.builder()
                .setIncludeTokenCostCalculator(false)
                .setEnableVisionModality(mpImage != null)
                .setEnableAudioModality(mediaPipeAudioBytes != null)
                .build()
            val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTopK(40)
                .setTopP(0.95f)
                .setTemperature(0.7f)
                .setRandomSeed(0)
                .setGraphOptions(graphOptions)
                .build()
            val session = try {
                var created: LlmInferenceSession? = null
                var attempts = 0
                while (created == null && attempts < 5) {
                    try {
                        created = LlmInferenceSession.createFromOptions(inference, sessionOptions)
                    } catch (t: Throwable) {
                        if (t.message?.contains("still processing", ignoreCase = true) == true && attempts < 4) {
                            attempts++
                            Thread.sleep(60)
                        } else {
                            throw t
                        }
                    }
                }
                created ?: throw IllegalStateException("Failed to create vision session")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to create vision session", t)
                throw IllegalStateException("Failed to create vision session", t)
            }
            activeMediaPipeSession = session
            try {
                if (stopRequested) {
                    runCatching { session.cancelGenerateResponseAsync() }
                    return "Stopped."
                }
                if (mpImage != null) {
                    session.addImage(mpImage)
                    if (stopRequested) {
                        runCatching { session.cancelGenerateResponseAsync() }
                        return "Stopped."
                    }
                }
                if (mediaPipeAudioBytes != null) {
                    session.addAudio(mediaPipeAudioBytes)
                    if (stopRequested) {
                        runCatching { session.cancelGenerateResponseAsync() }
                        return "Stopped."
                    }
                }
                if (prompt.isNotBlank() || (mpImage == null && mediaPipeAudioBytes == null)) {
                    session.addQueryChunk(prompt)
                    if (stopRequested) {
                        runCatching { session.cancelGenerateResponseAsync() }
                        return "Stopped."
                    }
                }
                var firstChunkRecorded = false
                var mediaPipeTokenCount = 0
                val callbackDone = CountDownLatch(1)
                activeMediaPipeCallbackDone = callbackDone
                val future = session.generateResponseAsync(
                    ProgressListener<String> { partial, done ->
                        try {
                            if (stopRequested) return@ProgressListener
                            if (partial.isNullOrEmpty()) return@ProgressListener
                            mediaPipeTokenCount++
                            if (!firstChunkRecorded) {
                                firstChunkRecorded = true
                                timing?.recordFirstToken()
                                onProgress?.invoke(RequestProgress.Generating)
                            }

                            timing?.recordChunk(partial)
                            runCatching { onUpdate(partial) }
                        } finally {
                            if (done) {
                                callbackDone.countDown()
                            }
                        }
                    }
                )
                activeMediaPipeFuture = future
                // If stop was already requested before we call future.get(), return early
                // so we don't block on a future that may never complete cleanly.
                if (stopRequested) {
                    runCatching { session.cancelGenerateResponseAsync() }
                    runCatching { future.cancel(true) }
                    val rawTokens = if (mediaPipeTokenCount > 0) mediaPipeTokenCount else maxOf(1, Math.round(responseSnapshot().length / 3.8).toInt())
                    onTokensGenerated?.invoke(rawTokens)
                    return ModelOutputSanitizer.clean(responseSnapshot()).ifBlank { "Stopped." }
                }
                val finalResponse = try {
                    while (!future.isDone) {
                        if (stopRequested) {
                            runCatching { session.cancelGenerateResponseAsync() }
                            runCatching { future.cancel(true) }
                            break
                        }
                        try {
                            future.get(50, TimeUnit.MILLISECONDS)
                            break
                        } catch (_: TimeoutException) {
                            // Check stopRequested on each tick
                        }
                    }
                    if (future.isDone && !future.isCancelled && !stopRequested) {
                        runCatching { future.get() }.getOrNull()
                    } else {
                        null
                    }
                } catch (_: FutureCancellationException) {
                    Log.i(TAG, "Vision generation stopped (future cancelled)")
                    null
                } catch (_: java.util.concurrent.CancellationException) {
                    Log.i(TAG, "Vision generation stopped (cancelled)")
                    null
                } catch (_: InterruptedException) {
                    Log.i(TAG, "Vision generation interrupted")
                    Thread.currentThread().interrupt()
                    null
                } catch (t: Throwable) {
                    if (stopRequested) {
                        Log.i(TAG, "Vision stream error after stop", t)
                        null
                    } else {
                        throw t
                    }
                } finally {
                    activeMediaPipeFuture = null
                }
                val accumulatedText = responseSnapshot()
                val returnText = if (stopRequested || finalResponse == null) {
                    ModelOutputSanitizer.clean(accumulatedText).ifBlank { "Stopped." }
                } else if (finalResponse.isNotBlank() && finalResponse != accumulatedText) {
                    finalResponse
                } else {
                    accumulatedText.ifBlank { finalResponse }
                }
                val rawTokens = if (mediaPipeTokenCount > 0) {
                    mediaPipeTokenCount
                } else {
                    maxOf(1, Math.round(returnText.length / 3.8).toInt())
                }
                onTokensGenerated?.invoke(rawTokens)
                return returnText
            } finally {
                // Close only the session created by this invocation. If close()
                // already claimed it, or a future invocation owns a newer
                // session, this block leaves that object alone.
                if (activeMediaPipeSession === session) {
                    val callbackDone = activeMediaPipeCallbackDone
                    activeMediaPipeCallbackDone = null
                    activeMediaPipeSession = null
                    if (!isBeingClosed && !stopRequested) {
                        if (waitForCallbackToReturn(callbackDone)) {
                            runCatching { session.close() }.onFailure { t ->
                                Log.w(TAG, "Session close error (safe to ignore after stop)", t)
                            }
                        }
                    } else if (stopRequested) {
                        Log.i(TAG, "Leaving stopped MediaPipe vision session for native cleanup")
                    }
                }
            }
        } catch (t: Throwable) {
            // Catch-all for any native crash that propagates as an Error
            // (e.g. UnsatisfiedLinkError, InternalError from JNI).
            Log.e(TAG, "Vision stream unexpected error", t)
            throw t
        } finally {
            runCatching { mpImage?.close() }.onFailure { t ->
                Log.w(TAG, "MPImage close error (safe to ignore)", t)
            }
        }
    }

    private fun waitForCallbackToReturn(callbackDone: CountDownLatch?): Boolean {
        if (callbackDone == null || callbackDone.count == 0L) return true
        if (stopRequested) return false
        val completed = runCatching {
            callbackDone.await(150, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (!completed) {
            Log.w(TAG, "Timed out waiting for MediaPipe vision callback; leaving session for native cleanup")
        }
        return completed
    }

    private fun File.containsAudioModelAssets(): Boolean {
        val audioMarkers = listOf("AUDIO_ENCODER", "AUDIO_ADAPTER", "AUDIO_EMBEDDER", "AUDIO")
        return when {
            isDirectory -> walkTopDown()
                .take(80)
                .any { file -> audioMarkers.any { marker -> file.name.contains(marker, ignoreCase = true) } }
            else -> runCatching {
                java.util.zip.ZipFile(this).use { zip ->
                    zip.entries().asSequence().any { entry ->
                        audioMarkers.any { marker -> entry.name.contains(marker, ignoreCase = true) }
                    }
                }
            }.getOrElse {
                containsAnyAsciiMarker(audioMarkers)
            }
        }
    }

    private fun File.containsAnyAsciiMarker(markers: List<String>, scanBytes: Int = 256 * 1024): Boolean {
        if (!isFile || length() <= 0L) return false
        val markerBytes = markers.map { it.uppercase(java.util.Locale.US).toByteArray(Charsets.US_ASCII) }
        return runCatching {
            inputStream().use { input ->
                val buffer = ByteArray(minOf(length(), scanBytes.toLong()).toInt())
                val read = input.read(buffer)
                read > 0 && markerBytes.any { marker -> buffer.indexOf(marker, read) >= 0 }
            }
        }.getOrDefault(false)
    }

    private fun ByteArray.indexOf(needle: ByteArray, length: Int): Int {
        if (needle.isEmpty() || length < needle.size) return -1
        val maxStart = length - needle.size
        for (start in 0..maxStart) {
            var matches = true
            for (index in needle.indices) {
                val candidate = this[start + index].toInt().let { byte ->
                    if (byte in 97..122) byte - 32 else byte
                }.toByte()
                if (candidate != needle[index]) {
                    matches = false
                    break
                }
            }
            if (matches) return start
        }
        return -1
    }

    private fun Bitmap.writeToCacheFile(): File {
        val dir = File(context.cacheDir, "vision_questions").apply { mkdirs() }
        pruneVisionQuestionCache(dir)
        val file = File(dir, "vision-question-${System.currentTimeMillis()}.jpg")
        val maxDimension = 1024
        val scaledBitmap = if (width > maxDimension || height > maxDimension) {
            val scale = maxDimension.toFloat() / maxOf(width, height)
            val destW = (width * scale).toInt().coerceAtLeast(1)
            val destH = (height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(this, destW, destH, true)
        } else {
            null
        }
        val target = scaledBitmap ?: this
        try {
            FileOutputStream(file).use { output ->
                target.compress(Bitmap.CompressFormat.JPEG, 90, output)
            }
        } finally {
            if (scaledBitmap != null && scaledBitmap != this) {
                runCatching { scaledBitmap.recycle() }
            }
        }
        return file
    }

    private fun pruneVisionQuestionCache(dir: File) {
        val now = System.currentTimeMillis()
        val files = dir.listFiles()?.filter { it.isFile && it.name.startsWith("vision-question-") } ?: return
        files
            .filter { now - it.lastModified() > 6L * 60L * 60L * 1000L }
            .forEach { runCatching { it.delete() } }
        files
            .sortedByDescending { it.lastModified() }
            .drop(8)
            .forEach { runCatching { it.delete() } }
    }

    private fun finalizeModelOutput(
        text: String,
        thinkingMode: Boolean,
        shouldStripThinking: Boolean
    ): String {
        return if (shouldStripThinking || !thinkingMode) {
            ThinkingTextUtils.extractFinalAnswerOnly(text)
        } else {
            ThinkingTextUtils.normalizeFinalOutput(text)
        }
    }

    private fun stripThinkingTags(text: String): String {
        var result = THINK_BLOCK_REGEX.replace(text, "")
        result = THINK_UNCLOSED_REGEX.replace(result, "")
        result = THINK_PREFILLED_CLOSE_REGEX.replace(result, "")
        return result.trimStart()
    }

    private enum class RuntimeKind {
        None,
        MediaPipeVision,
        LiteRtLm
    }
}
