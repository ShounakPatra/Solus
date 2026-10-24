package com.shounak.localmeshai.ui.viewmodels

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shounak.localmeshai.ai.ChatInferenceManager
import com.shounak.localmeshai.utils.CrashReportManager
import com.shounak.localmeshai.utils.InitCrashGuard
import com.shounak.localmeshai.utils.ModelOutputSanitizer
import com.shounak.localmeshai.utils.ModelResponseQuality
import com.shounak.localmeshai.utils.ModelRuntimeCoordinator
import com.shounak.localmeshai.utils.RegistrationToken
import com.shounak.localmeshai.utils.ModelRuntimeOwner
import com.shounak.localmeshai.utils.ThinkingTextUtils
import com.shounak.localmeshai.utils.ThinkingModeConfig
import com.shounak.localmeshai.utils.AppCacheCleaner
import com.shounak.localmeshai.ai.ModelLoadController
import com.shounak.localmeshai.ai.ModelLoadIdentity
import com.shounak.localmeshai.ai.ModelLoadState
import com.shounak.localmeshai.ai.ModelLoadStage
import com.shounak.localmeshai.models.ModelBundle
import com.shounak.localmeshai.models.ThinkingCapability
import com.shounak.localmeshai.ai.ModelActualConfiguration
import com.shounak.localmeshai.ai.GgufExecutionProfile
import com.shounak.localmeshai.utils.DeviceUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.net.Uri
import com.shounak.localmeshai.rag.RagIngestionResult
import com.shounak.localmeshai.rag.RagManager
import com.shounak.localmeshai.rag.store.RagDocumentSummary
import com.shounak.localmeshai.utils.InferenceTiming
import com.shounak.localmeshai.utils.ChatRuntimePolicy
import com.shounak.localmeshai.utils.AppSettings
import com.shounak.localmeshai.utils.AttachmentViewerUtils
import com.shounak.localmeshai.ai.EmptyResponseReason
import com.shounak.localmeshai.ai.RequestProgress
import com.shounak.localmeshai.ai.RequestTerminalStatus
import com.shounak.localmeshai.ai.InferenceMetrics
import com.shounak.localmeshai.ai.InferenceExecutionResult
import com.shounak.localmeshai.ai.PreparedAttachment
import com.shounak.localmeshai.ai.DocumentPreparation
import com.shounak.localmeshai.data.ChatSessionRepository
import com.shounak.localmeshai.utils.AttachedDocumentContext
import kotlinx.coroutines.NonCancellable
import com.shounak.localmeshai.memory.MemoryCategory
import com.shounak.localmeshai.memory.MemoryEntry
import com.shounak.localmeshai.memory.PersistentMemoryManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID

@Immutable
data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val id: String = UUID.randomUUID().toString(),
    val ragSources: List<String> = emptyList(),
    val ragChunkCount: Int = 0,
    val ragTopMatchPct: Int = 0,
    val documentName: String? = null,
    val documentPath: String? = null,
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
    val responseFormatVersion: Int = 0,
    val toolUses: List<com.shounak.localmeshai.ai.tools.ToolUse> = emptyList(),
    val ragPassages: List<com.shounak.localmeshai.features.RagPassage> = emptyList()
)

