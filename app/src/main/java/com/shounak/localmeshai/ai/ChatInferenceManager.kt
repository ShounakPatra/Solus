@file:Suppress("DEPRECATION")
package com.shounak.localmeshai.ai

import com.shounak.localmeshai.ai.tools.ConversationToolSettings
import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.tool
import com.shounak.localmeshai.ai.tools.CalculatorTool
import com.shounak.localmeshai.ai.tools.CurrentDateTimeTool
import com.shounak.localmeshai.ai.tools.ToolUseRecorder
import com.shounak.localmeshai.ai.tools.ToolUse
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.ProgressListener
import com.shounak.localmeshai.models.ArtifactSource
import com.shounak.localmeshai.models.ModelCapabilities
import com.shounak.localmeshai.models.ModelCapabilitySnapshot
import com.shounak.localmeshai.models.ModelFormat
import com.shounak.localmeshai.utils.AppCacheCleaner
import com.shounak.localmeshai.utils.AppSettings
import com.shounak.localmeshai.utils.ChatPromptPolicy
import com.shounak.localmeshai.utils.ChatRuntimePolicy
import com.shounak.localmeshai.utils.DeviceUtils
import com.shounak.localmeshai.utils.InferenceBackend
import com.shounak.localmeshai.utils.InferenceTiming
import com.shounak.localmeshai.utils.InitCrashGuard
import com.shounak.localmeshai.utils.LiteRtRuntimeCache
import com.shounak.localmeshai.utils.ModelArtifactStorage
import com.shounak.localmeshai.utils.ModelDownloader
import com.shounak.localmeshai.utils.ModelOutputSanitizer
import com.shounak.localmeshai.utils.ModelResponseQuality
import com.shounak.localmeshai.utils.ModelSourceResolver
import com.shounak.localmeshai.utils.NonGgufBundleInspector
import com.shounak.localmeshai.utils.PromptMathNormalizer
import com.shounak.localmeshai.utils.StreamingResponsePublisher
import com.shounak.localmeshai.utils.ThinkingModeConfig
import com.shounak.localmeshai.utils.ThinkingTextUtils
import java.io.File
import java.util.Locale
import java.util.concurrent.CancellationException as FutureCancellationException
import java.util.concurrent.Future
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

class ChatInferenceManager(context: Context) {
    private val context: Context = context.applicationContext
    private var mediaPipeInference: LlmInference? = null
    private var liteRtEngine: Engine? = null
    private var liteRtConversation: Conversation? = null
    private val liteRtToolRecorder = ToolUseRecorder()
    val lastLiteRtToolsUsed: List<ToolUse> get() = liteRtToolRecorder.snapshot()
    private var ggufConversationTools: List<com.shounak.localmeshai.ai.tools.ChatTool> = emptyList()
    private var ggufConversationHasTools = false
    private val ggufToolRecorder = ToolUseRecorder()
    val lastToolsUsed: List<ToolUse> get() = when (runtime) {
        RuntimeKind.LlamaCpp -> ggufToolRecorder.snapshot()
        RuntimeKind.LiteRtLm -> lastLiteRtToolsUsed
        else -> emptyList()
    }
    private var lastToolsRequestId = ""
    fun toolsUsedForRequest(requestId: String): List<ToolUse> = if (requestId == lastToolsRequestId) lastToolsUsed else emptyList()
    private val _toolTurnState = kotlinx.coroutines.flow.MutableStateFlow<com.shounak.localmeshai.models.state.ToolTurnState>(
        com.shounak.localmeshai.models.state.ToolTurnState.Idle
    )
    val toolTurnState: kotlinx.coroutines.flow.StateFlow<com.shounak.localmeshai.models.state.ToolTurnState> = _toolTurnState.asStateFlow()
    private var liteRtToolsDisabledForLoad = false
    private var retryToolsNextTurn = false
    @Volatile private var documentSearchDocumentIds: Set<String> = emptySet()
    private var liteRtConversationHasTools = false
    private var liteRtCompletedTurns = 0
    private var liteRtCacheDir: File? = null
    private var llamaCppEngine: LlamaCppEngine? = null
    private var portableTextEngine: PortableTextEngine? = null
    private var profileModelId: String = ""
    private fun profileSampling(): LlamaSamplingParams {
        val profile = com.shounak.localmeshai.features.WorkspacePreferences.get(context).profile(profileModelId)
        val appData = AppSettings.getInstance(context).settings.value
        val maxTokens = if (appData.defaultMaxLength > 0) appData.defaultMaxLength else MAX_RESPONSE_TOKENS
        val defaultTemp = if (runtime == RuntimeKind.MediaPipe &&
            profile.temperature == null &&
            kotlin.math.abs(appData.defaultTemperature - AppSettings.DEFAULT_TEMPERATURE) < 0.01f
        ) {
            mediaPipeTemperature
        } else {
            profile.temperature ?: appData.defaultTemperature
        }
        return LlamaSamplingParams(
            temperature = defaultTemp,
            topP = profile.topP ?: appData.defaultTopP,
            topK = profile.topK ?: appData.defaultTopK,
            maxTokens = maxTokens
        )
    }

    private fun configuredLiteRtMaxOutputTokens(): Int? {
        val configured = AppSettings.getInstance(context).settings.value.defaultMaxLength
        return if (configured > 0) configured else null
    }

    private var runtime = RuntimeKind.None
    private var activeModelLease: AutoCloseable? = null
    @Volatile private var activeMediaPipeSession: LlmInferenceSession? = null
    @Volatile private var activeMediaPipeFuture: Future<String>? = null
    @Volatile private var stopRequested = false
    @Volatile private var activeLiteRtAdapter: LiteRtStreamAdapter? = null
    @Volatile private var lastLiteRtSnapshot: StructuredResponseSnapshot? = null
    @Volatile private var isBeingClosed = false
    @Volatile private var configuredCapabilities: ModelCapabilitySnapshot? = null
    @Volatile private var liteRtConversationThinking: Boolean? = null
    @Volatile private var initialThinkingPreference: Boolean = false
    @Volatile private var conversationToolSettings: ConversationToolSettings? = null
    val needsToolSettingsRefresh: Boolean
        get() = retryToolsNextTurn || (runtime == RuntimeKind.LiteRtLm || runtime == RuntimeKind.LlamaCpp) && conversationToolSettings?.requiresRefresh(
            ConversationToolSettings.from(AppSettings.getInstance(context).settings.value)
        ) == true

    val usesStatefulHistory: Boolean get() = runtime == RuntimeKind.LiteRtLm
    val hasActiveStatefulConversation: Boolean get() = runtime == RuntimeKind.LiteRtLm && liteRtConversation != null && liteRtMessageCount > 0
    private val runtimeContextCapacity = RuntimeContextCapacity()
    val runtimeCapabilities: ModelCapabilitySnapshot?
        get() = if (!isReady) null else configuredCapabilities?.let { snapshot ->
            val resolvedTemplate = snapshot.capabilities.chatTemplate
                ?: llamaCppEngine?.getMetadata()?.chatTemplate?.takeIf { it.isNotBlank() }
            snapshot.copy(initializedContextTokens = runtimeContextCapacity.tokens, capabilities = snapshot.capabilities.copy(
                chatTemplate = resolvedTemplate,
                supportsVision = false,
                supportsAudio = false
            ))
        }
    val isReady: Boolean get() = when (runtime) {
        RuntimeKind.LlamaCpp -> llamaCppEngine?.isInitialized == true
        RuntimeKind.LiteRtLm -> liteRtConversation != null
        RuntimeKind.MediaPipe -> mediaPipeInference != null
        RuntimeKind.Portable -> portableTextEngine != null
        RuntimeKind.None -> false
    }
    val usesStructuredHistory: Boolean get() = runtime == RuntimeKind.LlamaCpp || runtime == RuntimeKind.Portable
    @Volatile private var activeOperationControlId: Long = 0L
    @Volatile private var mediaPipeTemperature = DEFAULT_TEMPERATURE
    @Volatile private var liteRtMessageCount = 0
    var activeBackendDisplayName: String = ""
        private set
    private var requestsSinceLoad = 0
    private var warmupMs = 0L
    private var runtimeThreads = 0
    @Volatile private var currentGgufVerifiedSha: String = ""
    @Volatile private var currentGgufModelPath: String = ""
    @Volatile private var currentGgufThreads: Int = 4
    @Volatile private var currentGgufPrefillThreads: Int = 4
    @Volatile private var currentGgufContext: Int = 2048
    @Volatile private var currentGgufBatch: Int = 256
    @Volatile private var currentGgufUbatch: Int = 128
    @Volatile private var currentGgufExpectedBytes: Long = 0L
    @Volatile private var currentGgufBundle: com.shounak.localmeshai.models.ModelBundle? = null

    companion object {
        private const val TAG = "ChatInferenceManager"
        private const val MAX_RESPONSE_TOKENS = 1024
        private const val MEDIA_PIPE_MAX_TOP_K = 200
        private const val DEFAULT_TEMPERATURE = 0.7f
        private const val TINY_MODEL_TEMPERATURE = 0.35f
        private const val RETRY_TEMPERATURE = 0.2f

        private val _isGgufActive = MutableStateFlow(false)
        val isGgufActive: StateFlow<Boolean> = _isGgufActive.asStateFlow()
    }

