package com.shounak.localmeshai.ui.viewmodels

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shounak.localmeshai.ai.VisionInferenceManager
import com.shounak.localmeshai.models.ModelBundle
import com.shounak.localmeshai.ai.VisionRequestContextResolver
import com.shounak.localmeshai.ai.ResolvedVisionMedia
import com.shounak.localmeshai.ai.ResponseFinalizer
import com.shounak.localmeshai.utils.CrashReportManager
import com.shounak.localmeshai.ai.ModelLoadIdentity
import com.shounak.localmeshai.ai.GgufExecutionProfile
import com.shounak.localmeshai.utils.DeviceUtils
import com.shounak.localmeshai.models.ThinkingCapability
import com.shounak.localmeshai.utils.DocumentTextExtractor
import com.shounak.localmeshai.utils.ModelOutputSanitizer
import com.shounak.localmeshai.utils.ModelRuntimeCoordinator
import com.shounak.localmeshai.utils.RegistrationToken
import com.shounak.localmeshai.utils.ModelRuntimeOwner
import com.shounak.localmeshai.utils.AppCacheCleaner
import com.shounak.localmeshai.utils.ThinkingTextUtils
import com.shounak.localmeshai.utils.ThinkingModeConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import com.shounak.localmeshai.utils.InferenceTiming
import com.shounak.localmeshai.utils.ChatRuntimePolicy
import com.shounak.localmeshai.utils.AppSettings
import android.util.Log
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CancellationException as FutureCancellationException
import java.util.UUID
import com.shounak.localmeshai.rag.RagManager
import com.shounak.localmeshai.rag.RagIngestionResult
import com.shounak.localmeshai.utils.AttachmentViewerUtils
import com.shounak.localmeshai.memory.PersistentMemoryManager
import com.shounak.localmeshai.memory.MemoryEntry
import com.shounak.localmeshai.memory.MemoryCategory
import com.shounak.localmeshai.utils.ModelResponseQuality
import com.shounak.localmeshai.ai.EmptyResponseReason
import com.shounak.localmeshai.ai.RequestProgress
import com.shounak.localmeshai.ai.RequestTerminalStatus
import com.shounak.localmeshai.ai.InferenceMetrics
import com.shounak.localmeshai.ai.InferenceExecutionResult
import com.shounak.localmeshai.ai.PreparedAttachment
import com.shounak.localmeshai.ai.ImagePreparation
import com.shounak.localmeshai.ai.DocumentPreparation
import com.shounak.localmeshai.data.ChatSessionRepository
import com.shounak.localmeshai.utils.AttachedDocumentContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.Locale

data class VisionChatSession(
    val id: String,
    val title: String,
    val question: String,
    val answer: String,
    val updatedAt: Long,
    val audioPath: String? = null,
    val audioName: String? = null,
    val audioDurationMs: Long = 0L,
    val documentName: String? = null,
    val documentId: String? = null,
    val imagePath: String? = null,
    val messages: List<VisionChatMessage> = emptyList()
)

data class VisionChatMessage(
    val text: String,
    val isUser: Boolean,
    val bitmap: Bitmap? = null,
    val id: String = UUID.randomUUID().toString(),
    val ragSources: List<String> = emptyList(),
    val ragChunkCount: Int = 0,
    val ragTopMatchPct: Int = 0,
    val audioPath: String? = null,
    val audioName: String? = null,
    val audioDurationMs: Long = 0L,
    val documentName: String? = null,
    val documentPath: String? = null,
    val imagePath: String? = null,
    val progress: RequestProgress? = null,
    val terminalStatus: RequestTerminalStatus? = null,
    val metrics: InferenceMetrics? = null,
    val errorMessage: String? = null,
    val requestId: String? = null,
    val attemptId: Int = 1,
    val reasoningText: String? = null,
    val isThinkingActive: Boolean = false,
    val userMessageId: String? = null,
    val emptyReason: EmptyResponseReason? = null,
    val revision: Int = 0,
    val responseFormatVersion: Int = 0
)

class VisionViewModel(application: Application) : AndroidViewModel(application) {
    private val inferenceManager = VisionInferenceManager(application)
    val sessionRepository = ChatSessionRepository.getInstance(application)
    private val sessionPersistenceChannel = kotlinx.coroutines.channels.Channel<List<VisionChatSession>>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private val appSettings = AppSettings.getInstance(application)
    private val ragManager = RagManager.getInstance(application)
    private var pendingRagDocumentId: String? = null
    var currentSessionDocumentId: String? = null
        private set

    val memoryManager: PersistentMemoryManager = PersistentMemoryManager.getInstance(application)

    private val _isPersistentMemoryActive = MutableStateFlow(appSettings.settings.value.enablePersistentMemory)
    val isPersistentMemoryActive = _isPersistentMemoryActive.asStateFlow()

    private val _memories = MutableStateFlow<List<com.shounak.localmeshai.memory.MemoryEntry>>(emptyList())
    val memories = _memories.asStateFlow()

    private val _memoryFeedbackEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val memoryFeedbackEvent = _memoryFeedbackEvent.asSharedFlow()

    fun setPendingRagDocumentId(docId: String?) {
        pendingRagDocumentId = docId
        if (docId != null) {
            currentSessionDocumentId = docId
        }
    }

    suspend fun ingestDocumentForRag(uri: Uri, fileName: String, mimeType: String = ""): RagIngestionResult {
        val result = ragManager.ingestDocument(uri, fileName, mimeType)
        if (result.success) {
            pendingRagDocumentId = result.documentId
            currentSessionDocumentId = result.documentId
        }
        return result
    }

    val imageChatSessions = mutableStateListOf<VisionChatSession>()
    val messages = mutableStateListOf<VisionChatMessage>()

    private val _answer = MutableStateFlow("")
    val answer = _answer.asStateFlow()

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId = _currentSessionId.asStateFlow()

    private val _isInitializing = MutableStateFlow(false)
    val isInitializing = _isInitializing.asStateFlow()

    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing = _isAnalyzing.asStateFlow()

    private val _isStopping = MutableStateFlow(false)
    val isStopping = _isStopping.asStateFlow()

    private val _thinkingMode = MutableStateFlow(false)
    val thinkingMode = _thinkingMode.asStateFlow()

    fun setThinkingMode(enabled: Boolean) {
        _thinkingMode.value = enabled
    }

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _isModelReady = MutableStateFlow(false)
    val isModelReady = _isModelReady.asStateFlow()

    private val _activeBackend = MutableStateFlow<String?>(null)
    val activeBackend = _activeBackend.asStateFlow()

    private val _lastInferenceTime = MutableStateFlow(0L)
    val lastInferenceTime = _lastInferenceTime.asStateFlow()

    private val _tokensPerSecond = MutableStateFlow(0f)
    val tokensPerSecond = _tokensPerSecond.asStateFlow()

    private var currentModelPath: String? = null
    private var currentBundle: ModelBundle? = null
    private var currentModelId: String = ""
    private var currentModelName: String = ""
    private var currentModelSize: String = ""
    private var currentContextWindowTokensTokens: Int? = null
    private var currentSupportsAudioInput: Boolean = false
    private var currentSupportsThinkingMode: Boolean = false
    private var currentAllowUnsafeOverride: Boolean = false
    private var lastLoadedBackendPreference: String? = null
    private var currentRequestedIdentity: ModelLoadIdentity? = null
    private var currentLoadedIdentity: ModelLoadIdentity? = null
    @Volatile var currentResolvedThinkingCapability: ThinkingCapability = ThinkingCapability.Unknown
        private set
    private var currentContextWindowTokens: Int = DEFAULT_VISION_CONTEXT_WINDOW_TOKENS
    @Volatile private var currentModelEpoch: Long = 0L
    private val _activeRequestIdState = MutableStateFlow<String?>(null)
    val activeRequestIdState = _activeRequestIdState.asStateFlow()

    @Volatile private var activeRequestIdInternal: String? = null
    var activeRequestId: String?
        get() = activeRequestIdInternal
        private set(value) {
            activeRequestIdInternal = value
            _activeRequestIdState.value = value
        }
    @Volatile var activeLoadOperationId: String? = null
        private set
    private var initGeneration: Int = 0
    private var initJob: Job? = null
    private var analysisGeneration: Int = 0
    private var analysisJob: Job? = null
    private var autoUnloadJob: Job? = null
    private var lastAttachedFileName: String? = null
    @Volatile private var resetRuntimeConversationBeforeNextAsk = false
    private var coordinatorToken: RegistrationToken? = null
    private var lastShutdownResult: com.shounak.localmeshai.ai.ShutdownResult? = null

    fun retryMessage(sessionId: String, userMessageId: String) {
        if (_isAnalyzing.value || _isStopping.value || analysisJob?.isActive == true) return
        val session = imageChatSessions.firstOrNull { it.id == sessionId } ?: return
        val userMsg = session.messages.firstOrNull { it.isUser && it.id == userMessageId } ?: return

        if (_currentSessionId.value != sessionId) {
            selectChatSession(sessionId)
        }

        // Reconstruct inputs from durable files
        val imgPath = userMsg.imagePath ?: session.imagePath
        if (!imgPath.isNullOrBlank()) {
            val file = File(imgPath)
            if (!file.exists()) {
                _error.value = "Referenced image file is missing or unavailable for retry: ${file.name}"
                return
            }
        }

        val audPath = userMsg.audioPath ?: session.audioPath
        if (!audPath.isNullOrBlank()) {
            val file = File(audPath)
            if (!file.exists()) {
                _error.value = "Referenced audio file is missing or unavailable for retry: ${file.name}"
                return
            }
        }

        val docPath = userMsg.documentPath
        if (!docPath.isNullOrBlank()) {
            val file = File(docPath)
            if (!file.exists()) {
                _error.value = "Referenced document file is missing or unavailable for retry: ${file.name}"
                return
            }
        }

        if (!imgPath.isNullOrBlank()) {
            val modelPath = currentModelPath
            val isMultimodal = modelPath != null && ChatRuntimePolicy.supportsAttachments(modelPath)
            if (modelPath != null && !isMultimodal) {
                _error.value = "The selected model does not support image inputs."
                return
            }
        }

        autoUnloadJob?.cancel()

        val userIdx = messages.indexOfFirst { it.isUser && it.id == userMessageId }
        if (userIdx < 0) return

        val assistantIndex = messages.indexOfFirst { !it.isUser && (it.userMessageId == userMessageId || it.id == userMsg.id) }
        val nextAttempt = if (assistantIndex >= 0) {
            messages[assistantIndex].attemptId + 1
        } else {
            1
        }
        val newRequestId = UUID.randomUUID().toString()

        val initialProgress = if (!_isModelReady.value) RequestProgress.LoadingModel else RequestProgress.PreparingInput
        val initialPlaceholder = if (!_isModelReady.value) "Loading model…" else "Reading input…"
        val placeholderAssistant = VisionChatMessage(
            id = if (assistantIndex >= 0 && assistantIndex < messages.size) messages[assistantIndex].id else UUID.randomUUID().toString(),
            text = initialPlaceholder,
            isUser = false,
            userMessageId = userMsg.id,
            attemptId = nextAttempt,
            requestId = newRequestId,
            progress = initialProgress,
            terminalStatus = null,
            errorMessage = null
        )

        val actualAssistantIndex = if (assistantIndex >= 0 && assistantIndex < messages.size) {
            messages[assistantIndex] = placeholderAssistant
            assistantIndex
        } else {
            messages.add(userIdx + 1, placeholderAssistant)
            userIdx + 1
        }

        val sIdx = imageChatSessions.indexOfFirst { it.id == sessionId }
        if (sIdx >= 0) {
            val sessionList = session.messages.toMutableList()
            val sessAssistantIdx = sessionList.indexOfFirst { !it.isUser && (it.userMessageId == userMessageId || it.id == userMsg.id) }
            if (sessAssistantIdx >= 0) {
                sessionList[sessAssistantIdx] = placeholderAssistant
            } else {
                val sessionUserIdx = sessionList.indexOfFirst { it.isUser && it.id == userMessageId }
                if (sessionUserIdx >= 0) {
                    sessionList.add(sessionUserIdx + 1, placeholderAssistant)
                } else {
                    sessionList.add(placeholderAssistant)
                }
            }
            imageChatSessions[sIdx] = session.copy(messages = sessionList)
        }

        persistSessions()

        val historyUpToUser = messages.take(userIdx + 1)
        val resolved = VisionRequestContextResolver.resolve(
            rawQuestion = userMsg.text,
            newBitmap = userMsg.bitmap,
            newFileUri = docPath?.let { Uri.fromFile(File(it)) },
            newAudioUri = audPath?.let { Uri.fromFile(File(it)) },
            newAudioBytes = null,
            currentSessionMessages = historyUpToUser,
            currentSessionId = sessionId
        )

        val retryBitmap = userMsg.bitmap?.takeUnless { it.isRecycled }
            ?: resolved.bitmapToUse
            ?: resolved.imageFileToUse?.let { VisionRequestContextResolver.decodeImageFile(it) }
            ?: imgPath?.takeIf { it.isNotBlank() }?.let { VisionRequestContextResolver.decodeImageFile(File(it)) }

        val retryDocUri = docPath?.let { Uri.fromFile(File(it)) }
        val retryAudUri = audPath?.let { Uri.fromFile(File(it)) }

        ask(
            bitmap = retryBitmap,
            question = userMsg.text,
            fileUri = retryDocUri,
            fileName = userMsg.documentName,
            audioUri = retryAudUri,
            audioName = userMsg.audioName ?: session.audioName,
            audioBytes = null,
            thinkingMode = _thinkingMode.value,
            jobSessionId = sessionId,
            existingUserMessageId = userMsg.id,
            existingAssistantIndex = actualAssistantIndex,
            targetAttemptId = nextAttempt,
            forcedRequestId = newRequestId,
            isRetry = true
        )
    }

