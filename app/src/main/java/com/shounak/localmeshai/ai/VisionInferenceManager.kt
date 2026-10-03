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
import com.shounak.localmeshai.utils.AppSettings
import com.shounak.localmeshai.utils.DeviceUtils
import com.shounak.localmeshai.utils.InferenceBackend
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

data class VisionLabel(
    val label: String,
    val score: Float
)

class VisionInferenceManager(private val context: Context) {
    private var mediaPipeInference: LlmInference? = null
    private var liteRtEngine: Engine? = null
    private var liteRtConversation: Conversation? = null
    private var liteRtCacheDir: File? = null
    private var runtime = RuntimeKind.None
    @Volatile private var activeMediaPipeSession: LlmInferenceSession? = null
    @Volatile private var activeMediaPipeFuture: Future<String>? = null
    @Volatile private var activeMediaPipeCallbackDone: CountDownLatch? = null
    @Volatile private var stopRequested = false
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

    fun initialize(
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

        close()
        this.supportsThinkingMode = supportsThinkingMode
        val effectiveId = modelId.ifBlank { file.nameWithoutExtension }
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
    }

    /**
     * Streaming ask. Emits partial text to [onUpdate] as the model produces it.
     * The returned String is the final, accumulated response.
     */
    suspend fun askStreaming(
        bitmap: Bitmap?,
        question: String,
        audioFile: File? = null,
        audioBytes: ByteArray? = null,
        thinkingMode: Boolean = false,
        onUpdate: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        stopRequested = false
        val prompt = question.trim()
        val shouldStripThinking = !thinkingMode
        val effectiveOnUpdate: (String) -> Unit = if (shouldStripThinking) {
            { partial -> onUpdate(stripThinkingTags(partial)) }
        } else {
            onUpdate
        }
        val rawResponse = when (runtime) {
            RuntimeKind.LiteRtLm -> streamLiteRtLmVision(
                bitmap = bitmap,
                prompt = prompt,
                audioFile = audioFile,
                audioBytes = audioBytes,
                thinkingMode = thinkingMode,
                onUpdate = effectiveOnUpdate
            )
            RuntimeKind.MediaPipeVision -> streamMediaPipeVision(
                bitmap = bitmap,
                prompt = prompt,
                audioFile = audioFile,
                audioBytes = audioBytes,
                onUpdate = effectiveOnUpdate
            )
            RuntimeKind.None -> "Choose and initialise a multimodal model first."
        }
        finalizeModelOutput(
            text = rawResponse,
            thinkingMode = thinkingMode,
            shouldStripThinking = shouldStripThinking
        )
    }

    suspend fun classify(bitmap: Bitmap): List<VisionLabel> = withContext(Dispatchers.IO) {
        emptyList()
    }

    fun close() {
        val wasMediaPipeVision = runtime == RuntimeKind.MediaPipeVision
        isBeingClosed = true
        cancelGeneration()
        // Claim the active session atomically before destroying the parent
        // LlmInference object. This prevents streamMediaPipeVision's finally
        // block from calling session.close() AFTER mediaPipeInference.close()
        // destroys the underlying native context — which is the root cause of
        // the SIGSEGV when the user taps Stop.
        val sessionToClose = activeMediaPipeSession
        val callbackDone = activeMediaPipeCallbackDone
        activeMediaPipeSession = null    // null first so finally block skips it
        activeMediaPipeCallbackDone = null
        if (!wasMediaPipeVision && sessionToClose != null && waitForCallbackToReturn(callbackDone)) {
            runCatching { sessionToClose.close() }
        } else if (wasMediaPipeVision && sessionToClose != null) {
            Log.w(TAG, "Skipping MediaPipe vision session.close(); native close is unstable on this device")
        }
        if (wasMediaPipeVision) {
            Log.w(TAG, "Skipping MediaPipe vision inference.close(); native close is unstable on this device")
        } else {
            runCatching { mediaPipeInference?.close() }
        }
        mediaPipeInference = null
        runCatching { liteRtConversation?.close() }
        liteRtConversation = null
        runCatching { liteRtEngine?.close() }
        liteRtEngine = null
        LiteRtRuntimeCache.clear(context, liteRtCacheDir)
        liteRtCacheDir = null
        runtime = RuntimeKind.None
        activeBackendDisplayName = ""
        isDefaultThinkingModel = false
        supportsThinkingMode = false
        isBeingClosed = false
    }

    fun cancelGeneration() {
        stopRequested = true
        runCatching { liteRtConversation?.cancelProcess() }
        val session = activeMediaPipeSession
        if (session != null) {
            // Use MediaPipe's native cancellation path. Cancelling the Future
            // directly makes future.get() return before MediaPipe releases its
            // single inference lock, which caused the next request to fail with
            // "Previous invocation still processing" and could crash on close.
            runCatching { session.cancelGenerateResponseAsync() }
        } else {
            runCatching { activeMediaPipeFuture?.cancel(true) }
            activeMediaPipeFuture = null
        }
        // Do NOT close activeMediaPipeSession here — the streaming method's
        // finally block owns session lifecycle. Closing it from two threads
        // simultaneously causes a native crash.
    }

