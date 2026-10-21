@file:Suppress("DEPRECATION")
package com.shounak.localmeshai.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.AudioModelOptions
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.ProgressListener
import com.shounak.localmeshai.ai.ShutdownResult
import com.shounak.localmeshai.models.ArtifactSource
import com.shounak.localmeshai.models.ModelBundle
import com.shounak.localmeshai.models.ModelCapabilities
import com.shounak.localmeshai.models.ModelCapabilitySnapshot
import com.shounak.localmeshai.models.ModelFormat
import com.shounak.localmeshai.models.ThinkingCapability
import com.shounak.localmeshai.models.ThinkingControlStrategy
import com.shounak.localmeshai.utils.AppCacheCleaner
import com.shounak.localmeshai.utils.AppSettings
import com.shounak.localmeshai.utils.AudioUtils
import com.shounak.localmeshai.utils.DeviceUtils
import com.shounak.localmeshai.utils.InferenceBackend
import com.shounak.localmeshai.utils.InferenceTiming
import com.shounak.localmeshai.utils.InitCrashGuard
import com.shounak.localmeshai.utils.LegacyThinkingProfiles
import com.shounak.localmeshai.utils.LiteRtRuntimeCache
import com.shounak.localmeshai.utils.ModelArtifactStorage
import com.shounak.localmeshai.utils.ModelDownloader
import com.shounak.localmeshai.utils.ModelOutputSanitizer
import com.shounak.localmeshai.utils.ModelSourceResolver
import com.shounak.localmeshai.utils.NonGgufBundleInspector
import com.shounak.localmeshai.utils.StreamingResponsePublisher
import com.shounak.localmeshai.utils.ThinkingModeConfig
import com.shounak.localmeshai.utils.ThinkingTextUtils
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CancellationException as FutureCancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

data class VisionLabel(
    val label: String,
    val score: Float
)

class VisionInferenceManager(context: Context) {
    private val context: Context = context.applicationContext
    private var mediaPipeInference: LlmInference? = null
    private var liteRtEngine: Engine? = null
    private var liteRtConversation: Conversation? = null
    private var liteRtCacheDir: File? = null
    private var llamaCppEngine: LlamaCppEngine? = null
    private var profileModelId: String = ""
    private fun profileSampling(): LlamaSamplingParams {
        val profile = com.shounak.localmeshai.features.WorkspacePreferences.get(context).profile(profileModelId)
        return LlamaSamplingParams(temperature = profile.temperature ?: 0.7f, topP = profile.topP ?: 0.95f, topK = profile.topK ?: 40)
    }