    fun retryLastFailedMessage() {
        val sId = _currentSessionId.value ?: return
        val failedAssistant = messages.lastOrNull { !it.isUser && (it.terminalStatus == RequestTerminalStatus.Failed || it.errorMessage != null) } ?: return
        val userMsgId = failedAssistant.userMessageId ?: messages.takeWhile { it.id != failedAssistant.id }.lastOrNull { it.isUser }?.id ?: return
        retryMessage(sId, userMsgId)
    }

    // Serializes vision init and ask so they cannot run at the same time.
    private val ioMutex = Mutex()
    private var releaseJob: Job? = null
    private val documentPreparation = DocumentPreparation(application, viewModelScope)

    private val imagePreparation = ImagePreparation(getApplication(), viewModelScope)
    fun stageImageAttachment(bitmap: Bitmap?) = imagePreparation.stage(bitmap)

    fun stageDocumentAttachment(uri: Uri, name: String) = documentPreparation.stage(uri, name)
    fun cancelStagedDocument() = documentPreparation.cancel()


    fun resetAutoUnloadTimer() {
        autoUnloadJob?.cancel()
        autoUnloadJob = null

        val timeoutMinutes = appSettings.settings.value.autoUnloadMinutes
        if (timeoutMinutes <= 0) return
        if (!_isModelReady.value || _isAnalyzing.value || _isInitializing.value || _isStopping.value) return

        autoUnloadJob = viewModelScope.launch {
            delay(timeoutMinutes * 60 * 1000L)
            if (_isModelReady.value && !_isAnalyzing.value && !_isInitializing.value && !_isStopping.value) {
                Log.i("VisionViewModel", "Auto-unloading idle vision model after $timeoutMinutes minute(s) of inactivity")
                releaseModel(clearCoordinator = true, clearSelection = false)
            }
        }
    }