    fun resetConversation(thinkingMode: Boolean = false) {
        if (runtime != RuntimeKind.LiteRtLm) return
        val engine = liteRtEngine ?: return
        runCatching { liteRtConversation?.close() }.onFailure { t ->
            Log.w(TAG, "LiteRT-LM vision conversation close during reset failed", t)
        }
        val config = if (isDefaultThinkingModel) {
            ConversationConfig(
                extraContext = ThinkingModeConfig.liteRtExtraContext(
                    isDefaultThinkingModel = true,
                    thinkingMode = thinkingMode
                )
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
            val backend = when (inferenceBackend) {
                InferenceBackend.LITERT_GPU -> Backend.GPU()
                InferenceBackend.LITERT_CPU,
                InferenceBackend.MEDIAPIPE -> Backend.CPU()
            }
            val runtimeCacheDir = LiteRtRuntimeCache.prepare(context, modelId, inferenceBackend)
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
                eng.initialize()
                engine = eng
                initialized = true
            } catch (t: Throwable) {
                Log.w(TAG, "LiteRT-LM init with audioBackend=$audioBackendCandidate and maxTokens=$effectiveMaxTokens failed: ${t.message}", t)
                runCatching { engine?.close() }
                engine = null
            }

            // Try 2: If primary audio backend was GPU and failed, retry with CPU audioBackend
            if (!initialized && supportsAudioInput && backend !is Backend.CPU) {
                try {
                    audioBackendCandidate = Backend.CPU()
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
                    eng.initialize()
                    engine = eng
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
                    eng.initialize()
                    engine = eng
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
                eng.initialize()
                engine = eng
                initialized = true
                Log.w(TAG, "LiteRT-LM initialized with safe 2048 tokens fallback")
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
        onUpdate: (String) -> Unit
    ): String {
        if (bitmap?.isRecycled == true) return "Error: Image was recycled before analysis."
        val hasAudioBytes = audioBytes?.isNotEmpty() == true
        val hasAudioInput = audioFile != null || hasAudioBytes
        if (hasAudioInput && !modelSupportsAudioInput) return "This model does not support audio input."
        val conversation = liteRtConversation ?: return "Image model is not initialised."
        val imageFile = bitmap?.writeToCacheFile()
        val cachedAudioFile = if (audioFile == null && hasAudioBytes) {
            val f = File(context.cacheDir, "multimodal_inputs/audio_temp_${System.currentTimeMillis()}.wav").apply {
                parentFile?.mkdirs()
                writeBytes(audioBytes!!)
            }
            f
        } else null
        val effectiveAudioFile = audioFile ?: cachedAudioFile
        val response = StringBuilder()
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
                effectiveAudioFile != null -> Content.AudioFile(effectiveAudioFile.absolutePath)
                hasAudioBytes -> Content.AudioBytes(audioBytes!!)
                else -> null
            }
            val imageContent: Content? = imageFile?.let { Content.ImageFile(it.absolutePath) }
            val textContent = if (prompt.isNotBlank()) Content.Text(prompt) else null
            val message = when {
                imageContent != null && audioContent != null && textContent != null -> Message.of(imageContent, audioContent, textContent)
                imageContent != null && audioContent != null -> Message.of(imageContent, audioContent)
                imageContent != null && textContent != null -> Message.of(imageContent, textContent)
                imageContent != null -> Message.of(imageContent)
                audioContent != null && textContent != null -> Message.of(audioContent, textContent)
                audioContent != null -> Message.of(audioContent)
                textContent != null -> Message.of(textContent)
                else -> Message.of(Content.Text(""))
            }
            conversation.sendMessageAsync(
                message,
                requestContext
            ).collect { message ->
                if (stopRequested) {
                    // Throwing here breaks out of the Flow collector cleanly.
                    throw kotlinx.coroutines.CancellationException("Stop requested")
                }
                val chunk = message.toString()
                // Duplicate-partial handling: MediaPipe / LiteRT-LM sometimes
                // delivers a chunk that already contains the previous accumulation
                // as a prefix. Detect that and replace instead of append.
                val current = response.toString()
                if (current.isNotEmpty() && chunk.startsWith(current)) {
                    response.clear()
                    response.append(chunk)
                } else {
                    response.append(chunk)
                }
                runCatching { onUpdate(ModelOutputSanitizer.clean(response.toString())) }
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
            // Expected when the user stops generation — coroutine was cancelled
            // or stopRequested flag was set.
            Log.i(TAG, "LiteRT-LM vision stream cancelled")
        } catch (t: Throwable) {
            // Catch everything including native crashes that propagate as Error
            // so the caller never sees an unhandled exception.
            Log.w(TAG, "LiteRT-LM vision stream error", t)
            if (response.isEmpty()) {
                val errorMsg = t.localizedMessage ?: t.message ?: t.javaClass.simpleName
                response.append("Error: ").append(errorMsg)
            }
        } finally {
            runCatching { imageFile?.delete() }
            runCatching { cachedAudioFile?.delete() }
        }
        if (stopRequested) return ModelOutputSanitizer.clean(response.toString()).ifBlank { "Stopped." }
        return ModelOutputSanitizer.clean(response.toString()).ifBlank { "No answer generated." }
    }

    private fun streamMediaPipeVision(
        bitmap: Bitmap?,
        prompt: String,
        audioFile: File?,
        audioBytes: ByteArray?,
        onUpdate: (String) -> Unit
    ): String {
        if (bitmap?.isRecycled == true) return "Error: Image was recycled before analysis."
        val hasAudioBytes = audioBytes?.isNotEmpty() == true
        val hasAudioInput = audioFile != null || hasAudioBytes
        if (hasAudioInput && !modelSupportsAudioInput) return "This model does not support audio input."
        val inference = mediaPipeInference ?: return "Image model is not initialised."
        val mpImage = try {
            bitmap?.let { BitmapImageBuilder(it).build() }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to build MPImage", t)
            return "Failed to process image."
        }
        val mediaPipeAudioBytes = try {
            if (hasAudioBytes) audioBytes else audioFile?.readBytes()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to read audio attachment", t)
            return "Failed to read audio."
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
                LlmInferenceSession.createFromOptions(inference, sessionOptions)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to create vision session", t)
                return "Failed to create vision session."
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
                val accumulated = StringBuilder()
                val callbackDone = CountDownLatch(1)
                activeMediaPipeCallbackDone = callbackDone
                val future = session.generateResponseAsync(
                    ProgressListener<String> { partial, done ->
                        try {
                            if (stopRequested) return@ProgressListener
                            if (partial.isNullOrEmpty()) return@ProgressListener
                            val current = accumulated.toString()
                            if (current.isNotEmpty() && partial.startsWith(current)) {
                                accumulated.clear()
                                accumulated.append(partial)
                            } else {
                                accumulated.append(partial)
                            }
                            runCatching { onUpdate(ModelOutputSanitizer.clean(accumulated.toString())) }
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
                }
                val finalResponse = try {
                    future.get()
                } catch (_: FutureCancellationException) {
                    Log.i(TAG, "Vision generation stopped (future cancelled)")
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
                        // Don't rethrow — return the accumulated text or error.
                        Log.e(TAG, "Vision generateResponseAsync failed", t)
                        null
                    }
                } finally {
                    activeMediaPipeFuture = null
                }
                val accumulatedText = accumulated.toString()
                if (stopRequested || finalResponse == null) {
                    return ModelOutputSanitizer.clean(accumulatedText).ifBlank { "Stopped." }
                }
                if (finalResponse.isNotBlank() && finalResponse != accumulatedText) {
                    runCatching { onUpdate(ModelOutputSanitizer.clean(finalResponse)) }
                    ModelOutputSanitizer.clean(finalResponse)
                } else {
                    ModelOutputSanitizer.clean(accumulatedText.ifBlank { finalResponse })
                }
            } finally {
                // Close only the session created by this invocation. If close()
                // already claimed it, or a future invocation owns a newer
                // session, this block leaves that object alone.
                if (activeMediaPipeSession === session) {
                    val callbackDone = activeMediaPipeCallbackDone
                    activeMediaPipeCallbackDone = null
                    activeMediaPipeSession = null
                    if (!isBeingClosed) {
                        if (waitForCallbackToReturn(callbackDone)) {
                            runCatching { session.close() }.onFailure { t ->
                                Log.w(TAG, "Session close error (safe to ignore after stop)", t)
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            // Catch-all for any native crash that propagates as an Error
            // (e.g. UnsatisfiedLinkError, InternalError from JNI).
            Log.e(TAG, "Vision stream unexpected error", t)
            "Image analysis failed."
        } finally {
            runCatching { mpImage?.close() }.onFailure { t ->
                Log.w(TAG, "MPImage close error (safe to ignore)", t)
            }
        }
    }

    private fun waitForCallbackToReturn(callbackDone: CountDownLatch?): Boolean {
        if (callbackDone == null || callbackDone.count == 0L) return true
        val completed = runCatching {
            callbackDone.await(2, TimeUnit.SECONDS)
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
        FileOutputStream(file).use { output ->
            compress(Bitmap.CompressFormat.JPEG, 92, output)
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
