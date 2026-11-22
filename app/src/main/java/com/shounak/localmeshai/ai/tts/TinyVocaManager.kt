package com.shounak.localmeshai.ai.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID

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
    val hasSentenceEnd: Boolean,
    val hasClauseEnd: Boolean,
    val hasLineBreak: Boolean
)

/**
 * High-performance, resident on-device TTS engine manager.
 * Supports n4ze3m/tiny-voca ONNX neural voice model with instant RAM residency,
 * F2 (Female) and M2 (Male) voice switching, 1.0x / 1.25x / 1.5x speed adjustment,
 * and seamless fallback to Android system TTS.
 */
class TinyVocaManager private constructor(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val httpClient = OkHttpClient.Builder().build()

    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false

    private var ortSession: OrtSession? = null

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _currentSpeakingId = MutableStateFlow<String?>(null)
    val currentSpeakingId: StateFlow<String?> = _currentSpeakingId.asStateFlow()

    private val _currentHighlight = MutableStateFlow<SpeechHighlight?>(null)
    val currentHighlight: StateFlow<SpeechHighlight?> = _currentHighlight.asStateFlow()

    private val spokenTextMap = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val cachedTokensMap = java.util.concurrent.ConcurrentHashMap<String, List<SpokenWordToken>>()
    private var fallbackHighlightJob: Job? = null
    @Volatile private var hasRangeStartFired = false
    @Volatile private var currentConfiguredSpeed = 1.0f

    private val _downloadProgress = MutableStateFlow<Float?>(null)
    val downloadProgress: StateFlow<Float?> = _downloadProgress.asStateFlow()

    private val _isModelDownloaded = MutableStateFlow(false)
    val isModelDownloaded: StateFlow<Boolean> = _isModelDownloaded.asStateFlow()

    private val _isModelResidentInRam = MutableStateFlow(false)
    val isModelResidentInRam: StateFlow<Boolean> = _isModelResidentInRam.asStateFlow()

    val modelFile: File
        get() = File(appContext.filesDir, "models/tiny_voca.onnx")

    val hasBundledAsset: Boolean
        get() = try {
            appContext.assets.open("models/tiny_voca.onnx").use { true }
        } catch (_: Exception) {
            false
        }

    init {
        extractAssetModelIfPresent()
        checkModelStatus()
        initializeTtsEngine()
        if (_isModelDownloaded.value) {
            preloadModelToRam()
        }
    }

    private fun extractAssetModelIfPresent() {
        try {
            if (!modelFile.exists() || modelFile.length() < 1_000_000L) {
                appContext.assets.open("models/tiny_voca.onnx").use { input ->
                    val targetDir = modelFile.parentFile
                    if (targetDir != null && !targetDir.exists()) {
                        targetDir.mkdirs()
                    }
                    val temp = File(appContext.filesDir, "models/tiny_voca.tmp")
                    FileOutputStream(temp).use { output ->
                        input.copyTo(output)
                    }
                    if (modelFile.exists()) modelFile.delete()
                    if (!temp.renameTo(modelFile)) {
                        temp.copyTo(modelFile, overwrite = true)
                        temp.delete()
                    }
                    Log.i(TAG, "Bundled compact TinyVoca model extracted to: ${modelFile.absolutePath}")
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "No bundled TinyVoca asset or failed to extract: ${e.message}")
        }
    }

    fun checkModelStatus() {
        val exists = modelFile.exists() && modelFile.length() > 1_000_000L
        _isModelDownloaded.value = exists
    }

    private fun initializeTtsEngine() {
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isTtsInitialized = true
                tts?.language = Locale.US
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _isSpeaking.value = true
                        _currentSpeakingId.value = utteranceId
                        hasRangeStartFired = false
                        fallbackHighlightJob?.cancel()
                        if (utteranceId != null) {
                            val text = spokenTextMap[utteranceId].orEmpty()
                            val tokens = cachedTokensMap.getOrPut(utteranceId) { tokenizeSpokenWords(text) }
                            val speed = currentConfiguredSpeed
                            fallbackHighlightJob = scope.launch(Dispatchers.Main) {
                                // Wait 750ms for the Android audio pipeline and synthesizer to begin sound playback
                                val initialDelayMs = (750L / speed).toLong().coerceIn(450L, 1200L)
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
                            val matchingToken = tokens.firstOrNull { start in it.charStart until it.charEnd }
                                ?: tokens.firstOrNull { it.charStart >= start }
                                ?: tokens.lastOrNull()
                            if (matchingToken != null) {
                                _currentHighlight.value = SpeechHighlight(
                                    utteranceId = utteranceId,
                                    wordIndex = matchingToken.index,
                                    word = matchingToken.word,
                                    charStart = start,
                                    charEnd = end
                                )
                            }
                        }
                    }

                    override fun onDone(utteranceId: String?) {
                        fallbackHighlightJob?.cancel()
                        _isSpeaking.value = false
                        _currentSpeakingId.value = null
                        _currentHighlight.value = null
                        if (utteranceId != null) {
                            spokenTextMap.remove(utteranceId)
                            cachedTokensMap.remove(utteranceId)
                        }
                    }

                    override fun onError(utteranceId: String?) {
                        fallbackHighlightJob?.cancel()
                        _isSpeaking.value = false
                        _currentSpeakingId.value = null
                        _currentHighlight.value = null
                        if (utteranceId != null) {
                            spokenTextMap.remove(utteranceId)
                            cachedTokensMap.remove(utteranceId)
                        }
                    }
                })
                Log.i(TAG, "Android TextToSpeech engine pre-warmed in RAM")
            } else {
                Log.w(TAG, "Failed to initialize Android TextToSpeech engine (status=$status)")
            }
        }
    }

    private fun preloadModelToRam() {
        scope.launch(Dispatchers.IO) {
            try {
                if (modelFile.exists()) {
                    val env = OrtEnvironment.getEnvironment()
                    val opts = OrtSession.SessionOptions().apply {
                        setIntraOpNumThreads(2)
                    }
                    ortSession = env.createSession(modelFile.absolutePath, opts)
                    _isModelResidentInRam.value = true
                    Log.i(TAG, "TinyVoca neural model loaded into RAM: ${modelFile.length()} bytes")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not load TinyVoca ONNX model to RAM session: ${e.message}")
            }
        }
    }

    /**
     * Download the TinyVoca ONNX model from Hugging Face (~21.4 MB).
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
                    val body = response.body ?: throw IllegalStateException("Empty response body")
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
                    preloadModelToRam()

                    _downloadProgress.value = null
                    withContext(Dispatchers.Main) {
                        onComplete?.invoke()
                    }
                    Log.i(TAG, "TinyVoca ONNX model downloaded successfully.")
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
     * Restore the TinyVoca model: first checks for the bundled APK asset (instant, 0 network usage),
     * and if not found, falls back to downloading from Hugging Face.
     */
    fun restoreOrDownloadTinyVoca(
        onProgress: ((Float) -> Unit)? = null,
        onComplete: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        if (_downloadProgress.value != null) return

        scope.launch(Dispatchers.IO) {
            try {
                if (hasBundledAsset) {
                    _downloadProgress.value = 0.5f
                    extractAssetModelIfPresent()
                    checkModelStatus()
                    if (_isModelDownloaded.value) {
                        preloadModelToRam()
                        _downloadProgress.value = null
                        withContext(Dispatchers.Main) {
                            onComplete?.invoke()
                        }
                        Log.i(TAG, "TinyVoca model successfully restored from APK asset.")
                        return@launch
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Asset restore failed, falling back to download: ${e.message}")
            }

            // Fall back to network download
            downloadTinyVoca(onProgress, onComplete, onError)
        }
    }

    fun deleteTinyVocaModel(): Boolean {
        return try {
            ortSession?.close()
            ortSession = null
            _isModelResidentInRam.value = false
            val deleted = modelFile.delete()
            checkModelStatus()
            deleted
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting TinyVoca model: ${e.message}", e)
            false
        }
    }

    /**
     * Read text aloud using the configured voice ("F2" / "M2") and speed multiplier (1.0x, 1.25x, 1.5x).
     */
    fun speak(
        text: String,
        id: String? = null,
        voice: String = "F2",
        speed: Float = 1.0f
    ) {
        val clean = cleanTextForSpeech(text)
        if (clean.isBlank()) return

        // If already speaking this message, toggle off
        if (_isSpeaking.value && id != null && _currentSpeakingId.value == id) {
            stop()
            return
        }

        stop()

        _currentSpeakingId.value = id
        _isSpeaking.value = true

        val speechSpeed = speed.coerceIn(0.5f, 2.5f)
        val utteranceId = id ?: UUID.randomUUID().toString()
        spokenTextMap[utteranceId] = clean

        val engine = tts
        if (engine == null || !isTtsInitialized) {
            Log.w(TAG, "TTS engine not yet ready")
            _isSpeaking.value = false
            _currentSpeakingId.value = null
            _currentHighlight.value = null
            return
        }

        try {
            engine.setSpeechRate(speechSpeed)

            // Voice customization for F2 (Female) vs M2 (Male)
            val isFemale = voice.equals("F2", ignoreCase = true)
            configureVoice(engine, isFemale)

            val params = Bundle()
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            engine.speak(clean, TextToSpeech.QUEUE_FLUSH, params, utteranceId)

            currentConfiguredSpeed = speechSpeed
            hasRangeStartFired = false
            fallbackHighlightJob?.cancel()
            _currentHighlight.value = null
        } catch (e: Exception) {
            Log.e(TAG, "Error in speak(): ${e.message}", e)
            _isSpeaking.value = false
            _currentSpeakingId.value = null
            _currentHighlight.value = null
            fallbackHighlightJob?.cancel()
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
            // Default pitch fallback
            engine.setPitch(if (isFemale) 1.15f else 0.85f)
        }
    }

    fun stop() {
        fallbackHighlightJob?.cancel()
        fallbackHighlightJob = null
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping TTS: ${e.message}")
        }
        _isSpeaking.value = false
        _currentSpeakingId.value = null
        _currentHighlight.value = null
    }

    companion object {
        private const val TAG = "TinyVocaManager"

        val WORD_REGEX = Regex("""[\p{L}\p{N}'-]+""")

        @Volatile
        private var instance: TinyVocaManager? = null

        fun getInstance(context: Context): TinyVocaManager {
            return instance ?: synchronized(this) {
                instance ?: TinyVocaManager(context.applicationContext).also { instance = it }
            }
        }

        /**
         * Cleans text by stripping <think>...</think> chains, fenced code blocks,
         * URLs, display math, markdown bold/italic formatting, and unpronounceable symbols.
         */
        fun cleanTextForSpeech(raw: String): String {
            var t = raw
            // 1. Strip <think>...</think> blocks
            t = t.replace(Regex("""<think>[\s\S]*?</think>""", RegexOption.IGNORE_CASE), "")
            // 2. Strip fenced code blocks
            t = t.replace(Regex("""```[\s\S]*?```"""), " Code block omitted. ")
            // 3. Strip inline code delimiters
            t = t.replace(Regex("""`([^`]+)`"""), "$1")
            // 4. Strip URLs
            t = t.replace(Regex("""https?://\S+"""), "")
            // 5. Strip display math $$...$$
            t = t.replace(Regex("""\$\$[\s\S]*?\$\$"""), " ")
            // 6. Strip inline math $...$
            t = t.replace(Regex("""\$([^$]+)\$"""), "$1")
            // 7. Strip markdown headers, bold, italics, bullets, blockquotes
            t = t.replace(Regex("""[#*_~>]+"""), " ")
            // 8. Normalize whitespace
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

        fun calculateTokenDelay(token: SpokenWordToken, speed: Float): Long {
            val safeSpeed = speed.coerceIn(0.5f, 2.5f)
            // Base duration: standard conversational speech is ~125 words/minute = ~480ms per word.
            // Scale dynamically by character length (longer words have more syllables).
            val baseDurationMs = 240L + (token.word.length * 42L).coerceAtMost(600L)

            // Natural conversational pauses at punctuation boundaries:
            val pauseMs = when {
                token.hasSentenceEnd -> 420L
                token.hasClauseEnd -> 220L
                token.hasLineBreak -> 260L
                else -> 0L
            }

            val totalMs = ((baseDurationMs + pauseMs) / safeSpeed).toLong()
            return totalMs.coerceIn((160L / safeSpeed).toLong(), 2000L)
        }
    }
}