    /**
     * Initializes the inference engine.
     *
     * Uses LiteRT-LM Conversation for .litertlm files and Gemma chat task bundles.
     * Current LiteRT-LM accepts both containers and applies their native chat
     * template; the older stateless MediaPipe completion path does not preserve
     * the Gemma conversation contract reliably.
     * WARNING: Engine.initialize() can call native abort() which kills the process.
     * We use InitCrashGuard to detect this and block the model on next launch.
     *
     * @param modelId the catalog ID used for crash-guard tracking
     */
    suspend fun initialize(
        modelPath: String,
        modelId: String = "",
        modelName: String = "",
        modelSize: String = "",
        allowUnsafeOverride: Boolean = false,
        /**
         * Expected GGUF file size in bytes for artifact integrity validation. 0L = no check.
         * Pass [ModelInfo.expectedFileSizeBytes] from the catalog entry.
         */
        expectedFileSizeBytes: Long = 0L,
        contextWindowTokens: Int? = null,
        backendPreferenceOverride: String? = null,
        threadCountOverride: Int? = null,
        operationControlId: Long = 0L,
        onModelProgress: ((stage: ModelLoadStage, completed: Long?, total: Long?) -> Unit)? = null,
        onProgress: ((RequestProgress) -> Unit)? = null,
        bundle: com.shounak.localmeshai.models.ModelBundle? = null,
        initialThinkingPreference: Boolean = false
    ) {
        profileModelId = modelId.ifBlank { bundle?.id ?: File(modelPath).nameWithoutExtension }
        val configuredContextWindow = contextWindowTokens

        val file = if (bundle != null && bundle.isStorageImport) null else File(modelPath)
        if (file != null) {
            if (!file.exists()) {
                throw IllegalArgumentException("Model file not found at $modelPath")
            }
        }

        val effectiveId = modelId.ifBlank { bundle?.id ?: file?.nameWithoutExtension.orEmpty() }
        val effectiveName = modelName.ifBlank { bundle?.displayName ?: file?.nameWithoutExtension.orEmpty() }
        val shutdownResult = close()
        requestsSinceLoad = 0
        warmupMs = 0
        runtimeThreads = threadCountOverride?.takeIf { it > 0 } ?: RuntimeExecutionPolicy.resolve(context, effectiveId).cpuThreads
        if (shutdownResult is ShutdownResult.Failed) {
            throw IllegalStateException("Cannot initialize model: previous engine failed to shut down cleanly (${shutdownResult.error.message})", shutdownResult.error)
        }
        com.shounak.localmeshai.data.StartupCoordinator.getInstance(context).awaitCrashGuardChecked()
        var modelLease = file?.let { com.shounak.localmeshai.utils.ModelArtifactStorage.leaseArtifact(it) }
        activeModelLease = modelLease
        // Register active operation control handle only AFTER closing prior runtime!
        activeOperationControlId = operationControlId
        if (operationControlId != 0L && DefaultLlamaNativeBridge.getOperationControlProgress(operationControlId) == -2) {
            modelLease?.close()
            activeModelLease = null
            throw CancellationException("Operation was cancelled before initialization")
        }

        try {
        AppCacheCleaner.clearAppCache(context)
        this.initialThinkingPreference = initialThinkingPreference
        val searchString = "$modelPath|$effectiveId|$effectiveName".lowercase()
        configuredCapabilities = ModelCapabilitySnapshot(
            effectiveId,
            ModelCapabilitySnapshot.revision(bundle, modelPath),
            bundle?.capabilities ?: file?.takeIf {
                NonGgufBundleInspector.detectFormat(it) in setOf(ModelFormat.LiteRtLm, ModelFormat.MediaPipeTask)
            }?.let { NonGgufBundleInspector.inspectFile(it).capabilities } ?: ModelCapabilities()
        )
        val isTinyGemmaMediaPipeModel = listOf(
            "gemma3-1b",
            "gemma-3-1b",
            "gemma 3 1b",
            "gemma3-270m",
            "gemma-3-270m",
            "gemma 3 270m"
        ).any(searchString::contains)
        mediaPipeTemperature = if (isTinyGemmaMediaPipeModel) {
            TINY_MODEL_TEMPERATURE
        } else {
            DEFAULT_TEMPERATURE
        }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        stopRequested = false
        isBeingClosed = false

        val appSettings = AppSettings.getInstance(context)
        val pref = (backendPreferenceOverride?.takeIf { it.isNotBlank() }
            ?: appSettings.settings.value.llamaBackendPreference).uppercase()

        val detectedFormat = bundle?.format ?: file?.let { NonGgufBundleInspector.detectFormat(it) }
        val isGguf = detectedFormat == ModelFormat.GGUF
        if (isGguf) {
            val profile = GgufExecutionProfile.resolve(
                modelId = effectiveId,
                context = context,
                modelNameOrId = effectiveName.ifBlank { effectiveId },
                modelSize = modelSize,
                customContextTokens = configuredContextWindow,
                backendPreference = pref
            )
            val dynamicContext = profile.contextTokens
            val dynamicThreads = threadCountOverride?.takeIf { it > 0 } ?: profile.decodeThreads
            val prefillThreads = threadCountOverride?.takeIf { it > 0 } ?: profile.prefillThreads
            val nBatch = profile.batchTokens
            val nUbatch = profile.microBatchTokens

            val companionSummary = if (bundle != null) {
                "${bundle.mainArtifacts.size}_parts_${bundle.projectorArtifact?.displayName.orEmpty()}"
            } else ""

            onModelProgress?.invoke(ModelLoadStage.VerifyingFile, null, null)
            val verifiedSha = if (bundle != null) {
                GgufGpuValidator.resolveArtifactFingerprint(
                    context = context,
                    bundle = bundle,
                    isCancelled = { stopRequested },
                    onProgress = { bytesRead, totalBytes ->
                        onModelProgress?.invoke(ModelLoadStage.VerifyingFile, bytesRead, totalBytes)
                    }
                )
            } else {
                GgufGpuValidator.resolveModelSha256(
                    modelPath = file!!.absolutePath,
                    modelId = effectiveId,
                    context = context,
                    isCancelled = { stopRequested },
                    onProgress = { bytesRead, totalBytes ->
                        onModelProgress?.invoke(ModelLoadStage.VerifyingFile, bytesRead, totalBytes)
                    }
                )
            }
            val requestedBackend = when (pref) {
                "VULKAN", "GPU", "NPU" -> LlamaBackend.VULKAN
                "CPU" -> LlamaBackend.CPU
                else -> null // Auto mode
            }

            val decision = GgufBackendPolicy.selectBackend(
                context = context,
                modelSha256 = verifiedSha,
                threads = dynamicThreads,
                contextWindow = dynamicContext,
                nBatch = nBatch,
                nUbatch = nUbatch,
                prefillThreads = prefillThreads,
                requestedBackend = requestedBackend,
                companionSummary = companionSummary
            )

            var selectedBackend = decision.selectedBackend
            var fallbackReason: String? = if (decision.isAuto && selectedBackend == LlamaBackend.CPU) decision.reason else null
            var engine = LlamaCppEngine(context)

            try {
                onModelProgress?.invoke(
                    ModelLoadStage.ContextPreparation(if (selectedBackend == LlamaBackend.VULKAN) "Vulkan" else "CPU"),
                    null,
                    null
                )
                initializeGgufEngine(engine) {
                    if (bundle != null) {
                        engine.initializeBundle(
                            context = context,
                            bundle = bundle,
                            threads = dynamicThreads,
                            prefillThreads = prefillThreads,
                            contextWindow = dynamicContext,
                            nBatch = nBatch,
                            nUbatch = nUbatch,
                            backend = selectedBackend,
                            nGpuLayers = if (selectedBackend == LlamaBackend.VULKAN) 99 else 0,
                            expectedFileSizeBytes = expectedFileSizeBytes,
                            operationControlId = operationControlId
                        )
                    } else {
                        engine.initialize(
                            modelPath = file!!.absolutePath,
                            threads = dynamicThreads,
                            prefillThreads = prefillThreads,
                            contextWindow = dynamicContext,
                            nBatch = nBatch,
                            nUbatch = nUbatch,
                            backend = selectedBackend,
                            nGpuLayers = if (selectedBackend == LlamaBackend.VULKAN) 99 else 0,
                            expectedFileSizeBytes = expectedFileSizeBytes,
                            operationControlId = operationControlId
                        )
                    }
                }
                if (RuntimeWarmup.enabled(context)) onModelProgress?.invoke(ModelLoadStage.WarmingUp, null, null)
                warmupMs = RuntimeWarmup.gguf(context, engine)
            } catch (t: Throwable) {
                if (t is CancellationException) { engine.close(); throw t }
                if (selectedBackend == LlamaBackend.VULKAN) {
                    Log.w(TAG, "Vulkan initialization failed, falling back to true CPU", t)
                    fallbackReason = "Vulkan initialization failed: ${t.message}"
                    onModelProgress?.invoke(ModelLoadStage.ContextPreparation("CPU"), null, null)
                    engine.close() // Release failed GPU engine completely before retrying
                    val cpuRetryEngine = LlamaCppEngine(context)
                    try {
                        initializeGgufEngine(cpuRetryEngine) {
                            if (bundle != null) {
                                cpuRetryEngine.initializeBundle(
                                    context = context,
                                    bundle = bundle,
                                    threads = dynamicThreads,
                                    prefillThreads = prefillThreads,
                                    contextWindow = dynamicContext,
                                    nBatch = nBatch,
                                    nUbatch = nUbatch,
                                    backend = LlamaBackend.CPU,
                                    nGpuLayers = 0,
                                    expectedFileSizeBytes = expectedFileSizeBytes,
                                    operationControlId = operationControlId
                                )
                            } else {
                                cpuRetryEngine.initialize(
                                    modelPath = file!!.absolutePath,
                                    threads = dynamicThreads,
                                    prefillThreads = prefillThreads,
                                    contextWindow = dynamicContext,
                                    nBatch = nBatch,
                                    nUbatch = nUbatch,
                                    backend = LlamaBackend.CPU,
                                    nGpuLayers = 0,
                                    expectedFileSizeBytes = expectedFileSizeBytes,
                                    operationControlId = operationControlId
                                )
                            }
                        }
                        if (RuntimeWarmup.enabled(context)) onModelProgress?.invoke(ModelLoadStage.WarmingUp, null, null)
                        warmupMs = RuntimeWarmup.gguf(context, cpuRetryEngine)
                        engine = cpuRetryEngine
                        selectedBackend = LlamaBackend.CPU
                    } catch (cpuError: Throwable) {
                        cpuRetryEngine.close()
                        llamaCppEngine = null
                        runtimeContextCapacity.clear()
                        runtime = RuntimeKind.None
                        throw cpuError
                    }
                } else {
                    engine.close()
                    llamaCppEngine = null
                    runtime = RuntimeKind.None
                    throw t
                }
            }

            if (selectedBackend == LlamaBackend.VULKAN && engine.activeBackend != LlamaBackend.VULKAN) {
                fallbackReason = "Vulkan initialization fell back to CPU"
            }
            llamaCppEngine = engine
            runtime = RuntimeKind.LlamaCpp
            conversationToolSettings = ConversationToolSettings.from(AppSettings.getInstance(context).settings.value)
            ggufConversationTools = toolsForNewConversation()
            ggufConversationHasTools = conversationToolSettings?.enabled == true && ggufConversationTools.isNotEmpty() && llamaCppEngine?.supportsToolCalling() == true
            _isGgufActive.value = true

            // Set current model configuration for recovery fallback
            currentGgufVerifiedSha = verifiedSha
            currentGgufBundle = bundle
            currentGgufModelPath = file?.absolutePath.orEmpty()
            currentGgufThreads = dynamicThreads
            currentGgufPrefillThreads = prefillThreads
            currentGgufContext = engine.actualConfiguration?.contextWindow?.takeIf { it > 0 } ?: dynamicContext
            runtimeContextCapacity.initialize(currentGgufContext) { Unit }
            currentGgufBatch = nBatch
            currentGgufUbatch = nUbatch
            currentGgufExpectedBytes = expectedFileSizeBytes

            activeBackendDisplayName = if (engine.activeBackend == LlamaBackend.VULKAN) {
                "GGUF Vulkan GPU"
            } else if (!fallbackReason.isNullOrBlank()) {
                "GGUF CPU ($fallbackReason)"
            } else {
                "GGUF CPU"
            }
            runtimeThreads = dynamicThreads
            Log.i(TAG, "LlamaCpp GGUF engine initialized for: $effectiveName on $activeBackendDisplayName (ctx: $dynamicContext, threads: $dynamicThreads, prefillThreads: $prefillThreads, batch: $nBatch/$nUbatch, warmupMs=$warmupMs)")
            activeModelLease = modelLease
            return
        }

        _isGgufActive.value = false

        val effectivePath = file?.absolutePath
            ?: (bundle?.mainArtifacts?.firstOrNull()?.source as? ArtifactSource.AppManaged)?.path
            ?: bundle?.let {
                val lease = ModelSourceResolver.resolve(context, it)
                modelLease = lease
                activeModelLease = lease
                lease.paths.firstOrNull()
            }
            ?: throw IllegalArgumentException("No accessible file found for model.")
        val effectiveFile = File(effectivePath)

        if (detectedFormat?.isPortable == true) {
            if (InitCrashGuard.isModelBlocked(context, effectiveId)) throw IllegalStateException(InitCrashGuard.blockedModelMessage())
            onModelProgress?.invoke(ModelLoadStage.VerifyingFile, null, null)
            InitCrashGuard.markInitStarted(context, effectiveId)
            val engine = try {
                PortableTextEngine.open(effectiveFile, detectedFormat, configuredContextWindow ?: 2048, runtimeThreads)
            } catch (t: Throwable) {
                InitCrashGuard.markInitFailed(context, effectiveId)
                throw t
            }
            portableTextEngine = engine
            try {
                if (RuntimeWarmup.enabled(context)) {
                    onModelProgress?.invoke(ModelLoadStage.WarmingUp, null, null)
                    warmupMs = RuntimeWarmup.portable(engine)
                }
            } catch (failure: Throwable) {
                InitCrashGuard.markInitFailed(context, effectiveId)
                throw failure
            }
            Log.i(TAG, "Portable text engine ready warmupMs=$warmupMs threads=$runtimeThreads")
            InitCrashGuard.markInitSucceeded(context, effectiveId)
            // Native construction is synchronous. Honor cancellation before publishing readiness;
            // the outer cleanup owns the newly constructed engine even on this path.
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (stopRequested) throw CancellationException("Model initialization cancelled")
            runtimeContextCapacity.initialize(engine.contextTokens) { Unit }
            runtime = RuntimeKind.Portable
            activeBackendDisplayName = detectedFormat.backendLabel
            activeModelLease = modelLease ?: ModelArtifactStorage.leaseArtifact(effectiveFile)
            return
        }

        val modelIdentity = listOf(effectiveId, modelName, effectiveFile.name).filter { it.isNotBlank() }.joinToString(" ")
        val shouldUseLiteRtLmConversation = ChatRuntimePolicy.shouldUseLiteRtLmConversation(effectiveFile.name, modelIdentity)
        val resolvedModelPath = effectiveFile.absolutePath

        if (shouldUseLiteRtLmConversation) {
            val npuSnapshot = NpuCapabilityDetector.detect(context, effectiveId)
            val canTryLiteRtNpu = (detectedFormat == ModelFormat.LiteRtLm || detectedFormat == ModelFormat.MediaPipeTask) &&
                npuSnapshot.litertLm.supported &&
                !InitCrashGuard.isNpuBlocked(context, effectiveId)
            var blockedAutoLoad = false
            val inferenceBackend = when {
                pref == "CPU" -> InferenceBackend.LITERT_CPU
                pref == "NPU" -> when {
                    canTryLiteRtNpu -> InferenceBackend.LITERT_NPU
                    DeviceUtils.supportsLiteRtLmGpu() -> InferenceBackend.LITERT_GPU
                    else -> InferenceBackend.LITERT_CPU
                }
                pref == "VULKAN" || pref == "GPU" -> InferenceBackend.LITERT_GPU
                InitCrashGuard.isModelBlocked(context, effectiveId) -> {
                    blockedAutoLoad = true
                    InferenceBackend.LITERT_CPU
                }
                else -> RuntimeSpeedTuner.liteRtBackend(context, effectiveId, effectiveFile.absolutePath, configuredContextWindow, canTryLiteRtNpu)
            }
            val canRetryOnCpu = DeviceUtils.canUseGemma4LiteRtCpuFallback(
                context = context,
                modelId = effectiveId,
                modelName = modelName.ifBlank { effectiveFile.nameWithoutExtension },
                modelSize = modelSize,
                fileName = effectiveFile.name
            )
            if (InitCrashGuard.isModelBlocked(context, effectiveId) && (!allowUnsafeOverride || !InitCrashGuard.isRetryAuthorized(context, effectiveId))) {
                throw IllegalStateException(InitCrashGuard.blockedModelMessage())
            }
            // Only an authorised Try Anyway reaches here; keep the GPU tune unless the recorded crash was a GPU attempt.
            if (blockedAutoLoad && InitCrashGuard.blockedCrashMayHaveUsedGpu(context, effectiveId)) {
                RuntimeSpeedTuner.recordGpuFailure(context, effectiveId, effectiveFile.absolutePath)
            }
            if (!allowUnsafeOverride && inferenceBackend == InferenceBackend.LITERT_GPU) {
                val (allowed, reason) = DeviceUtils.canInitializeLiteRtLm(
                    context = context,
                    modelId = effectiveId,
                    modelName = modelName.ifBlank { effectiveFile.nameWithoutExtension },
                    modelSize = modelSize,
                    isVision = false,
                    fileName = effectiveFile.name,
                    backendLabel = "LiteRT-LM",
                    modelFilePath = effectiveFile.absolutePath
                )
                if (!allowed) {
                    throw IllegalStateException(reason)
                }
            }
            var loadedBackend = inferenceBackend
            var fellBackToMediaPipe = false
            try {
                initLiteRtLm(resolvedModelPath, effectiveId, loadedBackend, configuredContextWindow) {
                    onModelProgress?.invoke(ModelLoadStage.WarmingUp, null, null)
                }
            } catch (failure: Exception) {
                if (failure is CancellationException || stopRequested || isBeingClosed || liteRtEngine != null) throw failure
                if (loadedBackend == InferenceBackend.LITERT_NPU && (pref == "AUTO" || pref == "NPU")) {
                    Log.w(TAG, "LiteRT-LM NPU init failed for $effectiveId; falling back to GPU -> CPU", failure)
                    liteRtCacheDir?.let { LiteRtRuntimeCache.release(context, it) }
                    liteRtCacheDir = null
                    val nextBackend = if (DeviceUtils.supportsLiteRtLmGpu() && !InitCrashGuard.blockedCrashMayHaveUsedGpu(context, effectiveId)) {
                        InferenceBackend.LITERT_GPU
                    } else {
                        InferenceBackend.LITERT_CPU
                    }
                    loadedBackend = nextBackend
                    onModelProgress?.invoke(ModelLoadStage.ContextPreparation(if (nextBackend == InferenceBackend.LITERT_GPU) "Vulkan GPU" else "CPU"), null, null)
                    warmupMs = 0
                    try {
                        initLiteRtLm(resolvedModelPath, effectiveId, loadedBackend, configuredContextWindow) {
                            onModelProgress?.invoke(ModelLoadStage.WarmingUp, null, null)
                        }
                    } catch (gpuOrCpuFailure: Exception) {
                        if (gpuOrCpuFailure is CancellationException || stopRequested || isBeingClosed || liteRtEngine != null) throw gpuOrCpuFailure
                        if (loadedBackend == InferenceBackend.LITERT_GPU) {
                            RuntimeSpeedTuner.recordGpuFailure(context, effectiveId, resolvedModelPath)
                            liteRtCacheDir?.let { LiteRtRuntimeCache.release(context, it) }
                            liteRtCacheDir = null
                            loadedBackend = InferenceBackend.LITERT_CPU
                            onModelProgress?.invoke(ModelLoadStage.ContextPreparation("CPU"), null, null)
                            warmupMs = 0
                            try {
                                initLiteRtLm(resolvedModelPath, effectiveId, loadedBackend, configuredContextWindow) {
                                    onModelProgress?.invoke(ModelLoadStage.WarmingUp, null, null)
                                }
                            } catch (cpuFailure: Exception) {
                                if (cpuFailure is CancellationException || stopRequested || isBeingClosed || liteRtEngine != null ||
                                    detectedFormat != ModelFormat.MediaPipeTask) throw cpuFailure
                                liteRtCacheDir?.let { LiteRtRuntimeCache.release(context, it) }
                                liteRtCacheDir = null
                                val mpLabel = initMediaPipe(resolvedModelPath, configuredContextWindow, pref)
                                activeBackendDisplayName = mpLabel
                                InitCrashGuard.markInitSucceeded(context, effectiveId)
                                fellBackToMediaPipe = true
                            }
                        } else if (detectedFormat == ModelFormat.MediaPipeTask) {
                            liteRtCacheDir?.let { LiteRtRuntimeCache.release(context, it) }
                            liteRtCacheDir = null
                            val mpLabel = initMediaPipe(resolvedModelPath, configuredContextWindow, pref)
                            activeBackendDisplayName = mpLabel
                            InitCrashGuard.markInitSucceeded(context, effectiveId)
                            fellBackToMediaPipe = true
                        } else {
                            throw gpuOrCpuFailure
                        }
                    }
                } else if (pref == "AUTO" && inferenceBackend == InferenceBackend.LITERT_GPU) {
                    RuntimeSpeedTuner.recordGpuFailure(context, effectiveId, resolvedModelPath)
                    liteRtCacheDir?.let { LiteRtRuntimeCache.release(context, it) }
                    liteRtCacheDir = null
                    loadedBackend = InferenceBackend.LITERT_CPU
                    onModelProgress?.invoke(ModelLoadStage.ContextPreparation("CPU"), null, null)
                    warmupMs = 0
                    Log.w(TAG, "Measured GPU failed; retrying LiteRT initialization on CPU", failure)
                    try {
                        initLiteRtLm(resolvedModelPath, effectiveId, loadedBackend, configuredContextWindow) {
                            onModelProgress?.invoke(ModelLoadStage.WarmingUp, null, null)
                        }
                    } catch (cpuFailure: Exception) {
                        if (cpuFailure is CancellationException || stopRequested || isBeingClosed || liteRtEngine != null ||
                            detectedFormat != ModelFormat.MediaPipeTask) throw cpuFailure
                        Log.w(TAG, "LiteRT-LM CPU conversation unavailable for .task bundle; falling back to MediaPipe", cpuFailure)
                        liteRtCacheDir?.let { LiteRtRuntimeCache.release(context, it) }
                        liteRtCacheDir = null
                        val mpLabel = initMediaPipe(resolvedModelPath, configuredContextWindow, pref)
                        activeBackendDisplayName = mpLabel
                        InitCrashGuard.markInitSucceeded(context, effectiveId)
                        fellBackToMediaPipe = true
                    }
                } else if (pref == "AUTO" && detectedFormat == ModelFormat.MediaPipeTask) {
                    Log.w(TAG, "LiteRT-LM conversation unavailable for .task bundle; falling back to MediaPipe", failure)
                    liteRtCacheDir?.let { LiteRtRuntimeCache.release(context, it) }
                    liteRtCacheDir = null
                    val mpLabel = initMediaPipe(resolvedModelPath, configuredContextWindow, pref)
                    activeBackendDisplayName = mpLabel
                    InitCrashGuard.markInitSucceeded(context, effectiveId)
                    fellBackToMediaPipe = true
                } else {
                    throw failure
                }
            }
            if (!fellBackToMediaPipe) {
                activeBackendDisplayName = when (loadedBackend) {
                    InferenceBackend.LITERT_NPU -> npuSnapshot.primaryBadgeLabel
                    InferenceBackend.LITERT_GPU -> "LiteRT-LM GPU"
                    else -> "LiteRT-LM CPU"
                }
            }
        } else {
            val mpLabel = initMediaPipe(resolvedModelPath, configuredContextWindow, pref)
            activeBackendDisplayName = mpLabel
        }
        activeModelLease = modelLease ?: ModelArtifactStorage.leaseArtifact(effectiveFile)
        } catch (t: Throwable) {
            runtimeContextCapacity.clear()
            val cleanup = close()
            if (cleanup is ShutdownResult.Failed && cleanup.error !== t) t.addSuppressed(cleanup.error)
            throw t
        } finally {
            if (activeOperationControlId == operationControlId) {
                activeOperationControlId = 0L
            }
        }
    }