    @Volatile private var currentGgufBundle: ModelBundle? = null
    private var currentGgufModelPath: String = ""
    private var currentGgufThreads: Int = 4
    private var currentGgufPrefillThreads: Int = 4
    private var currentGgufContext: Int = 2048
    private var currentGgufBatch: Int = 512
    private var currentGgufUbatch: Int = 256
    private var currentGgufVerifiedSha: String = ""
    private var currentGgufExpectedBytes: Long = 0L
    @Volatile private var runtime = RuntimeKind.None
    private var activeModelLease: AutoCloseable? = null
    private val runtimeContextCapacity = RuntimeContextCapacity()
    val runtimeCapabilities: ModelCapabilitySnapshot?
        get() = if (!isReady) null else configuredCapabilities?.let { snapshot ->
            snapshot.copy(initializedContextTokens = runtimeContextCapacity.tokens, capabilities = snapshot.capabilities.copy(
                thinking = resolvedThinkingCapability,
                supportsVision = snapshot.capabilities.supportsVision && (runtime != RuntimeKind.LlamaCpp || llamaCppEngine?.getMetadata()?.supportsVision == true),
                supportsAudio = modelSupportsAudioInput
            ))
        }
    val isReady: Boolean get() = when (runtime) {
        RuntimeKind.LiteRtLm -> liteRtConversation != null
        RuntimeKind.MediaPipeVision -> mediaPipeInference != null
        RuntimeKind.LlamaCpp -> llamaCppEngine?.isInitialized == true
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
    @Volatile private var configuredCapabilities: ModelCapabilitySnapshot? = null
    @Volatile private var resolvedThinkingCapability: ThinkingCapability = ThinkingCapability.Unknown
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
        contextWindowTokens: Int? = null,
        bundle: ModelBundle? = null
    ) {
        profileModelId = modelId.ifBlank { bundle?.id ?: File(modelPath).nameWithoutExtension }
        val configuredContextWindow = contextWindowTokens

        val file = if (bundle != null && bundle.isStorageImport) null else File(modelPath)
        if (file != null) {
            if (!file.exists()) {
                throw IllegalArgumentException("Image model file not found.")
            }
        }

        val effectiveId = modelId.ifBlank { bundle?.id ?: file?.nameWithoutExtension.orEmpty() }
        val effectiveName = modelName.ifBlank { bundle?.displayName ?: file?.nameWithoutExtension.orEmpty() }
        val shutdownResult = close()
        if (shutdownResult is ShutdownResult.Failed) {
            throw IllegalStateException("Cannot initialize model: previous engine failed to shut down cleanly (${shutdownResult.error.message})", shutdownResult.error)
        }
        com.shounak.localmeshai.data.StartupCoordinator.getInstance(context).awaitCrashGuardChecked()
        var modelLease: AutoCloseable? = file?.let { com.shounak.localmeshai.utils.ModelArtifactStorage.leaseArtifact(it) }
        activeModelLease = modelLease
        try {
            AppCacheCleaner.clearAppCache(context)
            this.supportsThinkingMode = supportsThinkingMode
            val resolvedCap = ThinkingModeConfig.resolveThinkingCapability(
                bundle = bundle,
                localPath = modelPath,
                supportsThinkingFlag = supportsThinkingMode,
                id = effectiveId,
                name = effectiveName,
                backend = "",
                fileName = file?.name ?: "",
                context = context
            )
            resolvedThinkingCapability = resolvedCap
            configuredCapabilities = ModelCapabilitySnapshot(
                effectiveId,
                ModelCapabilitySnapshot.revision(bundle, modelPath),
                (bundle?.capabilities ?: file?.takeIf {
                    NonGgufBundleInspector.detectFormat(it) in setOf(ModelFormat.LiteRtLm, ModelFormat.MediaPipeTask)
                }?.let { NonGgufBundleInspector.inspectFile(it).capabilities } ?: ModelCapabilities(supportsVision = true, supportsAudio = supportsAudioInput))
                    .copy(thinking = resolvedCap)
            )

            isDefaultThinkingModel = (resolvedCap == ThinkingCapability.ModelControlled || resolvedCap == ThinkingCapability.Toggleable)
        val audioInputAvailable = bundle?.capabilities?.supportsAudio ?: supportsAudioInput
        modelSupportsAudioInput = audioInputAvailable
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        stopRequested = false
        isBeingClosed = false   // Reset after close() so initialize() starts clean

        val detectedFormat = bundle?.format ?: file?.let { NonGgufBundleInspector.detectFormat(it) }
        val isGguf = detectedFormat == ModelFormat.GGUF
        val effectivePath = file?.absolutePath
            ?: (bundle?.mainArtifacts?.firstOrNull()?.source as? ArtifactSource.AppManaged)?.path
            ?: bundle?.let {
                val lease = ModelSourceResolver.resolve(context, it)
                modelLease = lease
                activeModelLease = lease
                lease.paths.firstOrNull()
            }
        val effectiveFile: File? = effectivePath?.let { File(it) }
        when {
            isGguf -> {
                val appSettings = AppSettings.getInstance(context)
                val pref = appSettings.settings.value.llamaBackendPreference.uppercase()
                val profile = GgufExecutionProfile.resolve(
                    context = context,
                    modelNameOrId = effectiveName.ifBlank { effectiveId },
                    modelSize = modelSize,
                    customContextTokens = configuredContextWindow,
                    backendPreference = pref
                )
                val companionSummary = if (bundle != null) {
                    "${bundle.mainArtifacts.size}_parts_${bundle.projectorArtifact?.displayName.orEmpty()}"
                } else ""
                val verifiedSha = if (bundle != null) {
                    GgufGpuValidator.resolveArtifactFingerprint(
                        context = context,
                        bundle = bundle,
                        isCancelled = { stopRequested }
                    )
                } else {
                    GgufGpuValidator.resolveModelSha256(
                        modelPath = file!!.absolutePath,
                        modelId = effectiveId,
                        context = context,
                        isCancelled = { stopRequested }
                    )
                }
                val requestedBackend = when (pref) {
                    "VULKAN", "GPU" -> LlamaBackend.VULKAN
                    "CPU" -> LlamaBackend.CPU
                    else -> null
                }
                val decision = GgufBackendPolicy.selectBackend(
                    context = context,
                    modelSha256 = verifiedSha,
                    threads = profile.decodeThreads,
                    contextWindow = profile.contextTokens,
                    nBatch = profile.batchTokens,
                    nUbatch = profile.microBatchTokens,
                    prefillThreads = profile.prefillThreads,
                    requestedBackend = requestedBackend,
                    companionSummary = companionSummary
                )
                val engine = LlamaCppEngine(context)
                initializeGgufEngine(engine) {
                    if (bundle != null) {
                        engine.initializeBundle(
                            context = context,
                            bundle = bundle,
                            threads = profile.decodeThreads,
                            prefillThreads = profile.prefillThreads,
                            contextWindow = profile.contextTokens,
                            nBatch = profile.batchTokens,
                            nUbatch = profile.microBatchTokens,
                            backend = decision.selectedBackend,
                            nGpuLayers = if (decision.selectedBackend == LlamaBackend.VULKAN) 99 else 0
                        )
                    } else {
                        engine.initialize(
                            modelPath = file!!.absolutePath,
                            threads = profile.decodeThreads,
                            prefillThreads = profile.prefillThreads,
                            contextWindow = profile.contextTokens,
                            nBatch = profile.batchTokens,
                            nUbatch = profile.microBatchTokens,
                            backend = decision.selectedBackend,
                            nGpuLayers = if (decision.selectedBackend == LlamaBackend.VULKAN) 99 else 0
                        )
                    }
                }
                llamaCppEngine = engine
                currentGgufBundle = bundle
                currentGgufModelPath = bundle?.mainArtifacts?.firstOrNull()?.source?.location ?: file?.absolutePath.orEmpty()
                currentGgufThreads = profile.decodeThreads
                currentGgufPrefillThreads = profile.prefillThreads
                currentGgufContext = engine.actualConfiguration?.contextWindow?.takeIf { it > 0 } ?: profile.contextTokens
                runtimeContextCapacity.initialize(currentGgufContext) { Unit }
                currentGgufBatch = profile.batchTokens
                currentGgufUbatch = profile.microBatchTokens
                currentGgufVerifiedSha = verifiedSha
                currentGgufExpectedBytes = bundle?.totalSizeBytes ?: file?.length() ?: 0L
                runtime = RuntimeKind.LlamaCpp
                activeBackendDisplayName = if (engine.activeBackend == LlamaBackend.VULKAN) "GGUF Vulkan GPU" else "GGUF CPU"
                modelSupportsAudioInput = engine.getMetadata()?.supportsAudio == true
            }
            detectedFormat == ModelFormat.LiteRtLm -> {
                val resolvedModelPath = effectiveFile?.absolutePath ?: modelPath
                val modelIdForLiteRt = modelId.ifBlank { bundle?.id ?: effectiveFile?.nameWithoutExtension.orEmpty() }
                val appSettings = AppSettings.getInstance(context)
                val pref = appSettings.settings.value.llamaBackendPreference.uppercase()
                val inferenceBackend = when (pref) {
                    "CPU" -> InferenceBackend.LITERT_CPU
                    "VULKAN", "GPU" -> InferenceBackend.LITERT_GPU
                    else -> {
                        if (allowUnsafeOverride) {
                            InferenceBackend.LITERT_GPU
                        } else {
                            DeviceUtils.selectBackendForModelFile(context, effectiveFile?.name.orEmpty())
                        }
                    }
                }
                val canRetryOnCpu = DeviceUtils.canUseGemma4LiteRtCpuFallback(
                    context = context,
                    modelId = modelIdForLiteRt,
                    modelName = effectiveName,
                    modelSize = modelSize,
                    fileName = effectiveFile?.name.orEmpty(),
                    isMultimodalLiteRt = true
                )
                if (InitCrashGuard.isModelBlocked(context, modelIdForLiteRt) && (!allowUnsafeOverride || !InitCrashGuard.isRetryAuthorized(context, modelIdForLiteRt))) {
                    throw IllegalStateException(InitCrashGuard.blockedModelMessage())
                }
                if (!allowUnsafeOverride && inferenceBackend != InferenceBackend.LITERT_CPU) {
                    val (allowed, reason) = DeviceUtils.canInitializeLiteRtLm(
                        context = context,
                        modelId = modelIdForLiteRt,
                        modelName = effectiveName,
                        modelSize = modelSize,
                        isVision = true,
                        fileName = effectiveFile?.name.orEmpty(),
                        backendLabel = "LiteRT-LM multimodal",
                        supportsAudioInput = audioInputAvailable
                    )
                    if (!allowed) {
                        throw IllegalStateException(reason)
                    }
                }
                initializeLiteRtLm(
                    modelPath = resolvedModelPath,
                    modelId = modelIdForLiteRt,
                    inferenceBackend = inferenceBackend,
                    supportsAudioInput = audioInputAvailable,
                    contextWindowTokens = configuredContextWindow
                )
                activeBackendDisplayName = if (inferenceBackend == InferenceBackend.LITERT_GPU) "LiteRT-LM GPU" else "LiteRT-LM CPU"
            }
            detectedFormat == ModelFormat.MediaPipeTask -> {
                val resolvedModelPath = effectiveFile?.absolutePath ?: modelPath
                initializeMediaPipeVision(resolvedModelPath, audioInputAvailable, configuredContextWindow)
                activeBackendDisplayName = "MediaPipe CPU"
            }
            else -> {
                throw IllegalArgumentException("Unsupported vision model format. Solus multimodal models require GGUF, LiteRT (.litertlm), or MediaPipe (.task) bundles.")
            }
        }
        activeModelLease = modelLease ?: effectiveFile?.let { ModelArtifactStorage.leaseArtifact(it) }
        } catch (t: Throwable) {
            runtimeContextCapacity.clear()
            val cleanup = close()
            if (cleanup is ShutdownResult.Failed && cleanup.error !== t) t.addSuppressed(cleanup.error)
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
        onSnapshot: ((StructuredResponseSnapshot) -> Unit)? = null,
        messages: List<Pair<String, String>>? = null,
        multiTurnImageFiles: Map<Int, File> = emptyMap(),
        multiTurnAudioFiles: Map<Int, File> = emptyMap(),
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
            snapshotProvider = { responseController.snapshot() },
            publishSnapshot = onSnapshot,
            publish = onUpdate
        )
        var currentAttemptId = 1
        val effectiveOnUpdate: (String) -> Unit = { delta ->
            if (delta.isNotEmpty()) {
                responseController.appendDelta(delta, reqId = requestId, attemptId = currentAttemptId)
                if (!responseController.isStructuredMode) {
                    publisher.appendDelta(delta)
                }
            }
        }

        onProgress?.invoke(RequestProgress.ProcessingPrompt)

        val decodedBitmap = if (bitmap == null && preparedImageFile != null && preparedImageFile.exists()) {
            runCatching { BitmapFactory.decodeFile(preparedImageFile.absolutePath) }.getOrNull()
        } else null
        val effectiveBitmap = bitmap ?: decodedBitmap

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
                    publisher = publisher,
                    responseController = responseController,
                    onUpdate = effectiveOnUpdate
                )
                RuntimeKind.MediaPipeVision -> streamMediaPipeVision(
                    bitmap = effectiveBitmap,
                    prompt = prompt,
                    audioFile = audioFile,
                    audioBytes = audioBytes,
                    timing = timing,
                    onProgress = onProgress,
                    onTokensGenerated = { lastGeneratedTokens = it },
                    responseSnapshot = publisher::rawText,
                    onUpdate = effectiveOnUpdate
                )
                RuntimeKind.LlamaCpp -> {
                    responseController.isStructuredMode = true
                    streamLlamaCppVision(
                        bitmap = effectiveBitmap,
                        prompt = prompt,
                        messages = messages.orEmpty(),
                        multiTurnImageFiles = multiTurnImageFiles,
                        multiTurnAudioFiles = multiTurnAudioFiles,
                        thinkingMode = thinkingMode,
                        requestId = requestId,
                        audioFile = audioFile,
                        audioBytes = audioBytes,
                        timing = timing,
                        onProgress = onProgress,
                        onTokensGenerated = { lastGeneratedTokens = it },
                        responseController = responseController,
                        publisher = publisher,
                        responseSnapshot = publisher::rawText,
                        onUpdate = effectiveOnUpdate,
                        onAttemptChanged = { currentAttemptId = it }
                    )
                }
                RuntimeKind.None -> throw IllegalStateException("Choose and initialise a multimodal model first.")
            }
            responseController.finish()
            val rawReasoning = if (!thinkingMode) "" else if (runtime == RuntimeKind.LiteRtLm) {
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
                attemptId = currentAttemptId,
                rawAnswer = cleanResponse,
                rawReasoning = rawReasoning,
                isStopped = stopRequested,
                acceptedAnswerFallback = responseController.acceptedAnswerText(),
                acceptedReasoningFallback = responseController.acceptedReasoningText(),
                thinkingMode = thinkingMode
            )
            val finalFormatted = responseController.formatFinal(finalized.answerText, finalized.reasoningText)
            publisher.finishExplicit(finalFormatted)
            timing?.endGeneration()
            val totalTokens = lastGeneratedTokens ?: maxOf(1, Math.round((finalized.answerText.length + rawReasoning.length) / 3.8).toInt())
            val metrics = timing?.buildMetrics(
                requestId = requestId,
                sessionId = sessionId,
                attempt = currentAttemptId,
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
                attemptId = currentAttemptId,
                rawAnswer = acceptedAnswer,
                rawReasoning = rawReasoning,
                isStopped = true,
                acceptedAnswerFallback = responseController.acceptedAnswerText(),
                acceptedReasoningFallback = responseController.acceptedReasoningText(),
                thinkingMode = thinkingMode
            )
            val finalFormatted = responseController.formatFinal(finalized.answerText, finalized.reasoningText)
            publisher.finishExplicit(finalFormatted)
            val partialTokens = lastGeneratedTokens ?: maxOf(1, Math.round(finalFormatted.length / 3.8).toInt())
            val metrics = timing?.buildMetrics(
                requestId = requestId,
                sessionId = sessionId,
                attempt = currentAttemptId,
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
                attemptId = currentAttemptId,
                rawAnswer = acceptedAnswer,
                rawReasoning = rawReasoning,
                isStopped = stopRequested,
                sdkError = if (stopRequested) null else t,
                acceptedAnswerFallback = responseController.acceptedAnswerText(),
                acceptedReasoningFallback = responseController.acceptedReasoningText(),
                thinkingMode = thinkingMode
            )
            val finalFormatted = responseController.formatFinal(finalized.answerText, finalized.reasoningText)
            publisher.finishExplicit(finalFormatted)
            val partialTokens = lastGeneratedTokens ?: maxOf(1, Math.round(finalFormatted.length / 3.8).toInt())
            val metrics = timing?.buildMetrics(
                requestId = requestId,
                sessionId = sessionId,
                attempt = currentAttemptId,
                generatedTokens = partialTokens,
                isTokenCountEstimated = true
            )
            finalized.toExecutionResult(metrics)
        } finally {
            if (decodedBitmap != null && decodedBitmap != bitmap) {
                runCatching { decodedBitmap.recycle() }
            }
        }
    }

    suspend fun classify(bitmap: Bitmap): List<VisionLabel> = withContext(Dispatchers.IO) {
        emptyList()
    }

    /** Retain failed initializations too: native cleanup may require an app restart. */
    internal fun initializeGgufEngine(engine: LlamaCppEngine, initialize: () -> Unit) {
        llamaCppEngine = engine
        initialize()
    }

    fun close(): ShutdownResult {
        val wasMediaPipeVision = runtime == RuntimeKind.MediaPipeVision
        isBeingClosed = true
        cancelGeneration()
        var closeError: Throwable? = null

        val sessionToClose = activeMediaPipeSession
        val callbackDone = activeMediaPipeCallbackDone
        if (wasMediaPipeVision) {
            Log.w(TAG, "MediaPipe vision native runtime cannot be safely closed without process restart")
            if (closeError == null) {
                closeError = IllegalStateException("MediaPipe vision native runtime cannot be safely closed without process restart")
            }
        } else if (sessionToClose != null) {
            if (waitForCallbackToReturn(callbackDone)) {
                try {
                    sessionToClose.close()
                    activeMediaPipeSession = null
                    activeMediaPipeCallbackDone = null
                } catch (t: Throwable) {
                    if (closeError == null) closeError = t
                }
            } else {
                Log.w(TAG, "MediaPipe callback did not return before timeout during close")
                if (closeError == null) {
                    closeError = IllegalStateException("MediaPipe callback timed out during close")
                }
            }
        }

        if (!wasMediaPipeVision && mediaPipeInference != null) {
            if (closeError != null) {
                isBeingClosed = false
                return ShutdownResult.Failed(closeError)
            }
            try {
                mediaPipeInference?.close()
                mediaPipeInference = null
            } catch (t: Throwable) {
                closeError = t
            }
        }

        if (liteRtConversation != null) {
            try {
                liteRtConversation?.close()
                liteRtConversation = null
            } catch (t: Throwable) {
                if (closeError == null) closeError = t
            }
        }

        if (liteRtEngine != null) {
            if (closeError != null) {
                isBeingClosed = false
                return ShutdownResult.Failed(closeError)
            }
            try {
                liteRtEngine?.closeIfInitialized()
                liteRtEngine = null
            } catch (t: Throwable) {
                closeError = t
            }
        }

        if (llamaCppEngine != null) {
            try {
                llamaCppEngine?.close()
                llamaCppEngine = null
            } catch (t: Throwable) {
                if (closeError == null) closeError = t
            }
        }

        if (closeError != null) {
            isBeingClosed = false
            return ShutdownResult.Failed(closeError)
        }

        currentGgufBundle = null
        currentGgufModelPath = ""
        currentGgufVerifiedSha = ""

        LiteRtRuntimeCache.release(context, liteRtCacheDir)
        liteRtCacheDir = null

        try {
            activeModelLease?.close()
            activeModelLease = null
        } catch (t: Throwable) {
            isBeingClosed = false
            return ShutdownResult.Failed(t)
        }

        runtimeContextCapacity.clear()
        runtime = RuntimeKind.None
        activeBackendDisplayName = ""
        configuredCapabilities = null
        resolvedThinkingCapability = ThinkingCapability.Unknown
        isDefaultThinkingModel = false
        supportsThinkingMode = false
        isBeingClosed = false
        AppCacheCleaner.clearAppCache(context)

        return ShutdownResult.Success
    }

    fun cancelGeneration() {
        stopRequested = true
        runCatching { llamaCppEngine?.stop() }.onFailure { error ->
            Log.w(TAG, "Native stop failed; shutdown will retain ownership until close succeeds", error)
        }
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
        val extraCtx = ThinkingModeConfig.liteRtExtraContext(
            capability = resolvedThinkingCapability,
            userPreference = thinkingMode
        )

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
                        visionBackend = if (configuredCapabilities?.capabilities?.supportsVision == true) backend else null,
                        audioBackend = audioBackendCandidate,
                        maxNumTokens = effectiveMaxTokens,
                        cacheDir = runtimeCacheDir.absolutePath
                    )
                )
                engine = eng
                runtimeContextCapacity.initialize(effectiveMaxTokens) { eng.initialize() }
                initialized = true
            } catch (t: Throwable) {
                Log.w(TAG, "LiteRT-LM init with audioBackend=$audioBackendCandidate and maxTokens=$effectiveMaxTokens failed: ${t.message}", t)
                engine?.closeIfInitialized()
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
                            visionBackend = if (configuredCapabilities?.capabilities?.supportsVision == true) backend else null,
                            audioBackend = audioBackendCandidate,
                            maxNumTokens = effectiveMaxTokens,
                            cacheDir = runtimeCacheDir.absolutePath
                        )
                    )
                    engine = eng
                    runtimeContextCapacity.initialize(effectiveMaxTokens) { eng.initialize() }
                    initialized = true
                    Log.i(TAG, "LiteRT-LM audio backend fell back to CPU successfully")
                } catch (t: Throwable) {
                    Log.w(TAG, "LiteRT-LM init with CPU audioBackend failed: ${t.message}", t)
                    engine?.closeIfInitialized()
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
                            visionBackend = if (configuredCapabilities?.capabilities?.supportsVision == true) backend else null,
                            audioBackend = null,
                            maxNumTokens = effectiveMaxTokens,
                            cacheDir = runtimeCacheDir.absolutePath
                        )
                    )
                    engine = eng
                    runtimeContextCapacity.initialize(effectiveMaxTokens) { eng.initialize() }
                    initialized = true
                    Log.w(TAG, "LiteRT-LM initialized with audio disabled")
                } catch (t: Throwable) {
                    Log.w(TAG, "LiteRT-LM init with audio disabled failed: ${t.message}", t)
                    engine?.closeIfInitialized()
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
                            visionBackend = if (configuredCapabilities?.capabilities?.supportsVision == true) backend else null,
                            audioBackend = null,
                            maxNumTokens = 2048,
                            cacheDir = runtimeCacheDir.absolutePath
                        )
                    )
                    engine = eng
                    runtimeContextCapacity.initialize(2048) { eng.initialize() }
                    initialized = true
                    Log.w(TAG, "LiteRT-LM initialized with safe 2048 tokens fallback")
                } catch (t: Throwable) {
                    Log.w(TAG, "LiteRT-LM init with safe 2048 tokens fallback failed: ${t.message}", t)
                    engine?.closeIfInitialized()
                    engine = null
                }
            }

            modelSupportsAudioInput = finalAudioSupported
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
            if (stopRequested || isBeingClosed) throw CancellationException("Model initialization cancelled")
            InitCrashGuard.markInitSucceeded(context, modelId)
            Log.i(TAG, "LiteRT-LM vision engine ready on $backend (audio: $finalAudioSupported): $modelPath")
        } catch (t: Throwable) {
            InitCrashGuard.markInitFailed(context, modelId)
            try {
                engine?.closeIfInitialized()
                liteRtEngine = null
                liteRtConversation = null
            } catch (closeError: Throwable) {
                liteRtEngine = engine
                if (closeError !== t) t.addSuppressed(closeError)
                throw t
            }
            LiteRtRuntimeCache.release(context, liteRtCacheDir)
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
            mediaPipeInference = runtimeContextCapacity.initialize(targetTokens) { LlmInference.createFromOptions(context, options) }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to initialize MediaPipe with $targetTokens tokens (audio=$supportsAudioInput): ${t.message}, retrying safe fallback", t)
            val fallbackOptions = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(1024)
                .setMaxTopK(40)
                .setMaxNumImages(1)
                .setPreferredBackend(LlmInference.Backend.CPU)
                .build()
            mediaPipeInference = runtimeContextCapacity.initialize(1024) { LlmInference.createFromOptions(context, fallbackOptions) }
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
        publisher: StreamingResponsePublisher? = null,
        responseController: InferenceResponseController? = null,
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
        val requestedThinking = ThinkingModeConfig.resolveRequestThinking(
            capability = resolvedThinkingCapability,
            userPreference = thinkingMode
        )
        val requestContext = ThinkingModeConfig.liteRtExtraContext(
            capability = resolvedThinkingCapability,
            userPreference = thinkingMode
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
            val controlledPrompt = LegacyThinkingProfiles.applyPrompt(effectivePromptText, configuredCapabilities?.capabilities?.thinkingControl ?: ThinkingControlStrategy.None, thinkingMode)
            val textContent = if (controlledPrompt.isNotBlank()) Content.Text(controlledPrompt) else null
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
                reasoningChannelKeys = configuredCapabilities?.capabilities?.reasoningChannels.orEmpty(),
                startsInReasoning = configuredCapabilities?.capabilities?.reasoningPrefill == true,
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
                },
                onSnapshot = { snap ->
                    responseController?.appendStructured(
                        answer = snap.answerText,
                        reasoning = snap.reasoningText,
                        isThinkingActive = snap.isThinkingActive,
                        reqId = requestId,
                        attemptId = 1,
                        replaceSnapshot = true
                    )
                    publisher?.notifyStructuredUpdate()
                }
            )
            activeLiteRtAdapter = adapter
            try {
                conversation.sendMessageAsync(
                    message,
                    adapter,
                    requestContext,
                    thinkingConfig = if (configuredCapabilities?.capabilities?.thinkingControl == ThinkingControlStrategy.QwenSoftSwitch) null else ThinkingConfig(enableThinking = requestedThinking)
                )
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

    private suspend fun streamMediaPipeVision(
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
                            kotlinx.coroutines.delay(60)
                        } else {
                            throw t
                        }
                    }
                }
                created ?: throw IllegalStateException("Failed to create vision session")
            } catch (cancelled: CancellationException) {
                throw cancelled
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

    private suspend fun streamLlamaCppVision(
        bitmap: Bitmap?,
        prompt: String,
        messages: List<Pair<String, String>> = emptyList(),
        multiTurnImageFiles: Map<Int, File> = emptyMap(),
        multiTurnAudioFiles: Map<Int, File> = emptyMap(),
        thinkingMode: Boolean = false,
        requestId: String = "",
        audioFile: File? = null,
        audioBytes: ByteArray? = null,
        timing: InferenceTiming? = null,
        onProgress: ((RequestProgress) -> Unit)? = null,
        onTokensGenerated: ((Int) -> Unit)? = null,
        responseController: InferenceResponseController? = null,
        publisher: StreamingResponsePublisher? = null,
        responseSnapshot: () -> String,
        onUpdate: (String) -> Unit,
        onAttemptChanged: ((Int) -> Unit)? = null
    ): String {
        val engine = llamaCppEngine ?: throw IllegalStateException("LlamaCpp multimodal engine not initialized")
        val conversationMessages = if (messages.isNotEmpty()) messages else listOf("user" to prompt)
        val targetMessageIndex = conversationMessages.lastIndex.coerceAtLeast(0)
        val mediaInputs = mutableListOf<MultimodalMediaInput>()

        // Multi-turn prior images
        for ((msgIdx, file) in multiTurnImageFiles) {
            if (msgIdx != targetMessageIndex && file.exists() && file.isFile) {
                val decoded = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
                if (decoded != null) {
                    val width = decoded.width
                    val height = decoded.height
                    val pixels = IntArray(width * height)
                    decoded.getPixels(pixels, 0, width, 0, 0, width, height)
                    val rgb = ByteArray(width * height * 3)
                    var outIdx = 0
                    for (pixel in pixels) {
                        rgb[outIdx++] = ((pixel shr 16) and 0xFF).toByte()
                        rgb[outIdx++] = ((pixel shr 8) and 0xFF).toByte()
                        rgb[outIdx++] = (pixel and 0xFF).toByte()
                    }
                    mediaInputs.add(MultimodalMediaInput.Image(rgb, width, height, messageIndex = msgIdx))
                    decoded.recycle()
                }
            }
        }

        if (bitmap != null) {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val rgb = ByteArray(width * height * 3)
            var outIdx = 0
            for (pixel in pixels) {
                rgb[outIdx++] = ((pixel shr 16) and 0xFF).toByte()
                rgb[outIdx++] = ((pixel shr 8) and 0xFF).toByte()
                rgb[outIdx++] = (pixel and 0xFF).toByte()
            }
            mediaInputs.add(MultimodalMediaInput.Image(rgb, width, height, messageIndex = targetMessageIndex))
        }

        val targetSampleRate = engine.getMetadata()?.audioSampleRate?.takeIf { it > 0 } ?: AudioUtils.TARGET_SAMPLE_RATE

        // Multi-turn prior audio
        for ((msgIdx, file) in multiTurnAudioFiles) {
            if (msgIdx != targetMessageIndex && file.exists() && file.isFile) {
                val floatPcm = AudioUtils.decodeAudioToFloatPcm(context, Uri.fromFile(file), targetSampleRate = targetSampleRate)
                mediaInputs.add(MultimodalMediaInput.Audio(floatPcm, messageIndex = msgIdx))
            }
        }

        if (audioBytes != null && audioBytes.isNotEmpty()) {
            val floatPcm = AudioUtils.parseWavOrPcmToFloatPcm(audioBytes, targetSampleRate = targetSampleRate)
            mediaInputs.add(MultimodalMediaInput.Audio(floatPcm, messageIndex = targetMessageIndex))
        } else if (audioFile != null && audioFile.exists()) {
            val floatPcm = AudioUtils.decodeAudioToFloatPcm(context, Uri.fromFile(audioFile), targetSampleRate = targetSampleRate)
            mediaInputs.add(MultimodalMediaInput.Audio(floatPcm, messageIndex = targetMessageIndex))
        }

        // Sort media inputs strictly by messageIndex to match the sequential order of message markers
        mediaInputs.sortBy { it.messageIndex }

        var firstTokenRecorded = false
        var generatedTokens = 0
        val isVulkan = engine.activeBackend == LlamaBackend.VULKAN

        try {
            val result = engine.generateMultimodalStream(
                prompt = prompt,
                messages = conversationMessages,
                mediaInputs = mediaInputs,
                samplingParams = profileSampling(),
                enableThinking = thinkingMode,
                onMetrics = { metrics ->
                    generatedTokens = metrics.tokenCount
                },
                onFirstToken = {
                    timing?.recordFirstToken()
                    onProgress?.invoke(RequestProgress.Generating)
                },
                onStructuredToken = { answer, reasoning, isThinkingActive ->
                    responseController?.appendStructured(
                        answer = answer,
                        reasoning = reasoning,
                        isThinkingActive = isThinkingActive,
                        reqId = requestId,
                        attemptId = 1
                    )
                    publisher?.notifyStructuredUpdate()
                }
            ) { token ->
                if (stopRequested) return@generateMultimodalStream
                if (!firstTokenRecorded) {
                    firstTokenRecorded = true
                    onProgress?.invoke(RequestProgress.Generating)
                }
                timing?.recordChunk(token)
                onUpdate(token)
            }

            if (result == LlamaGenerationResult.Stopped || stopRequested) {
                throw kotlinx.coroutines.CancellationException("Generation stopped by user")
            }

            onTokensGenerated?.invoke(generatedTokens)
            return responseSnapshot()
        } catch (e: LlamaExecutionException) {
            val isRecoverable = isVulkan && (
                e.category == LlamaFailureCategory.NumericalInstability ||
                e.category == LlamaFailureCategory.BackendExecution ||
                e.category == LlamaFailureCategory.GenerationDecodeFailed ||
                e.category == LlamaFailureCategory.PromptDecodeFailed
            )

            if (isRecoverable && !stopRequested && (currentGgufModelPath.isNotBlank() || currentGgufBundle != null)) {
                Log.w(TAG, "Recoverable Vulkan error (${e.category}: ${e.message}). Releasing GPU context and retrying on CPU...")
                onProgress?.invoke(RequestProgress.RecoveringOnCpu)
                engine.close()

                // Clear failed partial response
                onAttemptChanged?.invoke(2)
                responseController?.beginAttempt(2)
                publisher?.beginAttempt(2) ?: onUpdate("")

                val cpuEngine = LlamaCppEngine(context)
                try {
                    initializeGgufEngine(cpuEngine) {
                        val bundleToUse = currentGgufBundle
                        if (bundleToUse != null) {
                            val actualSha = GgufGpuValidator.resolveArtifactFingerprint(context, bundleToUse)
                            check(actualSha == currentGgufVerifiedSha) { "Selected model changed during generation; reload it" }
                            cpuEngine.initializeBundle(
                                context = context,
                                bundle = bundleToUse,
                                threads = currentGgufThreads,
                                prefillThreads = currentGgufPrefillThreads,
                                contextWindow = currentGgufContext,
                                nBatch = currentGgufBatch,
                                nUbatch = currentGgufUbatch,
                                backend = LlamaBackend.CPU,
                                nGpuLayers = 0,
                                expectedFileSizeBytes = currentGgufExpectedBytes
                            )
                        } else {
                            val actualSha = GgufGpuValidator.resolveModelSha256(currentGgufModelPath, context = context)
                            check(actualSha == currentGgufVerifiedSha) { "Selected model changed during generation; reload it" }
                            cpuEngine.initialize(
                                modelPath = currentGgufModelPath,
                                threads = currentGgufThreads,
                                prefillThreads = currentGgufPrefillThreads,
                                contextWindow = currentGgufContext,
                                nBatch = currentGgufBatch,
                                nUbatch = currentGgufUbatch,
                                backend = LlamaBackend.CPU,
                                nGpuLayers = 0,
                                expectedFileSizeBytes = currentGgufExpectedBytes
                            )
                        }
                    }
                } catch (failure: Throwable) {
                    cpuEngine.close()
                    llamaCppEngine = null
                    runtime = RuntimeKind.None
                    throw failure
                }
                llamaCppEngine = cpuEngine
                activeBackendDisplayName = "GGUF CPU (Vulkan recovery fallback: ${e.category})"

                // Retry generation once on CPU
                var retryFirstTokenRecorded = false
                var retryGeneratedTokens = 0
                val retryResult = cpuEngine.generateMultimodalStream(
                    prompt = prompt,
                    messages = conversationMessages,
                    mediaInputs = mediaInputs,
                    samplingParams = profileSampling(),
                    enableThinking = thinkingMode,
                    onMetrics = { metrics ->
                        retryGeneratedTokens = metrics.tokenCount
                    },
                    onFirstToken = {
                        timing?.recordFirstToken()
                        onProgress?.invoke(RequestProgress.Generating)
                    },
                    onStructuredToken = { answer, reasoning, isThinkingActive ->
                        responseController?.appendStructured(
                            answer = answer,
                            reasoning = reasoning,
                            isThinkingActive = isThinkingActive,
                            reqId = requestId,
                            attemptId = 2
                        )
                        publisher?.notifyStructuredUpdate()
                    }
                ) { token ->
                    if (stopRequested) return@generateMultimodalStream
                    if (!retryFirstTokenRecorded) {
                        retryFirstTokenRecorded = true
                        onProgress?.invoke(RequestProgress.Generating)
                    }
                    timing?.recordChunk(token)
                    onUpdate(token)
                }

                if (retryResult == LlamaGenerationResult.Stopped || stopRequested) {
                    throw kotlinx.coroutines.CancellationException("Generation stopped by user")
                }

                onTokensGenerated?.invoke(retryGeneratedTokens)
                return responseSnapshot()
            }
            throw e
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
        val scaledBitmap = try {
            if (width > maxDimension || height > maxDimension) {
                val scale = maxDimension.toFloat() / maxOf(width, height)
                val destW = (width * scale).toInt().coerceAtLeast(1)
                val destH = (height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(this, destW, destH, true)
            } else {
                null
            }
        } catch (_: Throwable) {
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
        LiteRtLm,
        LlamaCpp
    }
}