@Immutable
data class ChatSession(
    val id: String,
    val title: String,
    val messages: List<ChatMessage>,
    val updatedAt: Long,
    val documentId: String? = null
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val conversationPrivacy = com.shounak.localmeshai.features.ConversationPrivacy()
    private val _temporaryChat = conversationPrivacy.temporary
    val temporaryChat = conversationPrivacy.temporary
    private val temporaryAttachments by lazy {
        com.shounak.localmeshai.data.ChatAttachmentStore(getApplication<Application>().cacheDir.resolve("temporary_chats/text"))
    }
    private val attachmentStoreForChat get() = if (_temporaryChat.value) temporaryAttachments else sessionRepository.attachmentStore
    fun setTemporaryChat(enabled: Boolean): Boolean = conversationPrivacy.setForNewChat(
        enabled, isNewChat = messages.isEmpty() && _currentSessionId.value == null && _pendingSessionId.value == null,
        busy = generationJob?.isActive == true || _isGenerating.value || _isStopping.value || _isInitializing.value || _isDeletingHistory.value
    )
    fun refreshHistory() { viewModelScope.launch { loadSessionsAsync() } }
    fun editAndRegenerate(userMessageId: String, replacement: String? = null) {
        if (generationJob?.isActive == true || _isInitializing.value) return
        if (_isGenerating.value || _isStopping.value || _isDeletingHistory.value) return
        val sessionId = _currentSessionId.value ?: return
        val index = messages.indexOfFirst { it.isUser && it.id == userMessageId }
        if (index < 0 || replacement != null && replacement.isBlank()) return
        if (currentModelPath == null) { _error.value = "Choose a model before editing this conversation."; return }
        val original = messages[index]
        if (listOf(original.documentPath).filterNotNull().any { it.isNotBlank() && !File(it).isFile }) {
            _error.value = "A required attachment is missing. The conversation was preserved."
            return
        }
        val attachmentName = original.documentName.orEmpty()
        val needsMedia = listOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp", ".mp3", ".wav", ".m4a", ".aac", ".ogg").any { attachmentName.endsWith(it, true) }
        if (needsMedia && !ChatRuntimePolicy.supportsAttachments(currentModelPath!!, inferenceManager.runtimeCapabilities?.capabilities ?: currentBundle?.capabilities)) {
            _error.value = "Choose a model that supports this attachment before editing."; return
        }
        val revised = try { com.shounak.localmeshai.features.ConversationEdits.text(messages.toList(), userMessageId, replacement) }
        catch (error: IllegalArgumentException) { _error.value = error.message; return }
        messages.clear(); messages.addAll(revised)
        currentSessionDocumentId = null
        pendingRagDocumentId = null
        val position = chatSessions.indexOfFirst { it.id == sessionId }
        if (position >= 0) chatSessions[position] = chatSessions[position].copy(messages = revised, documentId = null, updatedAt = System.currentTimeMillis())
        resetRuntimeConversationBeforeNextSend = true
        persistSessions()
        retryMessage(sessionId, userMessageId)
    }

    private val inferenceManager = ChatInferenceManager(application)
    val sessionRepository = ChatSessionRepository.getInstance(application)
    val messages = mutableStateListOf<ChatMessage>()
    val chatSessions = mutableStateListOf<ChatSession>()

    private val _lastInferenceTime = MutableStateFlow(0L)
    val lastInferenceTime = _lastInferenceTime.asStateFlow()

    private val _isInitializing = MutableStateFlow(false)
    val isInitializing = _isInitializing.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating = _isGenerating.asStateFlow()

    private val _isStopping = MutableStateFlow(false)
    val isStopping = _isStopping.asStateFlow()

    private val _isModelReady = MutableStateFlow(false)
    val isModelReady = _isModelReady.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _tokensPerSecond = MutableStateFlow(0f)
    val tokensPerSecond = _tokensPerSecond.asStateFlow()

    private val _thinkingMode = MutableStateFlow(false)
    val thinkingMode = _thinkingMode.asStateFlow()

    val ragManager: RagManager = RagManager.getInstance(application)
    private val appSettings = AppSettings.getInstance(application)

    private val _isRagActive = MutableStateFlow(appSettings.settings.value.enableRag)
    val isRagActive = _isRagActive.asStateFlow()

    private val _indexedRagDocuments = MutableStateFlow<List<com.shounak.localmeshai.rag.store.RagDocumentSummary>>(emptyList())
    val indexedRagDocuments = _indexedRagDocuments.asStateFlow()

    private val _ragIngestionStatus = MutableStateFlow<String?>(null)
    val ragIngestionStatus = _ragIngestionStatus.asStateFlow()

    val memoryManager: PersistentMemoryManager = PersistentMemoryManager.getInstance(application)

    private val _isPersistentMemoryActive = MutableStateFlow(appSettings.settings.value.enablePersistentMemory)
    val isPersistentMemoryActive = _isPersistentMemoryActive.asStateFlow()

    private val _memories = MutableStateFlow<List<com.shounak.localmeshai.memory.MemoryEntry>>(emptyList())
    val memories = _memories.asStateFlow()

    private val _memoryFeedbackEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val memoryFeedbackEvent = _memoryFeedbackEvent.asSharedFlow()

    private val _activeRequestIdState = MutableStateFlow<String?>(null)
    val activeRequestIdState = _activeRequestIdState.asStateFlow()

    @Volatile private var activeRequestIdInternal: String? = null
    var activeRequestId: String?
        get() = activeRequestIdInternal
        private set(value) {
            activeRequestIdInternal = value
            _activeRequestIdState.value = value
        }

    private val _draftText = MutableStateFlow("")
    val draftText = _draftText.asStateFlow()

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId = _currentSessionId.asStateFlow()

    /** Session selected while no model is ready; promoted when [isModelReady] becomes true. */
    private val _pendingSessionId = MutableStateFlow<String?>(null)
    val pendingSessionId = _pendingSessionId.asStateFlow()

    private val _activeBackend = MutableStateFlow<String?>(null)
    val activeBackend = _activeBackend.asStateFlow()

    private var currentModelPath: String? = null
    private var currentBundle: ModelBundle? = null
    private var currentModelId: String = ""
    private var currentModelName: String = ""
    private var currentModelSize: String = ""
    private var currentContextWindowTokens: Int? = null
    private var currentSupportsThinkingMode: Boolean = false
    private var currentAllowUnsafeOverride: Boolean = false
    private var lastLoadedBackendPreference: String? = null
    private var currentRequestedIdentity: ModelLoadIdentity? = null
    private var currentLoadedIdentity: ModelLoadIdentity? = null
    @Volatile var currentResolvedThinkingCapability: ThinkingCapability = ThinkingCapability.Unknown
        private set
    private val _runtimeCapabilities = MutableStateFlow<com.shounak.localmeshai.models.ModelCapabilitySnapshot?>(null)
    val runtimeCapabilities = _runtimeCapabilities.asStateFlow()
    @Volatile private var currentModelEpoch: Long = 0L
    private var initGeneration: Int = 0
    private var initJob: Job? = null
    private var generationJob: Job? = null
    private val _isDeletingHistory = MutableStateFlow(false)
    val isDeletingHistory = _isDeletingHistory.asStateFlow()
    private var autoUnloadJob: Job? = null
    @Volatile private var resetRuntimeConversationBeforeNextSend = false
    @Volatile private var lastRuntimeThinkingMode: Boolean? = null
    private var coordinatorToken: RegistrationToken? = null
    private var lastShutdownResult: com.shounak.localmeshai.ai.ShutdownResult? = null

    fun retryMessage(sessionId: String, userMessageId: String) {
        if (_isDeletingHistory.value) return
        if (_isGenerating.value || generationJob?.isActive == true) return
        val session = chatSessions.firstOrNull { it.id == sessionId }
            ?: if (_temporaryChat.value && _currentSessionId.value == sessionId) ChatSession(sessionId, "Temporary chat", messages.toList(), 0L) else return
        val userMsg = session.messages.firstOrNull { it.isUser && it.id == userMessageId } ?: return

        if (_currentSessionId.value != sessionId) {
            selectChatSession(sessionId)
        }

        val docPath = userMsg.documentPath
        if (!docPath.isNullOrBlank()) {
            val file = File(docPath)
            if (!file.exists()) {
                _error.value = "Referenced document file is missing or unavailable for retry: ${file.name}"
                return
            }
        }

        val path = currentModelPath
        if (path == null) {
            _error.value = "Choose and initialise a chat model first."
            return
        }
        if (!docPath.isNullOrBlank()) {
            val fileName = userMsg.documentName ?: "document"
            val isImageOrAudio = listOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp", ".mp3", ".wav", ".m4a", ".aac", ".ogg")
                .any { fileName.endsWith(it, ignoreCase = true) }
            val isMultimodal = ChatRuntimePolicy.supportsAttachments(path, inferenceManager.runtimeCapabilities?.capabilities ?: currentBundle?.capabilities)
            if (isImageOrAudio && !isMultimodal) {
                _error.value = "The selected model is a text-only model. Image or audio input requires a multimodal model from the Models tab."
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
        val initialPlaceholder = if (!_isModelReady.value) "Loading model…" else "Preparing input…"
        val placeholderAssistant = ChatMessage(
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

        val sIdx = chatSessions.indexOfFirst { it.id == sessionId }
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
            chatSessions[sIdx] = session.copy(messages = sessionList)
        }

        saveCurrentSession()

        startChatGeneration(
            userText = userMsg.text,
            userMsgId = userMsg.id,
            documentUri = docPath?.let { Uri.fromFile(File(it)) },
            documentName = userMsg.documentName,
            documentPath = userMsg.documentPath,
            requestId = newRequestId,
            attemptId = nextAttempt,
            assistantIndex = actualAssistantIndex,
            targetSessionId = sessionId,
            isRetry = true
        )
    }

    fun retryLastFailedMessage() {
        val sId = _currentSessionId.value ?: return
        val failedAssistant = messages.lastOrNull { !it.isUser && (it.terminalStatus == RequestTerminalStatus.Failed || it.errorMessage != null) } ?: return
        val userMsgId = failedAssistant.userMessageId ?: messages.takeWhile { it.id != failedAssistant.id }.lastOrNull { it.isUser }?.id ?: return
        retryMessage(sId, userMsgId)
    }
    private val ioMutex = Mutex()
    private var releaseJob: Job? = null
    private val documentPreparation = DocumentPreparation(application, viewModelScope)

    val modelLoadController = ModelLoadController()
    val modelLoadState: StateFlow<ModelLoadState> = modelLoadController.loadState

    private val sessionPersistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionPersistenceChannel = Channel<List<ChatSession>>(Channel.CONFLATED)

    fun cancelModelInitialization() {
        val opId = modelLoadController.activeOperationId
        viewModelScope.launch {
            if (opId != null) {
                modelLoadController.cancelActiveOperation(targetOperationId = opId, reason = "Cancelled by user")
            }
            inferenceManager.cancelGeneration()
        }
    }

    fun stageDocumentAttachment(uri: Uri, name: String) = documentPreparation.stage(uri, name)
    fun cancelStagedDocument() = documentPreparation.cancel()


    fun resetAutoUnloadTimer() {
        autoUnloadJob?.cancel()
        autoUnloadJob = null

        val timeoutMinutes = appSettings.settings.value.autoUnloadMinutes
        if (timeoutMinutes <= 0) return
        if (!_isModelReady.value || _isGenerating.value || _isInitializing.value) return

        autoUnloadJob = viewModelScope.launch {
            delay(timeoutMinutes * 60 * 1000L)
            if (_isModelReady.value && !_isGenerating.value && !_isInitializing.value) {
                Log.i("ChatViewModel", "Auto-unloading idle chat model after $timeoutMinutes minute(s) of inactivity")
                releaseModel(clearCoordinator = true, clearSelection = false)
            }
        }
    }

    init {
        viewModelScope.launch {
            sessionRepository.externalChanges.collect { loadSessionsAsync() }
        }
        viewModelScope.launch {
            com.shounak.localmeshai.features.WorkspacePreferences.get(getApplication()).profileChanges.collect { ids ->
                if (currentModelId in ids) {
                    val path = currentModelPath
                    releaseModel(clearCoordinator = true, clearSelection = false)
                    if (path != null) initModel(path, modelId = currentModelId, modelName = currentModelName,
                        modelSize = currentModelSize, contextWindowTokens = defaultProfileContext,
                        supportsThinkingMode = currentSupportsThinkingMode, allowUnsafeOverride = currentAllowUnsafeOverride, forceReload = true, bundle = currentBundle)
                }
            }
        }

        sessionPersistenceScope.launch {
            for (snapshot in sessionPersistenceChannel) {
                writeSessionsToStorage(snapshot)
            }
        }
        viewModelScope.launch {
            modelLoadController.loadState.collect { state ->
                when (state) {
                    is ModelLoadState.Ready -> {
                        publishRuntimeCapabilities()
                        _isInitializing.value = false
                        _isModelReady.value = inferenceManager.isReady
                    }
                    is ModelLoadState.Loading -> {
                        _isInitializing.value = true
                        _runtimeCapabilities.value = null
                        _isModelReady.value = false
                    }
                    is ModelLoadState.Stopping -> {
                        _isInitializing.value = true
                        _runtimeCapabilities.value = null
                        _isModelReady.value = false
                    }
                    is ModelLoadState.Failed -> {
                        _isInitializing.value = false
                        _runtimeCapabilities.value = null
                        _isModelReady.value = false
                        _error.value = state.typedError
                    }
                    is ModelLoadState.ShutdownStalled -> {
                        _isInitializing.value = false
                        _runtimeCapabilities.value = null
                        _isModelReady.value = false
                        _error.value = "Native runtime shutdown stalled. Please restart the app."
                    }
                    is ModelLoadState.Unloaded -> {
                        _isInitializing.value = false
                        _runtimeCapabilities.value = null
                        _isModelReady.value = false
                    }
                }
            }
        }
        coordinatorToken = ModelRuntimeCoordinator.register(
            owner = ModelRuntimeOwner.Chat,
            loadedModelIdentity = { currentLoadedIdentity?.modelPath ?: currentModelPath ?: currentModelId },
            stableModelIdProvider = { currentModelId },
            modelPathProvider = { currentModelPath ?: currentLoadedIdentity?.modelPath.orEmpty() },
            loadOperationIdProvider = { modelLoadController.activeOperationId.orEmpty() },
            requestStop = {
                initJob?.cancel()
                generationJob?.cancel()
                inferenceManager.cancelGeneration()
                val opId = modelLoadController.activeOperationId
                if (opId != null) {
                    viewModelScope.launch {
                        modelLoadController.cancelActiveOperation(targetOperationId = opId, reason = "Runtime coordinator displacement")
                    }
                }
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
                    com.shounak.localmeshai.ai.ShutdownResult.Failed(IllegalStateException("Chat inference manager still ready after release"))
                } else {
                    com.shounak.localmeshai.ai.ShutdownResult.Success
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            loadSessionsAsync()
            memoryManager.loadAsync()
            ragManager.loadAsync()
            _memories.value = memoryManager.getAllMemories()
            _indexedRagDocuments.value = ragManager.indexedDocuments
            val crashMsg = InitCrashGuard.consumeLastCrashMessage(application)
            if (crashMsg != null) {
                withContext(Dispatchers.Main.immediate) {
                    messages.add(ChatMessage("⚠️ $crashMsg\n\nTry a MediaPipe (.task) model instead.", false))
                }
            }
        }
        // Promote any deferred history selection once a model finishes initialising.
        viewModelScope.launch {
            isModelReady.collect { ready ->
                if (ready) {
                    com.shounak.localmeshai.utils.ModelUsageStore.get(getApplication()).recordUsed(currentModelId)
                    commitPendingSession()
                }
            }
        }
        // Auto-unload idle model timer
        viewModelScope.launch {
            combine(isModelReady, isGenerating, isInitializing) { ready, generating, initializing ->
                Triple(ready, generating, initializing)
            }.distinctUntilChanged().collect { (ready, generating, initializing) ->
                if (ready && !generating && !initializing) {
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
        // Reinitialize active model if user changes backend preference in settings
        viewModelScope.launch {
            appSettings.settings
                .map { it.llamaBackendPreference }
                .distinctUntilChanged()
                .drop(1)
                .collect { newPref ->
                    val path = currentModelPath
                    if (path != null && _isModelReady.value) {
                        Log.i("ChatViewModel", "Backend preference changed to $newPref, reloading active model: $path")
                        initModel(
                            path = path,
                            modelId = currentModelId,
                            modelName = currentModelName,
                            modelSize = currentModelSize,
                            contextWindowTokens = defaultProfileContext,
                            supportsThinkingMode = currentSupportsThinkingMode,
                            allowUnsafeOverride = currentAllowUnsafeOverride,
                            forceReload = true
                        )
                    }
                }
        }
        viewModelScope.launch {
            appSettings.settings
                .map { it.enableRag }
                .distinctUntilChanged()
                .collect { enabled ->
                    _isRagActive.value = enabled
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

    private var pendingRagDocumentId: String? = null
    var currentSessionDocumentId: String? = null
        private set

    fun setRagActive(enabled: Boolean) {
        _isRagActive.value = enabled
        appSettings.updateSettings { it.copy(enableRag = enabled) }
    }

    fun refreshRagDocuments() {
        _indexedRagDocuments.value = ragManager.indexedDocuments
    }

    suspend fun ingestDocumentForRag(uri: Uri, fileName: String, mimeType: String = ""): RagIngestionResult {
        if (_temporaryChat.value) return RagIngestionResult(false, "", fileName, 0, 0, "Temporary chats do not add documents to the knowledge base.")
        val result = ragManager.ingestDocument(uri, fileName, mimeType)
        if (result.success) {
            appSettings.updateSettings { it.copy(enableRag = true) }
            pendingRagDocumentId = result.documentId
            currentSessionDocumentId = result.documentId
            val activeSessionId = _currentSessionId.value ?: UUID.randomUUID().toString().also {
                _currentSessionId.value = it
            }
            try {
                val sessionDir = getApplication<Application>().filesDir.resolve("chat_attachments").resolve(activeSessionId).apply { mkdirs() }
                val safeFileName = File(fileName).name.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "document.bin" }
                val targetFile = sessionDir.resolve(safeFileName)
                val tempFile = sessionDir.resolve("$safeFileName.tmp")
                try {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (targetFile.exists()) targetFile.delete()
                    if (!tempFile.renameTo(targetFile)) {
                        tempFile.copyTo(targetFile, overwrite = true)
                        tempFile.delete()
                    }
                } catch (t: Throwable) {
                    tempFile.delete()
                    throw t
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Failed to copy ingested document to session attachments", e)
            }
        }
        refreshRagDocuments()
        _ragIngestionStatus.value = result.message
        return result
    }

    suspend fun ingestTextForRag(name: String, content: String): RagIngestionResult {
        if (_temporaryChat.value) return RagIngestionResult(false, "", name, 0, 0, "Temporary chats do not add documents to the knowledge base.")
        val result = ragManager.ingestText(name, content)
        if (result.success) {
            pendingRagDocumentId = result.documentId
            currentSessionDocumentId = result.documentId
        }
        refreshRagDocuments()
        _ragIngestionStatus.value = result.message
        return result
    }

    fun clearRagKnowledgeBase() {
        ragManager.clearKnowledgeBase()
        pendingRagDocumentId = null
        currentSessionDocumentId = null
        refreshRagDocuments()
    }

    fun removeRagDocument(documentId: String) {
        ragManager.removeDocument(documentId)
        if (currentSessionDocumentId == documentId) currentSessionDocumentId = null
        if (pendingRagDocumentId == documentId) pendingRagDocumentId = null
        refreshRagDocuments()
    }

    fun setPersistentMemoryActive(enabled: Boolean) {
        _isPersistentMemoryActive.value = enabled
        appSettings.updateSettings { it.copy(enablePersistentMemory = enabled) }
    }

    fun refreshMemories() {
        _memories.value = memoryManager.getAllMemories()
    }

    fun addMemory(content: String, category: MemoryCategory = MemoryCategory.GENERAL): Boolean {
        val entry = memoryManager.addMemory(content, category)
        if (entry != null) {
            refreshMemories()
            return true
        }
        return false
    }

    fun updateMemory(entry: MemoryEntry): Boolean {
        val updated = memoryManager.updateMemory(entry)
        if (updated) refreshMemories()
        return updated
    }

    fun toggleMemory(id: String, enabled: Boolean) {
        memoryManager.toggleMemory(id, enabled)
        refreshMemories()
    }

    fun deleteMemory(id: String) {
        memoryManager.deleteMemory(id)
        refreshMemories()
    }

    fun clearAllMemories() {
        memoryManager.clearAllMemories()
        refreshMemories()
    }

    fun setThinkingMode(enabled: Boolean) {
        _thinkingMode.value = enabled
    }

    fun setDraftText(text: String) {
        _draftText.value = text
        resetAutoUnloadTimer()
    }

    fun clearDraftText() {
        _draftText.value = ""
    }

    private var defaultProfileContext: Int? = null

    fun initModel(
        path: String,
        modelId: String = "",
        modelName: String = "",
        modelSize: String = "",
        contextWindowTokens: Int? = null,
        supportsThinkingMode: Boolean = false,
        allowUnsafeOverride: Boolean = false,
        forceReload: Boolean = false,
        bundle: ModelBundle? = null
    ) {
        defaultProfileContext = contextWindowTokens
        val configuredProfileContext = com.shounak.localmeshai.features.WorkspacePreferences.get(getApplication()).profile(modelId.ifBlank { bundle?.id ?: File(path).nameWithoutExtension }).contextTokens ?: contextWindowTokens

        val currentBackendPref = appSettings.settings.value.llamaBackendPreference
        val file = File(path)
        val isGguf = bundle?.isGguf ?: ChatRuntimePolicy.isGgufModel(file.name)
        val identity = if (bundle != null) {
            ModelLoadIdentity.fromBundle(
                bundle = bundle,
                runtimeKind = if (bundle.isGguf) "LlamaCpp" else "MediaPipe/LiteRT",
                backendPreference = currentBackendPref,
                threads = DeviceUtils.recommendedThreadCount(getApplication()),
                contextWindow = configuredProfileContext ?: 2048
            )
        } else if (isGguf) {
            val profile = GgufExecutionProfile.resolve(
                context = getApplication(),
                modelNameOrId = modelName.ifBlank { modelId.ifBlank { file.nameWithoutExtension } },
                modelSize = modelSize,
                customContextTokens = configuredProfileContext,
                backendPreference = currentBackendPref
            )
            profile.toIdentity(file, "LlamaCpp", currentBackendPref)
        } else {
            ModelLoadIdentity.fromFile(
                file = file,
                runtimeKind = "MediaPipe/LiteRT",
                backendPreference = currentBackendPref,
                threads = DeviceUtils.recommendedThreadCount(getApplication()),
                contextWindow = configuredProfileContext ?: 2048
            )
        }

        if (!forceReload) {
            if (_isModelReady.value && inferenceManager.isReady && currentLoadedIdentity == identity && ModelRuntimeCoordinator.isActive(ModelRuntimeOwner.Chat)) {
                publishRuntimeCapabilities()
                currentAllowUnsafeOverride = allowUnsafeOverride
                currentContextWindowTokens = configuredProfileContext
                return
            }
            val activeLoadState = modelLoadController.loadState.value
            if (activeLoadState is ModelLoadState.Loading && (modelLoadController.currentIdentity == identity || currentRequestedIdentity == identity) && initJob?.isActive == true && !_isStopping.value) {
                currentSupportsThinkingMode = supportsThinkingMode
                currentAllowUnsafeOverride = allowUnsafeOverride
                currentContextWindowTokens = configuredProfileContext
                return
            }
        }

        currentModelPath = path
        currentBundle = bundle
        currentModelId = modelId
        currentModelName = modelName
        currentModelSize = modelSize
        currentContextWindowTokens = configuredProfileContext
        currentSupportsThinkingMode = supportsThinkingMode
        currentAllowUnsafeOverride = allowUnsafeOverride
        currentRequestedIdentity = identity

        currentModelEpoch++
        activeRequestId = null
        val myGen = ++initGeneration

        initJob?.cancel()
        generationJob?.cancel()
        inferenceManager.cancelGeneration()
        _isInitializing.value = true
        _isGenerating.value = false
        _runtimeCapabilities.value = null
        _isModelReady.value = false
        _error.value = null

        initJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                modelLoadController.loadOrAttach(
                    identity = identity,
                    scope = viewModelScope,
                    cancellationAction = {
                        inferenceManager.cancelGeneration()
                    }
                ) { operationId, operationControlId, updateProgress ->
                    val shutdownOk = runCatching {
                        kotlinx.coroutines.withTimeout(10_000L) {
                            releaseJob?.join()
                        }
                    }.isSuccess
                    if (!shutdownOk) {
                        throw IllegalStateException("Previous model runtime shutdown timed out or failed")
                    }
                    updateProgress(ModelLoadStage.WaitingForRuntime, null, null)
                    ModelRuntimeCoordinator.withRuntime(ModelRuntimeOwner.Chat) {
                        ioMutex.withLock {
                            if (myGen != initGeneration) {
                                throw CancellationException("Superseded by newer model configuration")
                            }
                            AppCacheCleaner.clearAppCache(getApplication())
                            inferenceManager.initialize(
                                modelPath = path,
                                modelId = modelId,
                                modelName = modelName,
                                modelSize = modelSize,
                                supportsThinkingMode = supportsThinkingMode,
                                allowUnsafeOverride = allowUnsafeOverride,
                                contextWindowTokens = configuredProfileContext,
                                operationControlId = operationControlId,
                                onModelProgress = updateProgress,
                                bundle = bundle
                            )
                            if (myGen != initGeneration || !inferenceManager.isReady) {
                                inferenceManager.close()
                                throw IllegalStateException("Model initialization cancelled or manager unavailable")
                            }
                            currentModelPath = path
                            lastLoadedBackendPreference = currentBackendPref
                            currentLoadedIdentity = identity
                            publishRuntimeCapabilities()

                            resetRuntimeConversationBeforeNextSend = false
                            lastRuntimeThinkingMode = null
                            withContext(Dispatchers.Main.immediate) {
                                _activeBackend.value = inferenceManager.activeBackendDisplayName
                                _isModelReady.value = inferenceManager.isReady
                            }
                            ModelActualConfiguration(
                                backend = inferenceManager.activeBackendDisplayName,
                                contextLength = inferenceManager.runtimeCapabilities?.initializedContextTokens ?: 2048
                            )
                        }
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (myGen != initGeneration) return@launch
                val msg = t.message ?: "Unknown error during model load"
                Log.e("ChatViewModel", "Model init failed", t)
                withContext(Dispatchers.Main.immediate) {
                    _error.value = msg
                    lastLoadedBackendPreference = null
                    currentLoadedIdentity = null
                    currentRequestedIdentity = null
                    currentResolvedThinkingCapability = ThinkingCapability.Unknown
                    _activeBackend.value = null
                    _runtimeCapabilities.value = null
                    _isModelReady.value = false
                }
            } finally {
                if (myGen == initGeneration) {
                    _isInitializing.value = modelLoadController.isInitializing
                    initJob = null
                }
            }
        }
    }

    private fun publishRuntimeCapabilities() {
        val initialized = inferenceManager.runtimeCapabilities
        _runtimeCapabilities.value = initialized
        currentResolvedThinkingCapability = initialized?.capabilities?.thinking ?: ThinkingCapability.Unknown
        currentSupportsThinkingMode = currentResolvedThinkingCapability in setOf(ThinkingCapability.Toggleable, ThinkingCapability.ModelControlled)
    }

    suspend fun ensureModelReady(
        onProgress: ((RequestProgress) -> Unit)? = null
    ): Boolean {
        if (_isModelReady.value && inferenceManager.isReady) { publishRuntimeCapabilities(); return true }
        val path = currentModelPath ?: return false
        val bundle = currentBundle
        val file = File(path)
        val currentBackendPref = appSettings.settings.value.llamaBackendPreference
        val isGguf = bundle?.isGguf ?: ChatRuntimePolicy.isGgufModel(file.name)
        val identity = if (bundle != null) {
            ModelLoadIdentity.fromBundle(
                bundle = bundle,
                runtimeKind = if (bundle.isGguf) "LlamaCpp" else "MediaPipe/LiteRT",
                backendPreference = currentBackendPref,
                threads = DeviceUtils.recommendedThreadCount(getApplication()),
                contextWindow = currentContextWindowTokens ?: 2048
            )
        } else if (isGguf) {
            val profile = GgufExecutionProfile.resolve(
                context = getApplication(),
                modelNameOrId = currentModelName.ifBlank { currentModelId.ifBlank { file.nameWithoutExtension } },
                modelSize = currentModelSize,
                customContextTokens = currentContextWindowTokens,
                backendPreference = currentBackendPref
            )
            profile.toIdentity(file, "LlamaCpp", currentBackendPref)
        } else {
            ModelLoadIdentity.fromFile(
                file = file,
                runtimeKind = "MediaPipe/LiteRT",
                backendPreference = currentBackendPref,
                threads = DeviceUtils.recommendedThreadCount(getApplication()),
                contextWindow = currentContextWindowTokens ?: 2048
            )
        }
        val expectedGeneration = initGeneration
        onProgress?.invoke(RequestProgress.LoadingModel)
        return try {
            modelLoadController.loadOrAttach(
                identity = identity,
                scope = viewModelScope,
                cancellationAction = { inferenceManager.cancelGeneration() }
            ) { operationId, operationControlId, updateProgress ->
                releaseJob?.join()
                updateProgress(ModelLoadStage.WaitingForRuntime, null, null)
                ModelRuntimeCoordinator.withRuntime(ModelRuntimeOwner.Chat) {
                    ioMutex.withLock {
                        if (expectedGeneration != initGeneration || currentModelPath != path) throw CancellationException("Superseded on-demand load")
                        inferenceManager.initialize(
                            modelPath = path,
                            modelId = currentModelId,
                            modelName = currentModelName,
                            modelSize = currentModelSize,
                            supportsThinkingMode = currentSupportsThinkingMode,
                            allowUnsafeOverride = currentAllowUnsafeOverride,
                            contextWindowTokens = currentContextWindowTokens,
                            operationControlId = operationControlId,
                            onModelProgress = updateProgress,
                            onProgress = onProgress,
                            bundle = bundle
                        )
                        if (expectedGeneration != initGeneration || currentModelPath != path) {
                            inferenceManager.close()
                            throw CancellationException("Superseded on-demand load")
                        }
                        if (!inferenceManager.isReady) {
                            inferenceManager.close()
                            throw IllegalStateException("Manager unavailable after on-demand load")
                        }
                        publishRuntimeCapabilities()
                        lastLoadedBackendPreference = currentBackendPref
                        resetRuntimeConversationBeforeNextSend = false
                        lastRuntimeThinkingMode = null
                        withContext(Dispatchers.Main.immediate) {
                            _activeBackend.value = inferenceManager.activeBackendDisplayName
                            _isModelReady.value = inferenceManager.isReady
                        }
                        ModelActualConfiguration(
                            backend = inferenceManager.activeBackendDisplayName,
                            contextLength = inferenceManager.runtimeCapabilities?.initializedContextTokens ?: 2048
                        )
                    }
                }
            }
            if (expectedGeneration != initGeneration || currentModelPath != path) throw CancellationException("Superseded on-demand load")
            publishRuntimeCapabilities()
            _isModelReady.value = inferenceManager.isReady
            Log.i("ChatViewModel", "Model ready on-demand on ${inferenceManager.activeBackendDisplayName}: $path")
            _isModelReady.value
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            val msg = t.message ?: "Unknown error during model load"
            Log.e("ChatViewModel", "Model on-demand load failed", t)
            _error.value = msg
            false
        }
    }

    fun sendMessage(
        text: String,
        documentUri: Uri? = null,
        documentName: String? = null,
        documentPath: String? = null
    ): Boolean {
        if (_isDeletingHistory.value || _isGenerating.value || generationJob?.isActive == true) return false
        if (text.isBlank() && documentName.isNullOrBlank() && documentUri == null) return false
        val path = currentModelPath
        if (path == null) {
            _error.value = "Choose and initialise a chat model first."
            return false
        }
        if (documentUri != null || !documentPath.isNullOrBlank()) {
            val fileName = documentName ?: "document"
            val isImageOrAudio = listOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp", ".mp3", ".wav", ".m4a", ".aac", ".ogg")
                .any { fileName.endsWith(it, ignoreCase = true) }
            val isMultimodal = ChatRuntimePolicy.supportsAttachments(path, inferenceManager.runtimeCapabilities?.capabilities ?: currentBundle?.capabilities)
            if (isImageOrAudio && !isMultimodal) {
                _error.value = "The selected model is a text-only model. Image or audio input requires a multimodal model from the Models tab."
                return false
            }
        }
        autoUnloadJob?.cancel()
        val userText = text.trim()
        ensureCurrentSession(userText, documentName)

        val userMsgId = UUID.randomUUID().toString()
        val userMessage = ChatMessage(userText, true, id = userMsgId, documentName = documentName, documentPath = documentPath)
        messages.add(userMessage)

        val assistantIndex = messages.size
        val assistantMsgId = UUID.randomUUID().toString()
        val initialProgress = if (!_isModelReady.value) RequestProgress.LoadingModel else RequestProgress.PreparingInput
        val initialPlaceholder = if (!_isModelReady.value) "Loading model…" else "Preparing input…"
        val requestId = UUID.randomUUID().toString()
        val assistantMessage = ChatMessage(
            text = initialPlaceholder,
            isUser = false,
            id = assistantMsgId,
            userMessageId = userMsgId,
            progress = initialProgress,
            requestId = requestId,
            attemptId = 1
        )
        messages.add(assistantMessage)
        saveCurrentSession()

        val targetSessionId = _currentSessionId.value
        startChatGeneration(
            userText = userText,
            userMsgId = userMsgId,
            documentUri = documentUri,
            documentName = documentName,
            documentPath = documentPath,
            requestId = requestId,
            attemptId = 1,
            assistantIndex = assistantIndex,
            targetSessionId = targetSessionId
        )
        return true
    }

    private fun startChatGeneration(
        userText: String,
        userMsgId: String,
        documentUri: Uri? = null,
        documentName: String? = null,
        documentPath: String? = null,
        requestId: String,
        attemptId: Int,
        assistantIndex: Int,
        targetSessionId: String?,
        isRetry: Boolean = false
    ) {
        val timing = InferenceTiming()
        _isGenerating.value = true
        autoUnloadJob?.cancel()
        val savedDocument = messages.lastOrNull { it.isUser && !it.documentPath.isNullOrBlank() }
        val inputUri = documentUri ?: documentPath?.let { Uri.fromFile(File(it)) } ?: savedDocument?.documentPath?.let { Uri.fromFile(File(it)) }
        val inputName = documentName ?: savedDocument?.documentName ?: "document"
        val preparation = inputUri?.let { documentPreparation.take(it, inputName) }

        timing.recordAcknowledged()
        val requestThinkingPreference = _thinkingMode.value
        val capability = currentResolvedThinkingCapability.takeIf { it != ThinkingCapability.Unknown } ?: currentBundle?.capabilities?.thinking ?: ThinkingCapability.Unknown
        var thinkingModeForRequest = ThinkingModeConfig.resolveRequestThinking(capability, requestThinkingPreference)
        activeRequestId = requestId
        val reqModelEpoch = currentModelEpoch
        val assistantMessageId = messages[assistantIndex].id
        fun isCurrentRequest(): Boolean = activeRequestId == requestId && currentModelEpoch == reqModelEpoch &&
            _currentSessionId.value == targetSessionId &&
            assistantIndex < messages.size && messages[assistantIndex].id == assistantMessageId

        generationJob = viewModelScope.launch {
            _isGenerating.value = true
            _error.value = null
            var preparedInput: PreparedAttachment? = null
            var documentForIndex: PreparedAttachment? = null
            val latestResponse = java.util.concurrent.atomic.AtomicReference("")
            val latestStructuredSnapshot = java.util.concurrent.atomic.AtomicReference<com.shounak.localmeshai.ai.StructuredResponseSnapshot?>(null)
            val liveUpdates = Channel<com.shounak.localmeshai.ai.StructuredResponseSnapshot>(Channel.CONFLATED)
            val liveUpdateJob = launch(Dispatchers.Main.immediate) {
                if (!isCurrentRequest()) return@launch
                liveUpdates.receiveAsFlow().collect { snap ->
                    if (activeRequestId == requestId && currentModelEpoch == reqModelEpoch && _currentSessionId.value == targetSessionId && assistantIndex < messages.size && messages[assistantIndex].terminalStatus == null) {
                        if (snap.answerText.isNotBlank() || snap.reasoningText.isNotBlank()) timing.recordFirstVisibleText()
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

            var capturedMetrics: InferenceMetrics? = null
            try {
                timing.startPreparation()
                var resolvedDocName = documentName
                var resolvedDocPath = documentPath
                var attachedContext = ""
                if (preparation != null) {
                    val prepared = withTimeoutOrNull(60_000L) { preparation.await() }
                        ?: throw IllegalStateException("Reading the attached document timed out. Retry or attach a smaller document.")
                    preparedInput = prepared
                    val durable = withContext(Dispatchers.IO) {
                        prepared.persistInto(
                            attachmentStore = attachmentStoreForChat,
                            chatKind = com.shounak.localmeshai.data.ChatKind.Text,
                            sessionId = targetSessionId ?: requestId
                        )
                    }
                    documentPreparation.remember(durable)
                    documentForIndex = if (documentUri != null) durable else null
                    resolvedDocPath = durable.durableFile?.absolutePath
                    resolvedDocName = durable.name
                    withContext(Dispatchers.Main.immediate) {
                        if (!isCurrentRequest()) return@withContext
                        if (activeRequestId == requestId && _currentSessionId.value == targetSessionId) {
                            val userIdx = assistantIndex - 1
                            messages[userIdx] = messages[userIdx].copy(
                                documentName = if (documentUri != null) resolvedDocName else messages[userIdx].documentName,
                                documentPath = if (documentUri != null) resolvedDocPath else messages[userIdx].documentPath)
                        }
                    }
                    require(!AttachedDocumentContext.isExtractionFailure(prepared.extractedText.orEmpty())) {
                        prepared.extractedText?.take(240) ?: "Could not extract the attached document"
                    }
                    attachedContext = withContext(Dispatchers.Default) {
                        AttachedDocumentContext.select(durable.extractedText.orEmpty(), userText, 12_000)
                    }
                }
                if (activeRequestId != requestId || currentModelEpoch != reqModelEpoch) {
                    throw CancellationException("Request configuration changed")
                }

                if (currentModelId.isNotBlank() && (com.shounak.localmeshai.utils.ModelRemovalCoordinator.isReserved(currentModelId) || com.shounak.localmeshai.utils.ModelRemovalCoordinator.isModelDeletionPendingOrCompleted(currentModelId))) {
                    throw IllegalStateException("Model is pending deletion or has been removed.")
                }

                if (!_isModelReady.value) {
                    timing.startLoading()
                    withContext(Dispatchers.Main.immediate) {
                        if (!isCurrentRequest()) return@withContext
                        if (_currentSessionId.value == targetSessionId && assistantIndex < messages.size) {
                            messages[assistantIndex] = messages[assistantIndex].copy(
                                text = "Loading model…",
                                progress = RequestProgress.LoadingModel
                            )
                        }
                    }
                    releaseJob?.join()
                    val loaded = ensureModelReady { progress ->
                        launch(Dispatchers.Main.immediate) {
                            if (!isCurrentRequest()) return@launch
                            if (_currentSessionId.value == targetSessionId && assistantIndex < messages.size) {
                                messages[assistantIndex] = messages[assistantIndex].copy(
                                    text = "Loading model…",
                                    progress = progress
                                )
                            }
                        }
                    }
                    timing.endLoading()
                    if (!loaded || !_isModelReady.value) {
                        throw IllegalStateException(_error.value ?: "Failed to load model")
                    }
                }

                thinkingModeForRequest = ThinkingModeConfig.resolveRequestThinking(currentResolvedThinkingCapability, requestThinkingPreference)

                var effectivePromptText = if (attachedContext.isNotBlank()) {
                    "$userText\n\n--- ATTACHED DOCUMENT: $resolvedDocName ---\n$attachedContext\n--- END ATTACHED DOCUMENT ---"
                } else userText
                var ragPassages: List<com.shounak.localmeshai.features.RagPassage> = emptyList()
                var ragSources: List<String> = emptyList()
                var ragChunkCount = 0
                var ragTopMatchPct = 0

                var targetDocId = pendingRagDocumentId ?: currentSessionDocumentId
                pendingRagDocumentId = null

                if (targetDocId == null) {
                    val activeSession = _currentSessionId.value?.let { id ->
                        chatSessions.firstOrNull { it.id == id }
                    }
                    targetDocId = activeSession?.documentId
                        ?: activeSession?.messages?.firstNotNullOfOrNull { it.documentName }?.let { docName ->
                            ragManager.indexedDocuments.firstOrNull { it.documentName.equals(docName, ignoreCase = true) }?.documentId
                        }
                        ?: messages.firstNotNullOfOrNull { it.documentName }?.let { docName ->
                            ragManager.indexedDocuments.firstOrNull { it.documentName.equals(docName, ignoreCase = true) }?.documentId
                        }
                        ?: if (_isRagActive.value) ragManager.findMatchingDocumentForQuery(userText)?.documentId else null
                }

                val shouldQueryRag = attachedContext.isBlank() && ChatRuntimePolicy.shouldRetrieveDocuments(
                    _isRagActive.value, targetDocId, ragManager.totalIndexedChunks
                )
                if (shouldQueryRag) {
                    val ragSettings = appSettings.settings.value
                    val retrieval = withTimeoutOrNull(10_000L) { ragManager.retrieve(
                        query = userText,
                        topK = ragSettings.ragTopK,
                        minScore = ragSettings.ragMinSimilarity,
                        preferredDocumentId = targetDocId,
                        allowGlobalSearch = ragSettings.enableRag
                    ) } ?: throw IllegalStateException("Document search timed out. Retry or turn off Document search for this chat.")
                    if (retrieval.hasContext) {
                        effectivePromptText = ragManager.buildAugmentedPrompt(userText, retrieval)
                        ragPassages = retrieval.matches.map { com.shounak.localmeshai.features.RagPassage(it.chunk.documentId, it.chunk.documentName, it.chunk.chunkIndex, it.chunk.text) }
                        ragSources = retrieval.sourceNames
                        ragChunkCount = retrieval.matches.size
                        ragTopMatchPct = (retrieval.topMatchScore * 100f).toInt()

                        val matchedDocId = retrieval.matches.firstOrNull()?.chunk?.documentId
                        if (matchedDocId != null) {
                            currentSessionDocumentId = matchedDocId
                        }
                    }
                }

                val memoryContext = (if (!_temporaryChat.value && _isPersistentMemoryActive.value) {
                    withContext(Dispatchers.Default) { memoryManager.formatMemoryContext(query = effectivePromptText) }
                } else {
                    ""
                }).let { memory ->
                    listOf(com.shounak.localmeshai.features.WorkspacePreferences.get(getApplication()).profile(currentModelId).systemPrompt, memory)
                        .filter { it.isNotBlank() }.joinToString("\n\n")
                }
                val modeChangedInStatefulThinkingRuntime =
                    lastRuntimeThinkingMode?.let { it != thinkingModeForRequest } == true &&
                        inferenceManager.needsResetWhenThinkingModeChanges()
                val shouldResetRuntimeConversation =
                    resetRuntimeConversationBeforeNextSend ||
                        inferenceManager.needsToolSettingsRefresh ||
                        modeChangedInStatefulThinkingRuntime ||
                        isRetry

                val isWarmLiteRt = inferenceManager.usesStatefulHistory &&
                    !shouldResetRuntimeConversation &&
                    inferenceManager.hasActiveStatefulConversation

                val historySnapshot = if (isWarmLiteRt) {
                    emptyList()
                } else if (isRetry && assistantIndex in messages.indices) {
                    messages.take(assistantIndex)
                } else {
                    messages.toList()
                }
                val (prompt, structuredMessages) = withContext(Dispatchers.Default) {
                    if (inferenceManager.usesStructuredHistory) {
                        "" to buildStructuredHistory(effectivePromptText, memoryContext,
                            thinkingMode = thinkingModeForRequest, history = historySnapshot)
                    } else if (isWarmLiteRt) {
                        effectivePromptText to null
                    } else {
                        buildPrompt(effectivePromptText, memoryContext, thinkingMode = thinkingModeForRequest, history = historySnapshot) to null
                    }
                }

                timing.endPreparation()
                timing.startInference()

                launch(Dispatchers.Main.immediate) {
                        if (!isCurrentRequest()) return@launch
                    if (_currentSessionId.value == targetSessionId && assistantIndex < messages.size) {
                        messages[assistantIndex] = messages[assistantIndex].copy(
                            text = "Processing prompt…",
                            progress = RequestProgress.ProcessingPrompt
                        )
                    }
                }

                releaseJob?.join()
                timing.startQueue()
                val execResult = ModelRuntimeCoordinator.withRuntime(ModelRuntimeOwner.Chat) {
                  ioMutex.withLock {
                    timing.endQueue()
                    if (activeRequestId != requestId || currentModelEpoch != reqModelEpoch) {
                        throw CancellationException("Request configuration changed")
                    }
                    val resetForRequest = shouldResetRuntimeConversation || inferenceManager.needsToolSettingsRefresh
                    var lockedPrompt = prompt
                    var lockedMessages = structuredMessages
                    if (resetForRequest && !shouldResetRuntimeConversation) {
                        val history = if (isRetry) messages.take(assistantIndex) else messages.toList()
                        val rebuilt = withContext(Dispatchers.Default) {
                            if (inferenceManager.usesStructuredHistory) "" to buildStructuredHistory(effectivePromptText, memoryContext, thinkingModeForRequest, history)
                            else buildPrompt(effectivePromptText, memoryContext, thinkingModeForRequest, history) to null
                        }
                        lockedPrompt = rebuilt.first
                        lockedMessages = rebuilt.second
                    }
                    if (resetForRequest) {
                        inferenceManager.resetConversation(noThinking = !thinkingModeForRequest)
                        resetRuntimeConversationBeforeNextSend = false
                    }
                    inferenceManager.generateResponseStreaming(
                        prompt = lockedPrompt,
                        rawUserText = effectivePromptText,
                        memoryContext = memoryContext,
                        structuredMessages = lockedMessages,
                        restoreStatefulHistory = resetForRequest,
                        thinkingMode = thinkingModeForRequest,
                        requestId = requestId,
                        sessionId = targetSessionId ?: "",
                        timing = timing,
                        isRetry = isRetry,
                        onProgress = { progress ->
                            if (activeRequestId == requestId && currentModelEpoch == reqModelEpoch) {
                                launch(Dispatchers.Main.immediate) {
                        if (!isCurrentRequest()) return@launch
                                    if (_currentSessionId.value == targetSessionId && assistantIndex < messages.size) {
                                        messages[assistantIndex] = messages[assistantIndex].copy(progress = progress)
                                    }
                                }
                            }
                        },
                        onSnapshot = { snap ->
                            if (activeRequestId == requestId && currentModelEpoch == reqModelEpoch) {
                                latestStructuredSnapshot.set(snap)
                                liveUpdates.trySend(snap)
                            }
                        }
                    ) { partial ->
                        if (activeRequestId == requestId && currentModelEpoch == reqModelEpoch) {
                            latestResponse.set(partial)
                        }
                    }
                }
                }
                if (execResult.status != RequestTerminalStatus.Completed) resetRuntimeConversationBeforeNextSend = true
                capturedMetrics = execResult.metrics
                liveUpdates.close()
                liveUpdateJob.join()
                if (execResult.status == RequestTerminalStatus.Completed && !_temporaryChat.value && appSettings.settings.value.enableRag) {
                    documentForIndex?.let { document ->
                        viewModelScope.launch(Dispatchers.Default) {
                            val indexed = ragManager.ingestText(document.name, document.extractedText.orEmpty())
                            if (indexed.success) document.durableFile?.let { original ->
                                try { ragManager.retainOriginalDocument(indexed.documentId, document.name, original) }
                                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                catch (failure: Exception) { android.util.Log.w("RagPreview", "Could not retain original upload", failure) }
                            }
                            if (indexed.success && activeRequestId == requestId && _currentSessionId.value == targetSessionId) {
                                currentSessionDocumentId = indexed.documentId
                                refreshRagDocuments()
                            }
                        }
                    }
                }
                lastRuntimeThinkingMode = thinkingModeForRequest
                _activeBackend.value = inferenceManager.activeBackendDisplayName

                if (activeRequestId == requestId && currentModelEpoch == reqModelEpoch) {
                    withContext(Dispatchers.Main.immediate) {
                        if (!isCurrentRequest()) return@withContext
                        if (_currentSessionId.value == targetSessionId && assistantIndex < messages.size) {
                            val current = messages[assistantIndex].text
                            val snap = latestStructuredSnapshot.get()
                            val isErrorResponse = execResult.status == RequestTerminalStatus.Failed ||
                                !execResult.error.isNullOrBlank() ||
                                execResult.error == GENERATION_FAILURE_TEXT

                            val finalResponse = when (execResult.status) {
                                RequestTerminalStatus.Stopped -> {
                                    execResult.text.ifBlank {
                                        snap?.answerText?.trim()?.takeUnless { it in PLACEHOLDER_TEXTS }.orEmpty()
                                    }
                                }
                                RequestTerminalStatus.Failed -> {
                                    execResult.text.ifBlank {
                                        snap?.answerText?.trim()?.takeUnless { it in PLACEHOLDER_TEXTS }.orEmpty()
                                    }
                                }
                                RequestTerminalStatus.Completed -> {
                                    resolveFinalAssistantText(
                                        response = execResult.text,
                                        current = current,
                                        thinkingMode = thinkingModeForRequest,
                                        userText = userText,
                                        responseFormatVersion = 1
                                    )
                                }
                            }
                            val finalReasoningText = execResult.reasoningText
                                ?: snap?.reasoningText?.trim()?.takeIf { it.isNotBlank() }

                            val errorMessageToDisplay = if (isErrorResponse) {
                                execResult.error ?: "Generation failed."
                            } else null

                            val currentMessage = messages[assistantIndex]
                            messages[assistantIndex] = currentMessage.copy(
                                text = finalResponse,
                                toolUses = inferenceManager.toolsUsedForRequest(requestId),
                                errorMessage = errorMessageToDisplay,
                                ragSources = if (isErrorResponse) emptyList() else ragSources,
                                ragPassages = if (isErrorResponse) emptyList() else ragPassages,
                                ragChunkCount = if (isErrorResponse) 0 else ragChunkCount,
                                ragTopMatchPct = if (isErrorResponse) 0 else ragTopMatchPct,
                                progress = null,
                                terminalStatus = execResult.status,
                                isThinkingActive = false,
                                metrics = execResult.metrics?.copy(firstVisibleTextLatencyMs = timing.buildMetrics(requestId, targetSessionId ?: "").firstVisibleTextLatencyMs),
                                requestId = requestId,
                                attemptId = attemptId,
                                reasoningText = finalReasoningText,
                                userMessageId = userMsgId,
                                emptyReason = execResult.emptyReason,
                                responseFormatVersion = 1
                            )
                            saveCurrentSession()

                            if (execResult.status == RequestTerminalStatus.Completed && !isErrorResponse && finalResponse.isNotBlank()) {
                                if (!_temporaryChat.value && _isPersistentMemoryActive.value && appSettings.settings.value.autoExtractMemories) {
                                    val candidate = withContext(Dispatchers.Default) { memoryManager.extractMemoryCandidate(userText) }
                                    if (candidate != null) {
                                        val added = withContext(Dispatchers.IO) { memoryManager.addMemory(candidate.first, candidate.second) }
                                        if (added != null) {
                                            refreshMemories()
                                            _memoryFeedbackEvent.tryEmit("Remembered: ${candidate.first}")
                                            // The fact is already in this turn; keep native history warm.
                                            // New conversations seed all saved memories.
                                        }
                                    }
                                }
                            }
                        }
                    }
                    _lastInferenceTime.value = execResult.metrics?.decodeDurationMs ?: 0L
                    _tokensPerSecond.value = execResult.metrics?.decodeThroughputTokPerSec?.toFloat() ?: 0f
                }
            } catch (exception: CancellationException) {
                Log.i("ChatViewModel", "Inference stopped", exception)
                resetRuntimeConversationBeforeNextSend = true
                if (activeRequestId == requestId && currentModelEpoch == reqModelEpoch && _currentSessionId.value == targetSessionId && assistantIndex < messages.size) {
                    withContext(NonCancellable + Dispatchers.Main.immediate) {
                        if (!isCurrentRequest()) return@withContext
                        val snap = latestStructuredSnapshot.get()
                        val currentText = messages[assistantIndex].text.takeUnless { it in PLACEHOLDER_TEXTS }.orEmpty()
                        val finalAnswer = snap?.answerText?.trim()?.takeUnless { it in PLACEHOLDER_TEXTS }
                            ?: currentText
                        val finalReasoning = snap?.reasoningText?.trim()?.takeIf { it.isNotBlank() }
                            ?: messages[assistantIndex].reasoningText?.trim()?.takeIf { it.isNotBlank() }
                        messages[assistantIndex] = messages[assistantIndex].copy(
                            text = finalAnswer,
                            toolUses = inferenceManager.toolsUsedForRequest(requestId),
                            reasoningText = finalReasoning,
                            isThinkingActive = false,
                            progress = null,
                            terminalStatus = RequestTerminalStatus.Stopped,
                            requestId = requestId,
                            attemptId = attemptId,
                            userMessageId = userMsgId,
                            responseFormatVersion = 1
                        )
                        saveCurrentSession()
                    }
                }
            } catch (t: Throwable) {
                if (_isStopping.value) {
                    Log.i("ChatViewModel", "Inference error ignored because stop was requested", t)
                    resetRuntimeConversationBeforeNextSend = true
                } else {
                    Log.e("ChatViewModel", "Inference error", t)
                    resetRuntimeConversationBeforeNextSend = true
                    val mId = currentModelId.ifBlank { "unknown_model" }
                    val mName = currentModelName.ifBlank { "Unknown Model" }
                    val backendPref = appSettings.settings.value.llamaBackendPreference
                    runCatching {
                        CrashReportManager.recordModelCrash(
                            context = getApplication(),
                            modelId = mId,
                            modelName = mName,
                            errorMessage = t.message ?: "Model inference failure",
                            throwable = t,
                            runtimeInfo = "Chat Session: $targetSessionId | Backend: $backendPref"
                        )
                    }
                    if (_currentSessionId.value == targetSessionId && assistantIndex < messages.size) {
                        launch(Dispatchers.Main.immediate) {
                            if (!isCurrentRequest()) return@launch
                            val snap = latestStructuredSnapshot.get()
                            val currentText = messages[assistantIndex].text.takeUnless { it in PLACEHOLDER_TEXTS }.orEmpty()
                            val partialAnswer = snap?.answerText?.trim()?.takeUnless { it in PLACEHOLDER_TEXTS }
                                ?: currentText
                            val partialReasoning = snap?.reasoningText?.trim()?.takeIf { it.isNotBlank() }
                                ?: messages[assistantIndex].reasoningText?.trim()?.takeIf { it.isNotBlank() }
                            messages[assistantIndex] = messages[assistantIndex].copy(
                                text = partialAnswer,
                                toolUses = inferenceManager.toolsUsedForRequest(requestId),
                                reasoningText = partialReasoning,
                                isThinkingActive = false,
                                errorMessage = t.message ?: "Model inference failure",
                                progress = null,
                                terminalStatus = RequestTerminalStatus.Failed,
                                requestId = requestId,
                                attemptId = attemptId,
                                userMessageId = userMsgId,
                                responseFormatVersion = 1
                            )
                            saveCurrentSession()
                        }
                    }
                }
            } finally {
                val finalMetricsStr = capturedMetrics?.let { m ->
                    timing.formatMetricsSummary(m)
                } ?: timing.detailedSummary(requestId, targetSessionId ?: "")
                Log.i("ChatLatency", "backend=${inferenceManager.activeBackendDisplayName} $finalMetricsStr")
                liveUpdates.close()
                liveUpdateJob.cancel()
                preparedInput?.close()
                documentPreparation.release(preparation)
                if (activeRequestId == requestId) {
                    generationJob = null
                    _isGenerating.value = false
                    _isStopping.value = false
                    resetAutoUnloadTimer()
                }
            }
        }
    }

    fun stopGenerating() {
        val wasGenerating = _isGenerating.value
        val wasInitializing = _isInitializing.value
        if (!wasGenerating && !wasInitializing) return
        resetRuntimeConversationBeforeNextSend = true
        inferenceManager.cancelGeneration()
        if (wasInitializing) {
            val opId = modelLoadController.activeOperationId
            if (opId != null) {
                viewModelScope.launch {
                    modelLoadController.cancelActiveOperation(targetOperationId = opId, reason = "Stopped by user")
                }
            }
            if (!wasGenerating) {
                initGeneration++
                initJob?.cancel()
                initJob = null
                _isInitializing.value = false
                _isStopping.value = false
                return
            }
        }
        if (!wasGenerating) return
        _isStopping.value = true
        val jobToCancel = generationJob
        generationJob = null
        jobToCancel?.cancel()

        // Immediately update UI state on the main thread
        val assistantIndex = messages.indexOfLast { !it.isUser }
        if (assistantIndex >= 0 && assistantIndex < messages.size) {
            val targetMsg = messages[assistantIndex]
            if (activeRequestId == null || targetMsg.requestId == activeRequestId) {
                val current = targetMsg.text
                val finalText = if (current in PLACEHOLDER_TEXTS || current.isBlank()) {
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
                saveCurrentSession()
            }
        }
    }

    fun uninitializeModel() {
        releaseModel(clearCoordinator = true, clearSelection = true)
    }

    fun startNewChat() {
        if (_temporaryChat.value) {
            val oldId = _currentSessionId.value
            val running = generationJob
            viewModelScope.launch(Dispatchers.IO) {
                running?.join()
                oldId?.let { com.shounak.localmeshai.features.TemporaryChatFiles.discard(temporaryAttachments.getSessionAttachmentsDir(com.shounak.localmeshai.data.ChatKind.Text, it)) }
            }
        }

        if (_isGenerating.value) {
            stopGenerating()
        }
        saveCurrentSession()
        messages.clear()
        _currentSessionId.value = null
        _pendingSessionId.value = null
        pendingRagDocumentId = null
        currentSessionDocumentId = null
        resetRuntimeConversationBeforeNextSend = true
        lastRuntimeThinkingMode = null
        _lastInferenceTime.value = 0L
        _tokensPerSecond.value = 0f
        _error.value = null
    }

    fun selectChatSession(sessionId: String) {
        val session = chatSessions.firstOrNull { it.id == sessionId } ?: return
        if (!conversationPrivacy.openSavedChat(
                busy = generationJob?.isActive == true || _isGenerating.value || _isStopping.value,
                discardTemporary = ::startNewChat)) {
            _error.value = "Stop the current temporary request before switching chats."
            return
        }
        if (sessionId == _currentSessionId.value) return
        if (_isGenerating.value) {
            stopGenerating()
        }
        saveCurrentSession()
        messages.clear()
        messages.addAll(session.messages.map { msg ->
            val resolvedDocPath = if (!msg.documentPath.isNullOrBlank() && File(msg.documentPath).exists()) {
                msg.documentPath
            } else if (!msg.documentName.isNullOrBlank()) {
                com.shounak.localmeshai.utils.AttachmentViewerUtils.findAttachmentFile(getApplication(), msg.documentName)?.absolutePath
            } else {
                null
            }
            if (resolvedDocPath != msg.documentPath) {
                msg.copy(documentPath = resolvedDocPath)
            } else {
                msg
            }
        })
        currentSessionDocumentId = session.documentId
            ?: session.messages.firstNotNullOfOrNull { it.documentName }?.let { docName ->
                ragManager.indexedDocuments.firstOrNull { it.documentName.equals(docName, ignoreCase = true) }?.documentId
            }
        resetRuntimeConversationBeforeNextSend = true
        lastRuntimeThinkingMode = null
        _lastInferenceTime.value = 0L
        _tokensPerSecond.value = 0f
        _error.value = null
        if (_isModelReady.value) {
            // Model ready: open the session immediately (unchanged behaviour).
            _currentSessionId.value = session.id
            _pendingSessionId.value = null
        } else {
            // No model yet: load messages into memory so Share/hasMessages work,
            // but keep currentSessionId null and queue the session for later.
            // Rapid re-selects: last sessionId wins on _pendingSessionId.
            _pendingSessionId.value = session.id
            _currentSessionId.value = null
        }
    }

    /**
     * Promotes a deferred history selection once a model is ready.
     * Messages are already loaded; this only commits [currentSessionId].
     */
    fun commitPendingSession() {
        val pending = _pendingSessionId.value ?: return
        val pendingSession = chatSessions.firstOrNull { it.id == pending }
        if (pendingSession == null) {
            _pendingSessionId.value = null
            return
        }
        messages.clear()
        messages.addAll(pendingSession.messages.map { msg ->
            val resolvedDocPath = if (!msg.documentPath.isNullOrBlank() && File(msg.documentPath).exists()) {
                msg.documentPath
            } else if (!msg.documentName.isNullOrBlank()) {
                com.shounak.localmeshai.utils.AttachmentViewerUtils.findAttachmentFile(getApplication(), msg.documentName)?.absolutePath
            } else {
                null
            }
            if (resolvedDocPath != msg.documentPath) {
                msg.copy(documentPath = resolvedDocPath)
            } else {
                msg
            }
        })
        currentSessionDocumentId = pendingSession.documentId
            ?: pendingSession.messages.firstNotNullOfOrNull { it.documentName }?.let { docName ->
                ragManager.indexedDocuments.firstOrNull { it.documentName.equals(docName, ignoreCase = true) }?.documentId
            }
        _currentSessionId.value = pending
        _pendingSessionId.value = null
    }

    fun deleteChatSession(sessionId: String) {
        deleteSessions(listOf(sessionId))
    }

    fun clearHistory() {
        deleteSessions(chatSessions.map { it.id })
    }

    private fun deleteSessions(sessionIds: List<String>) {
        if (_isDeletingHistory.value) return
        _isDeletingHistory.value = true
        val running = generationJob.takeIf { _currentSessionId.value in sessionIds }
        if (running != null) stopGenerating()
        viewModelScope.launch {
            try {
                running?.join()
                com.shounak.localmeshai.data.HistoryDeletionCoordinator(sessionRepository).deleteSessions(
                    kind = com.shounak.localmeshai.data.ChatKind.Text,
                    sessionIds = sessionIds,
                    onFailure = { _error.value = "Couldn't delete chat: $it" },
                    onCommitted = { sessionId, cleanupPending ->
                        // RAG documents belong to the shared knowledge base, not to this chat.
                        chatSessions.removeAll { it.id == sessionId }
                        val removingPending = _pendingSessionId.value == sessionId
                        if (removingPending) _pendingSessionId.value = null
                        if (_currentSessionId.value == sessionId || (removingPending && _currentSessionId.value == null)) {
                            messages.clear()
                            _currentSessionId.value = null
                            currentSessionDocumentId = null
                            resetRuntimeConversationBeforeNextSend = true
                            lastRuntimeThinkingMode = null
                            _lastInferenceTime.value = 0L
                            _tokensPerSecond.value = 0f
                        }
                        if (cleanupPending) {
                            _error.value = "Chat deleted. File cleanup is pending and will be retried."
                        }
                    }
                )
                persistSessions()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _error.value = "Couldn't finish deleting history: ${error.message}"
            } finally {
                _isDeletingHistory.value = false
            }
        }
    }

    private fun ensureCurrentSession(firstUserText: String, documentName: String? = null) {
        if (_temporaryChat.value) {
            if (_currentSessionId.value == null) _currentSessionId.value = UUID.randomUUID().toString()
            com.shounak.localmeshai.features.TemporaryChatFiles.retain(temporaryAttachments.getSessionAttachmentsDir(com.shounak.localmeshai.data.ChatKind.Text, _currentSessionId.value!!))
            return
        }

        val currentId = _currentSessionId.value
        val now = System.currentTimeMillis()
        if (currentId != null) {
            val index = chatSessions.indexOfFirst { it.id == currentId }
            if (index != -1) {
                val previous = chatSessions[index]
                val sessionDocId = previous.documentId
                    ?: currentSessionDocumentId
                    ?: previous.messages.firstNotNullOfOrNull { it.documentName }?.let { docName ->
                        ragManager.indexedDocuments.firstOrNull { it.documentName.equals(docName, ignoreCase = true) }?.documentId
                    }
                val updated = previous.copy(
                    updatedAt = now,
                    documentId = sessionDocId
                )
                if (currentSessionDocumentId == null && sessionDocId != null) {
                    currentSessionDocumentId = sessionDocId
                }
                chatSessions.removeAt(index)
                chatSessions.add(0, updated)
                return
            }
        }
        val title = if (firstUserText.isNotBlank()) {
            firstUserText.toChatTitle()
        } else {
            documentName?.let { "Document: $it" } ?: "Chat"
        }
        val session = ChatSession(
            id = currentId ?: UUID.randomUUID().toString(),
            title = title,
            messages = emptyList(),
            updatedAt = now,
            documentId = currentSessionDocumentId
        )
        _currentSessionId.value = session.id
        chatSessions.add(0, session)
    }

    private fun saveCurrentSession() {
        if (_temporaryChat.value) return

        val sessionId = _currentSessionId.value ?: return
        val index = chatSessions.indexOfFirst { it.id == sessionId }
        if (index == -1) return
        val currentMessages = messages.toList()
        if (currentMessages.isEmpty()) {
            chatSessions.removeAt(index)
            _currentSessionId.value = null
            persistSessions()
            return
        }

        val previous = chatSessions[index]
        val messagesChanged = previous.messages != currentMessages
        if (!messagesChanged && previous.updatedAt > 0L) {
            return
        }

        val firstUserMsg = currentMessages.firstOrNull { it.isUser }
        val titleText = firstUserMsg?.text?.takeIf { it.isNotBlank() }?.toChatTitle()
            ?: firstUserMsg?.documentName?.let { "Document: $it" }
            ?: previous.title
        val now = System.currentTimeMillis()
        val sessionDocId = previous.documentId
            ?: currentSessionDocumentId
            ?: currentMessages.firstNotNullOfOrNull { it.documentName }?.let { docName ->
                ragManager.indexedDocuments.firstOrNull { it.documentName.equals(docName, ignoreCase = true) }?.documentId
            }
        val updated = previous.copy(
            title = titleText,
            messages = currentMessages,
            updatedAt = if (messagesChanged || previous.updatedAt <= 0L) now else previous.updatedAt,
            documentId = sessionDocId
        )
        if (currentSessionDocumentId == null && sessionDocId != null) {
            currentSessionDocumentId = sessionDocId
        }
        chatSessions.removeAt(index)
        chatSessions.add(0, updated)
        persistSessions()
    }

    private suspend fun loadSessionsAsync() {
        val coordinator = com.shounak.localmeshai.data.StartupCoordinator.getInstance(getApplication())
        when (val res = sessionRepository.loadChatSessionsAsync()) {
            is com.shounak.localmeshai.utils.AtomicReadResult.Success -> {
                val sorted = res.data.sortedByDescending { it.updatedAt }
                withContext(Dispatchers.Main.immediate) {
                    chatSessions.clear()
                    chatSessions.addAll(sorted)
                }
                coordinator.updateChatStoreState(com.shounak.localmeshai.data.StoreLoadState.Ready)
            }
            is com.shounak.localmeshai.utils.AtomicReadResult.UnsupportedVersion -> {
                coordinator.updateChatStoreState(
                    com.shounak.localmeshai.data.StoreLoadState.RecoveryRequired(
                        "Chat history uses a newer schema version (${res.foundVersion} > ${res.maxSupportedVersion}). Update the app to view these sessions without data loss.",
                        res.file
                    )
                )
            }
            is com.shounak.localmeshai.utils.AtomicReadResult.Corrupt -> {
                coordinator.updateChatStoreState(
                    com.shounak.localmeshai.data.StoreLoadState.RecoveryRequired(
                        "Chat history file is corrupt: ${res.cause.message}. Preserved at ${res.backupFile?.name ?: "backup"}.",
                        res.file
                    )
                )
            }
            is com.shounak.localmeshai.utils.AtomicReadResult.Missing -> {
                coordinator.updateChatStoreState(com.shounak.localmeshai.data.StoreLoadState.Ready)
            }
        }
    }

    private fun loadSessions() {
        val loaded = sessionRepository.loadChatSessions()
        chatSessions.clear()
        chatSessions.addAll(loaded.sortedByDescending { it.updatedAt })
    }

    private fun persistSessions() {
        val state = com.shounak.localmeshai.data.StartupCoordinator.getInstance(getApplication()).chatStoreState.value
        if (state !is com.shounak.localmeshai.data.StoreLoadState.Ready) {
            return
        }
        sessionPersistenceChannel.trySend(chatSessions.toList())
    }

    private fun writeSessionsToStorage(sessions: List<ChatSession>) {
        if (!runCatching { sessionRepository.saveChatSessions(sessions) }.getOrDefault(false)) {
            _error.value = "Couldn't save chat history. Your conversation is still open; check available storage before closing the app."
        }
    }

    private fun buildStructuredHistory(
        latestUserText: String,
        memoryContext: String,
        thinkingMode: Boolean = true,
        history: List<ChatMessage> = messages.toList()
    ): List<Pair<String, String>> {
        val turns = mutableListOf<Pair<String, String>>()
        if (!thinkingMode) {
            val systemInstruction = if (memoryContext.isNotBlank()) {
                "$memoryContext\n\nProvide direct, concise answers. Do not output internal monologue, reasoning steps, or <think> tags."
            } else {
                "Provide direct, concise answers. Do not output internal monologue, reasoning steps, or <think> tags."
            }
            turns.add("system" to systemInstruction)
        } else if (memoryContext.isNotBlank()) {
            turns.add("system" to memoryContext)
        }
        val priorHistory = if (history.size >= 2 && !history.last().isUser) {
            history.dropLast(2)
        } else if (history.isNotEmpty() && history.last().isUser) {
            history.dropLast(1)
        } else {
            history
        }

        val validTurns = priorHistory.filter { message ->
            if (message.isUser) {
                true
            } else {
                message.terminalStatus != RequestTerminalStatus.Failed &&
                message.errorMessage == null &&
                message.text.isNotBlank() &&
                message.text !in PLACEHOLDER_TEXTS
            }
        }.takeLast(12)

        validTurns.forEach { message ->
            val cleanText = message.text.toPromptText(message.isUser, thinkingMode)
            if (cleanText.isNotBlank()) {
                val role = if (message.isUser) "user" else "assistant"
                turns.add(role to cleanText)
            }
        }
        turns.add("user" to latestUserText.trim())
        return turns
    }

    private fun buildPrompt(
        latestUserText: String,
        memoryContext: String,
        thinkingMode: Boolean = true,
        history: List<ChatMessage> = messages.toList()
    ): String {
        val memoryPrefix = if (memoryContext.isNotBlank()) "$memoryContext\n\n" else ""
        val priorHistory = if (history.size >= 2 && !history.last().isUser) {
            history.dropLast(2)
        } else if (history.isNotEmpty() && history.last().isUser) {
            history.dropLast(1)
        } else {
            history
        }

        val validTurns = priorHistory.filter { message ->
            if (message.isUser) {
                true
            } else {
                message.terminalStatus != RequestTerminalStatus.Failed &&
                message.errorMessage == null &&
                message.text.isNotBlank() &&
                message.text !in PLACEHOLDER_TEXTS
            }
        }.takeLast(12)

        val previousTurns = validTurns
            .mapNotNull { message ->
                val cleanText = message.text.toPromptText(message.isUser, thinkingMode)
                if (cleanText.isBlank()) {
                    null
                } else {
                    val speaker = if (message.isUser) "User" else "Assistant"
                    "$speaker: $cleanText"
                }
            }
            .joinToString(separator = "\n")
        return if (previousTurns.isBlank()) {
            "$memoryPrefix$latestUserText"
        } else {
            "$memoryPrefix$previousTurns\nUser: $latestUserText\nAssistant:"
        }
    }

    private fun String.toPromptText(isUser: Boolean, thinkingMode: Boolean = false): String {
        val visibleText = if (isUser) {
            trim()
        } else if (thinkingMode) {
            ThinkingTextUtils.normalizeFinalOutput(this).trim()
        } else {
            ThinkingTextUtils.parse(this, allowActiveThinking = false)
                .finalResponseText
                .trim()
        }
        val cleanText = if (isUser) visibleText else ModelOutputSanitizer.clean(visibleText)
        return cleanText
            .takeUnless { it in PLACEHOLDER_TEXTS }
            ?.takeUnless { !isUser && ModelResponseQuality.isGenericNonAnswer(it) }
            .orEmpty()
    }

    private fun estimateTokenCount(text: String): Int {
        return (text.length / 4).coerceAtLeast(1)
    }

    private fun String.toChatTitle(): String {
        return lineSequence()
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
            .trim()
            .take(48)
            .ifBlank { "New chat" }
    }

    override fun onCleared() {
        documentPreparation.cancel()
        saveCurrentSession()
        releaseModel(clearCoordinator = false, clearSelection = true)
        val temporaryId = _currentSessionId.value.takeIf { _temporaryChat.value }
        val token = coordinatorToken
        ModelRuntimeCoordinator.shutdownScope.launch {
            try {
                kotlinx.coroutines.withTimeout(10_000L) {
                    releaseJob?.join()
                }
                if (lastShutdownResult?.isSuccess == true && temporaryId != null) {
                    com.shounak.localmeshai.features.TemporaryChatFiles.discard(temporaryAttachments.getSessionAttachmentsDir(com.shounak.localmeshai.data.ChatKind.Text, temporaryId))
                }
            } finally {
                if (token != null) {
                    ModelRuntimeCoordinator.unregister(ModelRuntimeOwner.Chat, token)
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
        val jobToCancel = generationJob
        generationJob = null
        jobToCancel?.cancel()
        inferenceManager.cancelGeneration()
        initGeneration++
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
            val closeResult = ioMutex.withLock {
                val res = inferenceManager.close()
                AppCacheCleaner.clearAppCache(getApplication())
                res
            }
            lastShutdownResult = closeResult
            if (closeResult.isSuccess) {
                if (clearCoordinator) ModelRuntimeCoordinator.clear(ModelRuntimeOwner.Chat)
            }
            withContext(Dispatchers.Main.immediate) {
                if (closeResult.isSuccess) {
                    currentRequestedIdentity = null
                    currentLoadedIdentity = null
                    currentResolvedThinkingCapability = ThinkingCapability.Unknown
                    if (clearSelection) {
                        currentModelPath = null
                        currentBundle = null
                        currentModelId = ""
                        currentModelName = ""
                        currentModelSize = ""
                        currentAllowUnsafeOverride = false
                    }
                    _isInitializing.value = false
                    _isGenerating.value = false
                    _isStopping.value = false
                    _runtimeCapabilities.value = null
                    _isModelReady.value = false
                    modelLoadController.resetToUnloaded()
                } else {
                    _isInitializing.value = false
                    _isStopping.value = false
                    _isModelReady.value = inferenceManager.isReady
                    _error.value = "Failed to cleanly unload chat model: ${if (closeResult is com.shounak.localmeshai.ai.ShutdownResult.Failed) (closeResult.error.message ?: closeResult.error.toString()) else "Timeout"}"
                }
            }
        }
        lastLoadedBackendPreference = null
        _activeBackend.value = null
        resetRuntimeConversationBeforeNextSend = false
        lastRuntimeThinkingMode = null
    }

    private fun resolveFinalAssistantText(
        response: String,
        current: String,
        thinkingMode: Boolean,
        userText: String,
        responseFormatVersion: Int = 0
    ): String {
        if (responseFormatVersion >= 1) {
            val clean = ModelOutputSanitizer.clean(response).trim()
            return clean.ifBlank { current.takeUnless { it in PLACEHOLDER_TEXTS } ?: GENERATION_FAILURE_TEXT }
        }
        val clean = ThinkingTextUtils.extractFinalAnswerOnly(ModelOutputSanitizer.clean(response))
        return clean.ifBlank { current.takeUnless { it in PLACEHOLDER_TEXTS } ?: GENERATION_FAILURE_TEXT }
    }

    private fun String.isUsableAssistantText(): Boolean {
        val normalized = trim()
        return normalized.isNotBlank() &&
            normalized !in PLACEHOLDER_TEXTS
    }

    private companion object {
        const val KEY_SESSIONS = "sessions_json"
        const val GENERATION_FAILURE_TEXT =
            "I could not generate a response. Try asking again with a little more detail."
        /** UI/status text that must never be fed back into a future model prompt. */
        val PLACEHOLDER_TEXTS = setOf(
            "Generating…",
            "Generating...",
            "…",
            "Stopped.",
            "Reading input…",
            "Reading input...",
            "Loading model…",
            "Loading model...",
            "Preparing input…",
            "Preparing input...",
            "Validating backend…",
            "Validating backend...",
            "Processing prompt…",
            "Processing prompt...",
            GENERATION_FAILURE_TEXT
        )
    }
}
