package com.shounak.localmeshai.ui.viewmodels

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shounak.localmeshai.ai.ChatInferenceManager
import com.shounak.localmeshai.utils.InitCrashGuard
import com.shounak.localmeshai.utils.ModelOutputSanitizer
import com.shounak.localmeshai.utils.ModelResponseQuality
import com.shounak.localmeshai.utils.ModelRuntimeCoordinator
import com.shounak.localmeshai.utils.ModelRuntimeOwner
import com.shounak.localmeshai.utils.ThinkingTextUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.net.Uri
import com.shounak.localmeshai.rag.RagIngestionResult
import com.shounak.localmeshai.rag.RagManager
import com.shounak.localmeshai.rag.store.RagDocumentSummary
import com.shounak.localmeshai.utils.AppSettings
import com.shounak.localmeshai.memory.MemoryCategory
import com.shounak.localmeshai.memory.MemoryEntry
import com.shounak.localmeshai.memory.PersistentMemoryManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
    val documentPath: String? = null
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
    private val inferenceManager = ChatInferenceManager(application)
    private val historyPrefs = application.getSharedPreferences("chat_history", Context.MODE_PRIVATE)
    val messages = mutableStateListOf<ChatMessage>()
    val chatSessions = mutableStateListOf<ChatSession>()

    private val _lastInferenceTime = MutableStateFlow(0L)
    val lastInferenceTime = _lastInferenceTime.asStateFlow()

    private val _isInitializing = MutableStateFlow(false)
    val isInitializing = _isInitializing.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating = _isGenerating.asStateFlow()

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

    private val _indexedRagDocuments = MutableStateFlow(ragManager.indexedDocuments)
    val indexedRagDocuments = _indexedRagDocuments.asStateFlow()

    private val _ragIngestionStatus = MutableStateFlow<String?>(null)
    val ragIngestionStatus = _ragIngestionStatus.asStateFlow()

    val memoryManager: PersistentMemoryManager = PersistentMemoryManager.getInstance(application)

    private val _isPersistentMemoryActive = MutableStateFlow(appSettings.settings.value.enablePersistentMemory)
    val isPersistentMemoryActive = _isPersistentMemoryActive.asStateFlow()

    private val _memories = MutableStateFlow(memoryManager.getAllMemories())
    val memories = _memories.asStateFlow()

    private val _memoryFeedbackEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val memoryFeedbackEvent = _memoryFeedbackEvent.asSharedFlow()

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
    private var currentModelId: String = ""
    private var currentModelName: String = ""
    private var currentModelSize: String = ""
    private var currentContextWindowTokens: Int? = null
    private var currentAllowUnsafeOverride: Boolean = false
    private var lastLoadedBackendPreference: String? = null
    private var initGeneration: Int = 0
    private var initJob: Job? = null
    private var generationJob: Job? = null
    @Volatile private var resetRuntimeConversationBeforeNextSend = false
    @Volatile private var lastRuntimeThinkingMode: Boolean? = null
    private val ioMutex = Mutex()

    init {
        ModelRuntimeCoordinator.register(ModelRuntimeOwner.Chat) {
            releaseModel(clearCoordinator = false)
        }
        loadSessions()
        // On launch, check if the app was killed by a native crash during model init
        val crashMsg = InitCrashGuard.consumeLastCrashMessage(application)
        if (crashMsg != null) {
            messages.add(ChatMessage("⚠️ $crashMsg\n\nTry a MediaPipe (.task) model instead.", false))
        }
        // Promote any deferred history selection once a model finishes initialising.
        viewModelScope.launch {
            isModelReady.collect { ready ->
                if (ready) commitPendingSession()
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
                            contextWindowTokens = currentContextWindowTokens,
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
        val result = ragManager.ingestDocument(uri, fileName, mimeType)
        if (result.success) {
            appSettings.updateSettings { it.copy(enableRag = true) }
            pendingRagDocumentId = result.documentId
            currentSessionDocumentId = result.documentId
            val activeSessionId = _currentSessionId.value ?: UUID.randomUUID().toString().also {
                _currentSessionId.value = it
            }
            try {
                val sessionDir = getApplication<Application>().filesDir.resolve("chat_attachments").resolve(activeSessionId)
                sessionDir.mkdirs()
                val targetFile = sessionDir.resolve(fileName)
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
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
    }

    fun clearDraftText() {
        _draftText.value = ""
    }

    fun initModel(
        path: String,
        modelId: String = "",
        modelName: String = "",
        modelSize: String = "",
        contextWindowTokens: Int? = null,
        allowUnsafeOverride: Boolean = false,
        forceReload: Boolean = false
    ) {
        if (!forceReload || path != currentModelPath) {
            appSettings.updateSettings { it.copy(llamaBackendPreference = "AUTO") }
        }
        val currentBackendPref = appSettings.settings.value.llamaBackendPreference
        if (!forceReload && path == currentModelPath && _isModelReady.value && currentBackendPref == lastLoadedBackendPreference && ModelRuntimeCoordinator.isActive(ModelRuntimeOwner.Chat)) {
            currentContextWindowTokens = contextWindowTokens
            return
        }

        currentModelPath = path
        currentModelId = modelId
        currentModelName = modelName
        currentModelSize = modelSize
        currentContextWindowTokens = contextWindowTokens
        currentAllowUnsafeOverride = allowUnsafeOverride

        val myGen = ++initGeneration
        initJob?.cancel()
        generationJob?.cancel()
        inferenceManager.cancelGeneration()
        _isInitializing.value = true
        _isGenerating.value = false
        _isModelReady.value = false
        _error.value = null
        initJob = viewModelScope.launch(Dispatchers.IO) {
            ioMutex.withLock {
                if (myGen != initGeneration) return@withLock
                try {
                    ModelRuntimeCoordinator.activate(ModelRuntimeOwner.Chat)
                    inferenceManager.initialize(
                        modelPath = path,
                        modelId = modelId,
                        modelName = modelName,
                        modelSize = modelSize,
                        allowUnsafeOverride = allowUnsafeOverride,
                        contextWindowTokens = contextWindowTokens
                    )
                    if (myGen != initGeneration) {
                        inferenceManager.close()
                        return@withLock
                    }
                    currentModelPath = path
                    lastLoadedBackendPreference = currentBackendPref
                    resetRuntimeConversationBeforeNextSend = false
                    lastRuntimeThinkingMode = null
                    _activeBackend.value = inferenceManager.activeBackendDisplayName
                    _isModelReady.value = true
                    Log.i("ChatViewModel", "Model ready on ${inferenceManager.activeBackendDisplayName}: $path")
                } catch (t: Throwable) {
                    if (myGen != initGeneration) return@withLock
                    val msg = t.message ?: "Unknown error during model load"
                    Log.e("ChatViewModel", "Model init failed", t)
                    _error.value = msg
                    currentModelPath = null
                    lastLoadedBackendPreference = null
                    _activeBackend.value = null
                    _isModelReady.value = false
                    launch(Dispatchers.Main) {
                        messages.add(ChatMessage("⚠️ $msg", false))
                    }
                } finally {
                    if (myGen == initGeneration) {
                        _isInitializing.value = false
                        initJob = null
                    }
                }
            }
        }
    }

    fun sendMessage(text: String, documentName: String? = null, documentPath: String? = null) {
        if (_isGenerating.value) return
        if (text.isBlank() && documentName.isNullOrBlank()) return
        if (!_isModelReady.value) {
            _error.value = "Choose and initialise a chat model first."
            return
        }
        val userText = text.trim()
        ensureCurrentSession(userText, documentName)
        messages.add(ChatMessage(userText, true, documentName = documentName, documentPath = documentPath))
        val assistantIndex = messages.size
        messages.add(ChatMessage("Generating…", false))
        // Do NOT saveCurrentSession() here — the placeholder "Generating…" must
        // never be persisted. The session is saved only after a real response arrives.

        val targetSessionId = _currentSessionId.value
        val thinkingModeForRequest = _thinkingMode.value
        generationJob = viewModelScope.launch {
            _isGenerating.value = true
            _error.value = null
            val liveUpdates = Channel<String>(Channel.CONFLATED)
            val liveUpdateJob = launch(Dispatchers.Main.immediate) {
                var lastUiUpdateAt = 0L
                liveUpdates.receiveAsFlow().collect { partial ->
                    val now = System.currentTimeMillis()
                    val waitMs = 33L - (now - lastUiUpdateAt)
                    if (waitMs > 0L) delay(waitMs)
                    lastUiUpdateAt = System.currentTimeMillis()
                    if (_currentSessionId.value == targetSessionId && assistantIndex < messages.size) {
                        val current = messages[assistantIndex]
                        val cleanPartial = ModelOutputSanitizer.cleanAssistantText(partial, userText)
                        val visiblePartial = if (
                            ModelResponseQuality.shouldSuppressLivePartial(cleanPartial)
                        ) {
                            "Generating…"
                        } else {
                            cleanPartial.ifBlank { "\u2026" }
                        }
                        messages[assistantIndex] = current.copy(text = visiblePartial)
                        // Do NOT call saveCurrentSession() here — persisting JSON on every
                        // token would hammer SharedPreferences. The final save happens below.
                    }
                }
            }
            if (_isPersistentMemoryActive.value && appSettings.settings.value.autoExtractMemories) {
                val candidate = memoryManager.extractMemoryCandidate(userText)
                if (candidate != null) {
                    val added = memoryManager.addMemory(candidate.first, candidate.second)
                    if (added != null) {
                        refreshMemories()
                        _memoryFeedbackEvent.tryEmit("Remembered: ${candidate.first}")
                    }
                }
            }
            try {
                var effectivePromptText = userText
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
                        ?: ragManager.findMatchingDocumentForQuery(userText)?.documentId
                }

                val shouldQueryRag = ragManager.totalIndexedChunks > 0
                if (shouldQueryRag) {
                    val ragSettings = appSettings.settings.value
                    val retrieval = ragManager.retrieve(
                        query = userText,
                        topK = ragSettings.ragTopK,
                        minScore = ragSettings.ragMinSimilarity,
                        preferredDocumentId = targetDocId
                    )
                    if (retrieval.hasContext) {
                        effectivePromptText = ragManager.buildAugmentedPrompt(userText, retrieval)
                        ragSources = retrieval.sourceNames
                        ragChunkCount = retrieval.matches.size
                        ragTopMatchPct = (retrieval.topMatchScore * 100f).toInt()

                        val matchedDocId = retrieval.matches.firstOrNull()?.chunk?.documentId
                        if (matchedDocId != null) {
                            currentSessionDocumentId = matchedDocId
                        }
                    }
                }

                val memoryContext = if (_isPersistentMemoryActive.value) {
                    memoryManager.formatMemoryContext()
                } else {
                    ""
                }
                val prompt = buildPrompt(effectivePromptText)
                val structuredMessages = buildStructuredHistory(effectivePromptText)
                val modeChangedInStatefulThinkingRuntime =
                    lastRuntimeThinkingMode?.let { it != thinkingModeForRequest } == true &&
                        inferenceManager.needsResetWhenThinkingModeChanges()
                val shouldResetRuntimeConversation =
                    resetRuntimeConversationBeforeNextSend ||
                        modeChangedInStatefulThinkingRuntime
                val (response, duration) = ioMutex.withLock {
                    if (shouldResetRuntimeConversation) {
                        inferenceManager.resetConversation(noThinking = !thinkingModeForRequest)
                        resetRuntimeConversationBeforeNextSend = false
                    }
                    inferenceManager.generateResponseStreaming(
                        prompt = prompt,
                        rawUserText = effectivePromptText,
                        memoryContext = memoryContext,
                        structuredMessages = structuredMessages,
                        restoreStatefulHistory = shouldResetRuntimeConversation,
                        thinkingMode = thinkingModeForRequest
                    ) { partial ->
                        liveUpdates.trySend(partial)
                    }
                }
                lastRuntimeThinkingMode = thinkingModeForRequest

                if (!inferenceManager.isStopRequested && _isGenerating.value) {
                    launch(Dispatchers.Main.immediate) {
                        if (_currentSessionId.value == targetSessionId && assistantIndex < messages.size) {
                            val current = messages[assistantIndex].text
                            val finalResponse = resolveFinalAssistantText(
                                response = response,
                                current = current,
                                thinkingMode = thinkingModeForRequest,
                                userText = userText
                            )
                            val currentMessage = messages[assistantIndex]
                            messages[assistantIndex] = currentMessage.copy(
                                text = finalResponse.ifBlank { GENERATION_FAILURE_TEXT },
                                ragSources = ragSources,
                                ragChunkCount = ragChunkCount,
                                ragTopMatchPct = ragTopMatchPct
                            )
                            saveCurrentSession()
                        }
                    }
                    _lastInferenceTime.value = duration
                    _tokensPerSecond.value = if (duration > 0L) {
                        estimateTokenCount(response) * 1000f / duration.toFloat()
                    } else {
                        0f
                    }
                }
            } catch (exception: CancellationException) {
                Log.i("ChatViewModel", "Inference stopped", exception)
                resetRuntimeConversationBeforeNextSend = true
                if (_currentSessionId.value == targetSessionId && assistantIndex < messages.size) {
                    launch(Dispatchers.Main.immediate) {
                        val current = messages[assistantIndex].text
                        // Preserve partial streamed text; only replace pure placeholders.
                        val finalText = if (current == "Generating…" || current == "…" || current.isBlank()) {
                            "Stopped."
                        } else {
                            ModelOutputSanitizer.clean(current)   // keep whatever the model streamed so far
                        }
                        messages[assistantIndex] = messages[assistantIndex].copy(text = finalText)
                        saveCurrentSession()
                    }
                }
            } catch (t: Throwable) {
                if (inferenceManager.isStopRequested) {
                    Log.i("ChatViewModel", "Inference error ignored because stop was requested", t)
                    resetRuntimeConversationBeforeNextSend = true
                } else {
                    Log.e("ChatViewModel", "Inference error", t)
                    resetRuntimeConversationBeforeNextSend = true
                    if (_currentSessionId.value == targetSessionId && assistantIndex < messages.size) {
                        launch(Dispatchers.Main.immediate) {
                            messages[assistantIndex] = messages[assistantIndex].copy(text = "⚠️ Error: ${t.message}")
                            saveCurrentSession()
                        }
                    }
                }
            } finally {
                liveUpdates.close()
                liveUpdateJob.cancel()
                generationJob = null
                _isGenerating.value = false
            }
        }
    }

    fun stopGenerating() {
        if (!_isGenerating.value) return
        _isGenerating.value = false
        resetRuntimeConversationBeforeNextSend = true
        inferenceManager.cancelGeneration()
        generationJob?.cancel()

        // Immediately update UI state on the main thread
        val assistantIndex = messages.indexOfLast { !it.isUser }
        if (assistantIndex >= 0 && assistantIndex < messages.size) {
            val current = messages[assistantIndex].text
            val finalText = if (current == "Generating…" || current == "…" || current.isBlank()) {
                "Stopped."
            } else {
                ModelOutputSanitizer.clean(current)
            }
            messages[assistantIndex] = messages[assistantIndex].copy(text = finalText)
            saveCurrentSession()
        }
    }

    fun uninitializeModel() {
        releaseModel(clearCoordinator = true)
    }

    fun startNewChat() {
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
        if (sessionId == _currentSessionId.value) return
        if (_isGenerating.value) {
            stopGenerating()
        }
        saveCurrentSession()
        val session = chatSessions.firstOrNull { it.id == sessionId } ?: return
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
        if (sessionId == _currentSessionId.value && _isGenerating.value) {
            stopGenerating()
        }
        val session = chatSessions.firstOrNull { it.id == sessionId }
        session?.documentId?.let { docId ->
            ragManager.removeDocument(docId)
        }
        val sessionDir = getApplication<Application>().filesDir.resolve("chat_attachments").resolve(sessionId)
        if (sessionDir.exists()) {
            sessionDir.deleteRecursively()
        }
        val removingCurrent = sessionId == _currentSessionId.value
        val removingPending = sessionId == _pendingSessionId.value
        chatSessions.removeAll { it.id == sessionId }
        persistSessions()
        if (removingPending) {
            _pendingSessionId.value = null
        }
        if (removingCurrent || (removingPending && _currentSessionId.value == null)) {
            messages.clear()
            _currentSessionId.value = null
            currentSessionDocumentId = null
            resetRuntimeConversationBeforeNextSend = true
            lastRuntimeThinkingMode = null
            _lastInferenceTime.value = 0L
            _tokensPerSecond.value = 0f
        }
    }

    fun clearHistory() {
        if (_isGenerating.value) {
            stopGenerating()
        }
        chatSessions.forEach { session ->
            session.documentId?.let { docId ->
                ragManager.removeDocument(docId)
            }
        }
        val attachmentsDir = getApplication<Application>().filesDir.resolve("chat_attachments")
        if (attachmentsDir.exists()) {
            attachmentsDir.deleteRecursively()
        }
        ragManager.clearKnowledgeBase()
        chatSessions.clear()
        messages.clear()
        _currentSessionId.value = null
        _pendingSessionId.value = null
        currentSessionDocumentId = null
        resetRuntimeConversationBeforeNextSend = true
        lastRuntimeThinkingMode = null
        _lastInferenceTime.value = 0L
        _tokensPerSecond.value = 0f
        persistSessions()
    }

    private fun ensureCurrentSession(firstUserText: String, documentName: String? = null) {
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
                persistSessions()
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
        persistSessions()
    }

    private fun saveCurrentSession() {
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

    private fun loadSessions() {
        val raw = historyPrefs.getString(KEY_SESSIONS, null) ?: return
        runCatching {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val messagesJson = item.optJSONArray("messages") ?: JSONArray()
                val sessionMessages = buildList {
                    for (j in 0 until messagesJson.length()) {
                        val messageJson = messagesJson.getJSONObject(j)
                        val text = messageJson.optString("text").orEmpty()
                        val isUser = messageJson.optBoolean("isUser", false)
                        val id = messageJson.optString("id").ifBlank { UUID.randomUUID().toString() }
                        val cleanText = if (isUser) text else ModelOutputSanitizer.clean(text)
                        val ragSourcesJson = messageJson.optJSONArray("ragSources")
                        val ragSources = if (ragSourcesJson != null) {
                            List(ragSourcesJson.length()) { k -> ragSourcesJson.getString(k) }
                        } else emptyList()
                        val ragChunkCount = messageJson.optInt("ragChunkCount", 0)
                        val ragTopMatchPct = messageJson.optInt("ragTopMatchPct", 0)
                        val docName = messageJson.optString("documentName").takeIf { it.isNotBlank() }
                        val docPath = messageJson.optString("documentPath").takeIf { it.isNotBlank() }
                        // Skip stale placeholder assistant messages persisted by older versions
                        if ((cleanText.isNotBlank() || !docName.isNullOrBlank()) && !((!isUser) && cleanText in PLACEHOLDER_TEXTS)) {
                            add(
                                ChatMessage(
                                    text = cleanText,
                                    isUser = isUser,
                                    id = id,
                                    ragSources = ragSources,
                                    ragChunkCount = ragChunkCount,
                                    ragTopMatchPct = ragTopMatchPct,
                                    documentName = docName,
                                    documentPath = docPath
                                )
                            )
                        }
                    }
                }.let { msgs ->
                    // Drop a trailing user message that lost its assistant reply
                    if (msgs.isNotEmpty() && msgs.last().isUser) msgs.dropLast(1) else msgs
                }
                if (sessionMessages.isNotEmpty()) {
                    chatSessions.add(
                        ChatSession(
                            id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                            title = item.optString("title").ifBlank { "New chat" },
                            messages = sessionMessages,
                            updatedAt = item.optLong("updatedAt", 0L),
                            documentId = item.optString("documentId").takeIf { it.isNotBlank() }
                        )
                    )
                }
            }
            chatSessions.sortByDescending { it.updatedAt }
        }.onFailure {
            historyPrefs.edit().remove(KEY_SESSIONS).apply()
        }
    }

    private fun persistSessions() {
        val array = JSONArray()
        chatSessions.forEach { session ->
            val messagesJson = JSONArray()
            session.messages.forEach { message ->
                val mObj = JSONObject()
                    .put("text", if (message.isUser) message.text else ModelOutputSanitizer.clean(message.text))
                    .put("isUser", message.isUser)
                    .put("id", message.id)
                if (!message.documentName.isNullOrBlank()) {
                    mObj.put("documentName", message.documentName)
                }
                if (!message.documentPath.isNullOrBlank()) {
                    mObj.put("documentPath", message.documentPath)
                }
                if (message.ragSources.isNotEmpty()) {
                    val srcArr = JSONArray()
                    message.ragSources.forEach { srcArr.put(it) }
                    mObj.put("ragSources", srcArr)
                    mObj.put("ragChunkCount", message.ragChunkCount)
                    mObj.put("ragTopMatchPct", message.ragTopMatchPct)
                }
                messagesJson.put(mObj)
            }
            array.put(
                JSONObject()
                    .put("id", session.id)
                    .put("title", session.title)
                    .put("updatedAt", session.updatedAt)
                    .put("messages", messagesJson)
                    .put("documentId", session.documentId ?: "")
            )
        }
        historyPrefs.edit().putString(KEY_SESSIONS, array.toString()).apply()
    }

    private fun buildStructuredHistory(latestUserText: String): List<Pair<String, String>> {
        val turns = mutableListOf<Pair<String, String>>()
        if (_isPersistentMemoryActive.value) {
            val memoryContext = memoryManager.formatMemoryContext()
            if (memoryContext.isNotBlank()) {
                turns.add("system" to memoryContext)
            }
        }
        messages
            .dropLast(2)
            .takeLast(12)
            .forEach { message ->
                val cleanText = message.text.toPromptText(message.isUser)
                if (cleanText.isNotBlank()) {
                    val role = if (message.isUser) "user" else "assistant"
                    turns.add(role to cleanText)
                }
            }
        turns.add("user" to latestUserText.trim())
        return turns
    }

    private fun buildPrompt(latestUserText: String): String {
        val memoryContext = if (_isPersistentMemoryActive.value) {
            memoryManager.formatMemoryContext()
        } else {
            ""
        }
        val memoryPrefix = if (memoryContext.isNotBlank()) "$memoryContext\n\n" else ""
        val previousTurns = messages
            .dropLast(2)
            .takeLast(12)
            .mapNotNull { message ->
                val cleanText = message.text.toPromptText(message.isUser)
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

    private fun String.toPromptText(isUser: Boolean): String {
        val visibleText = if (isUser) {
            trim()
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
        saveCurrentSession()
        releaseModel(clearCoordinator = true)
        ModelRuntimeCoordinator.unregister(ModelRuntimeOwner.Chat)
        super.onCleared()
    }

    private fun releaseModel(clearCoordinator: Boolean) {
        initGeneration++
        initJob?.cancel()
        initJob = null
        generationJob?.cancel()
        generationJob = null
        inferenceManager.cancelGeneration()
        inferenceManager.close()
        currentModelPath = null
        currentModelId = ""
        currentModelName = ""
        currentModelSize = ""
        currentAllowUnsafeOverride = false
        lastLoadedBackendPreference = null
        _activeBackend.value = null
        resetRuntimeConversationBeforeNextSend = false
        lastRuntimeThinkingMode = null
        _isInitializing.value = false
        _isGenerating.value = false
        _isModelReady.value = false
        if (clearCoordinator) {
            ModelRuntimeCoordinator.clear(ModelRuntimeOwner.Chat)
        }
    }

    private fun resolveFinalAssistantText(
        response: String,
        current: String,
        thinkingMode: Boolean,
        userText: String
    ): String {
        val normalizedResponseRaw = if (thinkingMode) {
            ThinkingTextUtils.normalizeFinalOutput(response)
        } else {
            ModelOutputSanitizer.clean(response).trim()
        }
        val normalizedResponse = if (thinkingMode) {
            normalizedResponseRaw
        } else {
            ThinkingTextUtils.finalResponseOrReasoning(normalizedResponseRaw)
        }
        val normalizedCurrent = ModelOutputSanitizer.clean(current).trim()
        return when {
            normalizedResponse.isUsableAssistantText() -> normalizedResponse
            normalizedCurrent.isUsableAssistantText() -> normalizedCurrent
            else -> ""
        }
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
            GENERATION_FAILURE_TEXT
        )
    }
}
