@file:Suppress("DEPRECATION")
package com.shounak.localmeshai.ai

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Message
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.ProgressListener
import com.shounak.localmeshai.utils.AppCacheCleaner
import com.shounak.localmeshai.utils.AppSettings
import com.shounak.localmeshai.utils.DeviceUtils
import com.shounak.localmeshai.utils.ChatPromptPolicy
import com.shounak.localmeshai.utils.ChatRuntimePolicy
import com.shounak.localmeshai.utils.InferenceBackend
import com.shounak.localmeshai.utils.StreamingResponsePublisher
import com.shounak.localmeshai.utils.InferenceTiming
import com.shounak.localmeshai.utils.InitCrashGuard
import com.shounak.localmeshai.utils.LiteRtRuntimeCache
import com.shounak.localmeshai.utils.ModelOutputSanitizer
import com.shounak.localmeshai.utils.PromptMathNormalizer
import com.shounak.localmeshai.utils.ModelResponseQuality
import com.shounak.localmeshai.utils.ModelDownloader
import com.shounak.localmeshai.utils.NoThinkingPromptUtils
import com.shounak.localmeshai.utils.ThinkingModeConfig
import com.shounak.localmeshai.utils.ThinkingTextUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.CancellationException as FutureCancellationException
import java.util.concurrent.Future