    /** Retain failed initializations too: native cleanup may require an app restart. */
    internal fun initializeGgufEngine(engine: LlamaCppEngine, initialize: () -> Unit) {
        llamaCppEngine = engine
        initialize()
    }

    private fun initLiteRtLm(
        modelPath: String,
        modelId: String,
        inferenceBackend: InferenceBackend,
        contextWindowTokens: Int? = null,
        onWarmupStart: () -> Unit = {}
    ) {
        val engineStart = TtftLog.now()
        val isNpuAttempt = inferenceBackend == InferenceBackend.LITERT_NPU
        // Wrap NPU attempts in dedicated NPU crash-guard so a native driver crash blacklists ONLY
        // NPU for this model/device on next launch and lets GPU/CPU fallback run automatically.
        if (isNpuAttempt) {
            InitCrashGuard.markNpuInitStarted(context, modelId)
        } else {
            InitCrashGuard.markInitStarted(
                context,
                modelId,
                if (inferenceBackend == InferenceBackend.LITERT_GPU) InitCrashGuard.BACKEND_GPU else InitCrashGuard.BACKEND_CPU
            )
        }

        // This call may invoke native abort() and kill the entire process.
        // If that happens, InitCrashGuard.checkAndRecoverCrash() on next
        // app launch will detect it and blocklist this model (or NPU).
        val effectiveMaxTokens = (contextWindowTokens ?: 4096).coerceIn(RuntimeExecutionPolicy.MIN_RECOVERY_CONTEXT_TOKENS, 8192)
        var engine: Engine? = null
        try {
            val executionPolicy = RuntimeExecutionPolicy.resolve(context, modelId)
            val backend = when (inferenceBackend) {
                InferenceBackend.LITERT_NPU -> Backend.NPU(context.applicationInfo.nativeLibraryDir.orEmpty())
                InferenceBackend.LITERT_GPU -> Backend.GPU()
                InferenceBackend.LITERT_CPU,
                InferenceBackend.MEDIAPIPE -> Backend.CPU(executionPolicy.cpuThreads)
            }
            runtimeThreads = if (backend is Backend.CPU) executionPolicy.cpuThreads else 0
            val runtimeCacheDir = LiteRtRuntimeCache.prepare(context, modelId, inferenceBackend, modelPath)
            liteRtCacheDir = runtimeCacheDir
            engine = Engine(
                EngineConfig(
                    modelPath = modelPath,
                    backend = backend,
                    maxNumTokens = effectiveMaxTokens,
                    cacheDir = runtimeCacheDir.absolutePath
                )
            )
            try {
                runtimeContextCapacity.initialize(effectiveMaxTokens) { requireNotNull(engine).initialize() }
            } catch (t: Throwable) {
                if (effectiveMaxTokens > 2048) {
                    Log.w(TAG, "LiteRT-LM init with $effectiveMaxTokens tokens failed, retrying with 2048: ${t.message}")
                    engine.closeIfInitialized()
                    engine = Engine(
                        EngineConfig(
                            modelPath = modelPath,
                            backend = backend,
                            maxNumTokens = 2048,
                            cacheDir = runtimeCacheDir.absolutePath
                        )
                    )
                    runtimeContextCapacity.initialize(2048) { requireNotNull(engine).initialize() }
                } else {
                    throw t
                }
            }

            TtftLog.d("chat engine.init ms=${TtftLog.elapsedMs(engineStart)} backend=${backend.name} threads=$runtimeThreads " +
                "maxTokens=${runtimeContextCapacity.tokens}")
            if (RuntimeWarmup.enabled(context)) {
                onWarmupStart()
                warmupMs = RuntimeWarmup.liteRt(requireNotNull(engine)) { liteRtConversation = it }.coerceAtLeast(1)
                TtftLog.d("chat warmup ms=$warmupMs")
            }

            liteRtToolsDisabledForLoad = false
            retryToolsNextTurn = false
            liteRtEngine = engine
            liteRtConversation = createLiteRtConversation(initialThinkingPreference)
            liteRtMessageCount = 0
            runtime = RuntimeKind.LiteRtLm
            if (stopRequested || isBeingClosed) throw CancellationException("Model initialization cancelled")
            if (isNpuAttempt) {
                InitCrashGuard.markNpuInitSucceeded(context, modelId)
            } else {
                InitCrashGuard.markInitSucceeded(context, modelId)
            }
            Log.i(TAG, "LiteRT-LM engine ready on $backend warmupMs=$warmupMs: $modelPath")
        } catch (t: Throwable) {
            if (isNpuAttempt) {
                InitCrashGuard.markNpuInitFailed(context, modelId)
            } else {
                InitCrashGuard.markInitFailed(context, modelId)
            }
            try {
                liteRtConversation?.close()
                liteRtConversation = null
                engine?.closeIfInitialized()
                liteRtEngine = null
                liteRtConversation = null
            } catch (closeError: Throwable) {
                liteRtEngine = engine // Retain the engine and both leases for shutdown retry.
                if (closeError !== t) t.addSuppressed(closeError)
                throw t
            }
            LiteRtRuntimeCache.release(context, liteRtCacheDir)
            if (t !is CancellationException && !stopRequested && !isBeingClosed) LiteRtRuntimeCache.clear(context, liteRtCacheDir)
            liteRtCacheDir = null
            throw t
        }
    }

