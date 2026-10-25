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
import com.shounak.localmeshai.models.ThinkingCapability
import com.shounak.localmeshai.models.ThinkingControlStrategy
import com.shounak.localmeshai.utils.AppCacheCleaner
import com.shounak.localmeshai.utils.AppSettings
import com.shounak.localmeshai.utils.ChatPromptPolicy
import com.shounak.localmeshai.utils.ChatRuntimePolicy
import com.shounak.localmeshai.utils.DeviceUtils
import com.shounak.localmeshai.utils.InferenceBackend
import com.shounak.localmeshai.utils.InferenceTiming
import com.shounak.localmeshai.utils.InitCrashGuard
import com.shounak.localmeshai.utils.LegacyThinkingProfiles
import com.shounak.localmeshai.utils.LiteRtRuntimeCache
import com.shounak.localmeshai.utils.ModelArtifactStorage
import com.shounak.localmeshai.utils.ModelDownloader
import com.shounak.localmeshai.utils.ModelOutputSanitizer
import com.shounak.localmeshai.utils.ModelResponseQuality
import com.shounak.localmeshai.utils.ModelSourceResolver
import com.shounak.localmeshai.utils.NoThinkingPromptUtils
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
        return LlamaSamplingParams(temperature = profile.temperature ?: 0.7f, topP = profile.topP ?: 0.95f, topK = profile.topK ?: 40)
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
    @Volatile private var resolvedThinkingCapability: ThinkingCapability = ThinkingCapability.Unknown
    @Volatile private var isDefaultThinkingModel = false
    @Volatile private var supportsTextNoThinkingSwitch = false
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
            snapshot.copy(initializedContextTokens = runtimeContextCapacity.tokens, capabilities = snapshot.capabilities.copy(
                thinking = resolvedThinkingCapability,
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
    @Volatile private var useNativeGemmaTaskTemplate = false
    @Volatile private var mediaPipeTemperature = DEFAULT_TEMPERATURE
    @Volatile private var liteRtConversationMode = LiteRtConversationMode.Default
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
        private const val DEFAULT_TEMPERATURE = 0.7f
        private const val TINY_MODEL_TEMPERATURE = 0.35f
        private const val RETRY_TEMPERATURE = 0.2f

        private val THINK_BLOCK_REGEX =
            Regex("""<\s*(?:think|thought|reasoning)\s*>[\s\S]*?<\s*/\s*(?:think|thought|reasoning)\s*>""", RegexOption.IGNORE_CASE)
        private val THINK_UNCLOSED_REGEX =
            Regex("""<\s*(?:think|thought|reasoning)\s*>[\s\S]*$""", RegexOption.IGNORE_CASE)
        private val THINK_PREFILLED_CLOSE_REGEX =
            Regex("""^[\s\S]*?<\s*/\s*(?:think|thought|reasoning)\s*>""", RegexOption.IGNORE_CASE)

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
        supportsThinkingMode: Boolean = false,
        allowUnsafeOverride: Boolean = false,
        /**
         * Expected GGUF file size in bytes for artifact integrity validation. 0L = no check.
         * Pass [ModelInfo.expectedFileSizeBytes] from the catalog entry.
         */
        expectedFileSizeBytes: Long = 0L,
        contextWindowTokens: Int? = null,
        operationControlId: Long = 0L,
        onModelProgress: ((stage: ModelLoadStage, completed: Long?, total: Long?) -> Unit)? = null,
        onProgress: ((RequestProgress) -> Unit)? = null,
        bundle: com.shounak.localmeshai.models.ModelBundle? = null
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
        runtimeThreads = RuntimeExecutionPolicy.resolve(context).cpuThreads
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
        val searchString = "$modelPath|$effectiveId|$effectiveName".lowercase()
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
            }?.let { NonGgufBundleInspector.inspectFile(it).capabilities } ?: ModelCapabilities())
                .copy(thinking = resolvedCap)
        )

        isDefaultThinkingModel = (resolvedCap == ThinkingCapability.ModelControlled || resolvedCap == ThinkingCapability.Toggleable)
        supportsTextNoThinkingSwitch = (resolvedCap == ThinkingCapability.Toggleable)
        val isTinyGemmaMediaPipeModel = listOf(
            "gemma3-1b",
            "gemma-3-1b",
            "gemma 3 1b",
            "gemma3-270m",
            "gemma-3-270m",
            "gemma 3 270m"
        ).any(searchString::contains)
        useNativeGemmaTaskTemplate = file?.extension.equals("task", ignoreCase = true) &&
            searchString.contains("gemma")
        mediaPipeTemperature = if (isTinyGemmaMediaPipeModel) {
            TINY_MODEL_TEMPERATURE
        } else {
            DEFAULT_TEMPERATURE
        }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        stopRequested = false
        isBeingClosed = false

        val appSettings = AppSettings.getInstance(context)
        val pref = appSettings.settings.value.llamaBackendPreference.uppercase()

        val detectedFormat = bundle?.format ?: file?.let { NonGgufBundleInspector.detectFormat(it) }
        val isGguf = detectedFormat == ModelFormat.GGUF
        if (isGguf) {
            val profile = GgufExecutionProfile.resolve(
                context = context,
                modelNameOrId = effectiveName.ifBlank { effectiveId },
                modelSize = modelSize,
                customContextTokens = configuredContextWindow,
                backendPreference = pref
            )
            val dynamicContext = profile.contextTokens
            val dynamicThreads = profile.decodeThreads
            val prefillThreads = profile.prefillThreads
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
                "VULKAN", "GPU" -> LlamaBackend.VULKAN
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
            warmupMs = RuntimeWarmup.gguf(context, engine)
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
                if (RuntimeWarmup.enabled(context)) warmupMs = RuntimeWarmup.portable(engine)
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

        val shouldUseLiteRtLmConversation = detectedFormat == ModelFormat.LiteRtLm
        val resolvedModelPath = effectiveFile.absolutePath

        if (shouldUseLiteRtLmConversation) {
            val inferenceBackend = when (pref) {
                "CPU" -> InferenceBackend.LITERT_CPU
                "VULKAN", "GPU" -> InferenceBackend.LITERT_GPU
                else -> RuntimeSpeedTuner.liteRtBackend(context, effectiveId, effectiveFile.absolutePath, configuredContextWindow)
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
            if (!allowUnsafeOverride && inferenceBackend != InferenceBackend.LITERT_CPU) {
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
            try {
                initLiteRtLm(resolvedModelPath, effectiveId, loadedBackend, configuredContextWindow)
            } catch (failure: Exception) {
                if (failure is CancellationException || stopRequested || isBeingClosed || liteRtEngine != null ||
                    pref != "AUTO" || inferenceBackend != InferenceBackend.LITERT_GPU) throw failure
                RuntimeSpeedTuner.recordGpuFailure(context, effectiveId, resolvedModelPath, configuredContextWindow)
                liteRtCacheDir?.let { LiteRtRuntimeCache.release(context, it) }
                liteRtCacheDir = null
                loadedBackend = InferenceBackend.LITERT_CPU
                warmupMs = 0
                Log.w(TAG, "Measured GPU failed; retrying LiteRT initialization on CPU", failure)
                initLiteRtLm(resolvedModelPath, effectiveId, loadedBackend, configuredContextWindow)
            }
            activeBackendDisplayName = if (loadedBackend == InferenceBackend.LITERT_GPU) "LiteRT-LM GPU" else "LiteRT-LM CPU"
        } else {
            initMediaPipe(resolvedModelPath, configuredContextWindow)
            activeBackendDisplayName = "MediaPipe CPU"
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
        contextWindowTokens: Int? = null
    ) {
        // Mark that we're about to attempt a potentially fatal native init
        InitCrashGuard.markInitStarted(context, modelId)

        // This call may invoke native abort() and kill the entire process.
        // If that happens, InitCrashGuard.checkAndRecoverCrash() on next
        // app launch will detect it and blocklist this model.
        val effectiveMaxTokens = (contextWindowTokens ?: 4096).coerceIn(2048, 8192)
        var engine: Engine? = null
        try {
            val executionPolicy = RuntimeExecutionPolicy.resolve(context)
            val backend = when (inferenceBackend) {
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

            if (RuntimeWarmup.enabled(context)) {
                warmupMs = RuntimeWarmup.liteRt(requireNotNull(engine)) { liteRtConversation = it }.coerceAtLeast(1)
            }

            liteRtToolsDisabledForLoad = false
            retryToolsNextTurn = false
            liteRtEngine = engine
            // The UI defaults to Think off. Start default-thinking models with the
            // same hard template context so the very first request cannot inherit
            // the model's thinking default before ChatViewModel observes a toggle.
            val initialMode = if (isDefaultThinkingModel) {
                LiteRtConversationMode.NoThinking
            } else {
                LiteRtConversationMode.Default
            }
            liteRtConversation = createLiteRtConversation(initialMode)
            liteRtMessageCount = 0
            runtime = RuntimeKind.LiteRtLm
            if (stopRequested || isBeingClosed) throw CancellationException("Model initialization cancelled")
            InitCrashGuard.markInitSucceeded(context, modelId)
            Log.i(TAG, "LiteRT-LM engine ready on $backend warmupMs=$warmupMs: $modelPath")
        } catch (t: Throwable) {
            InitCrashGuard.markInitFailed(context, modelId)
            try {
                engine?.closeIfInitialized()
                liteRtEngine = null
                liteRtConversation = null
            } catch (closeError: Throwable) {
                liteRtEngine = engine // Retain the engine and both leases for shutdown retry.
                if (closeError !== t) t.addSuppressed(closeError)
                throw t
            }
            LiteRtRuntimeCache.release(context, liteRtCacheDir)
            LiteRtRuntimeCache.clear(context, liteRtCacheDir)
            liteRtCacheDir = null
            throw t
        }
    }

    private fun initMediaPipe(modelPath: String, contextWindowTokens: Int? = null) {
        runtimeThreads = -1 // The MediaPipe API does not expose the CPU worker count.
        val effectiveTokens = (contextWindowTokens ?: 4096).coerceIn(1024, 4096)
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(effectiveTokens)
            .setMaxTopK(40)
            .setPreferredBackend(LlmInference.Backend.CPU)
            .build()

        mediaPipeInference = runtimeContextCapacity.initialize(effectiveTokens) { LlmInference.createFromOptions(context, options) }
        runtime = RuntimeKind.MediaPipe
        Log.i(TAG, "MediaPipe LLM inference ready on CPU-safe backend: $modelPath")
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

        // For MediaPipe: stateless — the full history prompt is sent each turn.
        // Prepend thinking instruction for non-default models only (default models
        // handle thinking natively via their chat template + /no_think token).
        val mediaPipeBasePrompt = if (runtime == RuntimeKind.MediaPipe) {
            ChatPromptPolicy.mediaPipeBasePrompt(
                fullHistoryPrompt = inferencePrompt,
                rawUserText = inferenceUserText,
                useNativeGemmaTaskTemplate = useNativeGemmaTaskTemplate,
                memoryContext = memoryContext
            )
        } else {
            inferencePrompt
        }
        val mediaPipePrompt = if (runtime != RuntimeKind.MediaPipe) "" else when {
            !thinkingMode ->
                buildNoThinkingMediaPipePrompt(mediaPipeBasePrompt)
            thinkingMode && !isDefaultThinkingModel ->
                "Reason carefully before giving the final answer.\n\n$mediaPipeBasePrompt"
            else -> mediaPipeBasePrompt
        }
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
                    var firstToken = true
                    requireNotNull(portableTextEngine).generate(
                        prompt = portableTextEngine!!.renderPrompt(structuredMessages, inferencePrompt, memoryContext, thinkingMode), limit = MAX_RESPONSE_TOKENS,
                        temperature = sampling.temperature, topP = sampling.topP, topK = sampling.topK,
                        onText = { delta -> timing?.recordChunk(delta); effectiveOnUpdate(delta) },
                        onToken = {
                            lastNonLlamaTokenCount = it
                            if (firstToken) { firstToken = false; timing?.recordFirstToken(); reportProgress(RequestProgress.Generating) }
                        }
                    )
                }
                RuntimeKind.LiteRtLm -> streamLiteRtLm(
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
                RuntimeKind.MediaPipe -> streamMediaPipe(
                    prompt = mediaPipePrompt,
                    timing = timing,
                    onProgress = reportProgress,
                    onTokensGenerated = { lastNonLlamaTokenCount = it },
                    responseSnapshot = publisher::rawText,
                    onUpdate = effectiveOnUpdate
                )
                RuntimeKind.LlamaCpp -> streamLlamaCpp(
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
                RuntimeKind.None -> throw IllegalStateException("Inference not initialized")
            } }
            responseController.finish()
            val rawReasoning = if (!thinkingMode) "" else if (runtime == RuntimeKind.LiteRtLm) {
                lastLiteRtSnapshot?.reasoningText.orEmpty().ifBlank { responseController.getReasoningText() }
            } else {
                responseController.getReasoningText()
            }
            val cleanResponse = if (runtime == RuntimeKind.LiteRtLm) {
                ModelOutputSanitizer.cleanAssistantText(response, rawUserText)
            } else {
                responseController.getFinalAnswer()
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
            responseController.finish()
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
            responseController.finish()
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
            responseController.finish()
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
            val toolsAlreadyUsed = liteRtToolRecorder.snapshot()
            onAttemptChanged?.invoke(2)
            responseController?.beginAttempt(2)
            publisher?.beginAttempt(2)
            runCatching { liteRtConversation?.close() }
            liteRtConversation = createLiteRtConversation(liteRtConversationMode)
            retryToolsNextTurn = true
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
        val requestMessage = Message.of(LegacyThinkingProfiles.applyPrompt(prompt, configuredCapabilities?.capabilities?.thinkingControl ?: ThinkingControlStrategy.None, thinkingMode))
        val requestedThinking = ThinkingModeConfig.resolveRequestThinking(
            capability = resolvedThinkingCapability,
            userPreference = thinkingMode
        )
        val requestContext = ThinkingModeConfig.liteRtExtraContext(
            capability = resolvedThinkingCapability,
            userPreference = thinkingMode
        )
        val requestStartedAt = System.currentTimeMillis()
        var firstChunkRecorded = false
        Log.i(
            TAG,
            "LiteRT request mode=${if (thinkingMode) "thinking" else "direct"} " +
                "defaultThinking=$isDefaultThinkingModel " +
                "contextKeys=${requestContext.keys} promptChars=${prompt.length}"
        )
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
                    Log.i(
                        TAG,
                        "LiteRT first token mode=${if (thinkingMode) "thinking" else "direct"} " +
                            "latencyMs=${System.currentTimeMillis() - requestStartedAt}"
                    )
                }
            },
            onDelta = { delta ->
                if (stopRequested) return@LiteRtStreamAdapter
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
            conversation.sendMessageAsync(
                requestMessage,
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
        temperature: Float = mediaPipeTemperature,
        seedSalt: Int = 0,
        timing: InferenceTiming? = null,
        onProgress: ((RequestProgress) -> Unit)? = null,
        onTokensGenerated: ((Int) -> Unit)? = null,
        responseSnapshot: () -> String,
        onUpdate: (String) -> Unit
    ): String {
        val inference = mediaPipeInference ?: throw IllegalStateException("Inference not initialized")
        val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(40)
            .setTopP(0.95f)
            .setTemperature(temperature)
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
            if (ggufConversationHasTools && mediaInputs.isEmpty()) {
                val toolStream = com.shounak.localmeshai.ai.tools.GgufToolTurnStream(
                    publish = { answer, reasoning, thinking ->
                        responseController?.appendStructured(answer, reasoning, thinking,
                            reqId = requestId, attemptId = 1, replaceSnapshot = true)
                        publisher?.notifyStructuredUpdate(allowEmpty = true)
                    },
                    onText = { text ->
                        if (!firstTokenRecorded) {
                            firstTokenRecorded = true
                            timing?.recordFirstToken()
                            onProgress?.invoke(RequestProgress.Generating)
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
                            onProgress?.invoke(RequestProgress.UsingTools)
                        }
                    ) { request ->
                        var parsed: String? = null
                        val status = engine.generateMultimodalStream(
                            prompt = prompt, messages = messages, enableThinking = !noThinking,
                            samplingParams = profileSampling(), onMetrics = onMetrics,
                            toolRequestJson = request, onToolResponse = { parsed = it },
                            onFirstToken = { onProgress?.invoke(RequestProgress.UsingTools) },
                            onStructuredToken = { answer, reasoning, thinking ->
                                if (!stopRequested && !isBeingClosed) toolStream.update(answer, reasoning, thinking)
                            },
                            onToken = { onProgress?.invoke(RequestProgress.UsingTools) }
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
                    ggufConversationHasTools = false
                    Log.w(TAG, "GGUF tools unavailable; retrying this conversation as ordinary chat", failure)
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


    private fun buildNoThinkingMediaPipePrompt(prompt: String): String {
        return "Skip analysis and reasoning. Do not emit <think> tags. " +
            "Start the final answer immediately.\n\n$prompt"
    }


    val isStopRequested: Boolean
        get() = stopRequested

    fun needsResetWhenThinkingModeChanges(): Boolean {
        return runtime == RuntimeKind.LiteRtLm && isDefaultThinkingModel
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

    fun resetConversation(noThinking: Boolean = false) {
        if (retryToolsNextTurn) { retryToolsNextTurn = false; liteRtToolsDisabledForLoad = false }
        if (runtime == RuntimeKind.LlamaCpp) {
            llamaCppEngine?.resetAfterValidation()
            conversationToolSettings = ConversationToolSettings.from(AppSettings.getInstance(context).settings.value)
            ggufConversationTools = toolsForNewConversation()
            ggufConversationHasTools = conversationToolSettings?.enabled == true && ggufConversationTools.isNotEmpty() && llamaCppEngine?.supportsToolCalling() == true
            ggufToolRecorder.clear()
        }
        if (runtime != RuntimeKind.LiteRtLm) return
        val mode = if (noThinking && isDefaultThinkingModel) {
            LiteRtConversationMode.NoThinking
        } else {
            LiteRtConversationMode.Default
        }
        runCatching { liteRtConversation?.close() }.onFailure { t ->
            Log.w(TAG, "LiteRT-LM conversation close during reset failed", t)
        }
        stopRequested = false
        liteRtConversation = createLiteRtConversation(mode)
        liteRtMessageCount = 0
    }

    private fun toolsForNewConversation(): List<com.shounak.localmeshai.ai.tools.ChatTool> =
        com.shounak.localmeshai.ai.tools.LocalTools.forConversation(context, settings = requireNotNull(conversationToolSettings),
            documentIds = { documentSearchDocumentIds }) { stopRequested || isBeingClosed }

    private fun createLiteRtConversation(mode: LiteRtConversationMode): Conversation? {
        val engine = liteRtEngine ?: return null
        val settings = ConversationToolSettings.from(AppSettings.getInstance(context).settings.value)
        if (conversationToolSettings?.requiresRefresh(settings) == true) liteRtToolsDisabledForLoad = false
        conversationToolSettings = settings
        liteRtConversationMode = mode
        liteRtCompletedTurns = 0
        liteRtConversationHasTools = false
        liteRtToolRecorder.clear()
        if (!liteRtToolsDisabledForLoad && settings.enabled) {
            try {
                val providers = toolsForNewConversation().map {
                    tool(liteRtToolRecorder.recording(it))
                }
                if (providers.isNotEmpty()) {
                    val conversation = engine.createConversation(ConversationConfig(
                        tools = providers,
                        automaticToolCalling = true,
                        extraContext = if (mode == LiteRtConversationMode.NoThinking)
                            ThinkingModeConfig.liteRtExtraContext(isDefaultThinkingModel = true, thinkingMode = false)
                        else emptyMap()
                    ))
                    liteRtConversationHasTools = true
                    return conversation
                }
            } catch (failure: Exception) {
                if (failure is CancellationException || stopRequested || isBeingClosed) throw failure
                liteRtToolsDisabledForLoad = true
                retryToolsNextTurn = true
                Log.w(TAG, "LiteRT tool conversation unavailable; using ordinary chat for this turn", failure)
            }
        }
        return when (mode) {
            LiteRtConversationMode.Default -> engine.createConversation()
            LiteRtConversationMode.NoThinking -> engine.createConversation(
                ConversationConfig(
                    extraContext = ThinkingModeConfig.liteRtExtraContext(
                        isDefaultThinkingModel = true,
                        thinkingMode = false
                    )
                )
            )
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
        resolvedThinkingCapability = ThinkingCapability.Unknown
        isDefaultThinkingModel = false
        supportsTextNoThinkingSwitch = false
        useNativeGemmaTaskTemplate = false
        mediaPipeTemperature = DEFAULT_TEMPERATURE
        liteRtConversationMode = LiteRtConversationMode.Default
        activeBackendDisplayName = ""
        isBeingClosed = false
        AppCacheCleaner.clearAppCache(context)

        return ShutdownResult.Success
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


    /**
     * Strips `<think>…</think>` blocks (and any unclosed `<think>…` tail) from
     * the model output. Used when thinking mode is disabled but the model emits
     * thinking tokens anyway.
     */
    private fun stripThinkingTags(text: String): String {
        // Remove complete <think>...</think> blocks (case-insensitive, dotall)
        var result = THINK_BLOCK_REGEX.replace(text, "")
        // Remove any unclosed <think>... at the end (model still streaming thinking)
        result = THINK_UNCLOSED_REGEX.replace(result, "")
        // Remove any prefilled opening tag where only closing tag is emitted
        result = THINK_PREFILLED_CLOSE_REGEX.replace(result, "")
        return result.trimStart()
    }

    fun isLlamaCppRuntime(): Boolean = runtime == RuntimeKind.LlamaCpp

    private enum class RuntimeKind {
        None,
        MediaPipe,
        LiteRtLm,
        Portable,
        LlamaCpp
    }

    private enum class LiteRtConversationMode {
        Default,
        NoThinking
    }
}