class ChatInferenceManager(private val context: Context) {
    private var mediaPipeInference: LlmInference? = null
    private var liteRtEngine: Engine? = null
    private var liteRtConversation: Conversation? = null
    private var liteRtCacheDir: File? = null
    private var llamaCppEngine: LlamaCppEngine? = null
    private var runtime = RuntimeKind.None
    private var activeModelLease: AutoCloseable? = null
    @Volatile private var activeMediaPipeSession: LlmInferenceSession? = null
    @Volatile private var activeMediaPipeFuture: Future<String>? = null
    @Volatile private var stopRequested = false
    @Volatile private var activeLiteRtAdapter: LiteRtStreamAdapter? = null
    @Volatile private var lastLiteRtSnapshot: StructuredResponseSnapshot? = null
    @Volatile private var isBeingClosed = false
    @Volatile private var isDefaultThinkingModel = false
    @Volatile private var supportsTextNoThinkingSwitch = false
    val usesStatefulHistory: Boolean get() = runtime == RuntimeKind.LiteRtLm
    val hasActiveStatefulConversation: Boolean get() = runtime == RuntimeKind.LiteRtLm && liteRtConversation != null && liteRtMessageCount > 0
    val isReady: Boolean get() = when (runtime) {
        RuntimeKind.LlamaCpp -> llamaCppEngine?.isInitialized == true
        RuntimeKind.LiteRtLm -> liteRtConversation != null
        RuntimeKind.MediaPipe -> mediaPipeInference != null
        RuntimeKind.None -> false
    }
    val usesStructuredHistory: Boolean get() = runtime == RuntimeKind.LlamaCpp
    @Volatile private var activeOperationControlId: Long = 0L
    @Volatile private var useNativeGemmaTaskTemplate = false
    @Volatile private var mediaPipeTemperature = DEFAULT_TEMPERATURE
    @Volatile private var liteRtConversationMode = LiteRtConversationMode.Default
    @Volatile private var liteRtMessageCount = 0
    var activeBackendDisplayName: String = ""
        private set
    @Volatile private var currentGgufVerifiedSha: String = ""
    @Volatile private var currentGgufModelPath: String = ""
    @Volatile private var currentGgufThreads: Int = 4
    @Volatile private var currentGgufPrefillThreads: Int = 4
    @Volatile private var currentGgufContext: Int = 2048
    @Volatile private var currentGgufBatch: Int = 256
    @Volatile private var currentGgufUbatch: Int = 128
    @Volatile private var currentGgufExpectedBytes: Long = 0L

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
        onProgress: ((RequestProgress) -> Unit)? = null
    ) {
        val file = File(modelPath)
        if (!file.exists()) {
            throw IllegalArgumentException("Model file not found at $modelPath")
        }
        if (file.extension.equals("task", ignoreCase = true) && !ModelDownloader.isLikelyTaskBundle(file)) {
            throw IllegalArgumentException("Selected .task file does not look like a MediaPipe or LiteRT task bundle. Download a verified Android .task model from the Models tab.")
        }

        val effectiveId = modelId.ifBlank { file.nameWithoutExtension }
        close()
        com.shounak.localmeshai.data.StartupCoordinator.getInstance(context).awaitCrashGuardChecked()
        val modelLease = com.shounak.localmeshai.utils.ModelArtifactStorage.leaseArtifact(file)
        AppCacheCleaner.clearAppCache(context)
        // Register active operation control handle only AFTER closing prior runtime!
        activeOperationControlId = operationControlId
        if (operationControlId != 0L && DefaultLlamaNativeBridge.getOperationControlProgress(operationControlId) == -2) {
            modelLease.close()
            throw CancellationException("Operation was cancelled before initialization")
        }

        try {
        val searchString = "$modelPath|$effectiveId|$modelName".lowercase()
        isDefaultThinkingModel = ThinkingModeConfig.isThinkingSupported(
            modelPath = modelPath,
            effectiveId = effectiveId,
            modelName = modelName,
            supportsThinkingFlag = supportsThinkingMode
        )
        supportsTextNoThinkingSwitch = ThinkingModeConfig.supportsTextNoThinkingSwitch(
            modelPath = modelPath,
            effectiveId = effectiveId,
            modelName = modelName
        )
        val isTinyGemmaMediaPipeModel = listOf(
            "gemma3-1b",
            "gemma-3-1b",
            "gemma 3 1b",
            "gemma3-270m",
            "gemma-3-270m",
            "gemma 3 270m"
        ).any(searchString::contains)
        useNativeGemmaTaskTemplate = file.extension.equals("task", ignoreCase = true) &&
            searchString.contains("gemma")
        mediaPipeTemperature = if (isTinyGemmaMediaPipeModel) {
            TINY_MODEL_TEMPERATURE
        } else {
            DEFAULT_TEMPERATURE
        }
        stopRequested = false
        isBeingClosed = false

        val appSettings = AppSettings.getInstance(context)
        val pref = appSettings.settings.value.llamaBackendPreference.uppercase()

        if (ChatRuntimePolicy.isGgufModel(file.name)) {
            val profile = GgufExecutionProfile.resolve(
                context = context,
                modelNameOrId = modelName.ifBlank { effectiveId },
                modelSize = modelSize,
                customContextTokens = contextWindowTokens,
                backendPreference = pref
            )
            val dynamicContext = profile.contextTokens
            val dynamicThreads = profile.decodeThreads
            val prefillThreads = profile.prefillThreads
            val nBatch = profile.batchTokens
            val nUbatch = profile.microBatchTokens

            onModelProgress?.invoke(ModelLoadStage.VerifyingFile, null, null)
            val verifiedSha = GgufGpuValidator.resolveModelSha256(
                modelPath = file.absolutePath,
                modelId = effectiveId,
                context = context,
                isCancelled = { stopRequested },
                onProgress = { bytesRead, totalBytes ->
                    onModelProgress?.invoke(ModelLoadStage.VerifyingFile, bytesRead, totalBytes)
                }
            )
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
                requestedBackend = requestedBackend
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
                engine.initialize(
                    modelPath = file.absolutePath,
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
            } catch (t: Throwable) {
                if (t is CancellationException) { engine.close(); throw t }
                if (selectedBackend == LlamaBackend.VULKAN) {
                    Log.w(TAG, "Vulkan initialization failed, falling back to true CPU", t)
                    fallbackReason = "Vulkan initialization failed: ${t.message}"
                    onModelProgress?.invoke(ModelLoadStage.ContextPreparation("CPU"), null, null)
                    engine.close() // Release failed GPU engine completely before retrying
                    val cpuRetryEngine = LlamaCppEngine(context)
                    try {
                        cpuRetryEngine.initialize(
                            modelPath = file.absolutePath,
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
                        engine = cpuRetryEngine
                        selectedBackend = LlamaBackend.CPU
                    } catch (cpuError: Throwable) {
                        cpuRetryEngine.close()
                        llamaCppEngine = null
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
            _isGgufActive.value = true

            // Set current model configuration for recovery fallback
            currentGgufVerifiedSha = verifiedSha
            currentGgufModelPath = file.absolutePath
            currentGgufThreads = dynamicThreads
            currentGgufPrefillThreads = prefillThreads
            currentGgufContext = dynamicContext
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
            Log.i(TAG, "LlamaCpp GGUF engine initialized for: ${file.name} on $activeBackendDisplayName (ctx: $dynamicContext, threads: $dynamicThreads, prefillThreads: $prefillThreads, batch: $nBatch/$nUbatch)")
            activeModelLease = modelLease
            return
        }

        _isGgufActive.value = false

        val shouldUseLiteRtLmConversation = ChatRuntimePolicy.shouldUseLiteRtLmConversation(
            fileName = file.name,
            modelIdentity = searchString
        )
        if (shouldUseLiteRtLmConversation) {
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
                fileName = file.name
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
                    isVision = false,
                    fileName = file.name,
                    backendLabel = "LiteRT-LM"
                )
                if (!allowed) {
                    throw IllegalStateException(reason)
                }
            }
            initLiteRtLm(
                modelPath = modelPath,
                modelId = effectiveId,
                inferenceBackend = inferenceBackend,
                contextWindowTokens = contextWindowTokens
            )
            activeBackendDisplayName = if (inferenceBackend == InferenceBackend.LITERT_GPU) "LiteRT-LM GPU" else "LiteRT-LM CPU"
        } else {
            initMediaPipe(modelPath, contextWindowTokens)
            activeBackendDisplayName = "MediaPipe CPU"
        }
        activeModelLease = modelLease
        } catch (t: Throwable) {
            modelLease.close()
            throw t
        } finally {
            if (activeOperationControlId == operationControlId) {
                activeOperationControlId = 0L
            }
        }
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
                engine.initialize()
            } catch (t: Throwable) {
                if (effectiveMaxTokens > 2048) {
                    Log.w(TAG, "LiteRT-LM init with $effectiveMaxTokens tokens failed, retrying with 2048: ${t.message}")
                    runCatching { engine.close() }
                    engine = Engine(
                        EngineConfig(
                            modelPath = modelPath,
                            backend = backend,
                            maxNumTokens = 2048,
                            cacheDir = runtimeCacheDir.absolutePath
                        )
                    )
                    engine.initialize()
                } else {
                    throw t
                }
            }

            // If we reach here, native init succeeded!
            InitCrashGuard.markInitCompleted(context)

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
            Log.i(TAG, "LiteRT-LM engine ready on $backend: $modelPath")
        } catch (t: Throwable) {
            InitCrashGuard.markInitCompleted(context)
            try { engine?.close() } catch (_: Throwable) {}
            LiteRtRuntimeCache.clear(context, liteRtCacheDir)
            liteRtCacheDir = null
            throw t
        }
    }

    private fun initMediaPipe(modelPath: String, contextWindowTokens: Int? = null) {
        val effectiveTokens = (contextWindowTokens ?: 4096).coerceIn(1024, 4096)
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(effectiveTokens)
            .setMaxTopK(40)
            .setPreferredBackend(LlmInference.Backend.CPU)
            .build()

        mediaPipeInference = LlmInference.createFromOptions(context, options)
        runtime = RuntimeKind.MediaPipe
        Log.i(TAG, "MediaPipe LLM inference ready on CPU-safe backend: $modelPath")
    }

    suspend fun generateResponseStreaming(
        prompt: String,           // Full history blob — used by MediaPipe (stateless)
        rawUserText: String,      // Bare user message — used by LiteRT-LM (Conversation owns history)
        memoryContext: String = "", // Persistent memory context block
        structuredMessages: List<Pair<String, String>>? = null, // Structured conversation turns for GGUF
        restoreStatefulHistory: Boolean = false,
        thinkingMode: Boolean,
        requestId: String = "",
        sessionId: String = "",
        timing: InferenceTiming? = null,
        isRetry: Boolean = false,
        onProgress: ((RequestProgress) -> Unit)? = null,
        onUpdate: (String) -> Unit
    ): InferenceExecutionResult = withContext(Dispatchers.IO) {
        stopRequested = false
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
            publish = onUpdate
        )
        var attempt = 1
        val effectiveOnUpdate: (String) -> Unit = { delta ->
            if (delta.isNotEmpty()) {
                responseController.appendDelta(delta, reqId = requestId, attemptId = attempt)
                publisher.appendDelta(delta)
            }
        }

        onProgress?.invoke(RequestProgress.ProcessingPrompt)
        var lastLlamaMetrics: LlamaGenerationMetrics? = null
        var lastNonLlamaTokenCount: Int? = null
        val reportProgress: (RequestProgress) -> Unit = {
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
            val response = when (runtime) {
                RuntimeKind.LiteRtLm -> streamLiteRtLm(
                    prompt = liteRtUserText,
                    thinkingMode = thinkingMode,
                    requestId = requestId,
                    timing = timing,
                    onProgress = reportProgress,
                    onTokensGenerated = { lastNonLlamaTokenCount = it },
                    responseSnapshot = publisher::rawText,
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
                    noThinking = !thinkingMode,
                    timing = timing,
                    onProgress = reportProgress,
                    onMetrics = { m -> lastLlamaMetrics = m },
                    responseSnapshot = publisher::rawText,
                    publisher = publisher,
                    responseController = responseController,
                    onUpdate = effectiveOnUpdate
                )
                RuntimeKind.None -> throw IllegalStateException("Inference not initialized")
            }
            responseController.finish()
            val rawReasoning = if (runtime == RuntimeKind.LiteRtLm) {
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
                isStopped = stopRequested,
                sdkError = if (stopRequested) null else exception,
                acceptedAnswerFallback = responseController.acceptedAnswerText(),
                thinkingMode = thinkingMode
            )
            val finalFormatted = responseController.formatFinal(finalized.answerText, finalized.reasoningText)
            publisher.finishExplicit(finalFormatted)
            finalized.toExecutionResult(currentMetrics())
        }
    }

    @OptIn(ExperimentalApi::class)
    private suspend fun streamLiteRtLm(
        prompt: String,
        thinkingMode: Boolean,
        requestId: String = "",
        timing: InferenceTiming? = null,
        onProgress: ((RequestProgress) -> Unit)? = null,
        onTokensGenerated: ((Int) -> Unit)? = null,
        responseSnapshot: () -> String,
        onUpdate: (String) -> Unit
    ): String {
        val conversation = liteRtConversation ?: throw IllegalStateException("Inference not initialized")
        val requestPrompt = if (!thinkingMode && supportsTextNoThinkingSwitch) {
            NoThinkingPromptUtils.wrap(prompt)
        } else {
            prompt
        }
        val requestMessage = Message.of(requestPrompt)
        val requestContext = ThinkingModeConfig.liteRtExtraContext(
            isDefaultThinkingModel = isDefaultThinkingModel,
            thinkingMode = thinkingMode
        )
        val requestStartedAt = System.currentTimeMillis()
        var firstChunkRecorded = false
        Log.i(
            TAG,
            "LiteRT request mode=${if (thinkingMode) "thinking" else "direct"} " +
                "defaultThinking=$isDefaultThinkingModel textSwitch=${requestPrompt != prompt} " +
                "contextKeys=${requestContext.keys} promptChars=${requestPrompt.length}"
        )
        val adapter = LiteRtStreamAdapter(
            requestId = requestId,
            attemptId = 1,
            thinkingMode = thinkingMode,
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
                timing?.recordChunk(delta)
                runCatching { onUpdate(delta) }
            }
        )
        activeLiteRtAdapter = adapter
        try {
            conversation.sendMessageAsync(requestMessage, adapter, requestContext)
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
        noThinking: Boolean = false,
        timing: InferenceTiming? = null,
        onProgress: ((RequestProgress) -> Unit)? = null,
        onMetrics: ((LlamaGenerationMetrics) -> Unit)? = null,
        responseSnapshot: () -> String,
        publisher: StreamingResponsePublisher? = null,
        responseController: InferenceResponseController? = null,
        onUpdate: (String) -> Unit
    ): String {
        val engine = llamaCppEngine ?: throw IllegalStateException("GGUF engine not initialized")
        // Use structured messages directly when available.
        // When they are absent, synthesise a single user turn from rawUserText.
        val baseMessages: List<Pair<String, String>> = structuredMessages
            ?: if (rawUserText.isNotBlank()) listOf("user" to rawUserText.trim())
            else listOf("user" to prompt.trim())
        val messages = NoThinkingPromptUtils.wrapLatestUserTurn(
            baseMessages, enabled = noThinking && supportsTextNoThinkingSwitch
        )

        val isVulkan = engine.activeBackend == LlamaBackend.VULKAN
        var firstTokenRecorded = false

        try {
            val result = engine.generateStream(
                prompt = prompt,
                messages = messages,
                onMetrics = onMetrics,
                onFirstToken = { timing?.recordFirstToken(); onProgress?.invoke(RequestProgress.Generating) }
            ) { token ->
                if (stopRequested) return@generateStream
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

            if (isRecoverable && !stopRequested && currentGgufModelPath.isNotBlank()) {
                Log.w(TAG, "Recoverable Vulkan error (${e.category}: ${e.message}). Releasing GPU context and retrying on CPU...")
                onProgress?.invoke(RequestProgress.RecoveringOnCpu)
                // 1. Release GPU context
                engine.close()

                // 2. Clear failed partial response
                responseController?.beginAttempt(2)
                publisher?.beginAttempt(2) ?: onUpdate("")

                // 3. Reinitialize on CPU
                val cpuEngine = LlamaCppEngine(context)
                try {
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
                val retryResult = cpuEngine.generateStream(
                    prompt = prompt,
                    messages = messages,
                    onMetrics = onMetrics,
                    onFirstToken = { timing?.recordFirstToken(); onProgress?.invoke(RequestProgress.Generating) }
                ) { token ->
                    if (stopRequested) return@generateStream
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
        val directAnswerPrompt = "Skip analysis and reasoning. Do not emit <think> tags. " +
            "Start the final answer immediately.\n\n$prompt"
        return if (supportsTextNoThinkingSwitch) {
            NoThinkingPromptUtils.wrap(directAnswerPrompt)
        } else {
            directAnswerPrompt
        }
    }


    val isStopRequested: Boolean
        get() = stopRequested

    fun needsResetWhenThinkingModeChanges(): Boolean {
        return runtime == RuntimeKind.LiteRtLm && isDefaultThinkingModel
    }

    fun cancelGeneration() {
        stopRequested = true
        val opId = activeOperationControlId
        if (opId != 0L) {
            runCatching { LlamaCppEngine.nativeCancelOperationControl(opId) }
        }
        llamaCppEngine?.stop()
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
        if (runtime == RuntimeKind.LlamaCpp) llamaCppEngine?.resetAfterValidation()
        if (runtime != RuntimeKind.LiteRtLm) return
        val mode = if (noThinking && isDefaultThinkingModel) {
            LiteRtConversationMode.NoThinking
        } else {
            LiteRtConversationMode.Default
        }
        runCatching { liteRtConversation?.close() }.onFailure { t ->
            Log.w(TAG, "LiteRT-LM conversation close during reset failed", t)
        }
        liteRtConversation = createLiteRtConversation(mode)
        liteRtMessageCount = 0
        stopRequested = false
    }

    private fun createLiteRtConversation(mode: LiteRtConversationMode): Conversation? {
        val engine = liteRtEngine ?: return null
        liteRtConversationMode = mode
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

    fun close() {
        isBeingClosed = true
        cancelGeneration()
        val sessionToClose = activeMediaPipeSession
        activeMediaPipeSession = null
        runCatching { sessionToClose?.close() }
        try { liteRtConversation?.close() } catch (_: Throwable) {}
        liteRtConversation = null
        liteRtMessageCount = 0
        try { liteRtEngine?.close() } catch (_: Throwable) {}
        liteRtEngine = null
        LiteRtRuntimeCache.release(context, liteRtCacheDir)
        liteRtCacheDir = null
        try { llamaCppEngine?.close() } catch (_: Throwable) {}
        llamaCppEngine = null
        try { mediaPipeInference?.close() } catch (_: Throwable) {}
        mediaPipeInference = null
        activeModelLease?.close()
        activeModelLease = null
        runtime = RuntimeKind.None
        _isGgufActive.value = false
        isDefaultThinkingModel = false
        supportsTextNoThinkingSwitch = false
        useNativeGemmaTaskTemplate = false
        mediaPipeTemperature = DEFAULT_TEMPERATURE
        liteRtConversationMode = LiteRtConversationMode.Default
        activeBackendDisplayName = ""
        isBeingClosed = false
        AppCacheCleaner.clearAppCache(context)
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
        LlamaCpp
    }

    private enum class LiteRtConversationMode {
        Default,
        NoThinking
    }
}