    init {
        coordinatorToken = ModelRuntimeCoordinator.register(
            owner = ModelRuntimeOwner.Vision,
            loadedModelIdentity = { currentLoadedIdentity?.modelPath ?: currentModelPath ?: currentModelId },
            stableModelIdProvider = { currentModelId },
            modelPathProvider = { currentModelPath ?: currentLoadedIdentity?.modelPath.orEmpty() },
            loadOperationIdProvider = { activeLoadOperationId.orEmpty() },
            requestStop = {
                initJob?.cancel()
                analysisJob?.cancel()
                inferenceManager.cancelGeneration()
            }
        ) {
            releaseModel(clearCoordinator = false)
            val joinOk = withTimeoutOrNull(10_000L) {
                releaseJob?.join()
                true
            } ?: false
            if (!joinOk) {
                com.shounak.localmeshai.ai.ShutdownResult.TimedOut
            } else {
                lastShutdownResult ?: if (inferenceManager.isReady) {
                    com.shounak.localmeshai.ai.ShutdownResult.Failed(IllegalStateException("Vision inference manager still ready after release"))
                } else {
                    com.shounak.localmeshai.ai.ShutdownResult.Success
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            for (snapshot in sessionPersistenceChannel) {
                writeSessionsToStorage(snapshot)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            loadSessionsAsync()
            memoryManager.loadAsync()
            ragManager.loadAsync()
            _memories.value = memoryManager.getAllMemories()
        }
        // Auto-unload idle vision model timer
        viewModelScope.launch {
            combine(isModelReady, isAnalyzing, isInitializing) { ready, analyzing, initializing ->
                Triple(ready, analyzing, initializing)
            }.distinctUntilChanged().collect { (ready, analyzing, initializing) ->
                if (ready && !analyzing && !initializing) {
                    resetAutoUnloadTimer()
                } else {
                    autoUnloadJob?.cancel()
                    autoUnloadJob = null
                }
            }
        }
        viewModelScope.launch {
            appSettings.settings
                .map { it.autoUnloadMinutes }
                .distinctUntilChanged()
                .collect {
                    resetAutoUnloadTimer()
                }
        }
        // Reinitialize active vision model if user changes backend preference in settings
        viewModelScope.launch {
            appSettings.settings
                .map { it.llamaBackendPreference }
                .distinctUntilChanged()
                .drop(1)
                .collect { newPref ->
                    val path = currentModelPath
                    if (path != null && _isModelReady.value) {
                        Log.i("VisionViewModel", "Backend preference changed to $newPref, reloading vision model: $path")
                        initModel(
                            path = path,
                            modelId = currentModelId,
                            modelName = currentModelName,
                            modelSize = currentModelSize,
                            contextWindowTokens = currentContextWindowTokensTokens,
                            supportsAudioInput = currentSupportsAudioInput,
                            supportsThinkingMode = currentSupportsThinkingMode,
                            allowUnsafeOverride = currentAllowUnsafeOverride,
                            forceReload = true
                        )
                    }
                }
        }
        viewModelScope.launch {
            appSettings.settings
                .map { it.enablePersistentMemory }
                .distinctUntilChanged()
                .collect { enabled ->
                    _isPersistentMemoryActive.value = enabled
                }
        }
    }

    fun setPersistentMemoryActive(enabled: Boolean) {
        _isPersistentMemoryActive.value = enabled
        appSettings.updateSettings { it.copy(enablePersistentMemory = enabled) }
        resetRuntimeConversationBeforeNextAsk = true
    }

    fun refreshMemories() {
        _memories.value = memoryManager.getAllMemories()
    }

    fun addMemory(content: String, category: MemoryCategory = MemoryCategory.GENERAL): Boolean {
        val entry = memoryManager.addMemory(content, category)
        if (entry != null) {
            refreshMemories()
            resetRuntimeConversationBeforeNextAsk = true
            return true
        }
        return false
    }

    fun updateMemory(entry: MemoryEntry): Boolean {
        val updated = memoryManager.updateMemory(entry)
        if (updated) {
            refreshMemories()
            resetRuntimeConversationBeforeNextAsk = true
        }
        return updated
    }

    fun toggleMemory(id: String, enabled: Boolean) {
        memoryManager.toggleMemory(id, enabled)
        refreshMemories()
        resetRuntimeConversationBeforeNextAsk = true
    }

    fun deleteMemory(id: String) {
        memoryManager.deleteMemory(id)
        refreshMemories()
        resetRuntimeConversationBeforeNextAsk = true
    }

    fun clearAllMemories() {
        memoryManager.clearAllMemories()
        refreshMemories()
        resetRuntimeConversationBeforeNextAsk = true
    }

    fun updateSupportsThinkingMode(supports: Boolean) {
        currentSupportsThinkingMode = supports
    }

    fun updateSupportsAudioInput(supports: Boolean) {
        currentSupportsAudioInput = supports
    }

    fun initModel(
        path: String,
        modelId: String = "",
        modelName: String = "",
        modelSize: String = "",
        contextWindowTokens: Int? = null,
        supportsAudioInput: Boolean = false,
        supportsThinkingMode: Boolean = false,
        allowUnsafeOverride: Boolean = false,
        forceReload: Boolean = false,
        bundle: ModelBundle? = null
    ) {
        val resolvedContextWindowTokens = contextWindowTokens
            ?: inferContextWindowTokens(path)
            ?: DEFAULT_VISION_CONTEXT_WINDOW_TOKENS

        val currentBackendPref = appSettings.settings.value.llamaBackendPreference
        val file = File(path)
        val isGguf = path.endsWith(".gguf", ignoreCase = true)
        val identity = if (bundle != null) {
            ModelLoadIdentity.fromBundle(
                bundle = bundle,
                runtimeKind = if (isGguf) "LlamaCpp" else "MediaPipe/LiteRT",
                backendPreference = currentBackendPref,
                threads = DeviceUtils.recommendedThreadCount(getApplication()),
                contextWindow = resolvedContextWindowTokens
            )
        } else if (isGguf) {
            val profile = GgufExecutionProfile.resolve(
                context = getApplication(),
                modelNameOrId = modelName.ifBlank { modelId.ifBlank { file.nameWithoutExtension } },
                modelSize = modelSize,
                customContextTokens = resolvedContextWindowTokens,
                backendPreference = currentBackendPref
            )
            profile.toIdentity(file, "LlamaCpp", currentBackendPref)
        } else {
            ModelLoadIdentity.fromFile(
                file = file,
                runtimeKind = "MediaPipe/LiteRT",
                backendPreference = currentBackendPref,
                threads = DeviceUtils.recommendedThreadCount(getApplication()),
                contextWindow = resolvedContextWindowTokens
            )
        }

        if (!forceReload) {
            if (_isModelReady.value && inferenceManager.isReady && currentLoadedIdentity == identity && ModelRuntimeCoordinator.isActive(ModelRuntimeOwner.Vision)) {
                currentContextWindowTokens = resolvedContextWindowTokens
                currentSupportsThinkingMode = supportsThinkingMode
                currentAllowUnsafeOverride = allowUnsafeOverride
                currentSupportsAudioInput = supportsAudioInput
                return
            }
            if (_isInitializing.value && initJob?.isActive == true && currentRequestedIdentity == identity && !_isStopping.value) {
                currentContextWindowTokens = resolvedContextWindowTokens
                currentSupportsThinkingMode = supportsThinkingMode
                currentAllowUnsafeOverride = allowUnsafeOverride
                currentSupportsAudioInput = supportsAudioInput
                return
            }
        }

        currentModelPath = path
        currentBundle = bundle
        currentModelId = modelId
        currentModelName = modelName
        currentModelSize = modelSize
        currentContextWindowTokensTokens = contextWindowTokens
        currentSupportsAudioInput = supportsAudioInput
        currentSupportsThinkingMode = supportsThinkingMode
        currentAllowUnsafeOverride = allowUnsafeOverride
        currentRequestedIdentity = identity

        currentModelEpoch++
        activeRequestId = null
        val opId = java.util.UUID.randomUUID().toString()
        activeLoadOperationId = opId
        val myGen = ++initGeneration
        initJob?.cancel()
        initJob = null
        analysisJob?.cancel()
        analysisJob = null
        analysisGeneration++
        inferenceManager.cancelGeneration()

        // Synchronous UI state updates BEFORE launching background work so
        // the spinner shows up immediately and Ask is disabled right away.
        _isInitializing.value = true
        _isAnalyzing.value = false
        _isStopping.value = false
        _isModelReady.value = false
        _error.value = null
        _answer.value = ""

        initJob = viewModelScope.launch(Dispatchers.IO) {
            val shutdownOk = runCatching {
                kotlinx.coroutines.withTimeout(10_000L) {
                    releaseJob?.join()
                }
            }.isSuccess
            if (!shutdownOk) {
                _error.value = "Previous model runtime shutdown timed out or failed"
                _isInitializing.value = false
                return@launch
            }
            try {
                ModelRuntimeCoordinator.withRuntime(ModelRuntimeOwner.Vision) {
                    ioMutex.withLock {
                        // A newer init has been requested; drop this one silently.
                        if (myGen != initGeneration) return@withLock
                        AppCacheCleaner.clearAppCache(getApplication())
                        try {
                            inferenceManager.initialize(
                                modelPath = path,
                                modelId = modelId,
                                modelName = modelName,
                                modelSize = modelSize,
                                supportsAudioInput = supportsAudioInput,
                                supportsThinkingMode = supportsThinkingMode,
                                allowUnsafeOverride = allowUnsafeOverride,
                                contextWindowTokens = resolvedContextWindowTokens,
                                bundle = bundle
                            )
                            // Re-check after the (potentially long) native init.
                            if (myGen != initGeneration) {
                                inferenceManager.close()
                                return@withLock
                            }
                            currentModelPath = path
                            lastLoadedBackendPreference = currentBackendPref
                            currentLoadedIdentity = identity
                            currentResolvedThinkingCapability = ThinkingModeConfig.resolveThinkingCapability(
                                bundle = bundle,
                                localPath = path,
                                supportsThinkingFlag = supportsThinkingMode,
                                id = modelId,
                                name = modelName,
                                context = getApplication()
                            )
                            currentContextWindowTokens = resolvedContextWindowTokens
                            resetRuntimeConversationBeforeNextAsk = false
                            _activeBackend.value = inferenceManager.activeBackendDisplayName
                            _isModelReady.value = inferenceManager.isReady
                        } catch (exception: Exception) {
                            if (exception is CancellationException) throw exception
                            if (myGen != initGeneration) return@withLock
                            _error.value = exception.message ?: "Failed to load multimodal model."
                            lastLoadedBackendPreference = null
                            currentLoadedIdentity = null
                            currentRequestedIdentity = null
                            currentResolvedThinkingCapability = ThinkingCapability.Unknown
                            _activeBackend.value = null
                            currentContextWindowTokens = DEFAULT_VISION_CONTEXT_WINDOW_TOKENS
                            _isModelReady.value = false
                        }
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (myGen != initGeneration) return@launch
                val msg = t.message ?: "Failed to load multimodal model."
                Log.e("VisionViewModel", "Multimodal model init failed", t)
                _error.value = msg
                lastLoadedBackendPreference = null
                currentLoadedIdentity = null
                currentRequestedIdentity = null
                currentResolvedThinkingCapability = ThinkingCapability.Unknown
                _activeBackend.value = null
                currentContextWindowTokens = DEFAULT_VISION_CONTEXT_WINDOW_TOKENS
                _isModelReady.value = false
            } finally {
                if (myGen == initGeneration) {
                    _isInitializing.value = false
                    _isStopping.value = false
                    initJob = null
                }
            }
        }
    }

    suspend fun ensureModelReady(
        onProgress: ((RequestProgress) -> Unit)? = null
    ): Boolean {
        if (_isModelReady.value) return true
        val path = currentModelPath ?: return false
        onProgress?.invoke(RequestProgress.LoadingModel)
        return try {
            val resolvedContextWindowTokens = currentContextWindowTokensTokens
                ?: inferContextWindowTokens(path)
                ?: DEFAULT_VISION_CONTEXT_WINDOW_TOKENS
            inferenceManager.initialize(
                modelPath = path,
                modelId = currentModelId,
                modelName = currentModelName,
                modelSize = currentModelSize,
                supportsAudioInput = currentSupportsAudioInput,
                supportsThinkingMode = currentSupportsThinkingMode,
                allowUnsafeOverride = currentAllowUnsafeOverride,
                contextWindowTokens = resolvedContextWindowTokens,
                bundle = currentBundle
            )
            lastLoadedBackendPreference = appSettings.settings.value.llamaBackendPreference
            currentContextWindowTokens = resolvedContextWindowTokens
            resetRuntimeConversationBeforeNextAsk = false
            _activeBackend.value = inferenceManager.activeBackendDisplayName
            _isModelReady.value = inferenceManager.isReady
            Log.i("VisionViewModel", "Model ready on-demand on ${inferenceManager.activeBackendDisplayName}: $path")
            true
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            val msg = t.message ?: "Failed to load multimodal model on-demand."
            Log.e("VisionViewModel", "Model on-demand load failed", t)
            _error.value = msg
            false
        }
    }

    val isFileReadingSupported: Boolean
        get() = currentModelPath?.let { path ->
            path.endsWith(".task", ignoreCase = true) || path.endsWith(".litertlm", ignoreCase = true)
        } ?: false

    private fun createPlaceholderBitmap(): Bitmap {
        return Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.WHITE)
        }
    }

    private fun getFileName(context: Context, uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        result = cursor.getString(index)
                    }
                }
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result ?: "document"
    }

    private fun readTextFromUri(context: Context, uri: Uri, budget: DocumentTextBudget): String {
        val contentResolver = context.contentResolver
        val mimeType = contentResolver.getType(uri) ?: ""
        val fileName = getFileName(context, uri)
        return try {
            DocumentTextExtractor.extract(
                context = context,
                uri = uri,
                fileName = fileName,
                mimeType = mimeType,
                maxExtractedChars = budget.maxExtractedChars,
                limitDescription = budget.limitDescription,
                maxInputBytes = budget.maxInputBytes
            )
        } catch (e: Exception) {
            "Error extracting text from $fileName: ${e.message}"
        }
    }

    private fun renderPdfPagesToBitmap(
        context: Context,
        uri: Uri,
        maxPages: Int = 2,
        maxDimension: Int = 1024
    ): Bitmap? {
        return try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            pfd.use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    val pageCount = renderer.pageCount.coerceAtMost(maxPages)
                    if (pageCount == 0) return null
                    val renderedPages = mutableListOf<Bitmap>()
                    try {
                        for (i in 0 until pageCount) {
                            renderer.openPage(i).use { page ->
                                val width = page.width
                                val height = page.height
                                val scale = (maxDimension.toFloat() / maxOf(width, height).coerceAtLeast(1)).coerceAtMost(1.5f)
                                val destW = (width * scale).toInt().coerceAtLeast(1)
                                val destH = (height * scale).toInt().coerceAtLeast(1)
                                val bmp = Bitmap.createBitmap(destW, destH, Bitmap.Config.ARGB_8888)
                                val canvas = Canvas(bmp)
                                canvas.drawColor(Color.WHITE)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                renderedPages.add(bmp)
                            }
                        }
                        if (renderedPages.isEmpty()) return null
                        if (renderedPages.size == 1) {
                            return renderedPages[0]
                        }
                        val totalW = renderedPages.maxOf { it.width }
                        val totalH = renderedPages.sumOf { it.height }
                        val combined = Bitmap.createBitmap(totalW, totalH, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(combined)
                        canvas.drawColor(Color.WHITE)
                        var yOffset = 0f
                        for (bmp in renderedPages) {
                            canvas.drawBitmap(bmp, 0f, yOffset, null)
                            yOffset += bmp.height
                            bmp.recycle()
                        }
                        combined
                    } catch (e: Throwable) {
                        renderedPages.forEach { runCatching { if (!it.isRecycled) it.recycle() } }
                        null
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w("VisionViewModel", "Failed to render PDF to bitmap: ${e.message}")
            null
        }
    }

    private fun copyUriToInferenceCache(
        context: Context,
        uri: Uri,
        fileName: String?,
        prefix: String
    ): File {
        val dir = File(context.cacheDir, "multimodal_inputs").apply { mkdirs() }
        pruneInferenceCache(dir)
        val extension = fileName
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.takeIf { it.length in 1..8 && it.all { ch -> ch.isLetterOrDigit() } }
            ?: "bin"
        val target = File(dir, "$prefix-${System.currentTimeMillis()}.$extension")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Could not open audio file.")
        return target
    }

    private fun pruneInferenceCache(dir: File) {
        val now = System.currentTimeMillis()
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        files
            .filter { now - it.lastModified() > 2L * 60L * 60L * 1000L }
            .forEach { runCatching { it.delete() } }
        files
            .sortedByDescending { it.lastModified() }
            .drop(6)
            .forEach { runCatching { it.delete() } }
    }

    private fun extractAudioDurationMs(file: File): Long {
        if (!file.exists()) return 0L
        try {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                val dur = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                if (dur != null && dur > 0L) return dur
            } finally {
                runCatching { retriever.release() }
            }
        } catch (_: Exception) {}
        if (file.extension.equals("wav", ignoreCase = true) && file.length() > 44) {
            return ((file.length() - 44) * 1000L / (16000 * 2)).coerceAtLeast(500L)
        }
        return 0L
    }

    fun ask(
        bitmap: Bitmap?,
        question: String,
        fileUri: Uri? = null,
        fileName: String? = null,
        audioUri: Uri? = null,
        audioName: String? = null,
        audioBytes: ByteArray? = null,
        thinkingMode: Boolean = _thinkingMode.value,
        preparedAttachment: PreparedAttachment? = null,
        jobSessionId: String? = null,
        existingUserMessageId: String? = null,
        existingAssistantIndex: Int? = null,
        targetAttemptId: Int = 1,
        forcedRequestId: String? = null,
        isRetry: Boolean = false
    ): Boolean {
        if (_isAnalyzing.value || _isStopping.value || analysisJob?.isActive == true) return false
        val path = currentModelPath
        if (path == null) {
            _error.value = "Choose and initialise a multimodal model first."
            return false
        }
        if (bitmap?.isRecycled == true) return false

        autoUnloadJob?.cancel()
        val savedDocument = messages.lastOrNull { it.isUser && !it.documentPath.isNullOrBlank() }
        val inputUri = fileUri ?: savedDocument?.documentPath?.let { Uri.fromFile(File(it)) }
        val inputName = fileName ?: savedDocument?.documentName ?: "document"
        val preparation = if (preparedAttachment == null) inputUri?.let { documentPreparation.take(it, inputName) } else null
        val imageInput = bitmap?.let(imagePreparation::take)
        val myGen = ++analysisGeneration
        val activeSessionId = jobSessionId ?: _currentSessionId.value ?: UUID.randomUUID().toString().also {
            _currentSessionId.value = it
        }

        if (isRetry || (fileName != null && lastAttachedFileName != null && fileName != lastAttachedFileName)) {
            resetRuntimeConversationBeforeNextAsk = true
        }
        if (fileName != null) {
            lastAttachedFileName = fileName
        }

        val resolved = VisionRequestContextResolver.resolve(
            rawQuestion = question,
            newBitmap = bitmap,
            newFileUri = fileUri,
            newAudioUri = audioUri,
            newAudioBytes = audioBytes,
            currentSessionMessages = messages.toList(),
            currentSessionId = activeSessionId
        )
        if (resolved.media is ResolvedVisionMedia.AttachmentError) {
            _error.value = resolved.media.message
            return false
        }

        val timing = InferenceTiming()
        val basePrompt = resolved.effectivePrompt
        val displayPrompt = if (resolved.isImageOnlyPrompt) "" else question.trim()
        val targetDocId = if (fileUri != null) null else pendingRagDocumentId ?: currentSessionDocumentId
        pendingRagDocumentId = null
        if (targetDocId != null) {
            currentSessionDocumentId = targetDocId
        }

        val userMessageId = existingUserMessageId ?: UUID.randomUUID().toString()
        val placeholderAudioName = audioName ?: if (audioBytes != null || audioUri != null) "Voice message" else null

        val assistantIndex: Int
        val requestId = forcedRequestId ?: UUID.randomUUID().toString()

        if (existingUserMessageId == null) {
            // Add message to chat list immediately on the calling thread so UI updates with 0ms delay
            messages.add(
                VisionChatMessage(
                    id = userMessageId,
                    text = displayPrompt,
                    isUser = true,
                    bitmap = bitmap,
                    imagePath = null,
                    audioPath = null,
                    audioName = placeholderAudioName,
                    audioDurationMs = 0L,
                    documentName = fileName,
                    documentPath = null
                )
            )
            assistantIndex = messages.size
            val initialProgress = if (!_isModelReady.value) RequestProgress.LoadingModel else RequestProgress.PreparingInput
            val initialPlaceholder = if (!_isModelReady.value) "Loading model…" else "Reading input…"
            messages.add(
                VisionChatMessage(
                    initialPlaceholder,
                    false,
                    null,
                    progress = initialProgress,
                    userMessageId = userMessageId,
                    requestId = requestId,
                    attemptId = targetAttemptId
                )
            )

            ensureCurrentSession(
                question = displayPrompt,
                answer = initialPlaceholder,
                bitmap = bitmap,
                imagePath = null,
                audioPath = null,
                audioName = placeholderAudioName,
                audioDurationMs = 0L,
                documentName = fileName,
                documentId = targetDocId
            )
            persistSessions()
        } else {
            assistantIndex = existingAssistantIndex ?: (messages.size - 1)
        }

        _isAnalyzing.value = true
        timing.recordAcknowledged()
        val capability = currentResolvedThinkingCapability.takeIf { it != ThinkingCapability.Unknown }
            ?: ThinkingModeConfig.resolveThinkingCapability(
                bundle = currentBundle,
                localPath = currentModelPath,
                supportsThinkingFlag = currentSupportsThinkingMode,
                id = currentModelId,
                name = currentModelName,
                context = getApplication<Application>()
            )
        val thinkingModeForRequest = ThinkingModeConfig.resolveRequestThinking(capability, thinkingMode)
        activeRequestId = requestId
        val reqModelEpoch = currentModelEpoch
        val assistantMessageId = messages[assistantIndex].id
        fun isCurrentRequest(): Boolean = activeRequestId == requestId && currentModelEpoch == reqModelEpoch &&
            _currentSessionId.value == activeSessionId &&
            assistantIndex < messages.size && messages[assistantIndex].id == assistantMessageId && myGen == analysisGeneration
        val historySnapshot = messages.toList()
        val sessionSnapshot = imageChatSessions.firstOrNull { it.id == activeSessionId }

        val job = viewModelScope.launch(Dispatchers.IO) {
            val runningJob = coroutineContext[Job]
            var preparedInput: PreparedAttachment? = null
            var documentForIndex: PreparedAttachment? = null
            val hasExplicitImage = bitmap != null && !bitmap.isRecycled
            var persistentImageFile: File? = null
            var persistentAudioFile: File? = null
            var persistentDocFile: File? = null
            var resolvedAudioDurationMs = 0L
            var effectiveBitmap: Bitmap? = if (hasExplicitImage) bitmap else null
            var fileText = ""
            var promptFileText = ""
            var documentBudget = documentTextBudget(
                userPrompt = basePrompt,
                hasBitmap = hasExplicitImage,
                hasAudio = audioUri != null || audioBytes?.isNotEmpty() == true,
                thinkingMode = thinkingModeForRequest
            )

            try {
                timing.startPreparation()
                imageInput?.let { pending ->
                    persistentImageFile = pending.await().persistInto(
                        attachmentStore = sessionRepository.attachmentStore,
                        chatKind = com.shounak.localmeshai.data.ChatKind.Vision,
                        sessionId = activeSessionId
                    ).durableFile
                }

                // 2. Persist audio in background IO thread
                if (audioBytes != null && audioBytes.isNotEmpty()) {
                    val tempAudio = File.createTempFile("audio_", ".wav", getApplication<Application>().cacheDir)
                    try {
                        tempAudio.writeBytes(audioBytes)
                        val storedAudio = sessionRepository.attachmentStore.storeAttachmentFromFile(
                            chatKind = com.shounak.localmeshai.data.ChatKind.Vision,
                            sessionId = activeSessionId,
                            sourceFile = tempAudio,
                            targetFileName = "audio_${System.currentTimeMillis()}.wav"
                        )
                        persistentAudioFile = storedAudio
                        resolvedAudioDurationMs = extractAudioDurationMs(storedAudio)
                    } catch (e: Exception) {
                        Log.e("VisionViewModel", "Failed to write audio bytes to attachments", e)
                    } finally {
                        tempAudio.delete()
                    }
                } else if (audioUri != null) {
                    val ext = audioName?.substringAfterLast('.', missingDelimiterValue = "wav")?.takeIf { it.isNotBlank() } ?: "wav"
                    try {
                        val storedAudio = sessionRepository.attachmentStore.storeAttachmentFromStream(
                            context = getApplication(),
                            chatKind = com.shounak.localmeshai.data.ChatKind.Vision,
                            sessionId = activeSessionId,
                            sourceUri = audioUri,
                            targetFileName = "audio_${System.currentTimeMillis()}.$ext"
                        )
                        persistentAudioFile = storedAudio
                        resolvedAudioDurationMs = extractAudioDurationMs(storedAudio)
                    } catch (e: Exception) {
                        throw IllegalStateException("Failed to preserve audio attachment", e)
                    }
                }

                // The same extraction supplies inference and optional later indexing.
                val prepared = preparedAttachment ?: preparation?.await()
                preparedInput = prepared
                if (prepared != null) {
                    val durable = prepared.persistInto(
                        attachmentStore = sessionRepository.attachmentStore,
                        chatKind = com.shounak.localmeshai.data.ChatKind.Vision,
                        sessionId = activeSessionId
                    )
                    documentPreparation.remember(durable)
                    persistentDocFile = durable.durableFile
                    fileText = durable.extractedText.orEmpty()
                    documentForIndex = if (fileUri != null) durable else null
                }

                var renderedPdfBitmap: Bitmap? = null
                val pdfUri = persistentDocFile?.let(Uri::fromFile) ?: prepared?.fileUri ?: inputUri
                val isPdf = pdfUri != null && (
                    inputName.endsWith(".pdf", ignoreCase = true) ||
                    getApplication<Application>().contentResolver.getType(pdfUri)?.contains("pdf", ignoreCase = true) == true
                )
                if (bitmap == null && isPdf && (fileText.isBlank() || fileText.isExtractionFailureText())) {
                    renderedPdfBitmap = renderPdfPagesToBitmap(getApplication(), pdfUri)
                    if (renderedPdfBitmap != null) {
                        fileText = ""
                        val sessionDir = sessionRepository.attachmentStore.getSessionAttachmentsDir(
                            com.shounak.localmeshai.data.ChatKind.Vision,
                            activeSessionId,
                            create = true
                        )
                        val pdfImgFile = sessionDir.resolve("pdf_page_${System.currentTimeMillis()}.png")
                        try {
                            java.io.FileOutputStream(pdfImgFile).use { out ->
                                renderedPdfBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                            }
                            persistentImageFile = pdfImgFile
                        } catch (e: Exception) {
                            Log.e("VisionViewModel", "Failed to persist PDF page bitmap", e)
                        }
                    }
                }
                effectiveBitmap = (if (hasExplicitImage) bitmap else null) ?: renderedPdfBitmap
                if (inputUri != null && renderedPdfBitmap == null && AttachedDocumentContext.isExtractionFailure(fileText)) {
                    throw IllegalArgumentException(fileText.take(240).ifBlank { "Could not read the attached document" })
                }
                promptFileText = withContext(Dispatchers.Default) {
                    AttachedDocumentContext.select(fileText, basePrompt, documentBudget.maxPromptChars)
                }
                if (myGen != analysisGeneration || reqModelEpoch != currentModelEpoch) {
                    throw CancellationException("Request configuration changed")
                }

                // Update user message in UI with disk paths and durations
                withContext(Dispatchers.Main.immediate) {
                        if (!isCurrentRequest()) return@withContext
                    val uIndex = messages.indexOfFirst { it.id == userMessageId }
                    if (uIndex >= 0 && uIndex < messages.size) {
                        messages[uIndex] = messages[uIndex].copy(
                            bitmap = effectiveBitmap,
                            imagePath = persistentImageFile?.absolutePath,
                            audioPath = persistentAudioFile?.absolutePath,
                            audioDurationMs = resolvedAudioDurationMs,
                            documentPath = persistentDocFile?.absolutePath
                        )
                    }
                }

                if (currentModelId.isNotBlank() && (com.shounak.localmeshai.utils.ModelRemovalCoordinator.isReserved(currentModelId) || com.shounak.localmeshai.utils.ModelRemovalCoordinator.isModelDeletionPendingOrCompleted(currentModelId))) {
                    throw IllegalStateException("Model is pending deletion or has been removed.")
                }

                // If model is not ready, load it on demand with LoadingModel progress
                if (!_isModelReady.value) {
                    timing.startLoading()
                    withContext(Dispatchers.Main.immediate) {
                        if (!isCurrentRequest()) return@withContext
                        if (_currentSessionId.value == activeSessionId && assistantIndex < messages.size) {
                            messages[assistantIndex] = messages[assistantIndex].copy(
                                text = "Loading model…",
                                progress = RequestProgress.LoadingModel
                            )
                        }
                    }
                    releaseJob?.join()
                    val loaded = ModelRuntimeCoordinator.withRuntime(ModelRuntimeOwner.Vision) {
                      ioMutex.withLock {
                        ensureModelReady { progress ->
                            launch(Dispatchers.Main.immediate) {
                        if (!isCurrentRequest()) return@launch
                                if (_currentSessionId.value == activeSessionId && assistantIndex < messages.size) {
                                    messages[assistantIndex] = messages[assistantIndex].copy(
                                        text = "Loading model…",
                                        progress = progress
                                    )
                                }
                            }
                        }
                    }
                    }
                    timing.endLoading()
                    if (!loaded || !_isModelReady.value) {
                        throw IllegalStateException(_error.value ?: "Failed to load multimodal model")
                    }
                }

                // Check if RAG is active and query context if applicable
                var currentRagSources: List<String> = emptyList()
                var currentRagChunkCount = 0
                var currentRagTopMatchPct = 0
                var ragAugmentedContext: String? = null

                var effectiveDocId = targetDocId ?: currentSessionDocumentId
                if (effectiveDocId == null) {
                    val activeSession = sessionSnapshot
                    effectiveDocId = activeSession?.documentId
                        ?: activeSession?.documentName?.let { docName ->
                            ragManager.indexedDocuments.firstOrNull { it.documentName.equals(docName, ignoreCase = true) }?.documentId
                        }
                        ?: historySnapshot.firstNotNullOfOrNull { it.documentName }?.let { docName ->
                            ragManager.indexedDocuments.firstOrNull { it.documentName.equals(docName, ignoreCase = true) }?.documentId
                        }
                        ?: if (appSettings.settings.value.enableRag) ragManager.findMatchingDocumentForQuery(basePrompt)?.documentId else null
                }

                val shouldQueryRag = promptFileText.isBlank() && basePrompt.isNotBlank() && ChatRuntimePolicy.shouldRetrieveDocuments(
                    appSettings.settings.value.enableRag, effectiveDocId, ragManager.totalIndexedChunks
                )
                if (shouldQueryRag) {
                    val ragSettings = appSettings.settings.value
                    val retrieval = ragManager.retrieve(
                        query = basePrompt,
                        topK = ragSettings.ragTopK,
                        minScore = ragSettings.ragMinSimilarity,
                        preferredDocumentId = effectiveDocId,
                        allowGlobalSearch = appSettings.settings.value.enableRag
                    )
                    if (retrieval.hasContext) {
                        currentRagSources = retrieval.sourceNames
                        currentRagChunkCount = retrieval.matches.size
                        currentRagTopMatchPct = (retrieval.topMatchScore * 100f).toInt()
                        ragAugmentedContext = ragManager.buildAugmentedPrompt(basePrompt, retrieval)

                        val matchedDocId = retrieval.matches.firstOrNull()?.chunk?.documentId
                        if (matchedDocId != null) {
                            currentSessionDocumentId = matchedDocId
                        }
                    }
                }

                val memoryContext = if (_isPersistentMemoryActive.value) {
                    memoryManager.formatMemoryContext(query = basePrompt)
                } else {
                    ""
                }

                val contentPrompt = buildString {
                    if (memoryContext.isNotBlank()) {
                        append(memoryContext.trim())
                        append("\n\n")
                    }
                    if (ragAugmentedContext != null) {
                        append(ragAugmentedContext)
                    } else {
                        if (basePrompt.isNotBlank()) {
                            append(basePrompt)
                            if (promptFileText.isNotBlank()) {
                                append("\n\n--- EXTRACTED ATTACHED FILE: ${fileName ?: "document"} ---\n")
                                append(promptFileText)
                            }
                        } else if (promptFileText.isNotBlank()) {
                            append("--- EXTRACTED ATTACHED FILE: ${fileName ?: "document"} ---\n")
                            append(promptFileText)
                        }
                    }
                }
                val prompt = contentPrompt

                timing.endPreparation()

                withContext(Dispatchers.Main.immediate) {
                        if (!isCurrentRequest()) return@withContext
                    if (_currentSessionId.value == activeSessionId && assistantIndex < messages.size) {
                        messages[assistantIndex] = messages[assistantIndex].copy(
                            text = "Processing prompt…",
                            progress = RequestProgress.ProcessingPrompt
                        )
                    }
                }

                val latestResponse = java.util.concurrent.atomic.AtomicReference("")
                val latestStructuredSnapshot = java.util.concurrent.atomic.AtomicReference<com.shounak.localmeshai.ai.StructuredResponseSnapshot?>(null)
                val liveUpdates = Channel<com.shounak.localmeshai.ai.StructuredResponseSnapshot>(Channel.CONFLATED)
                val liveUpdateJob = launch(Dispatchers.Main.immediate) {
                    if (!isCurrentRequest()) return@launch
                    liveUpdates.receiveAsFlow().collect { snap ->
                        if (activeRequestId == requestId && myGen == analysisGeneration && currentModelEpoch == reqModelEpoch && _currentSessionId.value == activeSessionId && assistantIndex < messages.size && messages[assistantIndex].terminalStatus == null) {
                            if (snap.answerText.isNotBlank() || snap.reasoningText.isNotBlank()) timing.recordFirstVisibleText()
                            _answer.value = snap.answerText
                            val displayText = snap.answerText.ifBlank {
                                if (messages[assistantIndex].progress == RequestProgress.RecoveringOnCpu) "GPU failed; retrying on CPU…" else "Generating…"
                            }
                            messages[assistantIndex] = messages[assistantIndex].copy(
                                text = displayText,
                                reasoningText = snap.reasoningText,
                                isThinkingActive = snap.isThinkingActive,
                                revision = messages[assistantIndex].revision + 1,
                                responseFormatVersion = 1,
                                progress = if (snap.answerText.isBlank() && snap.reasoningText.isBlank()) messages[assistantIndex].progress else RequestProgress.Generating
                            )
                        }
                    }
                }

                try {
                    releaseJob?.join()
                    timing.startQueue()
                    if (currentModelId.isNotBlank() && (com.shounak.localmeshai.utils.ModelRemovalCoordinator.isReserved(currentModelId) || com.shounak.localmeshai.utils.ModelRemovalCoordinator.isModelDeletionPendingOrCompleted(currentModelId))) {
                        throw IllegalStateException("Model is pending deletion or has been removed.")
                    }
                    val execResult = ModelRuntimeCoordinator.withRuntime(ModelRuntimeOwner.Vision) {
                      ioMutex.withLock {
                        timing.endQueue()
                        if (myGen != analysisGeneration || reqModelEpoch != currentModelEpoch) return@withLock null
                        if (resetRuntimeConversationBeforeNextAsk) {
                            val previousMessages = historySnapshot.filter { it.id != userMessageId && it.id != assistantMessageId }
                            val restorationHistory = VisionRequestContextResolver.buildRestorationHistory(
                                sessionMessages = previousMessages,
                                currentRequestId = requestId,
                                placeholderTexts = PLACEHOLDER_ANSWERS,
                                failedAnswerTexts = FAILED_ANSWER_STRINGS
                            )
                            inferenceManager.resetConversation(thinkingMode = thinkingModeForRequest, initialMessages = restorationHistory)
                            resetRuntimeConversationBeforeNextAsk = false
                        }
                        val effectiveImageFile = persistentImageFile ?: resolved.imageFileToUse
                        VisionRequestContextResolver.validatePreparedImageFile(effectiveImageFile)
                        val fallbackBitmap = resolved.bitmapToUse ?: (if (inferenceManager.activeBackendDisplayName.contains("MediaPipe", ignoreCase = true)) {
                            resolved.imageFileToUse?.let { VisionRequestContextResolver.decodeImageFile(it) }
                        } else null)
                        val bitmapCopy = effectiveBitmap ?: fallbackBitmap
                        val audioFile = persistentAudioFile ?: audioUri?.let {
                            copyUriToInferenceCache(
                                context = getApplication(),
                                uri = it,
                                fileName = audioName,
                                prefix = "audio-question"
                            )
                        }
                        val previousMessages = historySnapshot.filter { it.id != userMessageId && it.id != assistantMessageId }
                        val llamaMultimodalHistory = VisionRequestContextResolver.buildLlamaMultimodalHistory(
                            sessionMessages = previousMessages,
                            currentRequestId = requestId,
                            placeholderTexts = PLACEHOLDER_ANSWERS,
                            failedAnswerTexts = FAILED_ANSWER_STRINGS
                        )
                        val fullLlamaMessages = llamaMultimodalHistory.messages + listOf("user" to prompt)

                        timing.startInference()
                        inferenceManager.askStreaming(
                            bitmap = bitmapCopy,
                            question = prompt,
                            audioFile = audioFile,
                            audioBytes = audioBytes,
                            thinkingMode = thinkingModeForRequest,
                            preparedImageFile = effectiveImageFile,
                            requestId = requestId,
                            sessionId = activeSessionId,
                            timing = timing,
                            isRetry = isRetry,
                            onProgress = { p ->
                                launch(Dispatchers.Main.immediate) {
                        if (!isCurrentRequest()) return@launch
                                    if (myGen == analysisGeneration && reqModelEpoch == currentModelEpoch && _currentSessionId.value == activeSessionId && assistantIndex < messages.size) {
                                        messages[assistantIndex] = messages[assistantIndex].copy(progress = if (messages[assistantIndex].terminalStatus == null) p else null)
                                    }
                                }
                            },
                            onSnapshot = { snap ->
                                if (activeRequestId == requestId && myGen == analysisGeneration && currentModelEpoch == reqModelEpoch) {
                                    latestStructuredSnapshot.set(snap)
                                    liveUpdates.trySend(snap)
                                }
                            },
                            messages = fullLlamaMessages,
                            multiTurnImageFiles = llamaMultimodalHistory.imageFiles,
                            multiTurnAudioFiles = llamaMultimodalHistory.audioFiles
                        ) { partial ->
                            timing.recordChunk(partial)
                            latestResponse.set(partial)
                        }
                    }

                    }
                    liveUpdates.close()
                    liveUpdateJob.join()
                    if (myGen != analysisGeneration || execResult == null) return@launch

                    val snap = latestStructuredSnapshot.get()
                    val partialOrFinalText = when (execResult.status) {
                        RequestTerminalStatus.Stopped -> {
                            execResult.text.ifBlank {
                                snap?.answerText?.trim()?.takeUnless { it in PLACEHOLDER_ANSWERS }.orEmpty()
                            }
                        }
                        RequestTerminalStatus.Failed -> {
                            execResult.text.ifBlank {
                                snap?.answerText?.trim()?.takeUnless { it in PLACEHOLDER_ANSWERS }.orEmpty()
                            }
                        }
                        RequestTerminalStatus.Completed -> {
                            execResult.text.ifBlank {
                                snap?.answerText?.trim()?.takeUnless { it in PLACEHOLDER_ANSWERS }.orEmpty()
                            }
                        }
                    }
                    val finalReasoningText = execResult.reasoningText
                        ?: snap?.reasoningText?.trim()?.takeIf { it.isNotBlank() }
                    val isError = execResult.status == RequestTerminalStatus.Failed || !execResult.error.isNullOrBlank()
                    val textToDisplay = partialOrFinalText
                    val errorMessageToDisplay = if (isError) {
                        execResult.error ?: "Inference failed."
                    } else null

                    if (execResult.status == RequestTerminalStatus.Completed && appSettings.settings.value.enableRag) {
                        documentForIndex?.let { document ->
                            viewModelScope.launch(Dispatchers.Default) {
                                val indexed = ragManager.ingestText(document.name, document.extractedText.orEmpty())
                                if (indexed.success && activeRequestId == requestId && _currentSessionId.value == activeSessionId) {
                                    currentSessionDocumentId = indexed.documentId
                                }
                            }
                        }
                    }

                    withContext(Dispatchers.Main.immediate) {
                        if (!isCurrentRequest()) return@withContext
                        if (myGen == analysisGeneration && reqModelEpoch == currentModelEpoch && _currentSessionId.value == activeSessionId && assistantIndex < messages.size) {
                            messages[assistantIndex] = messages[assistantIndex].copy(
                                text = textToDisplay,
                                errorMessage = errorMessageToDisplay,
                                bitmap = null,
                                ragSources = currentRagSources,
                                ragChunkCount = currentRagChunkCount,
                                ragTopMatchPct = currentRagTopMatchPct,
                                progress = null,
                                terminalStatus = execResult.status,
                                isThinkingActive = false,
                                metrics = execResult.metrics?.copy(firstVisibleTextLatencyMs = timing.buildMetrics(requestId, activeSessionId).firstVisibleTextLatencyMs),
                                attemptId = targetAttemptId,
                                requestId = requestId,
                                userMessageId = userMessageId,
                                reasoningText = finalReasoningText,
                                emptyReason = execResult.emptyReason,
                                responseFormatVersion = 1
                            )
                            _lastInferenceTime.value = execResult.metrics?.decodeDurationMs ?: 0L
                            _tokensPerSecond.value = execResult.metrics?.decodeThroughputTokPerSec?.toFloat() ?: 0f
                        }
                        if (execResult.status == RequestTerminalStatus.Completed && textToDisplay.isNotBlank()) {
                            if (_isPersistentMemoryActive.value && appSettings.settings.value.autoExtractMemories) {
                                val candidate = withContext(Dispatchers.Default) { memoryManager.extractMemoryCandidate(basePrompt) }
                                if (candidate != null) {
                                    val entry = withContext(Dispatchers.IO) { memoryManager.addMemory(candidate.first, candidate.second) }
                                    if (entry != null) {
                                        refreshMemories()
                                        _memoryFeedbackEvent.tryEmit("Remembered: ${candidate.first}")
                                        resetRuntimeConversationBeforeNextAsk = true
                                    }
                                }
                            }
                        }
                        val textToSave = if (execResult.status == RequestTerminalStatus.Completed) {
                            textToDisplay
                        } else {
                            textToDisplay.ifBlank { errorMessageToDisplay ?: "Inference failed." }
                        }
                        saveCurrentSession(
                            displayPrompt,
                            textToSave,
                            effectiveBitmap,
                            targetSessionId = activeSessionId,
                            imagePath = persistentImageFile?.absolutePath,
                            audioPath = persistentAudioFile?.absolutePath,
                            audioName = audioName ?: if (persistentAudioFile != null) "Voice message" else null,
                            audioDurationMs = resolvedAudioDurationMs,
                            documentName = fileName,
                            documentId = targetDocId
                        )
                    }
                } catch (exception: CancellationException) {
                    withContext(NonCancellable + Dispatchers.Main.immediate) {
                        if (!isCurrentRequest()) return@withContext
                        val snap = latestStructuredSnapshot.get()
                        val currentText = messages[assistantIndex].text.takeUnless { it in PLACEHOLDER_ANSWERS }.orEmpty()
                        val finalAnswer = snap?.answerText?.trim()?.takeUnless { it in PLACEHOLDER_ANSWERS }
                            ?: currentText
                        val finalReasoning = snap?.reasoningText?.trim()?.takeIf { it.isNotBlank() }
                            ?: messages[assistantIndex].reasoningText?.trim()?.takeIf { it.isNotBlank() }
                        if (myGen == analysisGeneration && reqModelEpoch == currentModelEpoch && _currentSessionId.value == activeSessionId && assistantIndex < messages.size) {
                            messages[assistantIndex] = messages[assistantIndex].copy(
                                text = finalAnswer,
                                reasoningText = finalReasoning,
                                isThinkingActive = false,
                                bitmap = null,
                                progress = null,
                                terminalStatus = RequestTerminalStatus.Stopped,
                                attemptId = targetAttemptId,
                                requestId = requestId,
                                userMessageId = userMessageId,
                                responseFormatVersion = 1
                            )
                        }
                        saveCurrentSession(
                            displayPrompt,
                            finalAnswer.ifBlank { "Stopped." },
                            effectiveBitmap,
                            targetSessionId = activeSessionId,
                            imagePath = persistentImageFile?.absolutePath,
                            audioPath = persistentAudioFile?.absolutePath,
                            audioName = audioName ?: if (persistentAudioFile != null) "Voice message" else null,
                            audioDurationMs = resolvedAudioDurationMs,
                            documentName = fileName,
                            documentId = targetDocId
                        )
                    }
                } catch (exception: Exception) {
                    _error.value = exception.message ?: "Image question failed."
                    runCatching {
                        CrashReportManager.recordModelCrash(
                            context = getApplication(),
                            modelId = currentModelPath?.substringAfterLast('/') ?: "vision_model",
                            modelName = currentModelPath?.substringAfterLast('/') ?: "Vision Model",
                            errorMessage = exception.message ?: "Vision analysis failed",
                            throwable = exception,
                            runtimeInfo = "Vision Session: $activeSessionId"
                        )
                    }
                    withContext(Dispatchers.Main.immediate) {
                        if (!isCurrentRequest()) return@withContext
                        val snap = latestStructuredSnapshot.get()
                        val currentText = messages[assistantIndex].text.takeUnless { it in PLACEHOLDER_ANSWERS }.orEmpty()
                        val partialAnswer = snap?.answerText?.trim()?.takeUnless { it in PLACEHOLDER_ANSWERS }
                            ?: currentText
                        val partialReasoning = snap?.reasoningText?.trim()?.takeIf { it.isNotBlank() }
                            ?: messages[assistantIndex].reasoningText?.trim()?.takeIf { it.isNotBlank() }
                        val errorMsg = exception.message ?: "Image question failed."
                        if (myGen == analysisGeneration && reqModelEpoch == currentModelEpoch && _currentSessionId.value == activeSessionId && assistantIndex < messages.size) {
                            messages[assistantIndex] = messages[assistantIndex].copy(
                                text = partialAnswer,
                                reasoningText = partialReasoning,
                                isThinkingActive = false,
                                errorMessage = errorMsg,
                                bitmap = null,
                                progress = null,
                                terminalStatus = RequestTerminalStatus.Failed,
                                attemptId = targetAttemptId,
                                requestId = requestId,
                                userMessageId = userMessageId,
                                responseFormatVersion = 1
                            )
                        }
                        saveCurrentSession(
                            displayPrompt,
                            partialAnswer.ifBlank { "⚠️ Error: $errorMsg" },
                            effectiveBitmap,
                            targetSessionId = activeSessionId,
                            imagePath = persistentImageFile?.absolutePath,
                            audioPath = persistentAudioFile?.absolutePath,
                            audioName = audioName ?: if (persistentAudioFile != null) "Voice message" else null,
                            audioDurationMs = resolvedAudioDurationMs,
                            documentName = fileName,
                            documentId = targetDocId
                        )
                    }
                } finally {
                    liveUpdateJob.cancel()
                    Log.i("VisionLatency", timing.summary())
                    liveUpdates.close()
                    if (activeRequestId == requestId) {
                        _isAnalyzing.value = false
                        _isStopping.value = false
                        resetAutoUnloadTimer()
                    }
                }
            } catch (failure: Throwable) {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    if (!isCurrentRequest()) return@withContext
                    if (activeRequestId == requestId && myGen == analysisGeneration && assistantIndex < messages.size) {
                        val stopped = failure is CancellationException
                        val partial = messages[assistantIndex].text.takeUnless { it in PLACEHOLDER_ANSWERS }.orEmpty()
                        val status = if (stopped) RequestTerminalStatus.Stopped else RequestTerminalStatus.Failed
                        val errMsg = if (stopped) null else (failure.message ?: "Attachment/model error")
                        messages[assistantIndex] = messages[assistantIndex].copy(
                            text = partial,
                            errorMessage = errMsg,
                            progress = null,
                            isThinkingActive = false,
                            terminalStatus = status,
                            attemptId = targetAttemptId,
                            requestId = requestId,
                            userMessageId = userMessageId,
                            responseFormatVersion = 1
                        )
                        saveCurrentSession(
                            displayPrompt,
                            if (stopped) partial.ifBlank { "Stopped." } else partial.ifBlank { "⚠️ Error: ${failure.message ?: "Attachment/model error"}" },
                            effectiveBitmap,
                            targetSessionId = activeSessionId,
                            imagePath = persistentImageFile?.absolutePath,
                            audioPath = persistentAudioFile?.absolutePath,
                            audioName = audioName ?: if (persistentAudioFile != null) "Voice message" else null,
                            audioDurationMs = resolvedAudioDurationMs,
                            documentName = fileName,
                            documentId = targetDocId
                        )
                    }
                }
            } finally {
                preparedInput?.close()
                documentPreparation.release(preparation)
                imagePreparation.release(imageInput)
                if (activeRequestId == requestId) {
                    _isAnalyzing.value = false
                    _isStopping.value = false
                    resetAutoUnloadTimer()
                }
                if (analysisJob == runningJob) {
                    analysisJob = null
                }
            }
        }
        analysisJob = job
        return true
    }

