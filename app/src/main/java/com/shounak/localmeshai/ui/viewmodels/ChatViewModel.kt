package com.shounak.localmeshai.ui.viewmodels

import android.app.Application
import android.content.Context
import android.util.Log
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val id: String = UUID.randomUUID().toString()
)

data class ChatSession(
    val id: String,
    val title: String,
    val messages: List<ChatMessage>,
    val updatedAt: Long
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

    private val _draftText = MutableStateFlow("")
    val draftText = _draftText.asStateFlow()

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId = _currentSessionId.asStateFlow()

    private var currentModelPath: String? = null
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
        allowUnsafeOverride: Boolean = false
    ) {
        if (path == currentModelPath && _isModelReady.value && ModelRuntimeCoordinator.isActive(ModelRuntimeOwner.Chat)) {
            return
        }

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
                        allowUnsafeOverride = allowUnsafeOverride
                    )
                    if (myGen != initGeneration) {
                        inferenceManager.close()
                        return@withLock
                    }
                    currentModelPath = path
                    resetRuntimeConversationBeforeNextSend = false
                    lastRuntimeThinkingMode = null
                    _isModelReady.value = true
                    Log.i("ChatViewModel", "Model ready: $path")
                } catch (t: Throwable) {
                    if (myGen != initGeneration) return@withLock
                    val msg = t.message ?: "Unknown error during model load"
                    Log.e("ChatViewModel", "Model init failed", t)
                    _error.value = msg
                    currentModelPath = null
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

    fun sendMessage(text: String) {
        if (_isGenerating.value || text.isBlank()) return
        if (!_isModelReady.value) {
            _error.value = "Choose and initialise a chat model first."
            return
        }
        val userText = text.trim()
        ensureCurrentSession(userText)
        messages.add(ChatMessage(userText, true))
        val assistantIndex = messages.size
        messages.add(ChatMessage("Generating…", false))
        // Do NOT saveCurrentSession() here — the placeholder "Generating…" must
        // never be persisted. The session is saved only after a real response arrives.

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
                    if (assistantIndex < messages.size) {
                        val current = messages[assistantIndex]
                        val cleanPartial = ModelOutputSanitizer.clean(partial)
                        messages[assistantIndex] = current.copy(text = cleanPartial.ifBlank { "\u2026" })
                        // Do NOT call saveCurrentSession() here — persisting JSON on every
                        // token would hammer SharedPreferences. The final save happens below.
                    }
                }
            }
            try {
                val prompt = buildPrompt(userText)
                val modeChangedInStatefulThinkingRuntime =
                    lastRuntimeThinkingMode?.let { it != thinkingModeForRequest } == true &&
                        inferenceManager.needsResetWhenThinkingModeChanges()
                val noThinkingRequestNeedsFreshRuntime =
                    inferenceManager.needsFreshConversationForRequest(thinkingModeForRequest)
                val shouldResetRuntimeConversation =
                    resetRuntimeConversationBeforeNextSend ||
                        modeChangedInStatefulThinkingRuntime ||
                        noThinkingRequestNeedsFreshRuntime
                val runtimeUserText = if (shouldResetRuntimeConversation) prompt else userText
                val (response, duration) = ioMutex.withLock {
                    if (shouldResetRuntimeConversation) {
                        inferenceManager.resetConversation(noThinking = noThinkingRequestNeedsFreshRuntime)
                        resetRuntimeConversationBeforeNextSend = false
                    }
                    inferenceManager.generateResponseStreaming(
                        prompt = prompt,
                        rawUserText = runtimeUserText,
                        thinkingMode = thinkingModeForRequest
                    ) { partial ->
                        liveUpdates.trySend(partial)
                    }
                }
                lastRuntimeThinkingMode = thinkingModeForRequest

                launch(Dispatchers.Main.immediate) {
                    if (assistantIndex < messages.size) {
                        val current = messages[assistantIndex].text
                        val finalResponse = resolveFinalAssistantText(
                            response = response,
                            current = current,
                            thinkingMode = thinkingModeForRequest,
                            userText = userText
                        )
                        val currentMessage = messages[assistantIndex]
                        messages[assistantIndex] = currentMessage.copy(
                            text = finalResponse.ifBlank {
                                "I could not generate a response. Try asking again with a little more detail."
                            }
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
            } catch (exception: CancellationException) {
                Log.i("ChatViewModel", "Inference stopped", exception)
                resetRuntimeConversationBeforeNextSend = true
                if (assistantIndex < messages.size) {
                    viewModelScope.launch(Dispatchers.Main.immediate) {
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
                Log.e("ChatViewModel", "Inference error", t)
                resetRuntimeConversationBeforeNextSend = true
                if (assistantIndex < messages.size) {
                    viewModelScope.launch(Dispatchers.Main.immediate) {
                        messages[assistantIndex] = messages[assistantIndex].copy(text = "⚠️ Error: ${t.message}")
                        saveCurrentSession()
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
        resetRuntimeConversationBeforeNextSend = true
        inferenceManager.cancelGeneration()
        generationJob?.cancel()
    }

    fun uninitializeModel() {
        releaseModel(clearCoordinator = true)
    }

    fun startNewChat() {
        stopGenerating()
        saveCurrentSession()
        messages.clear()
        _currentSessionId.value = null
        resetRuntimeConversationBeforeNextSend = true
        lastRuntimeThinkingMode = null
        _lastInferenceTime.value = 0L
        _tokensPerSecond.value = 0f
        _error.value = null
    }

    fun selectChatSession(sessionId: String) {
        if (sessionId == _currentSessionId.value) return
        saveCurrentSession()
        val session = chatSessions.firstOrNull { it.id == sessionId } ?: return
        messages.clear()
        messages.addAll(session.messages)
        _currentSessionId.value = session.id
        resetRuntimeConversationBeforeNextSend = true
        lastRuntimeThinkingMode = null
        _lastInferenceTime.value = 0L
        _tokensPerSecond.value = 0f
        _error.value = null
    }

    fun deleteChatSession(sessionId: String) {
        val removingCurrent = sessionId == _currentSessionId.value
        chatSessions.removeAll { it.id == sessionId }
        persistSessions()
        if (removingCurrent) {
            messages.clear()
            _currentSessionId.value = null
            resetRuntimeConversationBeforeNextSend = true
            lastRuntimeThinkingMode = null
            _lastInferenceTime.value = 0L
            _tokensPerSecond.value = 0f
        }
    }

    fun clearHistory() {
        chatSessions.clear()
        messages.clear()
        _currentSessionId.value = null
        resetRuntimeConversationBeforeNextSend = true
        lastRuntimeThinkingMode = null
        _lastInferenceTime.value = 0L
        _tokensPerSecond.value = 0f
        persistSessions()
    }

    private fun ensureCurrentSession(firstUserText: String) {
        if (_currentSessionId.value != null) return
        val session = ChatSession(
            id = UUID.randomUUID().toString(),
            title = firstUserText.toChatTitle(),
            messages = emptyList(),
            updatedAt = System.currentTimeMillis()
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

        val firstUserMessage = currentMessages.firstOrNull { it.isUser }?.text
        val previous = chatSessions[index]
        val updated = previous.copy(
            title = firstUserMessage?.toChatTitle() ?: previous.title,
            messages = currentMessages,
            updatedAt = System.currentTimeMillis()
        )
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
                        // Skip stale placeholder assistant messages persisted by older versions
                        if (cleanText.isNotBlank() && !((!isUser) && cleanText in PLACEHOLDER_TEXTS)) {
                            add(ChatMessage(text = cleanText, isUser = isUser, id = id))
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
                            updatedAt = item.optLong("updatedAt", 0L)
                        )
                    )
                }
            }
        }.onFailure {
            historyPrefs.edit().remove(KEY_SESSIONS).apply()
        }
    }

    private fun persistSessions() {
        val array = JSONArray()
        chatSessions.forEach { session ->
            val messagesJson = JSONArray()
            session.messages.forEach { message ->
                messagesJson.put(
                    JSONObject()
                        .put("text", if (message.isUser) message.text else ModelOutputSanitizer.clean(message.text))
                        .put("isUser", message.isUser)
                        .put("id", message.id)
                )
            }
            array.put(
                JSONObject()
                    .put("id", session.id)
                    .put("title", session.title)
                    .put("updatedAt", session.updatedAt)
                    .put("messages", messagesJson)
            )
        }
        historyPrefs.edit().putString(KEY_SESSIONS, array.toString()).apply()
    }

    private fun buildPrompt(latestUserText: String): String {
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
            latestUserText
        } else {
            "$previousTurns\nUser: $latestUserText\nAssistant:"
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
        val normalizedResponse = if (thinkingMode) {
            ThinkingTextUtils.normalizeFinalOutput(response)
        } else {
            ModelOutputSanitizer.clean(response).trim()
        }
        if (normalizedResponse.isUsableAssistantText(userText)) {
            return normalizedResponse
        }

        val normalizedCurrent = ThinkingTextUtils.normalizeFinalOutput(current)
        if (normalizedCurrent.isUsableAssistantText(userText)) {
            return normalizedCurrent
        }

        return ""
    }

    private fun String.isUsableAssistantText(userText: String): Boolean {
        val normalized = trim()
        return normalized.isNotBlank() &&
            normalized !in PLACEHOLDER_TEXTS &&
            !ModelResponseQuality.isGenericNonAnswer(normalized, userText)
    }

    private companion object {
        const val KEY_SESSIONS = "sessions_json"
        /** Placeholder texts that should never be persisted as real assistant messages. */
        val PLACEHOLDER_TEXTS = setOf("Generating…", "Generating...", "…", "Reading input…", "Reading input...")
    }
}
