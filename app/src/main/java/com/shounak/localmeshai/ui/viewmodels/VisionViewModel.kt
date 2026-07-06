package com.shounak.localmeshai.ui.viewmodels

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shounak.localmeshai.ai.VisionInferenceManager
import com.shounak.localmeshai.utils.DocumentTextExtractor
import com.shounak.localmeshai.utils.ModelOutputSanitizer
import com.shounak.localmeshai.utils.ModelRuntimeCoordinator
import com.shounak.localmeshai.utils.ModelRuntimeOwner
import com.shounak.localmeshai.utils.ThinkingTextUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CancellationException as FutureCancellationException
import java.util.UUID

data class VisionChatSession(
    val id: String,
    val title: String,
    val question: String,
    val answer: String,
    val updatedAt: Long
)

data class VisionChatMessage(
    val text: String,
    val isUser: Boolean,
    val bitmap: Bitmap? = null,
    val id: String = UUID.randomUUID().toString()
)

class VisionViewModel(application: Application) : AndroidViewModel(application) {
    private val inferenceManager = VisionInferenceManager(application)
    private val historyPrefs = application.getSharedPreferences("vision_chat_history", Context.MODE_PRIVATE)
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

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _isModelReady = MutableStateFlow(false)
    val isModelReady = _isModelReady.asStateFlow()

    private var currentModelPath: String? = null
    private var currentContextWindowTokens: Int = DEFAULT_VISION_CONTEXT_WINDOW_TOKENS
    private var initGeneration: Int = 0
    private var initJob: Job? = null
    private var analysisGeneration: Int = 0
    private var analysisJob: Job? = null
    @Volatile private var resetRuntimeConversationBeforeNextAsk = false

    // Serializes vision init and ask so they cannot run at the same time.
    private val ioMutex = Mutex()

    init {
        ModelRuntimeCoordinator.register(ModelRuntimeOwner.Vision) {
            releaseModel(clearCoordinator = false)
        }
        loadSessions()
    }