    private fun initMediaPipe(
        modelPath: String,
        contextWindowTokens: Int? = null,
        preferredPref: String = "AUTO"
    ): String {
        runtimeThreads = -1 // The MediaPipe API does not expose the CPU worker count.
        val effectiveTokens = (contextWindowTokens ?: 4096).coerceIn(RuntimeExecutionPolicy.MIN_RECOVERY_CONTEXT_TOKENS, 4096)
        val tryGpuFirst = preferredPref.equals("NPU", ignoreCase = true) ||
            preferredPref.equals("VULKAN", ignoreCase = true) ||
            preferredPref.equals("GPU", ignoreCase = true)
        if (tryGpuFirst) {
            try {
                val gpuOptions = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelPath)
                    .setMaxTokens(effectiveTokens)
                    .setMaxTopK(MEDIA_PIPE_MAX_TOP_K)
                    .setPreferredBackend(LlmInference.Backend.GPU)
                    .build()
                mediaPipeInference = runtimeContextCapacity.initialize(effectiveTokens) { LlmInference.createFromOptions(context, gpuOptions) }
                runtime = RuntimeKind.MediaPipe
                Log.i(TAG, "MediaPipe LLM inference ready on GPU backend: $modelPath")
                return "MediaPipe GPU"
            } catch (t: Throwable) {
                Log.w(TAG, "MediaPipe GPU init failed; falling back to CPU: ${t.message}")
            }
        }
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(effectiveTokens)
            .setMaxTopK(MEDIA_PIPE_MAX_TOP_K)
            .setPreferredBackend(LlmInference.Backend.CPU)
            .build()

