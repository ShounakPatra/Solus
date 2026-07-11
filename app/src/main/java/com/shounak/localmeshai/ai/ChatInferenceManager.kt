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
import com.shounak.localmeshai.utils.DeviceUtils
import com.shounak.localmeshai.utils.ChatPromptPolicy
import com.shounak.localmeshai.utils.ChatRuntimePolicy
import com.shounak.localmeshai.utils.InferenceBackend
import com.shounak.localmeshai.utils.InitCrashGuard
import com.shounak.localmeshai.utils.LiteRtRuntimeCache
import com.shounak.localmeshai.utils.ModelOutputSanitizer
import com.shounak.localmeshai.utils.ModelResponseQuality
import com.shounak.localmeshai.utils.ModelDownloader
import com.shounak.localmeshai.utils.NoThinkingPromptUtils
import com.shounak.localmeshai.utils.ThinkingModeConfig
import com.shounak.localmeshai.utils.ThinkingTextUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
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
    private var runtime = RuntimeKind.None
    @Volatile private var activeMediaPipeSession: LlmInferenceSession? = null
    @Volatile private var activeMediaPipeFuture: Future<String>? = null
    @Volatile private var stopRequested = false
    @Volatile private var isBeingClosed = false
    @Volatile private var isDefaultThinkingModel = false
    @Volatile private var supportsTextNoThinkingSwitch = false
    @Volatile private var useNativeGemmaTaskTemplate = false
    @Volatile private var mediaPipeTemperature = DEFAULT_TEMPERATURE
    @Volatile private var liteRtConversationMode = LiteRtConversationMode.Default

    companion object {
        private const val TAG = "ChatInferenceManager"
        private const val MAX_RESPONSE_TOKENS = 1024
        private const val DEFAULT_TEMPERATURE = 0.7f
        private const val TINY_MODEL_TEMPERATURE = 0.35f
        private const val RETRY_TEMPERATURE = 0.2f

        private val THINK_BLOCK_REGEX =
            Regex("""<think>[\s\S]*?</think>""", RegexOption.IGNORE_CASE)
        private val THINK_UNCLOSED_REGEX =
            Regex("""<think>[\s\S]*$""", RegexOption.IGNORE_CASE)
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
    fun initialize(
        modelPath: String,
        modelId: String = "",
        modelName: String = "",
        modelSize: String = "",
        allowUnsafeOverride: Boolean = false
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
        val searchString = "$modelPath|$effectiveId|$modelName".lowercase()
        isDefaultThinkingModel = listOf("qwen3", "deepseek", "reasoning", "mimo", "exaone").any {
            searchString.contains(it)
        }
        supportsTextNoThinkingSwitch = searchString.contains("qwen3")
        val isTinyGemmaMediaPipeModel = listOf(
            "gemma3_270m",
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

        val shouldUseLiteRtLmConversation = ChatRuntimePolicy.shouldUseLiteRtLmConversation(
            fileName = file.name,
            modelIdentity = searchString
        )
        if (shouldUseLiteRtLmConversation) {
            val inferenceBackend = if (allowUnsafeOverride) {
                InferenceBackend.LITERT_GPU
            } else {
                DeviceUtils.selectBackendForModelFile(context, file.name)
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
            if (!allowUnsafeOverride) {
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
                inferenceBackend = inferenceBackend
            )
        } else {
            initMediaPipe(modelPath)
        }
    }

    private fun initLiteRtLm(
        modelPath: String,
        modelId: String,
        inferenceBackend: InferenceBackend
    ) {
        // Mark that we're about to attempt a potentially fatal native init
        InitCrashGuard.markInitStarted(context, modelId)

        // This call may invoke native abort() and kill the entire process.
        // If that happens, InitCrashGuard.checkAndRecoverCrash() on next
        // app launch will detect it and blocklist this model.
        var engine: Engine? = null
        try {
            val backend = when (inferenceBackend) {
                InferenceBackend.LITERT_GPU -> Backend.GPU()
                InferenceBackend.LITERT_CPU,
                InferenceBackend.MEDIAPIPE -> Backend.CPU()
            }
            val runtimeCacheDir = LiteRtRuntimeCache.prepare(context, modelId, inferenceBackend)
            liteRtCacheDir = runtimeCacheDir
            engine = Engine(
                EngineConfig(
                    modelPath = modelPath,
                    backend = backend,
                    maxNumTokens = MAX_RESPONSE_TOKENS,
                    cacheDir = runtimeCacheDir.absolutePath
                )
            )
            engine.initialize()

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

    private fun initMediaPipe(modelPath: String) {
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(MAX_RESPONSE_TOKENS)
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
        restoreStatefulHistory: Boolean = false,
        thinkingMode: Boolean,
        onUpdate: (String) -> Unit
    ): Pair<String, Long> = withContext(Dispatchers.IO) {
        stopRequested = false
        val startTime = System.currentTimeMillis()

        // LiteRT-LM applies the chat template internally. The hard thinking switch is
        // passed as request-level template context in streamLiteRtLm. Injecting
        // /no_think into both system and user messages corrupts some Qwen 3 templates
        // and can make the model emit only an unfinished thinking block.
        val liteRtUserText = if (runtime == RuntimeKind.LiteRtLm && restoreStatefulHistory) {
            prompt
        } else {
            rawUserText
        }

        // For MediaPipe: stateless — the full history prompt is sent each turn.
        // Prepend thinking instruction for non-default models only (default models
        // handle thinking natively via their chat template + /no_think token).
        val mediaPipeBasePrompt = if (runtime == RuntimeKind.MediaPipe) {
            ChatPromptPolicy.mediaPipeBasePrompt(
                fullHistoryPrompt = prompt,
                rawUserText = rawUserText,
                useNativeGemmaTaskTemplate = useNativeGemmaTaskTemplate
            )
        } else {
            prompt
        }
        val mediaPipePrompt = when {
            !thinkingMode && isDefaultThinkingModel ->
                buildNoThinkingMediaPipePrompt(mediaPipeBasePrompt)
            thinkingMode && !isDefaultThinkingModel ->
                "Reason carefully before giving the final answer.\n\n$mediaPipeBasePrompt"
            else -> mediaPipeBasePrompt
        }

        // Strip <think> blocks in real-time when thinking is disabled (default-thinking models
        // may still emit thinking tokens even when /no_think is set).
        val shouldStripThinking = !thinkingMode && isDefaultThinkingModel
        val effectiveOnUpdate: (String) -> Unit = if (shouldStripThinking) {
            { partial -> onUpdate(stripThinkingTags(partial)) }
        } else {
            onUpdate
        }
        try {
            val response = when (runtime) {
                RuntimeKind.LiteRtLm -> streamLiteRtLm(
                    prompt = liteRtUserText,
                    thinkingMode = thinkingMode,
                    onUpdate = effectiveOnUpdate
                )
                RuntimeKind.MediaPipe -> streamMediaPipe(mediaPipePrompt, effectiveOnUpdate)
                RuntimeKind.None -> "Inference not initialized"
            }
            var cleanResponse = ModelOutputSanitizer.cleanAssistantText(
                text = finalizeModelOutput(
                    text = response,
                    thinkingMode = thinkingMode,
                    shouldStripThinking = shouldStripThinking
                ),
                latestUserText = rawUserText
            )
            if (cleanResponse.isBlank() && rawUserText.isNotBlank() && !stopRequested) {
                val retryText = if (runtime == RuntimeKind.LiteRtLm && isDefaultThinkingModel) {
                    prompt
                } else {
                    rawUserText
                }
                val retryResponse = streamDirectAnswerRetry(retryText, onUpdate)
                val retryCleanResponse = ModelOutputSanitizer.cleanAssistantText(
                    text = finalizeModelOutput(
                        text = retryResponse,
                        thinkingMode = false,
                        shouldStripThinking = isDefaultThinkingModel
                    ),
                    latestUserText = rawUserText
                )
                if (retryCleanResponse.isNotBlank()) {
                    cleanResponse = retryCleanResponse
                }
            }
            if (
                runtime != RuntimeKind.None &&
                rawUserText.isNotBlank() &&
                !stopRequested &&
                ModelResponseQuality.isGenericNonAnswer(cleanResponse, rawUserText)
            ) {
                val retryResponse = when (runtime) {
                    RuntimeKind.MediaPipe -> streamMediaPipe(
                        prompt = buildMediaPipeRetryPrompt(
                            rawUserText,
                            thinkingMode && !isDefaultThinkingModel
                        ),
                        onUpdate = effectiveOnUpdate,
                        temperature = RETRY_TEMPERATURE,
                        seedSalt = 1
                    )
                    RuntimeKind.LiteRtLm -> streamDirectAnswerRetry(rawUserText, effectiveOnUpdate)
                    RuntimeKind.None -> ""
                }
                val retryCleanResponse = ModelOutputSanitizer.cleanAssistantText(
                    text = finalizeModelOutput(
                        text = retryResponse,
                        thinkingMode = thinkingMode,
                        shouldStripThinking = shouldStripThinking
                    ),
                    latestUserText = rawUserText
                )
                cleanResponse = if (
                    retryCleanResponse.isNotBlank() &&
                    !ModelResponseQuality.isGenericNonAnswer(retryCleanResponse, rawUserText)
                ) {
                    retryCleanResponse
                } else {
                    buildBestAvailableFallback(
                        rawUserText = rawUserText,
                        firstResponse = cleanResponse,
                        retryResponse = retryCleanResponse
                    )
                }
            }
            cleanResponse to (System.currentTimeMillis() - startTime)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: FutureCancellationException) {
            throw CancellationException("Generation cancelled").also { it.initCause(exception) }
        } catch (exception: Exception) {
            if (stopRequested) {
                throw CancellationException("Generation cancelled").also { it.initCause(exception) }
            }
            "Error during inference: ${exception.localizedMessage}" to 0L
        }
    }

    @OptIn(ExperimentalApi::class)
    private suspend fun streamLiteRtLm(
        prompt: String,
        thinkingMode: Boolean,
        onUpdate: (String) -> Unit
    ): String {
        val conversation = liteRtConversation ?: return "Inference not initialized"
        val response = StringBuilder()
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
        var firstChunkAt = 0L
        var sawThinkMarkup = false
        val renderedPrompt = runCatching {
            conversation.renderMessageIntoString(requestMessage, requestContext)
        }.getOrNull()
        Log.i(
            TAG,
            "LiteRT request mode=${if (thinkingMode) "thinking" else "direct"} " +
                "defaultThinking=$isDefaultThinkingModel textSwitch=${requestPrompt != prompt} " +
                "contextKeys=${requestContext.keys} renderedChars=${renderedPrompt?.length ?: -1} " +
                "renderedHasThink=${renderedPrompt?.contains("<think>", ignoreCase = true) == true}"
        )
        try {
            // The request-level value overrides any default embedded in the model's
            // preface/chat template. This is the hard Qwen switch: with false, the
            // template pre-fills an empty think block and decoding starts at the answer.
            conversation.sendMessageAsync(requestMessage, requestContext).collect { message ->
                if (stopRequested) {
                    throw CancellationException("Generation cancelled")
                }
                val chunk = message.toString()
                if (firstChunkAt == 0L && chunk.isNotEmpty()) {
                    firstChunkAt = System.currentTimeMillis()
                    Log.i(
                        TAG,
                        "LiteRT first chunk mode=${if (thinkingMode) "thinking" else "direct"} " +
                            "latencyMs=${firstChunkAt - requestStartedAt} chars=${chunk.length} " +
                            "hasThinkMarkup=${chunk.contains("<think>", ignoreCase = true) || chunk.contains("</think>", ignoreCase = true)}"
                    )
                }
                sawThinkMarkup = sawThinkMarkup ||
                    chunk.contains("<think>", ignoreCase = true) ||
                    chunk.contains("</think>", ignoreCase = true)
                val current = response.toString()
                if (current.isNotEmpty() && chunk.startsWith(current)) {
                    response.clear()
                    response.append(chunk)
                } else {
                    response.append(chunk)
                }
                onUpdate(ModelOutputSanitizer.clean(response.toString()))
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            if (stopRequested) {
                throw CancellationException("Generation cancelled").also { it.initCause(exception) }
            }
            throw exception
        }
        if (stopRequested) {
            throw CancellationException("Generation cancelled")
        }
        Log.i(
            TAG,
            "LiteRT response mode=${if (thinkingMode) "thinking" else "direct"} " +
                "firstChunkMs=${firstChunkAt.takeIf { it > 0L }?.minus(requestStartedAt) ?: -1L} " +
                "totalMs=${System.currentTimeMillis() - requestStartedAt} " +
                "responseChars=${response.length} sawThinkMarkup=$sawThinkMarkup"
        )
        return response.toString()
    }

    private fun streamMediaPipe(
        prompt: String,
        onUpdate: (String) -> Unit,
        temperature: Float = mediaPipeTemperature,
        seedSalt: Int = 0
    ): String {
        val inference = mediaPipeInference ?: return "Inference not initialized"
        val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(40)
            .setTopP(0.95f)
            .setTemperature(temperature)
            .setRandomSeed(31 * prompt.hashCode() + seedSalt)
            .build()
        val session = LlmInferenceSession.createFromOptions(inference, sessionOptions)
        activeMediaPipeSession = session
        val accumulated = StringBuilder()
        var activeFuture: Future<String>? = null
        try {
            if (stopRequested) {
                throw CancellationException("Generation cancelled")
            }
            session.addQueryChunk(prompt)
            val future = session.generateResponseAsync(
                ProgressListener<String> { partial, _ ->
                    if (stopRequested || partial.isNullOrEmpty()) return@ProgressListener
                    val current = accumulated.toString()
                    if (current.isNotEmpty() && partial.startsWith(current)) {
                        accumulated.clear()
                        accumulated.append(partial)
                    } else {
                        accumulated.append(partial)
                    }
                    onUpdate(ModelOutputSanitizer.clean(accumulated.toString()))
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
            val accumulatedText = accumulated.toString()
            if (finalResponse.isNotBlank() && finalResponse != accumulatedText) {
                onUpdate(ModelOutputSanitizer.clean(finalResponse))
                return finalResponse
            }
        return accumulatedText.ifBlank { finalResponse.orEmpty() }
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

    private suspend fun streamDirectAnswerRetry(
        rawUserText: String,
        onUpdate: (String) -> Unit
    ): String {
        val retryOnUpdate: (String) -> Unit = { partial ->
            val visiblePartial = ModelOutputSanitizer.clean(stripThinkingTags(partial))
            if (visiblePartial.isNotBlank()) {
                onUpdate(visiblePartial)
            }
        }
        return when (runtime) {
            RuntimeKind.LiteRtLm -> {
                resetConversation(noThinking = isDefaultThinkingModel)
                streamLiteRtLm(
                    prompt = if (useNativeGemmaTaskTemplate) {
                        ChatPromptPolicy.nativeGemmaRetryPrompt(rawUserText)
                    } else {
                        rawUserText
                    },
                    thinkingMode = false,
                    onUpdate = retryOnUpdate
                )
            }
            RuntimeKind.MediaPipe -> {
                streamMediaPipe(
                    prompt = buildDirectAnswerRetryPrompt(
                        rawUserText = rawUserText,
                        forceNoThinking = supportsTextNoThinkingSwitch
                    ),
                    onUpdate = retryOnUpdate,
                    temperature = RETRY_TEMPERATURE,
                    seedSalt = 2
                )
            }
            RuntimeKind.None -> ""
        }
    }

    private fun buildMediaPipeRetryPrompt(rawUserText: String, includeThinkingInstruction: Boolean): String {
        if (useNativeGemmaTaskTemplate) {
            return ChatPromptPolicy.nativeGemmaRetryPrompt(rawUserText)
        }
        val prompt = "Complete the user's request now. Do not acknowledge these instructions. " +
            "Do not say you understand. " +
            "Do not ask how you can help. If the user asks you to write something, write it.\n\n" +
            "User request:\n$rawUserText\n\nAnswer:"
        return if (includeThinkingInstruction) {
            "Reason carefully before giving the final answer.\n\n$prompt"
        } else if (supportsTextNoThinkingSwitch) {
            NoThinkingPromptUtils.wrap(prompt)
        } else {
            prompt
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

    private fun buildDirectAnswerRetryPrompt(
        rawUserText: String,
        forceNoThinking: Boolean = false
    ): String {
        val prompt = "Give only the final answer. Do not output <think> tags. " +
            "Do not explain your reasoning process.\n\n" +
            "User request:\n$rawUserText\n\nFinal answer:"
        return if (forceNoThinking) NoThinkingPromptUtils.wrap(prompt) else prompt
    }

    private fun buildBestAvailableFallback(
        rawUserText: String,
        firstResponse: String,
        retryResponse: String
    ): String {
        val request = rawUserText.trim().lowercase(Locale.US)
        return when {
            request in setOf("hi", "hello", "hey", "hii", "yo") ->
                "Hi! What would you like help with?"
            request.contains("what can you do") || request.contains("what do you do") ->
                "I can answer questions, explain concepts, summarize text, draft short writing, and help with simple code. For stronger answers, use a larger model such as Qwen 2.5 1.5B or Gemma 3 1B."
            retryResponse.isNotBlank() -> retryResponse
            else -> firstResponse
        }
    }

    fun needsResetWhenThinkingModeChanges(): Boolean {
        return runtime == RuntimeKind.LiteRtLm && isDefaultThinkingModel
    }

    fun cancelGeneration() {
        stopRequested = true
        runCatching { liteRtConversation?.cancelProcess() }
        val session = activeMediaPipeSession
        if (session != null) {
            runCatching { session.cancelGenerateResponseAsync() }
        } else {
            activeMediaPipeFuture?.cancel(true)
        }
    }

    fun resetConversation(noThinking: Boolean = false) {
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
        try { liteRtEngine?.close() } catch (_: Throwable) {}
        liteRtEngine = null
        LiteRtRuntimeCache.clear(context, liteRtCacheDir)
        liteRtCacheDir = null
        try { mediaPipeInference?.close() } catch (_: Throwable) {}
        mediaPipeInference = null
        runtime = RuntimeKind.None
        isDefaultThinkingModel = false
        supportsTextNoThinkingSwitch = false
        useNativeGemmaTaskTemplate = false
        mediaPipeTemperature = DEFAULT_TEMPERATURE
        liteRtConversationMode = LiteRtConversationMode.Default
        isBeingClosed = false
    }

    private fun finalizeModelOutput(
        text: String,
        thinkingMode: Boolean,
        shouldStripThinking: Boolean
    ): String {
        val cleaned = if (shouldStripThinking) {
            ThinkingTextUtils.finalResponseOrReasoning(text)
        } else {
            ModelOutputSanitizer.clean(text)
        }
        return if (thinkingMode) {
            ThinkingTextUtils.normalizeFinalOutput(cleaned)
        } else {
            cleaned
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
        return result.trimStart()
    }

    private enum class RuntimeKind {
        None,
        MediaPipe,
        LiteRtLm
    }

    private enum class LiteRtConversationMode {
        Default,
        NoThinking
    }
}