    fun stopAnalyzing() {
        val wasAnalyzing = _isAnalyzing.value
        val wasInitializing = _isInitializing.value
        if (!wasAnalyzing && !_isStopping.value && !wasInitializing) return
        resetRuntimeConversationBeforeNextAsk = true
        inferenceManager.cancelGeneration()
        if (!wasAnalyzing && !_isStopping.value) {
            if (wasInitializing) {
                initGeneration++
                initJob?.cancel()
                initJob = null
                _isInitializing.value = false
                _isStopping.value = false
            }
            return
        }
        val jobToCancel = analysisJob
        analysisJob = null
        jobToCancel?.cancel()
        _isAnalyzing.value = false
        _isStopping.value = false

        // Immediately update UI state on the main thread so "Reading input..." changes to stopped card
        val assistantIndex = messages.indexOfLast { !it.isUser }
        if (assistantIndex >= 0 && assistantIndex < messages.size) {
            val targetMsg = messages[assistantIndex]
            if (activeRequestId == null || targetMsg.requestId == activeRequestId) {
                val current = targetMsg.text
                val finalText = if (current in PLACEHOLDER_ANSWERS || current.isBlank()) {
                    ""
                } else {
                    ModelOutputSanitizer.clean(current)
                }
                messages[assistantIndex] = targetMsg.copy(
                    text = finalText,
                    progress = null,
                    isThinkingActive = false,
                    terminalStatus = RequestTerminalStatus.Stopped
                )
                saveVisibleConversationIfComplete()
            }
        }
        _answer.value = ""
    }

