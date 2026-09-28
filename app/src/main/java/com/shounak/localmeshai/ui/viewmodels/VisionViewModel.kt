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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import com.shounak.localmeshai.utils.AppSettings
import android.util.Log
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CancellationException as FutureCancellationException
import java.util.UUID
import com.shounak.localmeshai.rag.RagManager

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
    val imagePath: String? = null
)

class VisionViewModel(application: Application) : AndroidViewModel(application) {
    private val inferenceManager = VisionInferenceManager(application)
    private val historyPrefs = application.getSharedPreferences("vision_chat_history", Context.MODE_PRIVATE)
    private val ragManager = RagManager.getInstance(application)
    private var pendingRagDocumentId: String? = null
    var currentSessionDocumentId: String? = null
        private set

    fun setPendingRagDocumentId(docId: String?) {
        pendingRagDocumentId = docId
        if (docId != null) {
            currentSessionDocumentId = docId
        }
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

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _isModelReady = MutableStateFlow(false)
    val isModelReady = _isModelReady.asStateFlow()

    private val _activeBackend = MutableStateFlow<String?>(null)
    val activeBackend = _activeBackend.asStateFlow()

    private val appSettings = AppSettings.getInstance(application)

    private var currentModelPath: String? = null
    private var currentModelId: String = ""
    private var currentModelName: String = ""
    private var currentModelSize: String = ""
    private var currentContextWindowTokensTokens: Int? = null
    private var currentSupportsAudioInput: Boolean = false
    private var currentAllowUnsafeOverride: Boolean = false
    private var lastLoadedBackendPreference: String? = null
    private var currentContextWindowTokens: Int = DEFAULT_VISION_CONTEXT_WINDOW_TOKENS
    private var initGeneration: Int = 0
    private var initJob: Job? = null
    private var analysisGeneration: Int = 0
    private var analysisJob: Job? = null
    private var lastAttachedFileName: String? = null
    @Volatile private var resetRuntimeConversationBeforeNextAsk = false

    // Serializes vision init and ask so they cannot run at the same time.
    private val ioMutex = Mutex()

    init {
        ModelRuntimeCoordinator.register(ModelRuntimeOwner.Vision) {
            releaseModel(clearCoordinator = false)
        }
        loadSessions()
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
                            allowUnsafeOverride = currentAllowUnsafeOverride,
                            forceReload = true
                        )
                    }
                }
        }
    }

    fun initModel(
        path: String,
        modelId: String = "",
        modelName: String = "",
        modelSize: String = "",
        contextWindowTokens: Int? = null,
        supportsAudioInput: Boolean = false,
        allowUnsafeOverride: Boolean = false,
        forceReload: Boolean = false
    ) {
        val resolvedContextWindowTokens = contextWindowTokens
            ?: inferContextWindowTokens(path)
            ?: DEFAULT_VISION_CONTEXT_WINDOW_TOKENS

        if (!forceReload || path != currentModelPath) {
            appSettings.updateSettings { it.copy(llamaBackendPreference = "AUTO") }
        }
        val currentBackendPref = appSettings.settings.value.llamaBackendPreference
        if (!forceReload && path == currentModelPath && _isModelReady.value && currentBackendPref == lastLoadedBackendPreference && ModelRuntimeCoordinator.isActive(ModelRuntimeOwner.Vision)) {
            currentContextWindowTokens = resolvedContextWindowTokens
            return
        }

        currentModelPath = path
        currentModelId = modelId
        currentModelName = modelName
        currentModelSize = modelSize
        currentContextWindowTokensTokens = contextWindowTokens
        currentSupportsAudioInput = supportsAudioInput
        currentAllowUnsafeOverride = allowUnsafeOverride

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
                        allowUnsafeOverride = allowUnsafeOverride,
                        contextWindowTokens = resolvedContextWindowTokens
                    )
                    // Re-check after the (potentially long) native init.
                    if (myGen != initGeneration) {
                        inferenceManager.close()
                        return@withLock
                    }
                    currentModelPath = path
                    lastLoadedBackendPreference = currentBackendPref
                    currentContextWindowTokens = resolvedContextWindowTokens
                    resetRuntimeConversationBeforeNextAsk = false
                    _activeBackend.value = inferenceManager.activeBackendDisplayName
                    _isModelReady.value = true
                } catch (exception: Exception) {
                    if (myGen != initGeneration) return@withLock
                    _error.value = exception.message ?: "Failed to load multimodal model."
                    currentModelPath = null
                    lastLoadedBackendPreference = null
                    _activeBackend.value = null
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
                    } catch (e: Exception) {
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
            retriever.setDataSource(file.absolutePath)
            val dur = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            retriever.release()
            if (dur != null && dur > 0L) return dur
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
        thinkingMode: Boolean = false
    ) {
        if (_isInitializing.value || _isAnalyzing.value || _isStopping.value) return
        if (!_isModelReady.value) return
        if (bitmap?.isRecycled == true) return

        val myGen = ++analysisGeneration
        val jobSessionId = _currentSessionId.value

        // When switching to a different attached document in the same chat, reset the runtime
        // KV cache so previous document context does not overflow the token window or pollute the answer.
        if (fileName != null && lastAttachedFileName != null && fileName != lastAttachedFileName) {
            resetRuntimeConversationBeforeNextAsk = true
        }
        if (fileName != null) {
            lastAttachedFileName = fileName
        }

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

        // If no explicit image bitmap was provided, check if the attached file is a PDF that
        // either has no extractable text or failed text extraction. Multimodal vision models
        // can inspect rendered PDF pages visually.
        var renderedPdfBitmap: Bitmap? = null
        val isPdf = fileUri != null && (
            fileName?.endsWith(".pdf", ignoreCase = true) == true ||
            getApplication<Application>().contentResolver.getType(fileUri)?.contains("pdf", ignoreCase = true) == true
        )
        if (bitmap == null && isPdf && (fileText.isBlank() || fileText.isExtractionFailureText())) {
            renderedPdfBitmap = renderPdfPagesToBitmap(getApplication(), fileUri)
            if (renderedPdfBitmap != null) {
                fileText = ""
            }
        }
        val effectiveBitmap = bitmap ?: renderedPdfBitmap
        val promptFileText = fileText.limitForPrompt(documentBudget)
        val targetDocId = pendingRagDocumentId ?: currentSessionDocumentId
        pendingRagDocumentId = null
        if (targetDocId != null) {
            currentSessionDocumentId = targetDocId
        }

        val activeSessionId = jobSessionId ?: _currentSessionId.value ?: UUID.randomUUID().toString().also {
            _currentSessionId.value = it
        }

        val sessionAttachmentsDir = getApplication<Application>().filesDir.resolve("chat_attachments").resolve(activeSessionId)
        if (!sessionAttachmentsDir.exists()) {
            sessionAttachmentsDir.mkdirs()
        }

        var persistentImageFile: File? = null
        if (effectiveBitmap != null && !effectiveBitmap.isRecycled) {
            val imgFile = sessionAttachmentsDir.resolve("image_${System.currentTimeMillis()}.png")
            try {
                java.io.FileOutputStream(imgFile).use { out ->
                    effectiveBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                persistentImageFile = imgFile
                val legacyFile = getSessionBitmapFile(activeSessionId)
                java.io.FileOutputStream(legacyFile).use { out ->
                    effectiveBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            } catch (e: Exception) {
                Log.e("VisionViewModel", "Failed to persist image attachment", e)
            }
        }

        var persistentAudioFile: File? = null
        var resolvedAudioDurationMs = 0L
        if (audioBytes != null && audioBytes.isNotEmpty()) {
            val file = sessionAttachmentsDir.resolve("audio_${System.currentTimeMillis()}.wav")
            try {
                file.outputStream().use { it.write(audioBytes) }
                persistentAudioFile = file
                resolvedAudioDurationMs = extractAudioDurationMs(file)
            } catch (e: Exception) {
                Log.e("VisionViewModel", "Failed to write audio bytes to attachments", e)
            }
        } else if (audioUri != null) {
            val ext = audioName?.substringAfterLast('.', missingDelimiterValue = "wav")?.takeIf { it.isNotBlank() } ?: "wav"
            val file = sessionAttachmentsDir.resolve("audio_${System.currentTimeMillis()}.$ext")
            try {
                getApplication<Application>().contentResolver.openInputStream(audioUri)?.use { inStream ->
                    file.outputStream().use { outStream ->
                        inStream.copyTo(outStream)
                    }
                }
                persistentAudioFile = file
                resolvedAudioDurationMs = extractAudioDurationMs(file)
            } catch (e: Exception) {
                Log.e("VisionViewModel", "Failed to copy audio uri to attachments", e)
            }
        }

        var persistentDocFile: File? = null
        if (fileUri != null && fileName != null) {
            val docFile = sessionAttachmentsDir.resolve(fileName)
            if (!docFile.exists()) {
                try {
                    getApplication<Application>().contentResolver.openInputStream(fileUri)?.use { inStream ->
                        docFile.outputStream().use { outStream ->
                            inStream.copyTo(outStream)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("VisionViewModel", "Failed to copy document to attachments", e)
                }
            }
            if (docFile.exists()) {
                persistentDocFile = docFile
            }
        }

        // Add message to chat list
        val displayPrompt = basePrompt.trim()

        // Preserve previous multimodal turns in the visible chat. New Chat is the
        // explicit path that clears this list.
        messages.add(
            VisionChatMessage(
                text = displayPrompt,
                isUser = true,
                bitmap = effectiveBitmap,
                imagePath = persistentImageFile?.absolutePath,
                audioPath = persistentAudioFile?.absolutePath,
                audioName = audioName ?: if (persistentAudioFile != null) "Voice message" else null,
                audioDurationMs = resolvedAudioDurationMs,
                documentName = fileName,
                documentPath = persistentDocFile?.absolutePath
            )
        )
        val assistantIndex = messages.size
        messages.add(VisionChatMessage("Reading input…", false, null))

        // Ensure session is created (not saved to disk yet with placeholder answer)
        ensureCurrentSession(
            question = displayPrompt,
            answer = "Reading input…",
            bitmap = effectiveBitmap,
            imagePath = persistentImageFile?.absolutePath,
            audioPath = persistentAudioFile?.absolutePath,
            audioName = audioName ?: if (persistentAudioFile != null) "Voice message" else null,
            audioDurationMs = resolvedAudioDurationMs,
            documentName = fileName,
            documentId = targetDocId
        )
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

                    // Check if RAG is active and query context if applicable
                    var currentRagSources: List<String> = emptyList()
                    var currentRagChunkCount = 0
                    var currentRagTopMatchPct = 0
                    var ragAugmentedContext: String? = null

                    var effectiveDocId = targetDocId ?: currentSessionDocumentId
                    if (effectiveDocId == null) {
                        val activeSession = _currentSessionId.value?.let { id ->
                            imageChatSessions.firstOrNull { it.id == id }
                        }
                        effectiveDocId = activeSession?.documentId
                            ?: activeSession?.documentName?.let { docName ->
                                ragManager.indexedDocuments.firstOrNull { it.documentName.equals(docName, ignoreCase = true) }?.documentId
                            }
                            ?: messages.firstNotNullOfOrNull { it.documentName }?.let { docName ->
                                ragManager.indexedDocuments.firstOrNull { it.documentName.equals(docName, ignoreCase = true) }?.documentId
                            }
                            ?: ragManager.findMatchingDocumentForQuery(basePrompt)?.documentId
                    }

                    val shouldQueryRag = basePrompt.isNotBlank() && ragManager.totalIndexedChunks > 0
                    if (shouldQueryRag) {
                        val ragSettings = appSettings.settings.value
                        val queryForRag = basePrompt
                        val retrieval = ragManager.retrieve(
                            query = queryForRag,
                            topK = ragSettings.ragTopK,
                            minScore = ragSettings.ragMinSimilarity,
                            preferredDocumentId = effectiveDocId
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

                    // Build prompt
                    val contentPrompt = buildString {
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
                    val prompt = if (thinkingMode && contentPrompt.isNotBlank()) {
                        "Reason carefully before giving the final answer.\n\n$contentPrompt"
                    } else {
                        contentPrompt
                    }

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
                        val bitmapCopy = effectiveBitmap?.copy(effectiveBitmap.config ?: Bitmap.Config.ARGB_8888, false)
                        val audioFile = persistentAudioFile ?: audioUri?.let {
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
                        val hasAudio = audioUri != null || audioBytes?.isNotEmpty() == true
                        if (response.isNoAnswerGenerated() && bitmapCopy == null && !hasAudio) {
                            val retryBitmap = createPlaceholderBitmap()
                            val retryPrompt = when {
                                ragAugmentedContext != null -> ragAugmentedContext
                                fileText.isNotBlank() && !fileText.isExtractionFailureText() -> buildDocumentRetryPrompt(
                                    basePrompt = basePrompt,
                                    fileName = fileName,
                                    fileText = fileText,
                                    budget = documentBudget
                                )
                                else -> prompt
                            }
                            response = inferenceManager.askStreaming(
                                bitmap = retryBitmap,
                                question = retryPrompt,
                                audioFile = audioFile,
                                audioBytes = audioBytes
                            ) { partial ->
                                publishPartial(partial)
                            }
                            if (!retryBitmap.isRecycled) {
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
                                            bitmap = null,
                                            ragSources = currentRagSources,
                                            ragChunkCount = currentRagChunkCount,
                                            ragTopMatchPct = currentRagTopMatchPct
                                        )
                                    }
                                    saveCurrentSession(
                                        displayPrompt,
                                        finalResponse.ifBlank { "No answer generated." },
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
                        } else if (_error.value == null) {
                            _error.value = "No answer generated."
                            withContext(Dispatchers.Main.immediate) {
                                if (assistantIndex < messages.size) {
                                    messages[assistantIndex] = messages[assistantIndex].copy(
                                        text = "No answer generated.",
                                        bitmap = null
                                    )
                                }
                                saveCurrentSession(
                                    displayPrompt,
                                    "No answer generated.",
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
                            saveCurrentSession(
                                displayPrompt,
                                finalMsg,
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
                            saveCurrentSession(
                                displayPrompt,
                                finalMsg,
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
                        withContext(Dispatchers.Main.immediate) {
                            val errorMsg = "⚠️ Error: ${exception.message ?: "Image question failed."}"
                            if (myGen == analysisGeneration && assistantIndex < messages.size) {
                                messages[assistantIndex] = messages[assistantIndex].copy(
                                    text = errorMsg,
                                    bitmap = null
                                )
                            }
                            saveCurrentSession(
                                displayPrompt,
                                errorMsg,
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
                        // Always clear both analyzing and stopping flags
                        _isAnalyzing.value = false
                        _isStopping.value = false
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
        if (!_isAnalyzing.value && !_isStopping.value) return
        analysisGeneration++
        resetRuntimeConversationBeforeNextAsk = true
        inferenceManager.cancelGeneration()
        analysisJob?.cancel()
        _isAnalyzing.value = false
        _isStopping.value = false

        // Immediately update UI state on the main thread so "Reading input..." changes to "Stopped."
        val assistantIndex = messages.indexOfLast { !it.isUser }
        if (assistantIndex >= 0 && assistantIndex < messages.size) {
            val current = messages[assistantIndex].text
            val finalText = if (current == "Reading input…" || current == "Reading input..." || current == "…" || current.isBlank()) {
                "Stopped."
            } else {
                ModelOutputSanitizer.clean(current)
            }
            messages[assistantIndex] = messages[assistantIndex].copy(text = finalText)
            saveVisibleConversationIfComplete()
        }
        _answer.value = "Stopped."
    }

    fun uninitializeModel() {
        releaseModel(clearCoordinator = true)
    }
    fun startNewChat() {
        stopAnalyzing()
        _currentSessionId.value = null
        resetRuntimeConversationBeforeNextAsk = true
        lastAttachedFileName = null
        _answer.value = ""
        _error.value = null
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
        } catch (e: Exception) {
            e.printStackTrace()
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
                        } catch (_: Exception) {
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
                } catch (e: Exception) {
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
        return session
    }

    fun deleteChatSession(sessionId: String) {
        val removingCurrent = sessionId == _currentSessionId.value
        val session = imageChatSessions.firstOrNull { it.id == sessionId }
        session?.documentId?.let { docId ->
            ragManager.removeDocument(docId)
        }
        session?.imagePath?.let { path ->
            val imgFile = File(path)
            if (imgFile.exists()) imgFile.delete()
        }
        session?.messages?.forEach { msg ->
            msg.imagePath?.let { path ->
                val imgFile = File(path)
                if (imgFile.exists()) imgFile.delete()
            }
        }
        imageChatSessions.removeAll { it.id == sessionId }
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
        val sessionAttachmentsDir = getApplication<Application>().filesDir.resolve("chat_attachments").resolve(sessionId)
        if (sessionAttachmentsDir.exists()) {
            sessionAttachmentsDir.deleteRecursively()
        }
        persistSessions()
    }

    fun clearHistory() {
        imageChatSessions.forEach { session ->
            session.documentId?.let { docId ->
                ragManager.removeDocument(docId)
            }
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
        val attachmentsDir = getApplication<Application>().filesDir.resolve("chat_attachments")
        if (attachmentsDir.exists()) {
            attachmentsDir.deleteRecursively()
        }
        ragManager.clearKnowledgeBase()
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
                if (updated.isPersistable()) {
                    persistSessions()
                }
                return
            }
        }
        val sessionId = currentId ?: UUID.randomUUID().toString().also {
            _currentSessionId.value = it
        }
        if (bitmap != null && !bitmap.isRecycled) {
            saveSessionBitmap(sessionId, bitmap)
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
        if (session.isPersistable()) {
            persistSessions()
        }
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
        
        // Save bitmap if provided and not recycled
        if (bitmap != null && !bitmap.isRecycled) {
            saveSessionBitmap(sessionId, bitmap)
        }

        val cleanAnswer = ModelOutputSanitizer.clean(answer)
        val index = imageChatSessions.indexOfFirst { it.id == sessionId }
        val previous = if (index >= 0) imageChatSessions[index] else null
        val shouldUpdateTimestamp = index == -1 ||
            previous?.question != question ||
            previous?.answer != cleanAnswer
        val effectiveUpdatedAt = if (shouldUpdateTimestamp || previous?.updatedAt?.let { it <= 0L } == true) {
            now
        } else {
            previous?.updatedAt ?: now
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

    private fun loadSessions() {
        val raw = historyPrefs.getString(KEY_SESSIONS, null) ?: return
        runCatching {
            val array = JSONArray(raw)
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val answer = ModelOutputSanitizer.clean(item.optString("answer").orEmpty())
                // Skip sessions whose answer is blank or a stale placeholder
                if (answer.isBlank() || answer in PLACEHOLDER_ANSWERS) continue
                val question = item.optString("question")
                val imagePath = item.optString("imagePath").takeIf { it.isNotBlank() }
                val audioPath = item.optString("audioPath").takeIf { it.isNotBlank() }
                val audioName = item.optString("audioName").takeIf { it.isNotBlank() }
                val audioDurationMs = item.optLong("audioDurationMs", 0L)
                val documentName = item.optString("documentName").takeIf { it.isNotBlank() }
                val documentId = item.optString("documentId").takeIf { it.isNotBlank() }

                val sessionMessages = mutableListOf<VisionChatMessage>()
                val messagesArray = item.optJSONArray("messages")
                if (messagesArray != null && messagesArray.length() > 0) {
                    for (mIdx in 0 until messagesArray.length()) {
                        val mObj = messagesArray.getJSONObject(mIdx)
                        val mText = mObj.optString("text")
                        val mIsUser = mObj.optBoolean("isUser")
                        val mId = mObj.optString("id").ifBlank { UUID.randomUUID().toString() }
                        val mImagePath = mObj.optString("imagePath").takeIf { it.isNotBlank() }
                        val mAudioPath = mObj.optString("audioPath").takeIf { it.isNotBlank() }
                        val mAudioName = mObj.optString("audioName").takeIf { it.isNotBlank() }
                        val mAudioDurationMs = mObj.optLong("audioDurationMs", 0L)
                        val mDocumentName = mObj.optString("documentName").takeIf { it.isNotBlank() }
                        val mDocumentPath = mObj.optString("documentPath").takeIf { it.isNotBlank() }
                        val mRagChunkCount = mObj.optInt("ragChunkCount", 0)
                        val mRagTopMatchPct = mObj.optInt("ragTopMatchPct", 0)

                        val ragSourcesList = mutableListOf<String>()
                        val mRagSources = mObj.optJSONArray("ragSources")
                        if (mRagSources != null) {
                            for (sIdx in 0 until mRagSources.length()) {
                                ragSourcesList.add(mRagSources.getString(sIdx))
                            }
                        }

                        sessionMessages.add(
                            VisionChatMessage(
                                id = mId,
                                text = mText,
                                isUser = mIsUser,
                                bitmap = null, // decoded on demand in selectChatSession
                                imagePath = mImagePath,
                                audioPath = mAudioPath,
                                audioName = mAudioName,
                                audioDurationMs = mAudioDurationMs,
                                documentName = mDocumentName,
                                documentPath = mDocumentPath,
                                ragSources = ragSourcesList,
                                ragChunkCount = mRagChunkCount,
                                ragTopMatchPct = mRagTopMatchPct
                            )
                        )
                    }
                }

                imageChatSessions.add(
                    VisionChatSession(
                        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                        title = item.optString("title").ifBlank {
                            question.toImageChatTitle(
                                documentName = documentName,
                                audioName = audioName,
                                hasImage = !imagePath.isNullOrBlank()
                            )
                        },
                        question = question,
                        answer = answer,
                        updatedAt = item.optLong("updatedAt", 0L),
                        imagePath = imagePath,
                        audioPath = audioPath,
                        audioName = audioName,
                        audioDurationMs = audioDurationMs,
                        documentName = documentName,
                        documentId = documentId,
                        messages = sessionMessages
                    )
                )
            }
            imageChatSessions.sortByDescending { it.updatedAt }
        }.onFailure {
            historyPrefs.edit().remove(KEY_SESSIONS).apply()
        }
    }

    private fun persistSessions() {
        val array = JSONArray()
        imageChatSessions.filter { it.isPersistable() }.forEach { session ->
            val obj = JSONObject()
                .put("id", session.id)
                .put("title", session.title)
                .put("question", session.question)
                .put("answer", ModelOutputSanitizer.clean(session.answer))
                .put("updatedAt", session.updatedAt)
                .put("imagePath", session.imagePath ?: "")
                .put("audioPath", session.audioPath ?: "")
                .put("audioName", session.audioName ?: "")
                .put("audioDurationMs", session.audioDurationMs)
                .put("documentName", session.documentName ?: "")
                .put("documentId", session.documentId ?: "")

            val messagesArray = JSONArray()
            session.messages.forEach { msg ->
                val msgObj = JSONObject()
                    .put("id", msg.id)
                    .put("text", msg.text)
                    .put("isUser", msg.isUser)
                    .put("imagePath", msg.imagePath ?: "")
                    .put("audioPath", msg.audioPath ?: "")
                    .put("audioName", msg.audioName ?: "")
                    .put("audioDurationMs", msg.audioDurationMs)
                    .put("documentName", msg.documentName ?: "")
                    .put("documentPath", msg.documentPath ?: "")
                    .put("ragChunkCount", msg.ragChunkCount)
                    .put("ragTopMatchPct", msg.ragTopMatchPct)

                val ragSourcesArray = JSONArray()
                msg.ragSources.forEach { ragSourcesArray.put(it) }
                msgObj.put("ragSources", ragSourcesArray)

                messagesArray.put(msgObj)
            }
            obj.put("messages", messagesArray)

            array.put(obj)
        }
        // Chat history does not require crash-guard-style synchronous durability.
        // Avoid blocking the UI thread while the JSON is written to disk.
        historyPrefs.edit().putString(KEY_SESSIONS, array.toString()).apply()
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
        currentModelId = ""
        currentModelName = ""
        currentModelSize = ""
        currentContextWindowTokensTokens = null
        currentSupportsAudioInput = false
        currentAllowUnsafeOverride = false
        lastLoadedBackendPreference = null
        _activeBackend.value = null
        currentContextWindowTokens = DEFAULT_VISION_CONTEXT_WINDOW_TOKENS
        resetRuntimeConversationBeforeNextAsk = false
        lastAttachedFileName = null
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
