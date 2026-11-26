package com.shounak.localmeshai.ai.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

data class SpeechHighlight(
    val utteranceId: String,
    val wordIndex: Int,
    val word: String,
    val charStart: Int = 0,
    val charEnd: Int = 0
)

data class SpokenWordToken(
    val index: Int,
    val word: String,
    val charStart: Int,
    val charEnd: Int,
    val hasSentenceEnd: Boolean = false,
    val hasClauseEnd: Boolean = false,
    val hasLineBreak: Boolean = false
)

/**
 * Lazy on-device TTS engine manager.
 * Keeps zero ONNX/TTS RAM allocated at app startup; initializes strictly on demand
 * when the user taps a speaker button, Test Voice, or enters Hands-Free Voice Mode,
 * and unloads cleanly on idle timeout or memory trim.
 */
class TinyVocaManager internal constructor(
    private val appContext: Context,
    private val modelFileOverride: File? = null,
    private val idleTimeoutMs: Long = IDLE_UNLOAD_DELAY_MS,
    private val customInitializer: (suspend () -> Boolean)? = null,
    private val customUnloader: (() -> Unit)? = null,
    private val customSpeaker: ((text: String, utteranceId: String, voice: String, speed: Float) -> Boolean)? = null
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val httpClient = OkHttpClient.Builder().build()
    private val initMutex = Mutex()

    private var tts: TextToSpeech? = null
    @Volatile private var isTtsInitialized = false
    @Volatile private var ortSession: OrtSession? = null

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _currentSpeakingId = MutableStateFlow<String?>(null)
    val currentSpeakingId: StateFlow<String?> = _currentSpeakingId.asStateFlow()

    private val _currentHighlight = MutableStateFlow<SpeechHighlight?>(null)
    val currentHighlight: StateFlow<SpeechHighlight?> = _currentHighlight.asStateFlow()

    private val _isInitializing = MutableStateFlow(false)
    val isInitializing: StateFlow<Boolean> = _isInitializing.asStateFlow()

    private val _initializingTargetId = MutableStateFlow<String?>(null)
    val initializingTargetId: StateFlow<String?> = _initializingTargetId.asStateFlow()

    private val spokenTextMap = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val cachedTokensMap = java.util.concurrent.ConcurrentHashMap<String, List<SpokenWordToken>>()
    private val pendingQueuedUtterances = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
    @Volatile private var queuedQueueEmptyCallback: (() -> Unit)? = null
    private var fallbackHighlightJob: Job? = null
    private var idleUnloadJob: Job? = null
    private var speakRequestJob: Job? = null
    @Volatile private var hasRangeStartFired = false
    @Volatile private var currentConfiguredSpeed = 1.0f

    private val _downloadProgress = MutableStateFlow<Float?>(null)
    val downloadProgress: StateFlow<Float?> = _downloadProgress.asStateFlow()

    private val _isModelDownloaded = MutableStateFlow(false)
    val isModelDownloaded: StateFlow<Boolean> = _isModelDownloaded.asStateFlow()

    private val _isModelResidentInRam = MutableStateFlow(false)
    val isModelResidentInRam: StateFlow<Boolean> = _isModelResidentInRam.asStateFlow()

    val modelFile: File
        get() = modelFileOverride ?: File(appContext.filesDir, "models/tiny_voca.onnx")

    val hasBundledAsset: Boolean
        get() = false

    init {
        // Strictly zero ONNX / TextToSpeech allocation on construction.
        checkModelStatus()
    }

    fun checkModelStatus() {
        val exists = modelFile.exists() && modelFile.length() > 1_000_000L
        _isModelDownloaded.value = exists
    }

    /**
     * Initializes the ONNX session and TextToSpeech engine strictly on demand.
     * Safe to call concurrently; returns true when ready.
     */
    suspend fun ensureInitializedOnDemand(): Boolean = initMutex.withLock {
        cancelIdleUnload()
        checkModelStatus()
        if (!_isModelDownloaded.value) {
            return@withLock false
        }
        val alreadyReady = if (customInitializer != null) {
            _isModelResidentInRam.value
        } else {
            isTtsInitialized && tts != null && ortSession != null
        }
        if (alreadyReady) {
            return@withLock true
        }

        _isInitializing.value = true
        try {
            if (customInitializer != null) {
                val ok = customInitializer.invoke()
                _isModelResidentInRam.value = ok
                return@withLock ok
            }

            val onnxReady = withContext(Dispatchers.IO) {
                if (ortSession != null) return@withContext true
                runCatching {
                    if (modelFile.exists()) {
                        val env = OrtEnvironment.getEnvironment()
                        val opts = OrtSession.SessionOptions().apply {
                            setIntraOpNumThreads(2)
                        }
                        ortSession = env.createSession(modelFile.absolutePath, opts)
                        _isModelResidentInRam.value = true
                        runCatching { Log.i(TAG, "TinyVoca neural model loaded on demand: ${modelFile.length()} bytes") }
                        true
                    } else {
                        false
                    }
                }.getOrElse { e ->
                    runCatching { Log.w(TAG, "Could not load TinyVoca ONNX model on demand: ${e.message}") }
                    false
                }
            }
            if (!onnxReady) return@withLock false

            if (isTtsInitialized && tts != null) {
                return@withLock true
            }

            val ttsReady = withTimeoutOrNull(TTS_INIT_TIMEOUT_MS) {
                withContext(Dispatchers.Main.immediate) {
                    suspendCancellableCoroutine { cont ->
                        var localTts: TextToSpeech? = null
                        localTts = TextToSpeech(appContext) { status ->
                            if (status == TextToSpeech.SUCCESS) {
                                localTts?.language = Locale.US
                                localTts?.setOnUtteranceProgressListener(createUtteranceListener())
                                tts = localTts
                                isTtsInitialized = true
                                runCatching { Log.i(TAG, "Android TextToSpeech engine initialized on demand") }
                                if (cont.isActive) cont.resume(true)
                            } else {
                                runCatching { Log.w(TAG, "Failed to initialize Android TextToSpeech engine (status=$status)") }
                                runCatching { localTts?.shutdown() }
                                if (cont.isActive) cont.resume(false)
                            }
                        }
                        cont.invokeOnCancellation {
                            if (!isTtsInitialized) {
                                runCatching { localTts.shutdown() }
                            }
                        }
                    }
                }
            } ?: false

            if (!ttsReady) {
                unloadInternal("tts_init_failed")
            }
            ttsReady
        } finally {
            _isInitializing.value = false
        }
    }

    private fun createUtteranceListener(): UtteranceProgressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            cancelIdleUnload()
            _isSpeaking.value = true
            _currentSpeakingId.value = utteranceId
            hasRangeStartFired = false
            fallbackHighlightJob?.cancel()
            if (utteranceId != null) {
                val text = spokenTextMap[utteranceId].orEmpty()
                val tokens = cachedTokensMap.getOrPut(utteranceId) { tokenizeSpokenWords(text) }
                val speed = currentConfiguredSpeed
                fallbackHighlightJob = scope.launch(Dispatchers.Main) {
                    val initialDelayMs = (240L / speed).toLong().coerceIn(120L, 400L)
                    delay(initialDelayMs)
                    if (!hasRangeStartFired && _isSpeaking.value && _currentSpeakingId.value == utteranceId) {
                        for (i in tokens.indices) {
                            if (!_isSpeaking.value || _currentSpeakingId.value != utteranceId || hasRangeStartFired) break
                            val token = tokens[i]
                            _currentHighlight.value = SpeechHighlight(
                                utteranceId = utteranceId,
                                wordIndex = i,
                                word = token.word,
                                charStart = token.charStart,
                                charEnd = token.charEnd
                            )
                            val delayMs = calculateTokenDelay(token, speed)
                            delay(delayMs)
                        }
                    }
                }
            }
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            if (utteranceId != null) {
                hasRangeStartFired = true
                fallbackHighlightJob?.cancel()
                val text = spokenTextMap[utteranceId].orEmpty()
                val tokens = cachedTokensMap.getOrPut(utteranceId) { tokenizeSpokenWords(text) }
                val matchingToken = tokens.firstOrNull { start in it.charStart..it.charEnd }
                    ?: tokens.firstOrNull { it.charStart >= start }
                    ?: tokens.lastOrNull()
                if (matchingToken != null) {
                    _currentHighlight.value = SpeechHighlight(
                        utteranceId = utteranceId,
                        wordIndex = matchingToken.index,
                        word = matchingToken.word,
                        charStart = matchingToken.charStart,
                        charEnd = matchingToken.charEnd
                    )
                }
            }
        }

        override fun onDone(utteranceId: String?) {
            fallbackHighlightJob?.cancel()
            if (utteranceId != null) {
                spokenTextMap.remove(utteranceId)
                cachedTokensMap.remove(utteranceId)
                pendingQueuedUtterances.remove(utteranceId)
            }
            if (pendingQueuedUtterances.isEmpty()) {
                _isSpeaking.value = false
                _currentSpeakingId.value = null
                _currentHighlight.value = null
                queuedQueueEmptyCallback?.invoke()
                scheduleIdleUnload()
            }
        }

        @Deprecated("Deprecated in Java", ReplaceWith("onError(utteranceId, -1)"))
        override fun onError(utteranceId: String?) {
            onError(utteranceId, -1)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            fallbackHighlightJob?.cancel()
            if (utteranceId != null) {
                spokenTextMap.remove(utteranceId)
                cachedTokensMap.remove(utteranceId)
                pendingQueuedUtterances.remove(utteranceId)
            }
            if (pendingQueuedUtterances.isEmpty()) {
                _isSpeaking.value = false
                _currentSpeakingId.value = null
                _currentHighlight.value = null
                queuedQueueEmptyCallback?.invoke()
                scheduleIdleUnload()
            }
        }
    }

    private fun cancelIdleUnload() {
        idleUnloadJob?.cancel()
        idleUnloadJob = null
    }

    private fun scheduleIdleUnload() {
        if (idleTimeoutMs <= 0L) return
        cancelIdleUnload()
        idleUnloadJob = scope.launch {
            delay(idleTimeoutMs)
            if (!_isSpeaking.value && !_isInitializing.value) {
                unloadFromMemory("idle_timeout")
            }
        }
    }

    /**
     * Releases the ONNX session and TTS engine from RAM while keeping downloaded weights on disk.
     */
    fun unloadFromMemory(reason: String = "manual") {
        cancelIdleUnload()
        speakRequestJob?.cancel()
        speakRequestJob = null
        _initializingTargetId.value = null
        stopPlaybackOnly()
        unloadInternal(reason)
    }

    private fun unloadInternal(reason: String) {
        try {
            customUnloader?.invoke()
            ortSession?.close()
            ortSession = null
            _isModelResidentInRam.value = false
            tts?.stop()
            tts?.shutdown()
            tts = null
            isTtsInitialized = false
            spokenTextMap.clear()
            cachedTokensMap.clear()
            runCatching { Log.i(TAG, "TinyVoca unloaded from memory (reason=$reason)") }
        } catch (e: Exception) {
            runCatching { Log.w(TAG, "Error unloading TinyVoca ($reason): ${e.message}") }
        }
    }

    /**
     * Download the TinyVoca ONNX model from Hugging Face (~21.4 MB).
     * Leaves the model on disk (unloaded) until explicitly spoken/tested.
     */
    fun downloadTinyVoca(
        onProgress: ((Float) -> Unit)? = null,
        onComplete: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        if (_downloadProgress.value != null) return

        scope.launch(Dispatchers.IO) {
            _downloadProgress.value = 0.01f
            val tempFile = File(appContext.filesDir, "models/tiny_voca.tmp")
            val targetDir = tempFile.parentFile
            if (targetDir != null && !targetDir.exists()) {
                targetDir.mkdirs()
            }

            val url = "https://huggingface.co/n4ze3m/tiny-voca/resolve/main/onnx/model.onnx"
            try {
                val request = Request.Builder().url(url).build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("HTTP ${response.code}: ${response.message}")
                    }
                    val body = response.body
                    val totalBytes = body.contentLength().takeIf { it > 0 } ?: 21_400_000L
                    var downloadedBytes = 0L

                    body.byteStream().use { input ->
                        FileOutputStream(tempFile).use { output ->
                            val buffer = ByteArray(32 * 1024)
                            var bytesRead: Int
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                downloadedBytes += bytesRead
                                val progress = (downloadedBytes.toFloat() / totalBytes).coerceIn(0.01f, 0.99f)
                                _downloadProgress.value = progress
                                withContext(Dispatchers.Main) {
                                    onProgress?.invoke(progress)
                                }
                            }
                            output.flush()
                        }
                    }

                    if (modelFile.exists()) {
                        modelFile.delete()
                    }
                    if (!tempFile.renameTo(modelFile)) {
                        tempFile.copyTo(modelFile, overwrite = true)
                        tempFile.delete()
                    }

                    checkModelStatus()
                    // Keep unloaded in RAM after download until user presses speaker / Test Voice / Voice Mode.
                    _downloadProgress.value = null
                    withContext(Dispatchers.Main) {
                        onComplete?.invoke()
                    }
                    Log.i(TAG, "TinyVoca ONNX model downloaded successfully (lazy load enabled).")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download TinyVoca: ${e.message}", e)
                tempFile.delete()
                _downloadProgress.value = null
                withContext(Dispatchers.Main) {
                    onError?.invoke(e.message ?: "Download failed")
                }
            }
        }
    }

    /**
     * Download the TinyVoca model manually on demand from Hugging Face.
     * This is NEVER called automatically; only when the user explicitly taps "Download".
     */
    fun restoreOrDownloadTinyVoca(
        onProgress: ((Float) -> Unit)? = null,
        onComplete: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        downloadTinyVoca(onProgress, onComplete, onError)
    }

    /**
     * Delete the downloaded TinyVoca model.
     * Once deleted, it stays deleted and is NEVER re-downloaded automatically.
     */
    fun deleteTinyVocaModel(): Boolean {
        return try {
            unloadFromMemory("deleted")
            _downloadProgress.value = null
            val tempFile = File(appContext.filesDir, "models/tiny_voca.tmp")
            if (tempFile.exists()) tempFile.delete()
            val deleted = if (modelFile.exists()) modelFile.delete() else true
            checkModelStatus()
            Log.i(TAG, "TinyVoca model manually deleted by user. Auto-download is strictly disabled.")
            deleted
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting TinyVoca model: ${e.message}", e)
            false
        }
    }

    /**
     * Read text aloud using the configured voice ("F2" / "M2") and speed multiplier (1.0x, 1.25x, 1.5x).
     * Lazily initializes the ONNX session and TTS engine on first explicit invocation.
     */
    fun speak(
        text: String,
        id: String? = null,
        voice: String = "F2",
        speed: Float = 1.0f
    ) {
        checkModelStatus()
        if (!_isModelDownloaded.value) {
            runCatching { Log.w(TAG, "TinyVoca model is not downloaded. Speak disabled.") }
            return
        }
        val clean = cleanTextForSpeech(text)
        if (clean.isBlank()) return

        if (_isSpeaking.value && id != null && _currentSpeakingId.value == id) {
            stop()
            return
        }

        speakRequestJob?.cancel()
        stopPlaybackOnly()

        speakRequestJob = scope.launch {
            _initializingTargetId.value = id
            val initialized = ensureInitializedOnDemand()
            _initializingTargetId.value = null
            if (!initialized) {
                _isSpeaking.value = false
                _currentSpeakingId.value = null
                _currentHighlight.value = null
                scheduleIdleUnload()
                return@launch
            }
            speakInternal(clean, id, voice, speed)
        }
    }

    /**
     * Suspending speech helper for sequential voice mode or tests.
     */
    suspend fun speakOnDemand(
        text: String,
        id: String? = null,
        voice: String = "F2",
        speed: Float = 1.0f
    ): Boolean {
        checkModelStatus()
        if (!_isModelDownloaded.value) return false
        val clean = cleanTextForSpeech(text)
        if (clean.isBlank()) return false
        _initializingTargetId.value = id
        val initialized = try {
            ensureInitializedOnDemand()
        } finally {
            _initializingTargetId.value = null
        }
        if (!initialized) return false
        return speakInternal(clean, id, voice, speed)
    }

    /**
     * Queues a single cleaned sentence onto the active TTS queue (`QUEUE_ADD`) without cutting off
     * previous sentences, initializing TinyVoca lazily on the first sentence if needed.
     */
    suspend fun enqueueSentence(
        sentence: String,
        utteranceId: String = UUID.randomUUID().toString(),
        voice: String = "F2",
        speed: Float = 1.0f,
        onQueueEmpty: (() -> Unit)? = null
    ): Boolean {
        checkModelStatus()
        if (!_isModelDownloaded.value) return false
        val clean = cleanTextForSpeech(sentence)
        if (clean.isBlank()) return false
        _initializingTargetId.value = utteranceId
        val initialized = try {
            ensureInitializedOnDemand()
        } finally {
            _initializingTargetId.value = null
        }
        if (!initialized) return false
        queuedQueueEmptyCallback = onQueueEmpty
        pendingQueuedUtterances.add(utteranceId)
        val ok = speakInternal(clean, utteranceId, voice, speed, queueMode = TextToSpeech.QUEUE_ADD)
        if (!ok) {
            pendingQueuedUtterances.remove(utteranceId)
        }
        return ok
    }

    fun hasPendingQueuedAudio(): Boolean = pendingQueuedUtterances.isNotEmpty() || _isSpeaking.value

    /**
     * Immediately flushes any buffered or actively playing audio (e.g., on voice barge-in)
     * while keeping the lazy ONNX/TTS engine warm for the next listening turn.
     */
    fun flushSpeech() {
        speakRequestJob?.cancel()
        speakRequestJob = null
        _initializingTargetId.value = null
        pendingQueuedUtterances.clear()
        queuedQueueEmptyCallback = null
        spokenTextMap.clear()
        cachedTokensMap.clear()
        stopPlaybackOnly()
    }

    private fun speakInternal(
        clean: String,
        id: String?,
        voice: String,
        speed: Float,
        queueMode: Int = TextToSpeech.QUEUE_FLUSH
    ): Boolean {
        cancelIdleUnload()
        _currentSpeakingId.value = id
        _isSpeaking.value = true

        val speechSpeed = speed.coerceIn(0.5f, 2.5f)
        val utteranceId = id ?: UUID.randomUUID().toString()
        spokenTextMap[utteranceId] = clean

        if (customSpeaker != null) {
            val ok = customSpeaker.invoke(clean, utteranceId, voice, speechSpeed)
            if (!ok) {
                pendingQueuedUtterances.remove(utteranceId)
                if (pendingQueuedUtterances.isEmpty()) {
                    _isSpeaking.value = false
                    _currentSpeakingId.value = null
                    _currentHighlight.value = null
                    scheduleIdleUnload()
                }
            }
            return ok
        }

        val engine = tts
        if (engine == null || !isTtsInitialized) {
            runCatching { Log.w(TAG, "TTS engine not ready after on-demand init") }
            pendingQueuedUtterances.remove(utteranceId)
            if (pendingQueuedUtterances.isEmpty()) {
                _isSpeaking.value = false
                _currentSpeakingId.value = null
                _currentHighlight.value = null
                scheduleIdleUnload()
            }
            return false
        }

        return try {
            engine.setSpeechRate(speechSpeed)
            val isFemale = voice.equals("F2", ignoreCase = true)
            configureVoice(engine, isFemale)

            val params = Bundle()
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            engine.speak(clean, queueMode, params, utteranceId)

            currentConfiguredSpeed = speechSpeed
            hasRangeStartFired = false
            fallbackHighlightJob?.cancel()
            _currentHighlight.value = null
            true
        } catch (e: Exception) {
            runCatching { Log.e(TAG, "Error in speak(): ${e.message}", e) }
            _isSpeaking.value = false
            _currentSpeakingId.value = null
            _currentHighlight.value = null
            fallbackHighlightJob?.cancel()
            scheduleIdleUnload()
            false
        }
    }

    private fun configureVoice(engine: TextToSpeech, isFemale: Boolean) {
        try {
            if (isFemale) {
                engine.setPitch(1.15f)
                val voices = engine.voices.orEmpty()
                val match = voices.firstOrNull { v ->
                    (v.name.contains("female", ignoreCase = true) || v.name.contains("en-us-x-sfg", ignoreCase = true) || v.name.contains("f0", ignoreCase = true)) &&
                        v.locale.language == Locale.US.language
                }
                if (match != null) {
                    engine.voice = match
                }
            } else {
                engine.setPitch(0.85f)
                val voices = engine.voices.orEmpty()
                val match = voices.firstOrNull { v ->
                    (v.name.contains("male", ignoreCase = true) || v.name.contains("en-us-x-iom", ignoreCase = true) || v.name.contains("m0", ignoreCase = true)) &&
                        v.locale.language == Locale.US.language &&
                        !v.name.contains("female", ignoreCase = true)
                }
                if (match != null) {
                    engine.voice = match
                }
            }
        } catch (e: Exception) {
            engine.setPitch(if (isFemale) 1.15f else 0.85f)
        }
    }

    private fun stopPlaybackOnly() {
        fallbackHighlightJob?.cancel()
        fallbackHighlightJob = null
        pendingQueuedUtterances.clear()
        queuedQueueEmptyCallback = null
        try {
            tts?.stop()
        } catch (e: Exception) {
            runCatching { Log.w(TAG, "Error stopping TTS: ${e.message}") }
        }
        _isSpeaking.value = false
        _currentSpeakingId.value = null
        _currentHighlight.value = null
    }

    fun stop() {
        speakRequestJob?.cancel()
        speakRequestJob = null
        _initializingTargetId.value = null
        stopPlaybackOnly()
        if (_isModelResidentInRam.value || isTtsInitialized) {
            scheduleIdleUnload()
        }
    }

    companion object {
        private const val TAG = "TinyVocaManager"
        const val IDLE_UNLOAD_DELAY_MS = 60_000L
        private const val TTS_INIT_TIMEOUT_MS = 8_000L

        val WORD_REGEX = Regex("""[\p{L}\p{N}'-]+""")

        @Volatile
        private var instance: TinyVocaManager? = null

        fun getInstance(context: Context): TinyVocaManager {
            return instance ?: synchronized(this) {
                instance ?: TinyVocaManager(context.applicationContext).also { instance = it }
            }
        }

        /**
         * Releases TinyVoca memory only if an instance was already created, without allocating one.
         */
        fun releaseIfInitialized(reason: String = "trim_memory") {
            instance?.unloadFromMemory(reason)
        }

        /**
         * Cleans text by stripping <think>...</think> chains, fenced code blocks,
         * URLs, display math, markdown bold/italic formatting, and unpronounceable symbols.
         */
        fun cleanTextForSpeech(raw: String): String {
            var t = raw
            t = t.replace(Regex("""<think>[\s\S]*?</think>""", RegexOption.IGNORE_CASE), "")
            t = t.replace(Regex("""```[\s\S]*?```"""), " Code block omitted. ")
            t = t.replace(Regex("""`([^`]+)`"""), "$1")
            t = t.replace(Regex("""https?://\S+"""), "")
            t = t.replace(Regex("""\$\$[\s\S]*?\$\$"""), " ")
            t = t.replace(Regex("""\$([^$]+)\$"""), "$1")
            t = t.replace(Regex("""[#*_~>]+"""), " ")
            t = t.replace(Regex("""\s+"""), " ").trim()
            return t
        }

        fun tokenizeSpokenWords(text: String): List<SpokenWordToken> {
            val matches = WORD_REGEX.findAll(text).toList()
            return matches.mapIndexed { idx, match ->
                val start = match.range.first
                val end = match.range.last + 1
                val nextStart = if (idx + 1 < matches.size) matches[idx + 1].range.first else text.length
                val trailing = if (end <= nextStart && end <= text.length) text.substring(end, nextStart) else ""
                SpokenWordToken(
                    index = idx,
                    word = match.value,
                    charStart = start,
                    charEnd = end,
                    hasSentenceEnd = trailing.any { it == '.' || it == '?' || it == '!' },
                    hasClauseEnd = trailing.any { it == ',' || it == ';' || it == ':' || it == '—' || it == '-' },
                    hasLineBreak = trailing.contains('\n')
                )
            }
        }

        fun estimateSyllables(word: String): Int {
            val clean = word.lowercase().filter { it in 'a'..'z' }
            if (clean.isEmpty()) return 1

            val vowelRegex = Regex("""[aeiouy]+""")
            var count = vowelRegex.findAll(clean).count()

            if (clean.endsWith("e") && !clean.endsWith("le") && !clean.endsWith("ee") && count > 1) {
                count--
            }

            if (clean.endsWith("ed") && count > 1 && !clean.endsWith("ted") && !clean.endsWith("ded")) {
                count--
            }

            if (count <= 0) {
                count = (clean.length / 2).coerceAtLeast(1)
            }

            return count.coerceAtLeast(1)
        }

        fun calculateTokenDelay(token: SpokenWordToken, speed: Float): Long {
            val safeSpeed = speed.coerceIn(0.5f, 2.5f)
            val wordLength = token.word.length.coerceAtLeast(1)
            val syllables = estimateSyllables(token.word)

            val wordDurationMs = 120L + (syllables * 170L) + (wordLength * 35L)

            val pauseMs = when {
                token.hasSentenceEnd -> 420L
                token.hasClauseEnd -> 220L
                token.hasLineBreak -> 260L
                else -> 60L
            }

            val totalUnscaledMs = wordDurationMs + pauseMs
            val totalScaledMs = (totalUnscaledMs / safeSpeed).toLong()

            val minDuration = (140L / safeSpeed).toLong()
            return totalScaledMs.coerceAtLeast(minDuration)
        }
    }
}