    fun initModel(
        path: String,
        modelId: String = "",
        modelName: String = "",
        modelSize: String = "",
        contextWindowTokens: Int? = null,
        supportsAudioInput: Boolean = false,
        allowUnsafeOverride: Boolean = false
    ) {
        val resolvedContextWindowTokens = contextWindowTokens
            ?: inferContextWindowTokens(path)
            ?: DEFAULT_VISION_CONTEXT_WINDOW_TOKENS

        // Already ready on the same path -> no-op.
        if (path == currentModelPath && _isModelReady.value && ModelRuntimeCoordinator.isActive(ModelRuntimeOwner.Vision)) {
            currentContextWindowTokens = resolvedContextWindowTokens
            return
        }

        val myGen = ++initGeneration
        initJob?.cancel()
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
            ioMutex.withLock {
                // A newer init has been requested; drop this one silently.
                if (myGen != initGeneration) return@withLock
                try {
                    ModelRuntimeCoordinator.activate(ModelRuntimeOwner.Vision)
                    inferenceManager.initialize(
                        modelPath = path,
                        modelId = modelId,
                        modelName = modelName,
                        modelSize = modelSize,
                        supportsAudioInput = supportsAudioInput,
                        allowUnsafeOverride = allowUnsafeOverride
                    )
                    // Re-check after the (potentially long) native init.
                    if (myGen != initGeneration) {
                        inferenceManager.close()
                        return@withLock
                    }
                    currentModelPath = path
                    currentContextWindowTokens = resolvedContextWindowTokens
                    resetRuntimeConversationBeforeNextAsk = false
                    _isModelReady.value = true
                } catch (exception: Exception) {
                    if (myGen != initGeneration) return@withLock
                    _error.value = exception.message ?: "Failed to load multimodal model."
                    currentModelPath = null
                    currentContextWindowTokens = DEFAULT_VISION_CONTEXT_WINDOW_TOKENS
                    _isModelReady.value = false
                }
            }
            if (myGen == initGeneration) {
                _isInitializing.value = false
                _isStopping.value = false
                initJob = null
            }
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

    fun ask(
        bitmap: Bitmap?,
        question: String,
        fileUri: Uri? = null,
        fileName: String? = null,
        audioUri: Uri? = null,
        audioName: String? = null,
        audioBytes: ByteArray? = null,
        thinkingMode: Boolean = false
    ) {
        if (_isInitializing.value || _isAnalyzing.value || _isStopping.value) return
        if (!_isModelReady.value) return
        if (bitmap?.isRecycled == true) return

        val myGen = ++analysisGeneration
        val jobSessionId = _currentSessionId.value
        val basePrompt = question.trim()
        val documentBudget = documentTextBudget(
            userPrompt = basePrompt,
            hasBitmap = bitmap != null && !bitmap.isRecycled,
            hasAudio = audioUri != null || audioBytes?.isNotEmpty() == true,
            thinkingMode = thinkingMode
        )

        // Extract file text if fileUri is provided
        var fileText = ""
        if (fileUri != null) {
            fileText = try {
                readTextFromUri(getApplication(), fileUri, documentBudget)
            } catch (e: Exception) {
                "Error reading file: ${e.message}"
            }
        }
        val promptFileText = fileText.limitForPrompt(documentBudget)

        // Build prompt
        val contentPrompt = buildString {
            if (basePrompt.isNotBlank()) {
                append(basePrompt)
                if (promptFileText.isNotBlank()) {
                    append("\n\nUse the extracted attached-file content below as evidence. If this is a ZIP archive, use its file list and readable extracted contents as evidence, but follow the user's request instead of summarizing by default. Do not ask the user to attach the file again; it is already available to you as extracted text.\n\n")
                }
            } else if (promptFileText.isNotBlank()) {
                if (fileName.isZipFileName()) {
                    append("Analyze the attached ZIP archive. Summarize its folder/file structure, readable extracted contents, important files, and any useful next steps. Do not ask the user to attach the file again; it is already available to you as extracted text.\n\n")
                } else {
                    append("Analyze the attached file. Summarize the important contents and answer based on the extracted text below. Do not ask the user to attach the file again; it is already available to you as extracted text.\n\n")
                }
            }
            if (promptFileText.isNotBlank()) {
                append("--- EXTRACTED ATTACHED FILE: ${fileName ?: "document"} ---\n")
                append(promptFileText)
            }
            if (audioUri != null || audioBytes?.isNotEmpty() == true) {
                if (this.isNotEmpty()) append("\n\n")
                append("--- ATTACHED AUDIO: ${audioName ?: "audio"} ---\n")
                append(
                    if (basePrompt.isBlank()) {
                        "Listen to the attached audio, transcribe or summarize the important speech/sounds, then answer with the useful details."
                    } else {
                        "Listen to the attached audio, use its spoken or acoustic content as evidence, and answer the user's question."
                    }
                )
            }
            if (this.isEmpty()) {
                append(DEFAULT_IMAGE_PROMPT)
            }
        }
        val prompt = if (thinkingMode) {
            "Reason carefully before giving the final answer.\n\n$contentPrompt"
        } else {
            contentPrompt
        }

        // Add message to chat list
        val displayPrompt = buildString {
            if (basePrompt.isNotBlank()) {
                append(basePrompt)
            }
            if (fileName != null) {
                if (this.isNotEmpty()) append("\n")
                append("📎 Attached File: $fileName")
            }
            if (audioName != null || audioBytes?.isNotEmpty() == true) {
                if (this.isNotEmpty()) append("\n")
                append("🎧 Attached Audio: ${audioName ?: "Mic recording"}")
            }
            if (this.isBlank()) {
                append(DEFAULT_IMAGE_PROMPT)
            }
        }

        // Preserve previous multimodal turns in the visible chat. New Chat is the
        // explicit path that clears this list.
        messages.add(VisionChatMessage(displayPrompt, true, bitmap))
        val assistantIndex = messages.size
        messages.add(VisionChatMessage("Reading input…", false, null))

        // Ensure session is created (not saved to disk yet with placeholder answer)
        ensureCurrentSession(displayPrompt, "Reading input…", bitmap)
        val activeSessionId = jobSessionId ?: _currentSessionId.value
        // Do NOT call saveCurrentSession() here — the placeholder "Reading input…"
        // must never be persisted. The session is saved only after a real response arrives.

        // Set analyzing immediately so the UI shows the spinner/Stop button
        // before the coroutine even acquires the ioMutex.
        _isAnalyzing.value = true
        val job = viewModelScope.launch(Dispatchers.IO) {
            val runningJob = coroutineContext[Job]
            try {
                ioMutex.withLock {
                    // Re-check after acquiring the lock — model state may have changed.
                    if (myGen != analysisGeneration) return@withLock
                    if (!_isModelReady.value || _isInitializing.value) {
                        _isAnalyzing.value = false
                        return@withLock
                    }
                    _error.value = null
                    _answer.value = ""
                    try {
                        var lastPartialUiUpdateAt = 0L
                        fun publishPartial(partial: String) {
                            if (myGen != analysisGeneration) return
                            val now = System.currentTimeMillis()
                            if (now - lastPartialUiUpdateAt < 33L) return
                            lastPartialUiUpdateAt = now
                            val cleanPartial = ModelOutputSanitizer.clean(partial)
                            _answer.value = cleanPartial
                            viewModelScope.launch(Dispatchers.Main.immediate) {
                                if (myGen == analysisGeneration && assistantIndex < messages.size) {
                                    messages[assistantIndex] = messages[assistantIndex].copy(
                                        text = cleanPartial.ifBlank { "…" },
                                        bitmap = null
                                    )
                                }
                            }
                        }
                        if (resetRuntimeConversationBeforeNextAsk) {
                            inferenceManager.resetConversation()
                            resetRuntimeConversationBeforeNextAsk = false
                        }
                        val bitmapCopy = bitmap?.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
                        val audioFile = audioUri?.let {
                            copyUriToInferenceCache(
                                context = getApplication(),
                                uri = it,
                                fileName = audioName,
                                prefix = "audio-question"
                            )
                        }
                        var response = inferenceManager.askStreaming(
                            bitmap = bitmapCopy,
                            question = prompt,
                            audioFile = audioFile,
                            audioBytes = audioBytes
                        ) { partial ->
                            publishPartial(partial)
                        }
                        if (response.isNoAnswerGenerated() && fileText.isNotBlank() && !fileText.isExtractionFailureText()) {
                            val retryBitmap = bitmapCopy ?: createPlaceholderBitmap()
                            val retryPrompt = buildDocumentRetryPrompt(
                                basePrompt = basePrompt,
                                fileName = fileName,
                                fileText = fileText,
                                budget = documentBudget
                            )
                            response = inferenceManager.askStreaming(
                                bitmap = retryBitmap,
                                question = retryPrompt,
                                audioFile = null,
                                audioBytes = null
                            ) { partial ->
                                publishPartial(partial)
                            }
                            if (bitmapCopy == null && !retryBitmap.isRecycled) {
                                retryBitmap.recycle()
                            }
                        }
                        if (response.isNoAnswerGenerated() && fileText.isNotBlank()) {
                            response = buildDocumentExtractiveFallback(
                                fileName = fileName,
                                fileText = fileText,
                                basePrompt = basePrompt,
                                budget = documentBudget
                            )
                        }
                        if (myGen != analysisGeneration) return@withLock
                        if (response.isNotBlank() && !response.isNoAnswerGenerated()) {
                            // Only persist the final answer, not every partial.
                            withContext(Dispatchers.Main.immediate) {
                                if (myGen == analysisGeneration) {
                                    val current = if (assistantIndex < messages.size) messages[assistantIndex].text else ""
                                    val finalResponse = resolveFinalVisionAnswer(
                                        response = response,
                                        current = current,
                                        thinkingMode = thinkingMode
                                    )
                                    if (assistantIndex < messages.size) {
                                        messages[assistantIndex] = messages[assistantIndex].copy(
                                            text = finalResponse.ifBlank { "No answer generated." },
                                            bitmap = null
                                        )
                                    }
                                    saveCurrentSession(
                                        displayPrompt,
                                        finalResponse.ifBlank { "No answer generated." },
                                        bitmap,
                                        targetSessionId = activeSessionId
                                    )
                                }
                            }
                        } else if (_error.value == null) {
                            _error.value = "No answer generated."
                            withContext(Dispatchers.Main.immediate) {
                                if (assistantIndex < messages.size) {
                                    messages[assistantIndex] = messages[assistantIndex].copy(
                                        text = "No answer generated.",
                                        bitmap = null
                                    )
                                }
                                saveCurrentSession(displayPrompt, "No answer generated.", bitmap, targetSessionId = activeSessionId)
                            }
                        }
                    } catch (exception: CancellationException) {
                        withContext(Dispatchers.Main.immediate) {
                            val current = if (assistantIndex < messages.size) messages[assistantIndex].text else ""
                            val finalMsg = if (current == "Reading input…" || current == "…" || current.isBlank()) {
                                "Stopped."
                            } else {
                                ModelOutputSanitizer.clean(current)
                            }
                            if (myGen == analysisGeneration && assistantIndex < messages.size) {
                                messages[assistantIndex] = messages[assistantIndex].copy(
                                    text = finalMsg,
                                    bitmap = null
                                )
                            }
                            saveCurrentSession(displayPrompt, finalMsg, bitmap, targetSessionId = activeSessionId)
                        }
                    } catch (exception: FutureCancellationException) {
                        withContext(Dispatchers.Main.immediate) {
                            val current = if (assistantIndex < messages.size) messages[assistantIndex].text else ""
                            val finalMsg = if (current == "Reading input…" || current == "…" || current.isBlank()) {
                                "Stopped."
                            } else {
                                ModelOutputSanitizer.clean(current)
                            }
                            if (myGen == analysisGeneration && assistantIndex < messages.size) {
                                messages[assistantIndex] = messages[assistantIndex].copy(
                                    text = finalMsg,
                                    bitmap = null
                                )
                            }
                            saveCurrentSession(displayPrompt, finalMsg, bitmap, targetSessionId = activeSessionId)
                        }
                    } catch (exception: Exception) {
                        _error.value = exception.message ?: "Image question failed."
                        withContext(Dispatchers.Main.immediate) {
                            val errorMsg = "⚠️ Error: ${exception.message ?: "Image question failed."}"
                            if (myGen == analysisGeneration && assistantIndex < messages.size) {
                                messages[assistantIndex] = messages[assistantIndex].copy(
                                    text = errorMsg,
                                    bitmap = null
                                )
                            }
                            saveCurrentSession(displayPrompt, errorMsg, bitmap, targetSessionId = activeSessionId)
                        }
                    } finally {
                        // Always clear — stopAnalyzing() may have already
                        // set this to false, but this is a safety net so the
                        // flag can never get stuck at true.
                        _isAnalyzing.value = false
                        if (myGen != analysisGeneration) {
                            _isStopping.value = false
                        }
                    }
                }
            } finally {
                if (analysisJob == runningJob) {
                    analysisJob = null
                }
            }
        }
        analysisJob = job
    }

    fun stopAnalyzing() {
        if (!_isAnalyzing.value) return
        // Bump generation so the streaming lambda ignores further updates.
        analysisGeneration++
        // Signal the native runtime to stop producing tokens.
        // This cancels the Future (interrupting future.get()) and sets the
        // stopRequested flag.
        inferenceManager.cancelGeneration()
        // Keep the worker coroutine alive so it can wait for MediaPipe's native
        // done callback and close the session on the same serialized path.
        _isAnalyzing.value = false
        _isStopping.value = true
        markStoppedIfBlank()
    }

    fun uninitializeModel() {
        releaseModel(clearCoordinator = true)
    }
    fun startNewChat() {
        stopAnalyzing()
        _currentSessionId.value = null
        resetRuntimeConversationBeforeNextAsk = true
        _answer.value = ""
        _error.value = null
        messages.clear()
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
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun selectChatSession(sessionId: String): VisionChatSession? {
        val session = imageChatSessions.firstOrNull { it.id == sessionId } ?: return null
        _currentSessionId.value = session.id
        resetRuntimeConversationBeforeNextAsk = true
        _answer.value = session.answer
        _error.value = null
        messages.clear()
        val bitmap = loadSessionBitmap(session.id)
        messages.add(VisionChatMessage(session.question, true, bitmap))
        messages.add(VisionChatMessage(session.answer, false, null))
        return session
    }

    fun deleteChatSession(sessionId: String) {
        val removingCurrent = sessionId == _currentSessionId.value
        imageChatSessions.removeAll { it.id == sessionId }
        if (removingCurrent) {
            _currentSessionId.value = null
            resetRuntimeConversationBeforeNextAsk = true
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
        persistSessions()
    }

    fun clearHistory() {
        imageChatSessions.clear()
        _currentSessionId.value = null
        resetRuntimeConversationBeforeNextAsk = true
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
        persistSessions()
    }

    private fun ensureCurrentSession(question: String, answer: String, bitmap: Bitmap? = null) {
        if (_currentSessionId.value != null) return
        val sessionId = UUID.randomUUID().toString().also {
            _currentSessionId.value = it
        }
        if (bitmap != null && !bitmap.isRecycled) {
            saveSessionBitmap(sessionId, bitmap)
        }
        val session = VisionChatSession(
            id = sessionId,
            title = question.toImageChatTitle(),
            question = question,
            answer = answer,
            updatedAt = System.currentTimeMillis()
        )
        imageChatSessions.add(0, session)
        if (session.isPersistable()) {
            persistSessions()
        }
    }

    private fun saveCurrentSession(
        question: String,
        answer: String,
        bitmap: Bitmap? = null,
        targetSessionId: String? = null
    ) {
        val now = System.currentTimeMillis()
        val sessionId = targetSessionId ?: _currentSessionId.value ?: UUID.randomUUID().toString().also {
            _currentSessionId.value = it
        }
        
        // Save bitmap if provided and not recycled
        if (bitmap != null && !bitmap.isRecycled) {
            saveSessionBitmap(sessionId, bitmap)
        }

        val cleanAnswer = ModelOutputSanitizer.clean(answer)
        val updated = VisionChatSession(
            id = sessionId,
            title = question.toImageChatTitle(),
            question = question,
            answer = cleanAnswer,
            updatedAt = now
        )
        val index = imageChatSessions.indexOfFirst { it.id == sessionId }
        if (index >= 0) {
            imageChatSessions.removeAt(index)
        }
        imageChatSessions.add(0, updated)
        persistSessions()
    }

    private fun loadSessions() {
        val raw = historyPrefs.getString(KEY_SESSIONS, null) ?: return
        runCatching {
            val array = JSONArray(raw)
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val answer = ModelOutputSanitizer.clean(item.optString("answer").orEmpty())
                // Skip sessions whose answer is blank or a stale placeholder
                if (answer.isBlank() || answer in PLACEHOLDER_ANSWERS) continue
                val question = item.optString("question").ifBlank { DEFAULT_IMAGE_PROMPT }
                imageChatSessions.add(
                    VisionChatSession(
                        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                        title = item.optString("title").ifBlank { question.toImageChatTitle() },
                        question = question,
                        answer = answer,
                        updatedAt = item.optLong("updatedAt", 0L)
                    )
                )
            }
        }.onFailure {
            historyPrefs.edit().remove(KEY_SESSIONS).commit()
        }
    }

    private fun persistSessions() {
        val array = JSONArray()
        imageChatSessions.filter { it.isPersistable() }.forEach { session ->
            array.put(
                JSONObject()
                    .put("id", session.id)
                    .put("title", session.title)
                    .put("question", session.question)
                    .put("answer", ModelOutputSanitizer.clean(session.answer))
                    .put("updatedAt", session.updatedAt)
            )
        }
        historyPrefs.edit().putString(KEY_SESSIONS, array.toString()).commit()
    }

    private fun VisionChatSession.isPersistable(): Boolean {
        val cleanAnswer = answer.trim()
        return cleanAnswer.isNotBlank() && cleanAnswer !in PLACEHOLDER_ANSWERS
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
        val task = basePrompt.ifBlank {
            if (fileName.isZipFileName()) {
                "Analyze and summarize this ZIP archive. Mention the folder/file structure, readable contents, important files, and any useful next steps."
            } else {
                "Analyze and summarize this attached file. Mention the main topic, important details, and any useful next steps."
            }
        }
        val promptFileText = fileText.limitForPrompt(budget)
        return buildString {
            append("Ignore any blank placeholder image. Use only the extracted attached-file text below.\n")
            append("Do not say that the file is missing. Do not ask the user to attach it again.\n\n")
            append("User request: ").append(task).append("\n\n")
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
        val contextTokens = currentContextWindowTokens.coerceAtLeast(MIN_CONTEXT_WINDOW_TOKENS)
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
            normalized.startsWith("this file type is not text-extractable")
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
        saveCurrentSession(
            question = userMessage.text,
            answer = answer,
            bitmap = userMessage.bitmap,
            targetSessionId = _currentSessionId.value
        )
    }

    private fun String.toImageChatTitle(): String {
        return lineSequence()
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
            .trim()
            .take(48)
            .ifBlank { "Image question" }
    }

    override fun onCleared() {
        saveVisibleConversationIfComplete()
        releaseModel(clearCoordinator = true)
        ModelRuntimeCoordinator.unregister(ModelRuntimeOwner.Vision)
        super.onCleared()
    }

    private fun releaseModel(clearCoordinator: Boolean) {
        initGeneration++
        analysisGeneration++
        initJob?.cancel()
        initJob = null
        analysisJob?.cancel()
        analysisJob = null
        inferenceManager.cancelGeneration()
        inferenceManager.close()
        currentModelPath = null
        currentContextWindowTokens = DEFAULT_VISION_CONTEXT_WINDOW_TOKENS
        resetRuntimeConversationBeforeNextAsk = false
        _isInitializing.value = false
        _isAnalyzing.value = false
        _isStopping.value = false
        _isModelReady.value = false
        if (clearCoordinator) {
            ModelRuntimeCoordinator.clear(ModelRuntimeOwner.Vision)
        }
    }

    private fun resolveFinalVisionAnswer(
        response: String,
        current: String,
        thinkingMode: Boolean
    ): String {
        val normalizedResponse = if (thinkingMode) {
            ThinkingTextUtils.normalizeFinalOutput(response)
        } else {
            ModelOutputSanitizer.clean(response).trim()
        }
        if (normalizedResponse.isUsableVisionAnswer()) {
            return normalizedResponse
        }

        val normalizedCurrent = ThinkingTextUtils.normalizeFinalOutput(current)
        if (normalizedCurrent.isUsableVisionAnswer()) {
            return normalizedCurrent
        }

        return ""
    }

    private fun String.isUsableVisionAnswer(): Boolean {
        val normalized = trim()
        return normalized.isNotBlank() &&
            normalized !in PLACEHOLDER_ANSWERS &&
            !normalized.isNoAnswerGenerated()
    }

    private companion object {
        const val KEY_SESSIONS = "sessions_json"
        const val DEFAULT_IMAGE_PROMPT = "Describe the image"
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
        val PLACEHOLDER_ANSWERS = setOf("Reading input…", "Reading input...", "…", "Generating…", "Generating...")
    }
}

private fun Int.withThousandsSeparator(): String {
    return toString()
        .reversed()
        .chunked(3)
        .joinToString(",")
        .reversed()
}