        mediaPipeInference = runtimeContextCapacity.initialize(effectiveTokens) { LlmInference.createFromOptions(context, options) }
        runtime = RuntimeKind.MediaPipe
        Log.i(TAG, "MediaPipe LLM inference ready on CPU-safe backend: $modelPath")
        return "MediaPipe CPU"
    }

    suspend fun generateResponseStreaming(
        prompt: String,           // Full history blob — used by MediaPipe (stateless)
        rawUserText: String,      // Bare user message — used by LiteRT-LM (Conversation owns history)
        memoryContext: String = "", // Persistent memory context block
        structuredMessages: List<Pair<String, String>>? = null, // Structured conversation turns for GGUF
        documentSearchDocumentIds: Set<String> = emptySet(),
        mediaInputs: List<MultimodalMediaInput> = emptyList(), // Multimodal media attachments for GGUF
        restoreStatefulHistory: Boolean = false,
        thinkingMode: Boolean,
        requestId: String = "",
        sessionId: String = "",
        timing: InferenceTiming? = null,
        isRetry: Boolean = false,
        onProgress: ((RequestProgress) -> Unit)? = null,
        onSnapshot: ((StructuredResponseSnapshot) -> Unit)? = null,
        onUpdate: (String) -> Unit
    ): InferenceExecutionResult = withContext(Dispatchers.IO) {
        this@ChatInferenceManager.documentSearchDocumentIds = documentSearchDocumentIds.toSet()
        stopRequested = false
        lastToolsRequestId = requestId
        liteRtToolRecorder.clear()
        ggufToolRecorder.clear()
        lastLiteRtSnapshot = null
        // Keep the user's original spelling in chat/history, but translate
        // Unicode scripts such as (a+b)² for the inference request.  Small
        // models commonly mis-tokenize those characters while understanding
        // the equivalent ASCII (a+b)^2 correctly.
        val inferencePrompt = PromptMathNormalizer.normalizeForInference(prompt)
        val inferenceUserText = PromptMathNormalizer.normalizeForInference(rawUserText)
        val memoryPrefix = if (memoryContext.isNotBlank()) "${memoryContext.trim()}\n\n" else ""

        // LiteRT-LM maintains stateful conversation history in native C++ engine.
        // The memory context is seeded on the first turn or after reset, but NOT
        // repeatedly injected into subsequent user turns, which causes repetitive loops
        // and identical response prefixes across turns.
        val liteRtUserText = if (runtime != RuntimeKind.LiteRtLm) {
            ""
        } else if (restoreStatefulHistory) {
            liteRtMessageCount = 1
            inferencePrompt
        } else if (liteRtMessageCount == 0) {
            liteRtMessageCount = 1
            if (memoryPrefix.isNotBlank()) "$memoryPrefix$inferenceUserText" else inferenceUserText
        } else {
            liteRtMessageCount++
            inferenceUserText
        }

        // For MediaPipe: stateless — the full history prompt is sent each turn without forced thinking instructions.
        val mediaPipeBasePrompt = if (runtime == RuntimeKind.MediaPipe) {
            ChatPromptPolicy.mediaPipeBasePrompt(
                fullHistoryPrompt = inferencePrompt,
                rawUserText = inferenceUserText,
                memoryContext = memoryContext
            )
        } else {
            inferencePrompt
        }
        val mediaPipePrompt = mediaPipeBasePrompt
        timing?.recordRuntime(++requestsSinceLoad, runtimeThreads, activeBackendDisplayName,
            when (runtime) {
                RuntimeKind.LiteRtLm -> liteRtUserText.length
                RuntimeKind.MediaPipe -> mediaPipePrompt.length
                else -> inferencePrompt.length
            }, warmupMs)
        timing?.recordPromptBlocks(toolChars = when {
            runtime == RuntimeKind.LiteRtLm && liteRtConversationHasTools -> toolsForNewConversation().sumOf { it.getToolDescriptionJsonString().length }
            runtime == RuntimeKind.LlamaCpp && ggufConversationHasTools -> ggufConversationTools.sumOf { it.getToolDescriptionJsonString().length }
            else -> 0
        })

        val responseController = InferenceResponseController(
            thinkingMode = thinkingMode,
            rawUserText = rawUserText,
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
        var attempt = 1
        val effectiveOnUpdate: (String) -> Unit = { delta ->
            if (delta.isNotEmpty()) {
                responseController.appendDelta(delta, reqId = requestId, attemptId = attempt)
                if (!responseController.isStructuredMode) {
                    publisher.appendDelta(delta)
                }
            }
        }

        onProgress?.invoke(RequestProgress.ProcessingPrompt)
        var lastLlamaMetrics: LlamaGenerationMetrics? = null
        var lastNonLlamaTokenCount: Int? = null
        val promptDeadline = PromptDeadline.forPrompt(inferencePrompt.length + memoryContext.length +
            structuredMessages.orEmpty().sumOf { it.first.length + it.second.length })
        val reportProgress: (RequestProgress) -> Unit = {
            promptDeadline.onProgress(it)
            if (it == RequestProgress.RecoveringOnCpu) {
                attempt = 2
                lastLlamaMetrics = null
                lastNonLlamaTokenCount = null
                lastLiteRtSnapshot = null
                responseController.beginAttempt(2)
                publisher.beginAttempt(2)
            }
            onProgress?.invoke(it)
        }

        liteRtToolRecorder.onProgress = { reportProgress(RequestProgress.UsingTools) }
        if (runtime == RuntimeKind.LiteRtLm && liteRtToolsDisabledForLoad) reportProgress(RequestProgress.RetryingWithoutTools)

        fun currentMetrics() = timing?.buildMetrics(
            requestId = requestId, sessionId = sessionId, attempt = attempt,
            generatedTokens = lastLlamaMetrics?.tokenCount ?: lastNonLlamaTokenCount,
            isTokenCountEstimated = lastLlamaMetrics == null,
            nativeDecodeDurationMs = lastLlamaMetrics?.decodeDurationMs,
            prefillDurationMs = lastLlamaMetrics?.prefillDurationMs,
            promptTokens = lastLlamaMetrics?.promptTokens,
            reusedTokens = lastLlamaMetrics?.reusedTokens,
            evaluatedTokens = lastLlamaMetrics?.evaluatedTokens
        )

        try {
            val response = promptDeadline.run(::cancelGeneration,
                onTimeout = { onProgress?.invoke(RequestProgress.StoppingAfterTimeout) }
            ) { when (runtime) {
                RuntimeKind.Portable -> {
                    val sampling = profileSampling()
                    val engine = requireNotNull(portableTextEngine)
                    val runPortable: suspend (List<Pair<String, String>>?, String, String) -> String = { msgs, promptStr, mem ->
                        var firstToken = true
                        engine.generate(
                            prompt = engine.renderPrompt(msgs, promptStr, mem, thinkingMode),
                            limit = if (sampling.maxTokens > 0) sampling.maxTokens else MAX_RESPONSE_TOKENS,
                            temperature = sampling.temperature, topP = sampling.topP, topK = sampling.topK,
                            onText = { delta -> timing?.recordChunk(delta); effectiveOnUpdate(delta) },
                            onToken = {
                                lastNonLlamaTokenCount = it
                                if (firstToken) { firstToken = false; timing?.recordFirstToken(); reportProgress(RequestProgress.Generating) }
                            }
                        )
                    }
                    val firstOutput = runPortable(structuredMessages, inferencePrompt, memoryContext)
                    val candidateFirst = responseController.getFinalAnswer()
                        .ifBlank { ModelOutputSanitizer.cleanAssistantText(firstOutput, rawUserText) }
                    val hasSystemWrapper = memoryContext.isNotBlank() ||
                        structuredMessages?.any { it.first == "system" } == true
                    if (!stopRequested && !isBeingClosed && hasSystemWrapper &&
                        ModelResponseQuality.isGenericNonAnswer(candidateFirst, rawUserText)
                    ) {
                        Log.w(TAG, "Portable returned generic non-answer; retrying once without system wrapper")
                        attempt = 2
                        lastNonLlamaTokenCount = null
                        timing?.beginAttempt()
                        responseController.beginAttempt(2)
                        publisher.beginAttempt(2)
                        val directMessages = structuredMessages?.filter { it.first != "system" }
                            ?.takeIf { it.isNotEmpty() } ?: listOf("user" to inferenceUserText.trim())
                        runPortable(directMessages, inferenceUserText.trim(), "")
                    } else {
                        firstOutput
                    }
                }
                RuntimeKind.LiteRtLm -> {
                    val firstOutput = streamLiteRtLm(
                        prompt = liteRtUserText,
                        thinkingMode = thinkingMode,
                        requestId = requestId,
                        timing = timing,
                        onProgress = reportProgress,
                        onTokensGenerated = { lastNonLlamaTokenCount = it },
                        onAttemptChanged = { attempt = it; lastNonLlamaTokenCount = null; timing?.beginAttempt() },
                        responseSnapshot = publisher::rawText,
                        publisher = publisher,
                        responseController = responseController,
                        onUpdate = effectiveOnUpdate
                    )
                    val candidateFirst = ModelOutputSanitizer.cleanAssistantText(firstOutput, rawUserText)
                    if (!stopRequested && !isBeingClosed && memoryPrefix.isNotBlank() &&
                        ModelResponseQuality.isGenericNonAnswer(candidateFirst, rawUserText)
                    ) {
                        Log.w(TAG, "LiteRtLm returned generic non-answer; retrying once with direct user prompt")
                        attempt = 2
                        lastNonLlamaTokenCount = null
                        timing?.beginAttempt()
                        responseController.beginAttempt(2)
                        publisher.beginAttempt(2)
                        resetConversation(thinkingMode = thinkingMode)
                        streamLiteRtLm(
                            prompt = inferenceUserText.trim(),
                            thinkingMode = thinkingMode,
                            requestId = requestId,
                            timing = timing,
                            onProgress = reportProgress,
                            onTokensGenerated = { lastNonLlamaTokenCount = it },
                            onAttemptChanged = { attempt = it; lastNonLlamaTokenCount = null; timing?.beginAttempt() },
                            responseSnapshot = publisher::rawText,
                            publisher = publisher,
                            responseController = responseController,
                            onUpdate = effectiveOnUpdate
                        )
                    } else {
                        firstOutput
                    }
                }
                RuntimeKind.MediaPipe -> {
                    val firstOutput = streamMediaPipe(
                        prompt = mediaPipePrompt,
                        timing = timing,
                        onProgress = reportProgress,
                        onTokensGenerated = { lastNonLlamaTokenCount = it },
                        responseSnapshot = publisher::rawText,
                        onUpdate = effectiveOnUpdate
                    )
                    val cleanedFirst = ModelOutputSanitizer.cleanAssistantText(firstOutput, rawUserText)
                    val visibleFirst = ThinkingTextUtils.finalResponseOrReasoning(cleanedFirst, allowReasoningFallback = true)
                    val controllerAnswer = responseController.getFinalAnswer()
                    val candidateFirst = visibleFirst.ifBlank { controllerAnswer }
                    val isBlank = candidateFirst.isBlank()
                    val isBoilerplate = !isBlank && ModelResponseQuality.isGenericNonAnswer(candidateFirst, rawUserText)
                    if (!stopRequested && !isBeingClosed && (isBlank || isBoilerplate)) {
                        Log.w(TAG, "MediaPipe returned ${if (isBlank) "empty" else "generic non-answer"} on attempt 1; retrying once with direct user prompt")
                        attempt = 2
                        lastNonLlamaTokenCount = null
                        timing?.beginAttempt()
                        responseController.beginAttempt(2)
                        publisher.beginAttempt(2)
                        // Retry directly with the bare user prompt so tiny models do not acknowledge
                        // meta-instructions ("Okay, I understand...") instead of answering the user.
                        val retryPrompt = inferenceUserText.trim().ifBlank { mediaPipeBasePrompt }
                        streamMediaPipe(
                            prompt = retryPrompt,
                            temperatureOverride = RETRY_TEMPERATURE,
                            seedSalt = if (isBoilerplate) 2 else 1,
                            timing = timing,
                            onProgress = reportProgress,
                            onTokensGenerated = { lastNonLlamaTokenCount = it },
                            responseSnapshot = publisher::rawText,
                            onUpdate = effectiveOnUpdate
                        )
                    } else {
                        firstOutput
                    }
                }
                RuntimeKind.LlamaCpp -> {
                    val firstOutput = streamLlamaCpp(
                        prompt = inferencePrompt,
                        rawUserText = inferenceUserText,
                        structuredMessages = structuredMessages,
                        mediaInputs = mediaInputs,
                        noThinking = !thinkingMode,
                        requestId = requestId,
                        timing = timing,
                        onProgress = reportProgress,
                        onMetrics = { m -> lastLlamaMetrics = m },
                        responseSnapshot = publisher::rawText,
                        publisher = publisher,
                        responseController = responseController,
                        onUpdate = effectiveOnUpdate,
                        onAttemptChanged = { attempt = it; lastNonLlamaTokenCount = null; timing?.beginAttempt() }
                    )
                    val candidateFirst = responseController.getFinalAnswer()
                        .ifBlank { ModelOutputSanitizer.cleanAssistantText(firstOutput, rawUserText) }
                    val hasSystemTurn = structuredMessages?.any { it.first == "system" } == true
                    if (!stopRequested && !isBeingClosed && mediaInputs.isEmpty() &&
                        hasSystemTurn && ModelResponseQuality.isGenericNonAnswer(candidateFirst, rawUserText)
                    ) {
                        Log.w(TAG, "LlamaCpp returned generic non-answer; retrying once without system wrapper")
                        attempt = 2
                        lastLlamaMetrics = null
                        timing?.beginAttempt()
                        responseController.beginAttempt(2)
                        publisher.beginAttempt(2)
                        val directMessages = structuredMessages.filter { it.first != "system" }
                            .takeIf { it.isNotEmpty() } ?: listOf("user" to inferenceUserText.trim())
                        streamLlamaCpp(
                            prompt = inferenceUserText,
                            rawUserText = inferenceUserText,
                            structuredMessages = directMessages,
                            mediaInputs = mediaInputs,
                            noThinking = !thinkingMode,
                            requestId = requestId,
                            timing = timing,
                            onProgress = reportProgress,
                            onMetrics = { m -> lastLlamaMetrics = m },
                            responseSnapshot = publisher::rawText,
                            publisher = publisher,
                            responseController = responseController,
                            onUpdate = effectiveOnUpdate,
                            onAttemptChanged = { attempt = it; lastLlamaMetrics = null; timing?.beginAttempt() }
                        )
                    } else {
                        firstOutput
                    }
                }
                RuntimeKind.None -> throw IllegalStateException("Inference not initialized")
            } }
            responseController.finish(recoverUnclosedOnCompletion = !stopRequested)
            val rawReasoning = if (runtime == RuntimeKind.LiteRtLm) {
                lastLiteRtSnapshot?.reasoningText.orEmpty().ifBlank { responseController.getReasoningText() }
            } else {
                responseController.getReasoningText()
            }
            val controllerAnswer = responseController.getFinalAnswer()
            val cleanResponse = when {
                runtime == RuntimeKind.LiteRtLm -> ModelOutputSanitizer.cleanAssistantText(response, rawUserText)
                controllerAnswer.isNotBlank() -> controllerAnswer
                response.isNotBlank() -> {
                    val sanitized = ModelOutputSanitizer.cleanAssistantText(response, rawUserText)
                    ThinkingTextUtils.finalResponseOrReasoning(sanitized, allowReasoningFallback = false)
                }
                else -> ""
            }
            val finalized = ResponseFinalizer.finalize(
                requestId = requestId,
                attemptId = attempt,
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
            finalized.toExecutionResult(currentMetrics())
        } catch (exception: CancellationException) {
            responseController.finish(recoverUnclosedOnCompletion = false)
            val acceptedAnswer = responseController.getFinalAnswer()
            val rawReasoning = responseController.getReasoningText()
            timing?.endGeneration()
            val finalized = ResponseFinalizer.finalize(
                requestId = requestId,
                attemptId = attempt,
                rawAnswer = acceptedAnswer,
                rawReasoning = rawReasoning,
                isStopped = true,
                acceptedAnswerFallback = responseController.acceptedAnswerText(),
                acceptedReasoningFallback = responseController.acceptedReasoningText(),
                thinkingMode = thinkingMode
            )
            val finalFormatted = responseController.formatFinal(finalized.answerText, finalized.reasoningText)
            publisher.finishExplicit(finalFormatted)
            finalized.toExecutionResult(currentMetrics())
        } catch (exception: FutureCancellationException) {
            responseController.finish(recoverUnclosedOnCompletion = !stopRequested)
            val acceptedAnswer = responseController.getFinalAnswer()
            val rawReasoning = responseController.getReasoningText()
            timing?.endGeneration()
            val finalized = ResponseFinalizer.finalize(
                requestId = requestId,
                attemptId = attempt,
                rawAnswer = acceptedAnswer,
                rawReasoning = rawReasoning,
                isStopped = true,
                acceptedAnswerFallback = responseController.acceptedAnswerText(),
                acceptedReasoningFallback = responseController.acceptedReasoningText(),
                thinkingMode = thinkingMode
            )
            val finalFormatted = responseController.formatFinal(finalized.answerText, finalized.reasoningText)
            publisher.finishExplicit(finalFormatted)
            finalized.toExecutionResult(currentMetrics())
        } catch (exception: Exception) {
            responseController.finish(recoverUnclosedOnCompletion = false)
            val acceptedAnswer = responseController.getFinalAnswer()
            val rawReasoning = responseController.getReasoningText()
            timing?.endGeneration()
            val finalized = ResponseFinalizer.finalize(
                requestId = requestId,
                attemptId = attempt,
                rawAnswer = acceptedAnswer,
                rawReasoning = rawReasoning,
                isStopped = stopRequested && exception !is PromptTimeoutException,
                sdkError = if (stopRequested && exception !is PromptTimeoutException) null else exception,
                acceptedAnswerFallback = responseController.acceptedAnswerText(),
                acceptedReasoningFallback = responseController.acceptedReasoningText(),
                thinkingMode = thinkingMode
            )
            val finalFormatted = responseController.formatFinal(finalized.answerText, finalized.reasoningText)
            publisher.finishExplicit(finalFormatted)
            finalized.toExecutionResult(currentMetrics())
        } finally { liteRtToolRecorder.onProgress = null; this@ChatInferenceManager.documentSearchDocumentIds = emptySet() }
    }

    @OptIn(ExperimentalApi::class)
    private suspend fun streamLiteRtLm(
        prompt: String, thinkingMode: Boolean, requestId: String = "",
        timing: InferenceTiming? = null, onProgress: ((RequestProgress) -> Unit)? = null,
        onTokensGenerated: ((Int) -> Unit)? = null, onAttemptChanged: ((Int) -> Unit)? = null, responseSnapshot: () -> String,
        publisher: StreamingResponsePublisher? = null,
        responseController: InferenceResponseController? = null, onUpdate: (String) -> Unit
    ): String {
        liteRtToolRecorder.clear()
        val textSeen = java.util.concurrent.atomic.AtomicBoolean(false)
        suspend fun attempt(): String = streamLiteRtLmAttempt(
            prompt, thinkingMode, requestId, timing, onProgress, onTokensGenerated,
            responseSnapshot, publisher, responseController, textSeen, onUpdate
        )
        try {
            val answer = attempt()
            liteRtCompletedTurns++
            return answer
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (!liteRtConversationHasTools || liteRtCompletedTurns != 0 || textSeen.get() ||
                stopRequested || isBeingClosed || liteRtToolRecorder.snapshot().isNotEmpty()) throw failure
            Log.w(TAG, "First LiteRT tool turn failed before text; retrying without tools", failure)
            onProgress?.invoke(RequestProgress.RetryingWithoutTools)
            liteRtToolsDisabledForLoad = true
            retryToolsNextTurn = true
            val toolsAlreadyUsed = liteRtToolRecorder.snapshot()
            onAttemptChanged?.invoke(2)
            responseController?.beginAttempt(2)
            publisher?.beginAttempt(2)
            val previousConversation = liteRtConversation
            liteRtConversation = null
            runCatching { previousConversation?.close() }.onFailure { t ->
                Log.w(TAG, "LiteRT-LM conversation close before tool retry failed", t)
            }
            liteRtConversation = createLiteRtConversation(liteRtConversationThinking ?: thinkingMode)
            toolsAlreadyUsed.forEach(liteRtToolRecorder::add)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (stopRequested || isBeingClosed) throw CancellationException("Generation cancelled")
            val answer = attempt()
            liteRtCompletedTurns++
            return answer
        }
    }

    private suspend fun streamLiteRtLmAttempt(
        prompt: String,
        thinkingMode: Boolean,
        requestId: String = "",
        timing: InferenceTiming? = null,
        onProgress: ((RequestProgress) -> Unit)? = null,
        onTokensGenerated: ((Int) -> Unit)? = null,
        responseSnapshot: () -> String,
        publisher: StreamingResponsePublisher? = null,
        responseController: InferenceResponseController? = null,
        textSeen: java.util.concurrent.atomic.AtomicBoolean,
        onUpdate: (String) -> Unit
    ): String {
        val conversation = liteRtConversation ?: throw IllegalStateException("Inference not initialized")
        val requestMessage = Message.of(prompt)
        val requestContext = ThinkingModeConfig.liteRtExtraContext(thinkingMode)
        val requestStartedAt = System.currentTimeMillis()
        val sendStartNanos = java.util.concurrent.atomic.AtomicLong(0L)
        var firstChunkRecorded = false
        Log.i(
            TAG,
            "LiteRT request mode=${if (thinkingMode) "thinking" else "direct"} " +
                "contextKeys=${requestContext.keys} promptChars=${prompt.length}"
        )
        val adapter = LiteRtStreamAdapter(
            requestId = requestId,
            attemptId = 1,
            thinkingMode = thinkingMode,
            reasoningChannelKeys = configuredCapabilities?.capabilities?.reasoningChannels.orEmpty(),
            onFirstToken = {
                timing?.recordFirstToken()
                sendStartNanos.get().takeIf { it != 0L }?.let { start ->
                    TtftLog.d("chat send->firstToken req=$requestId ms=${TtftLog.elapsedMs(start)}")
                }
                if (!firstChunkRecorded) {
                    firstChunkRecorded = true
                    onProgress?.invoke(RequestProgress.Generating)
                    Log.i(
                        TAG,
                        "LiteRT first token mode=${if (thinkingMode) "thinking" else "direct"} " +
                            "latencyMs=${System.currentTimeMillis() - requestStartedAt}"
                    )
                }
            },
            onDelta = { delta ->
                if (stopRequested) return@LiteRtStreamAdapter
                if (delta.isNotEmpty()) onProgress?.invoke(RequestProgress.Generating)
                if (!firstChunkRecorded && delta.isNotEmpty()) {
                    firstChunkRecorded = true
                    timing?.recordFirstToken()
                    onProgress?.invoke(RequestProgress.Generating)
                    Log.i(
                        TAG,
                        "LiteRT first chunk mode=${if (thinkingMode) "thinking" else "direct"} " +
                            "latencyMs=${System.currentTimeMillis() - requestStartedAt} chars=${delta.length}"
                    )
                }
                if (delta.isNotEmpty()) textSeen.set(true)
                timing?.recordChunk(delta)
                runCatching { onUpdate(delta) }
            },
            onSnapshot = { snap ->
                if (snap.answerText.isNotEmpty() || snap.reasoningText.isNotEmpty()) textSeen.set(true)
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
            val kvBefore = runCatching { conversation.getTokenCount() }.getOrDefault(-1)
            TtftLog.d("chat send req=$requestId kvTokensBefore=$kvBefore promptChars=${prompt.length} tools=$liteRtConversationHasTools " +
                "thinking=$thinkingMode conversationThinking=$liteRtConversationThinking backend=${activeBackendDisplayName.replace(' ', '_')} threads=$runtimeThreads")
            sendStartNanos.set(TtftLog.now())
            sendLiteRtMessageSafely(
                conversation = conversation,
                message = requestMessage,
                adapter = adapter,
                requestContext = requestContext,
                thinkingMode = thinkingMode
            )
            val finalSnapshot = adapter.awaitDone()
            lastLiteRtSnapshot = finalSnapshot
            val kvAfter = runCatching { conversation.getTokenCount() }.getOrDefault(-1)
            TtftLog.d("chat done req=$requestId totalMs=${TtftLog.elapsedMs(sendStartNanos.get())} " +
                "kvTokensBefore=$kvBefore kvTokensAfter=$kvAfter callbacks=${adapter.callbackCount}")
            val answerText = finalSnapshot.answerText
            val rawTokens = if (adapter.callbackCount > 0) {
                adapter.callbackCount
            } else {
                val combinedText = answerText + finalSnapshot.reasoningText
                maxOf(1, Math.round(combinedText.length / 3.8).toInt())
            }
            onTokensGenerated?.invoke(rawTokens)
            return answerText
        } catch (cancelled: CancellationException) {
            adapter.cancel(conversation)
            lastLiteRtSnapshot = adapter.currentSnapshot()
            throw cancelled
        } catch (exception: Exception) {
            lastLiteRtSnapshot = adapter.currentSnapshot()
            adapter.cancel(conversation)
            if (stopRequested) {
                throw CancellationException("Generation cancelled").also { it.initCause(exception) }
            }
            throw exception
        } finally {
            if (activeLiteRtAdapter === adapter) {
                activeLiteRtAdapter = null
            }
        }
    }

    private fun streamMediaPipe(
        prompt: String,
        temperatureOverride: Float? = null,
        seedSalt: Int = 0,
        timing: InferenceTiming? = null,
        onProgress: ((RequestProgress) -> Unit)? = null,
        onTokensGenerated: ((Int) -> Unit)? = null,
        responseSnapshot: () -> String,
        onUpdate: (String) -> Unit
    ): String {
        val inference = mediaPipeInference ?: throw IllegalStateException("Inference not initialized")
        val sampling = profileSampling()
        val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(sampling.topK.coerceIn(1, MEDIA_PIPE_MAX_TOP_K))
            .setTopP(sampling.topP)
            .setTemperature(temperatureOverride ?: sampling.temperature)
            .setRandomSeed(31 * prompt.hashCode() + seedSalt)
            .build()
        val session = LlmInferenceSession.createFromOptions(inference, sessionOptions)
        activeMediaPipeSession = session
        var activeFuture: Future<String>? = null
        var firstChunkRecorded = false
        var mediaPipeTokenCount = 0
        try {
            if (stopRequested) {
                throw CancellationException("Generation cancelled")
            }
            session.addQueryChunk(prompt)
            val future = session.generateResponseAsync(
                ProgressListener<String> { partial, _ ->
                    if (stopRequested || partial.isNullOrEmpty()) return@ProgressListener
                    mediaPipeTokenCount++
                    if (!firstChunkRecorded) {
                        firstChunkRecorded = true
                        timing?.recordFirstToken()
                        onProgress?.invoke(RequestProgress.Generating)
                    }

                    timing?.recordChunk(partial)
                    onUpdate(partial)
                }
            )
            activeFuture = future
            activeMediaPipeFuture = future
            if (stopRequested) {
                runCatching { session.cancelGenerateResponseAsync() }
                throw CancellationException("Generation cancelled")
            }
            val finalResponse = try {
                future.get()
            } catch (exception: FutureCancellationException) {
                throw CancellationException("Generation cancelled").also { it.initCause(exception) }
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                throw CancellationException("Generation interrupted").also { it.initCause(exception) }
            } finally {
                if (activeMediaPipeFuture === future) {
                    activeMediaPipeFuture = null
                }
            }
            if (stopRequested) {
                throw CancellationException("Generation cancelled")
            }
            val accumulatedText = responseSnapshot()
            val resultText = if (finalResponse.isNotBlank() && finalResponse != accumulatedText) {
                finalResponse
            } else {
                accumulatedText.ifBlank { finalResponse.orEmpty() }
            }
            val rawTokens = if (mediaPipeTokenCount > 0) {
                mediaPipeTokenCount
            } else {
                maxOf(1, Math.round(resultText.length / 3.8).toInt())
            }
            onTokensGenerated?.invoke(rawTokens)
            return resultText
        } finally {
            if (activeMediaPipeFuture === activeFuture) {
                activeMediaPipeFuture = null
            }
            if (activeMediaPipeSession === session) {
                activeMediaPipeSession = null
                if (!isBeingClosed) {
                    runCatching { session.close() }.onFailure { t ->
                        Log.w(TAG, "MediaPipe chat session close error", t)
                    }
                }
            }
        }
    }

    private suspend fun streamLlamaCpp(
        prompt: String,
        rawUserText: String = "",
        structuredMessages: List<Pair<String, String>>? = null,
        mediaInputs: List<MultimodalMediaInput> = emptyList(),
        noThinking: Boolean = false,
        requestId: String = "",
        timing: InferenceTiming? = null,
        onProgress: ((RequestProgress) -> Unit)? = null,
        onMetrics: ((LlamaGenerationMetrics) -> Unit)? = null,
        responseSnapshot: () -> String,
        publisher: StreamingResponsePublisher? = null,
        responseController: InferenceResponseController? = null,
        onUpdate: (String) -> Unit,
        onAttemptChanged: ((Int) -> Unit)? = null
    ): String {
        val engine = llamaCppEngine ?: throw IllegalStateException("GGUF engine not initialized")
        // Use structured messages directly when available.
        // When they are absent, synthesise a single user turn from rawUserText.
        val baseMessages: List<Pair<String, String>> = structuredMessages
            ?: if (rawUserText.isNotBlank()) listOf("user" to rawUserText.trim())
            else listOf("user" to prompt.trim())
        val messages = baseMessages

        val isVulkan = engine.activeBackend == LlamaBackend.VULKAN
        var firstTokenRecorded = false
        var pendingToolHistory: org.json.JSONArray? = null
        responseController?.isStructuredMode = true
        try {
            ggufToolRecorder.clear()
            if (ggufConversationHasTools && mediaInputs.isEmpty() &&
                com.shounak.localmeshai.ai.tools.ToolTurnPolicy.needsTools(messages)) {
                val toolStream = com.shounak.localmeshai.ai.tools.GgufToolTurnStream(
                    publish = { answer, reasoning, thinking ->
                        responseController?.appendStructured(answer, reasoning, thinking,
                            reqId = requestId, attemptId = 1, replaceSnapshot = true)
                        publisher?.notifyStructuredUpdate(allowEmpty = true)
                    },
                    onText = { text ->
                        onProgress?.invoke(RequestProgress.Generating)
                        if (!firstTokenRecorded) {
                            firstTokenRecorded = true
                            timing?.recordFirstToken()
                        }
                        timing?.recordChunk(text)
                    }
                )
                try {
                    val history = org.json.JSONArray().apply {
                        messages.forEach { (role, content) -> put(org.json.JSONObject().put("role", role).put("content", content)) }
                    }
                    pendingToolHistory = history
                    val result = com.shounak.localmeshai.ai.tools.ToolCallingLoop.run(
                        messages = history,
                        tools = ggufConversationTools,
                        record = ggufToolRecorder::add,
                        checkActive = {
                            if (stopRequested || isBeingClosed) throw CancellationException("Generation cancelled")
                        },
                        onToolStart = { onProgress?.invoke(RequestProgress.UsingTools) },
                        onStateChange = { _toolTurnState.value = it }
                    ) { request ->
                        var parsed: String? = null
                        onProgress?.invoke(RequestProgress.ProcessingPrompt)
                        val status = engine.generateMultimodalStream(
                            prompt = prompt, messages = messages, enableThinking = !noThinking,
                            samplingParams = profileSampling(), onMetrics = onMetrics,
                            toolRequestJson = request, onToolResponse = { parsed = it },
                            // Raw tokens can be tool arguments. Only parsed answer/reasoning is visible progress.
                            onFirstToken = {},
                            onStructuredToken = { answer, reasoning, thinking ->
                                if (!stopRequested && !isBeingClosed) toolStream.update(answer, reasoning, thinking)
                            },
                            onToken = {}
                        )
                        if (status == LlamaGenerationResult.Stopped) throw CancellationException("Generation cancelled")
                        org.json.JSONObject(checkNotNull(parsed) { "Missing tool response" }).also(toolStream::complete)
                    }
                    return result.optString("content", "")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (failure is LlamaExecutionException && failure.category in setOf(
                        LlamaFailureCategory.NumericalInstability, LlamaFailureCategory.BackendExecution,
                        LlamaFailureCategory.GenerationDecodeFailed, LlamaFailureCategory.PromptDecodeFailed
                    )) throw failure
                    if (ggufToolRecorder.snapshot().isNotEmpty()) throw failure
                    toolStream.clear()
                    pendingToolHistory = null
                    Log.w(TAG, "GGUF tools unavailable for this turn; retrying as ordinary chat", failure)
                }
            }

            val result = engine.generateMultimodalStream(
                prompt = prompt,
                messages = messages,
                mediaInputs = mediaInputs,
                enableThinking = !noThinking,
                samplingParams = profileSampling(),
                onMetrics = onMetrics,
                onFirstToken = { timing?.recordFirstToken(); onProgress?.invoke(RequestProgress.Generating) },
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
                throw CancellationException("Generation stopped by user")
            }
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
                // 1. Release GPU context
                engine.close()

                // 2. Clear failed partial response
                onAttemptChanged?.invoke(2)
                responseController?.beginAttempt(2)
                publisher?.beginAttempt(2) ?: onUpdate("")

                // 3. Reinitialize on CPU
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
                    _isGgufActive.value = false
                    throw failure
                }
                llamaCppEngine = cpuEngine
                activeBackendDisplayName = "GGUF CPU (Vulkan recovery fallback: ${e.category})"

                // 4. Retry generation once on CPU with the same messages
                var retryFirstTokenRecorded = false
                val recoveryToolRequest = pendingToolHistory?.takeIf { cpuEngine.supportsToolCalling() }?.let { history ->
                    org.json.JSONObject().put("messages", history).put("allow_calls", false)
                        .put("tools", org.json.JSONArray().apply {
                            ggufConversationTools.forEach { put(org.json.JSONObject().put("type", "function")
                                .put("function", org.json.JSONObject(it.getToolDescriptionJsonString()))) }
                        }).toString()
                }
                val retryResult = cpuEngine.generateMultimodalStream(
                    toolRequestJson = recoveryToolRequest,
                    prompt = prompt,
                    messages = messages,
                    mediaInputs = mediaInputs,
                    enableThinking = !noThinking,
                samplingParams = profileSampling(),
                    onMetrics = onMetrics,
                    onFirstToken = { timing?.recordFirstToken(); onProgress?.invoke(RequestProgress.Generating) },
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
                    throw CancellationException("Generation stopped by user")
                }
                return responseSnapshot()
            } else {
                throw e
            }
        }
    }


    val isStopRequested: Boolean
        get() = stopRequested

    fun needsResetForThinkingMode(requested: Boolean): Boolean {
        return runtime == RuntimeKind.LiteRtLm && liteRtConversationThinking != requested
    }

    fun cancelInitialization(expectedOperationControlId: Long? = null) {
        val opId = activeOperationControlId
        if (opId != 0L && (expectedOperationControlId == null || expectedOperationControlId == opId)) {
            runCatching { LlamaCppEngine.nativeCancelOperationControl(opId) }
        }
    }

    fun cancelGeneration() {
        stopRequested = true
        portableTextEngine?.stop()
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
    }

    fun resetConversation(thinkingMode: Boolean = false) {
        if (retryToolsNextTurn) { retryToolsNextTurn = false; liteRtToolsDisabledForLoad = false }
        if (runtime == RuntimeKind.LlamaCpp) {
            llamaCppEngine?.resetAfterValidation()
            conversationToolSettings = ConversationToolSettings.from(AppSettings.getInstance(context).settings.value)
            ggufConversationTools = toolsForNewConversation()
            ggufConversationHasTools = conversationToolSettings?.enabled == true && ggufConversationTools.isNotEmpty() && llamaCppEngine?.supportsToolCalling() == true
            ggufToolRecorder.clear()
        }
        if (runtime != RuntimeKind.LiteRtLm) return
        val previousConversation = liteRtConversation
        liteRtConversation = null
        runCatching { previousConversation?.close() }.onFailure { t ->
            Log.w(TAG, "LiteRT-LM conversation close during reset failed", t)
        }
        stopRequested = false
        val resetStart = TtftLog.now()
        liteRtConversation = createLiteRtConversation(thinkingMode)
        liteRtMessageCount = 0
        TtftLog.d("chat conversation.reset ms=${TtftLog.elapsedMs(resetStart)} thinking=$thinkingMode (history is re-sent in the next prompt)")
    }

    private fun toolsForNewConversation(): List<com.shounak.localmeshai.ai.tools.ChatTool> =
        com.shounak.localmeshai.ai.tools.LocalTools.forConversation(context, settings = requireNotNull(conversationToolSettings),
            documentIds = { documentSearchDocumentIds }) { stopRequested || isBeingClosed }

    private fun createLiteRtConversation(thinkingMode: Boolean): Conversation? {
        val engine = liteRtEngine ?: return null
        val base = LiteRtThinkingConfig.apply(
            ConversationConfig(
                extraContext = ThinkingModeConfig.liteRtExtraContext(thinkingMode),
                maxOutputToken = configuredLiteRtMaxOutputTokens()
            ),
            enabled = thinkingMode
        )
        val settings = ConversationToolSettings.from(AppSettings.getInstance(context).settings.value)
        if (conversationToolSettings?.requiresRefresh(settings) == true) liteRtToolsDisabledForLoad = false
        conversationToolSettings = settings
        liteRtConversationThinking = thinkingMode
        liteRtCompletedTurns = 0
        liteRtConversationHasTools = false
        liteRtToolRecorder.clear()
        if (!liteRtToolsDisabledForLoad && settings.enabled) {
            try {
                val providers = toolsForNewConversation().map {
                    tool(liteRtToolRecorder.recording(it))
                }
                if (providers.isNotEmpty()) {
                    val createStart = TtftLog.now()
                    val conversation = createLiteRtConversationSafely(
                        engine = engine,
                        config = base.copy(
                            tools = providers,
                            automaticToolCalling = true
                        )
                    )
                    liteRtConversationHasTools = true
                    TtftLog.d("chat conversation.create ms=${TtftLog.elapsedMs(createStart)} tools=${providers.size} thinking=$thinkingMode")
                    return conversation
                }
            } catch (failure: Exception) {
                if (failure is CancellationException || stopRequested || isBeingClosed) throw failure
                liteRtToolsDisabledForLoad = true
                retryToolsNextTurn = true
                Log.w(TAG, "LiteRT tool conversation unavailable; using ordinary chat for this turn", failure)
            }
        }
        return createLiteRtConversationSafely(engine, base)
    }

    private fun createLiteRtConversationSafely(engine: Engine, config: ConversationConfig): Conversation {
        return try {
            engine.createConversation(config)
        } catch (failure: Exception) {
            if (failure is CancellationException || stopRequested || isBeingClosed) throw failure
            if (config.thinkingConfig == null && config.extraContext.isEmpty()) throw failure
            Log.w(TAG, "LiteRT conversation rejected thinking config/extraContext; retrying without thinking overrides", failure)
            engine.createConversation(config.copy(thinkingConfig = null, extraContext = emptyMap()))
        }
    }

    @OptIn(ExperimentalApi::class)
    private fun sendLiteRtMessageSafely(
        conversation: Conversation,
        message: Message,
        adapter: LiteRtStreamAdapter,
        requestContext: Map<String, Any>,
        thinkingMode: Boolean
    ) {
        try {
            conversation.sendMessageAsync(
                message,
                adapter,
                requestContext,
                thinkingConfig = ThinkingConfig(enableThinking = thinkingMode)
            )
        } catch (failure: Exception) {
            if (failure is CancellationException || stopRequested || isBeingClosed) throw failure
            Log.w(TAG, "LiteRT sendMessageAsync rejected thinking config/extraContext; retrying without thinking overrides", failure)
            conversation.sendMessageAsync(message, adapter)
        }
    }

    fun close(): ShutdownResult {
        isBeingClosed = true
        cancelGeneration()
        var closeError: Throwable? = null

        if (portableTextEngine != null) {
            try { portableTextEngine?.close(); portableTextEngine = null }
            catch (t: Throwable) { closeError = t }
        }

        val sessionToClose = activeMediaPipeSession
        if (sessionToClose != null) {
            try {
                sessionToClose.close()
                activeMediaPipeSession = null
            } catch (t: Throwable) {
                if (closeError == null) closeError = t
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
        liteRtMessageCount = 0

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
        currentGgufBundle = null

        if (mediaPipeInference != null) {
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

        if (closeError != null) {
            isBeingClosed = false
            return ShutdownResult.Failed(closeError)
        }

        try {
            activeModelLease?.close()
            activeModelLease = null
        } catch (t: Throwable) {
            isBeingClosed = false
            return ShutdownResult.Failed(t)
        }
        LiteRtRuntimeCache.release(context, liteRtCacheDir)
        liteRtCacheDir = null

        runtimeContextCapacity.clear()
        runtime = RuntimeKind.None
        _isGgufActive.value = false
        configuredCapabilities = null
        mediaPipeTemperature = DEFAULT_TEMPERATURE
        liteRtConversationThinking = null
        activeBackendDisplayName = ""
        isBeingClosed = false
        AppCacheCleaner.clearAppCache(context)

        return ShutdownResult.Success
    }

    fun isLlamaCppRuntime(): Boolean = runtime == RuntimeKind.LlamaCpp

    private enum class RuntimeKind {
        None,
        MediaPipe,
        LiteRtLm,
        Portable,
        LlamaCpp
    }
}