    fun uninitializeModel() {
        releaseModel(clearCoordinator = true, clearSelection = true)
    }
    fun startNewChat() {
        stopAnalyzing()
        _currentSessionId.value = null
        resetRuntimeConversationBeforeNextAsk = true
        lastAttachedFileName = null
        _answer.value = ""
        _error.value = null
        _lastInferenceTime.value = 0L
        _tokensPerSecond.value = 0f
        messages.clear()
        pendingRagDocumentId = null
        currentSessionDocumentId = null
    }

    private fun getSessionBitmapFile(sessionId: String): File {
        val dir = getApplication<Application>().filesDir.resolve("vision_sessions")
        if (!dir.exists()) dir.mkdirs()
        return dir.resolve("session_$sessionId.png")
    }

    private fun getLegacySessionBitmapFile(sessionId: String): File {
        return getApplication<Application>().cacheDir
            .resolve("vision_sessions")
            .resolve("session_$sessionId.png")
    }

    private fun saveSessionBitmap(sessionId: String, bitmap: Bitmap) {
        viewModelScope.launch(Dispatchers.IO) {
            val file = getSessionBitmapFile(sessionId)
            try {
                java.io.FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun loadSessionBitmap(sessionId: String): Bitmap? {
        val file = getSessionBitmapFile(sessionId).takeIf { it.exists() }
            ?: getLegacySessionBitmapFile(sessionId)
        if (!file.exists()) return null
        return try {
            android.graphics.BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Throwable) {
            Log.w("VisionViewModel", "Failed to decode session bitmap: ${e.message}")
            null
        }
    }

    fun selectChatSession(sessionId: String): VisionChatSession? {
        val session = imageChatSessions.firstOrNull { it.id == sessionId } ?: return null
        _currentSessionId.value = session.id
        resetRuntimeConversationBeforeNextAsk = true
        lastAttachedFileName = session.documentName
        currentSessionDocumentId = session.documentId
            ?: session.documentName?.let { docName ->
                ragManager.indexedDocuments.firstOrNull { it.documentName.equals(docName, ignoreCase = true) }?.documentId
            }
            ?: session.messages.firstNotNullOfOrNull { it.documentName }?.let { docName ->
                ragManager.indexedDocuments.firstOrNull { it.documentName.equals(docName, ignoreCase = true) }?.documentId
            }
        _answer.value = session.answer
        _error.value = null
        messages.clear()

        val defaultSessionBitmap = loadSessionBitmap(session.id)

        if (session.messages.isNotEmpty()) {
            session.messages.forEachIndexed { index, msg ->
                val resolvedDocPath = if (!msg.documentPath.isNullOrBlank() && File(msg.documentPath).exists()) {
                    msg.documentPath
                } else {
                    msg.documentName?.let { com.shounak.localmeshai.utils.AttachmentViewerUtils.findAttachmentFile(getApplication(), it)?.absolutePath }
                }

                val resolvedAudioPath = if (!msg.audioPath.isNullOrBlank() && File(msg.audioPath).exists()) {
                    msg.audioPath
                } else {
                    msg.audioName?.let { com.shounak.localmeshai.utils.AttachmentViewerUtils.findAttachmentFile(getApplication(), it)?.absolutePath }
                        ?: (if (index == 0 && !session.audioPath.isNullOrBlank() && File(session.audioPath).exists()) session.audioPath else null)
                }

                val resolvedImagePath = if (!msg.imagePath.isNullOrBlank() && File(msg.imagePath).exists()) {
                    msg.imagePath
                } else if (index == 0 && !session.imagePath.isNullOrBlank() && File(session.imagePath).exists()) {
                    session.imagePath
                } else {
                    getSessionBitmapFile(session.id).takeIf { it.exists() }?.absolutePath
                }

                val decodedBmp = if (msg.isUser) {
                    if (resolvedImagePath != null && File(resolvedImagePath).exists()) {
                        try {
                            BitmapFactory.decodeFile(resolvedImagePath)
                        } catch (_: Throwable) {
                            null
                        }
                    } else if (index == 0) {
                        defaultSessionBitmap
                    } else null
                } else null

                messages.add(
                    msg.copy(
                        bitmap = decodedBmp,
                        imagePath = resolvedImagePath,
                        audioPath = resolvedAudioPath,
                        documentPath = resolvedDocPath
                    )
                )
            }
        } else {
            // Legacy session fallback (single question/answer)
            val initialBmp = if (session.imagePath != null && File(session.imagePath).exists()) {
                try {
                    BitmapFactory.decodeFile(session.imagePath)
                } catch (_: Throwable) {
                    defaultSessionBitmap
                }
            } else {
                defaultSessionBitmap
            }
            val legacyAudioPath = if (!session.audioPath.isNullOrBlank() && File(session.audioPath).exists()) {
                session.audioPath
            } else {
                session.audioName?.let { com.shounak.localmeshai.utils.AttachmentViewerUtils.findAttachmentFile(getApplication(), it)?.absolutePath }
            }
            val legacyDocPath = session.documentName?.let { com.shounak.localmeshai.utils.AttachmentViewerUtils.findAttachmentFile(getApplication(), it)?.absolutePath }
            messages.add(
                VisionChatMessage(
                    text = session.question,
                    isUser = true,
                    bitmap = initialBmp,
                    imagePath = session.imagePath,
                    audioPath = legacyAudioPath,
                    audioName = session.audioName,
                    audioDurationMs = session.audioDurationMs,
                    documentName = session.documentName,
                    documentPath = legacyDocPath
                )
            )
            messages.add(VisionChatMessage(session.answer, false, null))
        }
        val lastAssistant = messages.lastOrNull { !it.isUser && it.metrics != null }
        _lastInferenceTime.value = lastAssistant?.metrics?.decodeDurationMs ?: 0L
        _tokensPerSecond.value = lastAssistant?.metrics?.decodeThroughputTokPerSec?.toFloat() ?: 0f
        return session
    }

    fun deleteChatSession(sessionId: String) {
        if (sessionId == _currentSessionId.value && _isAnalyzing.value) {
            stopAnalyzing()
        }
        val removingCurrent = sessionId == _currentSessionId.value
        val session = imageChatSessions.firstOrNull { it.id == sessionId }
        session?.documentId?.let { docId ->
            ragManager.removeDocument(docId)
        }
        if (removingCurrent) {
            _currentSessionId.value = null
            resetRuntimeConversationBeforeNextAsk = true
            lastAttachedFileName = null
            currentSessionDocumentId = null
            _answer.value = ""
            messages.clear()
        }
        val file = getSessionBitmapFile(sessionId)
        if (file.exists()) {
            file.delete()
        }
        val legacyFile = getLegacySessionBitmapFile(sessionId)
        if (legacyFile.exists()) {
            legacyFile.delete()
        }
        imageChatSessions.removeAll { it.id == sessionId }
        viewModelScope.launch(Dispatchers.IO) {
            sessionRepository.deleteSessionAsync(com.shounak.localmeshai.data.ChatKind.Vision, sessionId)
            AttachmentViewerUtils.cleanupOrphanAttachments(
                getApplication(),
                setOfNotNull(_currentSessionId.value)
            )
        }
        persistSessions()
    }

    fun clearHistory() {
        if (_isAnalyzing.value) {
            stopAnalyzing()
        }
        val sessionIds = imageChatSessions.map { it.id }.toList()
        imageChatSessions.forEach { session ->
            session.documentId?.let { docId ->
                ragManager.removeDocument(docId)
            }
            val bmpFile = getSessionBitmapFile(session.id)
            if (bmpFile.exists()) bmpFile.delete()
            val legacyBmpFile = getLegacySessionBitmapFile(session.id)
            if (legacyBmpFile.exists()) legacyBmpFile.delete()
        }
        imageChatSessions.clear()
        _currentSessionId.value = null
        resetRuntimeConversationBeforeNextAsk = true
        lastAttachedFileName = null
        currentSessionDocumentId = null
        _answer.value = ""
        messages.clear()
        val dir = getApplication<Application>().filesDir.resolve("vision_sessions")
        if (dir.exists()) {
            dir.deleteRecursively()
        }
        val legacyDir = getApplication<Application>().cacheDir.resolve("vision_sessions")
        if (legacyDir.exists()) {
            legacyDir.deleteRecursively()
        }
        viewModelScope.launch(Dispatchers.IO) {
            sessionIds.forEach { id ->
                sessionRepository.deleteSessionAsync(com.shounak.localmeshai.data.ChatKind.Vision, id)
            }
            AttachmentViewerUtils.cleanupOrphanAttachments(getApplication(), emptySet())
        }
        persistSessions()
    }

    private fun ensureCurrentSession(
        question: String,
        answer: String,
        bitmap: Bitmap? = null,
        imagePath: String? = null,
        audioPath: String? = null,
        audioName: String? = null,
        audioDurationMs: Long = 0L,
        documentName: String? = null,
        documentId: String? = null
    ) {
        val currentId = _currentSessionId.value
        val now = System.currentTimeMillis()
        if (currentId != null) {
            val index = imageChatSessions.indexOfFirst { it.id == currentId }
            if (index != -1) {
                val previous = imageChatSessions[index]
                val sessionDocId = previous.documentId
                    ?: documentId
                    ?: currentSessionDocumentId
                    ?: previous.documentName?.let { docName ->
                        ragManager.indexedDocuments.firstOrNull { it.documentName.equals(docName, ignoreCase = true) }?.documentId
                    }
                    ?: previous.messages.firstNotNullOfOrNull { it.documentName }?.let { docName ->
                        ragManager.indexedDocuments.firstOrNull { it.documentName.equals(docName, ignoreCase = true) }?.documentId
                    }
                val updated = previous.copy(
                    updatedAt = now,
                    imagePath = imagePath ?: previous.imagePath,
                    audioPath = audioPath ?: previous.audioPath,
                    audioName = audioName ?: previous.audioName,
                    audioDurationMs = if (audioDurationMs > 0L) audioDurationMs else previous.audioDurationMs,
                    documentName = documentName ?: previous.documentName,
                    documentId = sessionDocId,
                    messages = messages.toList()
                )
                if (currentSessionDocumentId == null && sessionDocId != null) {
                    currentSessionDocumentId = sessionDocId
                }
                imageChatSessions.removeAt(index)
                imageChatSessions.add(0, updated)
                persistSessions()
                return
            }
        }
        val sessionId = currentId ?: UUID.randomUUID().toString().also {
            _currentSessionId.value = it
        }
        val sessionDocId = documentId
            ?: currentSessionDocumentId
            ?: documentName?.let { docName ->
                ragManager.indexedDocuments.firstOrNull { it.documentName.equals(docName, ignoreCase = true) }?.documentId
            }
            ?: messages.firstNotNullOfOrNull { it.documentName }?.let { docName ->
                ragManager.indexedDocuments.firstOrNull { it.documentName.equals(docName, ignoreCase = true) }?.documentId
            }
        val session = VisionChatSession(
            id = sessionId,
            title = question.toImageChatTitle(),
            question = question,
            answer = answer,
            updatedAt = now,
            imagePath = imagePath,
            audioPath = audioPath,
            audioName = audioName,
            audioDurationMs = audioDurationMs,
            documentName = documentName,
            documentId = sessionDocId,
            messages = messages.toList()
        )
        if (currentSessionDocumentId == null && sessionDocId != null) {
            currentSessionDocumentId = sessionDocId
        }
        imageChatSessions.add(0, session)
        persistSessions()
    }

    private fun saveCurrentSession(
        question: String,
        answer: String,
        bitmap: Bitmap? = null,
        targetSessionId: String? = null,
        imagePath: String? = null,
        audioPath: String? = null,
        audioName: String? = null,
        audioDurationMs: Long = 0L,
        documentName: String? = null,
        documentId: String? = null
    ) {
        val now = System.currentTimeMillis()
        val sessionId = targetSessionId ?: _currentSessionId.value ?: UUID.randomUUID().toString().also {
            _currentSessionId.value = it
        }
        
        // Per-message attachment files already preserve the original image.

        val cleanAnswer = ModelOutputSanitizer.clean(answer)
        val index = imageChatSessions.indexOfFirst { it.id == sessionId }
        val previous = if (index >= 0) imageChatSessions[index] else null
        val shouldUpdateTimestamp = previous == null ||
            previous.question != question ||
            previous.answer != cleanAnswer
        val effectiveUpdatedAt = when {
            previous == null || shouldUpdateTimestamp || previous.updatedAt <= 0L -> now
            else -> previous.updatedAt
        }

        val anyUserWithBitmap = messages.firstOrNull { it.isUser && (it.bitmap != null || !it.imagePath.isNullOrBlank()) }
        val anyUserWithDoc = messages.lastOrNull { it.isUser && !it.documentName.isNullOrBlank() }
        val anyUserWithAudio = messages.lastOrNull { it.isUser && !it.audioPath.isNullOrBlank() }
        val firstUserMsg = messages.firstOrNull { it.isUser }
        val effectiveTitle = previous?.title ?: firstUserMsg?.text?.toImageChatTitle(
            documentName = firstUserMsg.documentName ?: anyUserWithDoc?.documentName,
            audioName = firstUserMsg.audioName ?: anyUserWithAudio?.audioName,
            hasImage = firstUserMsg.bitmap != null || !firstUserMsg.imagePath.isNullOrBlank() || anyUserWithBitmap != null
        ) ?: question.toImageChatTitle(
            documentName = documentName ?: anyUserWithDoc?.documentName,
            audioName = audioName ?: anyUserWithAudio?.audioName,
            hasImage = bitmap != null || !imagePath.isNullOrBlank() || anyUserWithBitmap != null
        )

        val sessionDocId = documentId
            ?: previous?.documentId
            ?: currentSessionDocumentId
            ?: documentName?.let { docName ->
                ragManager.indexedDocuments.firstOrNull { it.documentName.equals(docName, ignoreCase = true) }?.documentId
            }
            ?: anyUserWithDoc?.documentName?.let { docName ->
                ragManager.indexedDocuments.firstOrNull { it.documentName.equals(docName, ignoreCase = true) }?.documentId
            }
            ?: previous?.documentName?.let { docName ->
                ragManager.indexedDocuments.firstOrNull { it.documentName.equals(docName, ignoreCase = true) }?.documentId
            }

        val updated = VisionChatSession(
            id = sessionId,
            title = effectiveTitle,
            question = previous?.question ?: question,
            answer = cleanAnswer,
            updatedAt = effectiveUpdatedAt,
            imagePath = imagePath ?: anyUserWithBitmap?.imagePath ?: previous?.imagePath,
            audioPath = audioPath ?: anyUserWithAudio?.audioPath ?: previous?.audioPath,
            audioName = audioName ?: anyUserWithAudio?.audioName ?: previous?.audioName,
            audioDurationMs = if (audioDurationMs > 0L) audioDurationMs else (anyUserWithAudio?.audioDurationMs ?: previous?.audioDurationMs ?: 0L),
            documentName = documentName ?: anyUserWithDoc?.documentName ?: previous?.documentName,
            documentId = sessionDocId,
            messages = messages.toList()
        )
        if (currentSessionDocumentId == null && sessionDocId != null) {
            currentSessionDocumentId = sessionDocId
        }
        if (index >= 0) {
            imageChatSessions.removeAt(index)
        }
        if (shouldUpdateTimestamp) {
            imageChatSessions.add(0, updated)
        } else {
            imageChatSessions.add(index.coerceAtMost(imageChatSessions.size), updated)
        }
        persistSessions()
    }

    private suspend fun loadSessionsAsync() {
        val coordinator = com.shounak.localmeshai.data.StartupCoordinator.getInstance(getApplication())
        when (val res = sessionRepository.loadVisionSessionsAsync()) {
            is com.shounak.localmeshai.utils.AtomicReadResult.Success -> {
                val sorted = res.data.sortedByDescending { it.updatedAt }
                withContext(Dispatchers.Main.immediate) {
                    imageChatSessions.clear()
                    imageChatSessions.addAll(sorted)
                }
                coordinator.updateVisionStoreState(com.shounak.localmeshai.data.StoreLoadState.Ready)
                migrateLegacySessionImages()
            }
            is com.shounak.localmeshai.utils.AtomicReadResult.UnsupportedVersion -> {
                coordinator.updateVisionStoreState(
                    com.shounak.localmeshai.data.StoreLoadState.RecoveryRequired(
                        "Vision history uses a newer schema version (${res.foundVersion} > ${res.maxSupportedVersion}). Update the app to view these sessions without data loss.",
                        res.file
                    )
                )
            }
            is com.shounak.localmeshai.utils.AtomicReadResult.Corrupt -> {
                coordinator.updateVisionStoreState(
                    com.shounak.localmeshai.data.StoreLoadState.RecoveryRequired(
                        "Vision history file is corrupt: ${res.cause.message}. Preserved at ${res.backupFile?.name ?: "backup"}.",
                        res.file
                    )
                )
            }
            is com.shounak.localmeshai.utils.AtomicReadResult.Missing -> {
                coordinator.updateVisionStoreState(com.shounak.localmeshai.data.StoreLoadState.Ready)
            }
        }
    }

    private fun loadSessions() {
        val loaded = sessionRepository.loadVisionSessions()
        imageChatSessions.clear()
        imageChatSessions.addAll(loaded.sortedByDescending { it.updatedAt })
    }

    private fun migrateLegacySessionImages() {
        val snapshot = imageChatSessions.toList()
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val migrated = snapshot.map { session ->
                val destination = sessionRepository.attachmentStore.getSessionAttachmentsDir(
                    com.shounak.localmeshai.data.ChatKind.Vision,
                    session.id,
                    create = true
                )
                fun migrate(path: String?): String? = path?.let {
                    com.shounak.localmeshai.utils.LegacyAttachmentMigration.copyReferenced(
                        File(it), app.cacheDir, destination)?.absolutePath ?: it
                }
                val legacy = session.imagePath ?: getLegacySessionBitmapFile(session.id)
                    .takeIf { it.isFile }?.absolutePath
                session.copy(imagePath = migrate(legacy), messages = session.messages.map {
                    it.copy(imagePath = migrate(it.imagePath))
                })
            }
            withContext(Dispatchers.Main.immediate) {
                var changed = false
                snapshot.zip(migrated).forEach { (before, after) ->
                    val index = imageChatSessions.indexOfFirst { it.id == before.id }
                    if (index >= 0 && imageChatSessions[index] == before && before != after) {
                        imageChatSessions[index] = after
                        changed = true
                    }
                }
                if (changed) persistSessions()
            }
        }
    }

    private fun persistSessions() {
        val state = com.shounak.localmeshai.data.StartupCoordinator.getInstance(getApplication()).visionStoreState.value
        if (state !is com.shounak.localmeshai.data.StoreLoadState.Ready) {
            return
        }
        sessionPersistenceChannel.trySend(imageChatSessions.toList())
    }

    private fun writeSessionsToStorage(sessions: List<VisionChatSession>) {
        sessionRepository.saveVisionSessions(sessions)
    }

    private fun markStoppedIfBlank() {
        if (_answer.value.isBlank()) {
            _answer.value = "Stopped."
        }
    }

    private fun buildDocumentRetryPrompt(
        basePrompt: String,
        fileName: String?,
        fileText: String,
        budget: DocumentTextBudget
    ): String {
        val promptFileText = fileText.limitForPrompt(budget)
        return buildString {
            append("Ignore any blank placeholder image. Use only the extracted attached-file text below.\n")
            append("Do not say that the file is missing. Do not ask the user to attach it again.\n\n")
            if (basePrompt.isNotBlank()) {
                append("User request: ").append(basePrompt).append("\n\n")
            }
            append("--- EXTRACTED FILE: ${fileName ?: "document"} ---\n")
            append(promptFileText)
        }
    }

    private fun buildDocumentExtractiveFallback(
        fileName: String?,
        fileText: String,
        basePrompt: String,
        budget: DocumentTextBudget
    ): String {
        val clean = fileText.trim()
        if (clean.isExtractionFailureText()) return clean
        return if (basePrompt.isBlank() || basePrompt.isSummaryLikeRequest()) {
            buildCompactDocumentOverview(
                fileName = fileName,
                fileText = clean,
                basePrompt = basePrompt,
                budget = budget
            )
        } else {
            buildDocumentCouldNotAnswerMessage(
                fileName = fileName,
                basePrompt = basePrompt,
                budget = budget
            )
        }
    }

    private fun buildCompactDocumentOverview(
        fileName: String?,
        fileText: String,
        basePrompt: String,
        budget: DocumentTextBudget
    ): String {
        val isZip = fileName.isZipFileName()
        val maxOverviewChars = minOf(
            budget.maxFallbackChars,
            if (isZip) ZIP_FALLBACK_OVERVIEW_CHARS else DOCUMENT_FALLBACK_OVERVIEW_CHARS
        )
        val overview = if (isZip) {
            fileText.compactZipOverview(maxOverviewChars)
        } else {
            fileText.compactDocumentOverview(maxOverviewChars)
        }
        val heading = when {
            basePrompt.isBlank() && isZip ->
                "The model did not return a generated summary, so here is a compact extracted overview of ${fileName ?: "the ZIP archive"}:"
            basePrompt.isBlank() ->
                "The model did not return a generated summary, so here is a compact extracted overview of ${fileName ?: "the document"}:"
            else ->
                "The model did not return a generated answer, so here is a compact extracted overview relevant to ${fileName ?: "the attached file"}:"
        }
        return buildString {
            append(heading)
            append("\n\n")
            append(overview)
            if (fileText.length > overview.length) {
                append("\n\n[Only a compact overview is shown here. The extracted document text was not pasted in full. This model's ")
                append(budget.contextWindowTokens.withThousandsSeparator())
                append("-token context budget allowed about ")
                append(budget.maxPromptTokens.withThousandsSeparator())
                append(" document tokens in the prompt.]")
            }
        }
    }

    private fun buildDocumentCouldNotAnswerMessage(
        fileName: String?,
        basePrompt: String,
        budget: DocumentTextBudget
    ): String {
        val quotedRequest = basePrompt
            .lineSequence()
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
            .trim()
            .take(160)
        return buildString {
            append("I extracted readable text from ")
            append(fileName ?: "the attached file")
            append(", but the model did not generate an answer for your request")
            if (quotedRequest.isNotBlank()) {
                append(": \"").append(quotedRequest).append("\"")
            }
            append(".")
            append("\n\nI did not paste the extracted document into chat because it can be very large. Try asking a shorter, more specific question, or use a model with a larger context window. Current document prompt budget: about ")
            append(budget.maxPromptTokens.withThousandsSeparator())
            append(" tokens.")
        }
    }

    private data class DocumentTextBudget(
        val contextWindowTokens: Int,
        val maxPromptTokens: Int,
        val maxExtractedTokens: Int,
        val maxPromptChars: Int,
        val maxExtractedChars: Int,
        val maxFallbackChars: Int,
        val maxInputBytes: Long
    ) {
        val limitDescription: String
            get() = "about ${maxExtractedTokens.withThousandsSeparator()} tokens (${maxExtractedChars.withThousandsSeparator()} characters)"
    }

    private fun documentTextBudget(
        userPrompt: String,
        hasBitmap: Boolean,
        hasAudio: Boolean,
        thinkingMode: Boolean
    ): DocumentTextBudget {
        val contextTokens = currentContextWindowTokens.coerceIn(MIN_CONTEXT_WINDOW_TOKENS, 4096)
        val outputReserveTokens = when {
            contextTokens < 2_048 -> 256
            contextTokens < 8_192 -> 512
            else -> 1_024
        }
        val mediaReserveTokens = (if (hasBitmap) 256 else 0) + (if (hasAudio) 120 else 0)
        val thinkingReserveTokens = if (thinkingMode) 64 else 0
        val reservedTokens = (
            DOCUMENT_PROMPT_OVERHEAD_TOKENS +
                outputReserveTokens +
                mediaReserveTokens +
                thinkingReserveTokens +
                userPrompt.estimatedTokenCount()
            ).coerceAtMost((contextTokens * 3) / 4)
        val availableTokens = (contextTokens - reservedTokens).coerceAtLeast(1)
        val minimumFileTokens = minOf(MIN_DOCUMENT_FILE_TOKENS, (contextTokens * 35) / 100).coerceAtLeast(1)
        val maxPromptTokens = ((availableTokens * 85) / 100)
            .coerceAtLeast(minimumFileTokens)
            .coerceAtMost(((contextTokens * 85) / 100).coerceAtLeast(1))
        val maxPromptChars = (maxPromptTokens * APPROX_CHARS_PER_TOKEN)
            .coerceIn(MIN_DOCUMENT_CHARS, MAX_DOCUMENT_EXTRACTED_CHARS)
        val maxExtractedChars = (maxPromptTokens * APPROX_CHARS_PER_TOKEN * 3 / 2)
            .coerceAtLeast(maxPromptChars)
            .coerceAtMost(MAX_DOCUMENT_EXTRACTED_CHARS)
        val maxInputBytes = (contextTokens.toLong() * DOCUMENT_INPUT_BYTES_PER_TOKEN)
            .coerceIn(MIN_DOCUMENT_INPUT_BYTES, MAX_DOCUMENT_INPUT_BYTES)
        return DocumentTextBudget(
            contextWindowTokens = contextTokens,
            maxPromptTokens = maxPromptTokens,
            maxExtractedTokens = maxExtractedChars.estimatedTokenCountFromChars(),
            maxPromptChars = maxPromptChars,
            maxExtractedChars = maxExtractedChars,
            maxFallbackChars = maxExtractedChars,
            maxInputBytes = maxInputBytes
        )
    }

    private fun String.limitForPrompt(budget: DocumentTextBudget): String {
        val clean = trim()
        if (clean.isBlank() || clean.isExtractionFailureText() || clean.length <= budget.maxPromptChars) {
            return clean
        }
        return buildString {
            append(clean.take(budget.maxPromptChars).trimEnd())
            append("\n\n[The document continues, but this ")
            append(budget.contextWindowTokens.withThousandsSeparator())
            append("-token model can receive about ")
            append(budget.maxPromptTokens.withThousandsSeparator())
            append(" document tokens in one prompt.]")
        }
    }

    private fun String?.isZipFileName(): Boolean {
        return this?.endsWith(".zip", ignoreCase = true) == true
    }

    private fun String.isSummaryLikeRequest(): Boolean {
        val normalized = lowercase()
        return SummaryRequestMarkers.any { marker -> normalized.contains(marker) }
    }

    private fun String.compactDocumentOverview(maxChars: Int): String {
        val blocks = meaningfulTextBlocks()
        if (blocks.isEmpty()) return take(maxChars).trimEnd()
        val title = blocks.firstOrNull { it.length <= 160 }.orEmpty()
        return buildString {
            if (title.isNotBlank()) {
                append("Likely title or opening topic: ")
                append(title)
                append("\n\n")
            }
            append("Key extracted passages:\n")
            blocks
                .drop(if (title.isNotBlank()) 1 else 0)
                .take(6)
                .forEach { block ->
                    append("- ")
                    append(block.take(360).trimEnd())
                    append("\n")
                }
        }.trim().take(maxChars).trimEnd()
    }

    private fun String.compactZipOverview(maxChars: Int): String {
        val marker = "Readable contents extracted from archive:"
        val markerIndex = indexOf(marker)
        val archiveSummary = if (markerIndex >= 0) substring(0, markerIndex) else this
        val readableContents = if (markerIndex >= 0) substring(markerIndex + marker.length) else ""

        return buildString {
            append(
                archiveSummary
                    .lineSequence()
                    .filter { it.isNotBlank() }
                    .take(55)
                    .joinToString("\n")
            )
            val readableBlocks = readableContents.meaningfulTextBlocks()
            if (readableBlocks.isNotEmpty()) {
                append("\n\nReadable content highlights:\n")
                readableBlocks.take(5).forEach { block ->
                    append("- ")
                    append(block.take(320).trimEnd())
                    append("\n")
                }
            }
        }.trim().take(maxChars).trimEnd()
    }

    private fun String.meaningfulTextBlocks(): List<String> {
        return replace("\r\n", "\n")
            .split(Regex("""\n{2,}|(?m)^\s*--- .+? ---\s*$"""))
            .map { block ->
                block
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                    .replace(Regex("""\s{2,}"""), " ")
                    .trim()
            }
            .filter { it.length >= 24 && !it.startsWith("[") }
    }

    private fun String.estimatedTokenCount(): Int {
        return length.estimatedTokenCountFromChars()
    }

    private fun Int.estimatedTokenCountFromChars(): Int {
        return (this + APPROX_CHARS_PER_TOKEN - 1) / APPROX_CHARS_PER_TOKEN
    }

    private fun inferContextWindowTokens(path: String): Int? {
        return ContextWindowInPath.find(path)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun String.isNoAnswerGenerated(): Boolean {
        val normalized = trim()
        return normalized.isBlank() ||
            normalized.equals("No answer generated.", ignoreCase = true) ||
            normalized.equals("No answer generated", ignoreCase = true)
    }

    private fun String.isExtractionFailureText(): Boolean {
        val normalized = trim().lowercase()
        return normalized.startsWith("no readable text could be extracted") ||
            normalized.startsWith("could not extract") ||
            normalized.startsWith("could not open the attached file stream") ||
            normalized.startsWith("error extracting") ||
            normalized.startsWith("error reading") ||
            normalized.startsWith("the attached file is") ||
            normalized.startsWith("this file type is not text-extractable") ||
            normalized.startsWith("decompression limit exceeded") ||
            normalized.contains("decompression bomb")
    }

    fun flushVisibleConversation() {
        saveVisibleConversationIfComplete()
    }

    private fun saveVisibleConversationIfComplete() {
        val assistantIndex = messages.indexOfLast { message ->
            !message.isUser &&
                message.text.trim().isNotBlank() &&
                message.text.trim() !in PLACEHOLDER_ANSWERS
        }
        if (assistantIndex == -1) {
            persistSessions()
            return
        }
        val userMessage = messages.take(assistantIndex).lastOrNull { it.isUser } ?: return
        val assistantMessage = messages[assistantIndex]
        val answer = ModelOutputSanitizer.clean(assistantMessage.text).trim()
        val anyUserWithBitmap = messages.firstOrNull { it.isUser && (it.bitmap != null || !it.imagePath.isNullOrBlank()) }
        val anyUserWithDoc = messages.lastOrNull { it.isUser && !it.documentName.isNullOrBlank() }
        val anyUserWithAudio = messages.lastOrNull { it.isUser && !it.audioPath.isNullOrBlank() }
        saveCurrentSession(
            question = userMessage.text,
            answer = answer,
            bitmap = userMessage.bitmap ?: anyUserWithBitmap?.bitmap,
            targetSessionId = _currentSessionId.value,
            imagePath = userMessage.imagePath ?: anyUserWithBitmap?.imagePath,
            audioPath = userMessage.audioPath ?: anyUserWithAudio?.audioPath,
            audioName = userMessage.audioName ?: anyUserWithAudio?.audioName,
            audioDurationMs = if (userMessage.audioDurationMs > 0L) userMessage.audioDurationMs else (anyUserWithAudio?.audioDurationMs ?: 0L),
            documentName = userMessage.documentName ?: anyUserWithDoc?.documentName,
            documentId = currentSessionDocumentId
        )
    }

    private fun String.toImageChatTitle(
        documentName: String? = null,
        audioName: String? = null,
        hasImage: Boolean = false
    ): String {
        val firstLine = lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.take(48)
        if (!firstLine.isNullOrBlank()) return firstLine
        return when {
            !documentName.isNullOrBlank() -> "Document: $documentName"
            !audioName.isNullOrBlank() -> "Audio: $audioName"
            hasImage -> "Image question"
            else -> "Media question"
        }
    }

    override fun onCleared() {
        documentPreparation.cancel()
        imagePreparation.cancel()
        saveVisibleConversationIfComplete()
        releaseModel(clearCoordinator = false, clearSelection = true)
        val token = coordinatorToken
        ModelRuntimeCoordinator.shutdownScope.launch {
            try {
                kotlinx.coroutines.withTimeout(10_000L) {
                    releaseJob?.join()
                }
            } finally {
                if (token != null) {
                    ModelRuntimeCoordinator.unregister(ModelRuntimeOwner.Vision, token)
                }
                coordinatorToken = null
            }
        }
        sessionPersistenceChannel.close()
        super.onCleared()
    }

    private fun releaseModel(clearCoordinator: Boolean, clearSelection: Boolean = false) {
        autoUnloadJob?.cancel()
        autoUnloadJob = null
        val jobToCancel = analysisJob
        analysisJob = null
        jobToCancel?.cancel()
        inferenceManager.cancelGeneration()
        initGeneration++
        analysisGeneration++
        initJob?.cancel()
        initJob = null
        if (jobToCancel != null && jobToCancel.isActive) {
            _isStopping.value = true
        }

        val priorReleaseJob = releaseJob
        releaseJob = ModelRuntimeCoordinator.shutdownScope.launch {
            priorReleaseJob?.join()
            if (jobToCancel != null && jobToCancel.isActive) {
                runCatching {
                    kotlinx.coroutines.withTimeout(5_000L) {
                        jobToCancel.join()
                    }
                }
            }
            currentModelEpoch++
            withContext(Dispatchers.Main.immediate) {
                activeRequestId = null
            }
            val result = ioMutex.withLock {
                val res = inferenceManager.close()
                AppCacheCleaner.clearAppCache(getApplication())
                res
            }
            lastShutdownResult = result
            if (!result.isSuccess) {
                Log.e("VisionViewModel", "Model shutdown failed: $result")
                withContext(Dispatchers.Main.immediate) {
                    _isStopping.value = false
                }
                return@launch
            }
            if (clearCoordinator) ModelRuntimeCoordinator.clear(ModelRuntimeOwner.Vision)
            activeLoadOperationId = null
            withContext(Dispatchers.Main.immediate) {
                currentRequestedIdentity = null
                currentLoadedIdentity = null
                currentResolvedThinkingCapability = ThinkingCapability.Unknown
                if (clearSelection) {
                    currentModelPath = null
                    currentBundle = null
                    currentModelId = ""
                    currentModelName = ""
                    currentModelSize = ""
                    currentContextWindowTokensTokens = null
                    currentSupportsAudioInput = false
                    currentAllowUnsafeOverride = false
                }
                lastLoadedBackendPreference = null
                _activeBackend.value = null
                currentContextWindowTokens = DEFAULT_VISION_CONTEXT_WINDOW_TOKENS
                resetRuntimeConversationBeforeNextAsk = false
                lastAttachedFileName = null
                _isInitializing.value = false
                _isAnalyzing.value = false
                _isStopping.value = false
                _isModelReady.value = false
            }
        }
    }

    private companion object {
        const val KEY_SESSIONS = "sessions_json"
        const val DEFAULT_IMAGE_PROMPT = "Describe this image."
        val FAILED_ANSWER_STRINGS = setOf(
            ResponseFinalizer.FAILED_NO_ANSWER_MESSAGE,
            "No answer generated.",
            "Inference failed."
        )
        const val DEFAULT_VISION_CONTEXT_WINDOW_TOKENS = 1_280
        const val MIN_CONTEXT_WINDOW_TOKENS = 512
        const val APPROX_CHARS_PER_TOKEN = 4
        const val DOCUMENT_PROMPT_OVERHEAD_TOKENS = 220
        const val MIN_DOCUMENT_FILE_TOKENS = 256
        const val MIN_DOCUMENT_CHARS = 512
        const val MAX_DOCUMENT_EXTRACTED_CHARS = 512_000
        const val DOCUMENT_INPUT_BYTES_PER_TOKEN = 1_024L
        const val MIN_DOCUMENT_INPUT_BYTES = 1_048_576L
        const val MAX_DOCUMENT_INPUT_BYTES = 67_108_864L
        const val DOCUMENT_FALLBACK_OVERVIEW_CHARS = 2_400
        const val ZIP_FALLBACK_OVERVIEW_CHARS = 3_200
        val ContextWindowInPath = Regex("""ekv(\d+)""", RegexOption.IGNORE_CASE)
        val SummaryRequestMarkers = listOf(
            "summarize",
            "summarise",
            "summary",
            "overview",
            "key points",
            "main points",
            "important points",
            "analyze",
            "analyse",
            "explain",
            "tl;dr",
            "tldr"
        )
        /** Placeholder answers that should never be treated as real saved sessions. */
        val PLACEHOLDER_ANSWERS = setOf(
            "Reading input…",
            "Reading input...",
            "…",
            "Generating…",
            "Generating...",
            "Loading model…",
            "Loading model...",
            "Preparing input…",
            "Preparing input...",
            "Validating backend…",
            "Validating backend...",
            "Processing prompt…",
            "Processing prompt...",
            "Stopped."
        )
    }
}

private fun Int.withThousandsSeparator(): String {
    return toString()
        .reversed()
        .chunked(3)
        .joinToString(",")
        .reversed()
}
