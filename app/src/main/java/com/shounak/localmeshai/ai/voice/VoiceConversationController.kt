package com.shounak.localmeshai.ai.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.util.Log
import com.shounak.localmeshai.ai.RequestTerminalStatus
import com.shounak.localmeshai.ai.tts.TinyVocaManager
import com.shounak.localmeshai.models.state.VoiceAssetReadiness
import com.shounak.localmeshai.models.state.VoiceModePhase
import com.shounak.localmeshai.models.state.VoiceModeUiState
import com.shounak.localmeshai.utils.AudioUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.pow
import kotlin.math.sqrt

interface VoiceStreamingObserver {
    fun onStreamingAnswerSnapshot(requestId: String, fullAnswerText: String)
    fun onLlmTurnFinished(
        requestId: String,
        status: RequestTerminalStatus?,
        finalAnswerText: String,
        errorMessage: String?
    )
}

/**
 * Orchestrates Milestone 2's Hands-Free Two-Way Offline Voice loop:
 * AudioRecord (VOICE_COMMUNICATION) -> Silero VAD + 250 ms PreRoll / ~500 ms hangover ->
 * Whisper Tiny EN INT8 STT -> ChatViewModel / VisionViewModel streaming LLM ->
 * SentenceSpeechChunker -> Lazy TinyVoca sequential TTS, with sustained-speech barge-in interruption.
 */
class VoiceConversationController(
    private val appContext: Context,
    private val hasChatModelReadyOrSelected: () -> Boolean,
    private val sendTranscriptToChat: suspend (transcript: String) -> String?,
    private val abortActiveChatTurn: () -> Unit,
    private val ttsVoiceProvider: () -> String,
    private val ttsSpeedProvider: () -> Float,
    private val hfTokenProvider: () -> String?
) : VoiceStreamingObserver, AutoCloseable {

    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val resourceMutex = Mutex()
    private val _uiState = MutableStateFlow(VoiceModeUiState())
    val uiState: StateFlow<VoiceModeUiState> = _uiState.asStateFlow()

    private val vadTurnState = VadTurnStateMachine()
    private val sentenceChunker = SentenceSpeechChunker()

    private var captureJob: Job? = null
    private var turnJob: Job? = null
    private var downloadJob: Job? = null
    @Volatile private var cleanupJob: Job? = null
    @Volatile private var vadDetector: SileroVadDetector? = null
    @Volatile private var whisperRecognizer: WhisperOnnxRecognizer? = null
    @Volatile private var audioRecord: AudioRecord? = null

    @Volatile private var activeVoiceRequestId: String? = null
    @Volatile private var isLlmStreamingForVoice = false
    private val sessionOpen = AtomicBoolean(false)

    fun refreshReadiness(): VoiceAssetReadiness {
        val tts = TinyVocaManager.getInstance(appContext).also { it.checkModelStatus() }
        val readiness = VoiceAssetReadiness(
            hasSileroVad = VoiceModelCatalog.isInstalled(appContext, VoiceModelCatalog.SILERO_VAD),
            hasWhisperStt = VoiceModelCatalog.isInstalled(appContext, VoiceModelCatalog.WHISPER_TINY_EN),
            hasTinyVoca = tts.isModelDownloaded.value
        )
        _uiState.update { it.copy(readiness = readiness) }
        return readiness
    }

    fun open() {
        if (!sessionOpen.compareAndSet(false, true)) return
        val readiness = refreshReadiness()
        if (!readiness.allReady) {
            _uiState.update {
                it.copy(
                    isOpen = true,
                    phase = VoiceModePhase.MissingAssets,
                    readiness = readiness,
                    errorMessage = null
                )
            }
            return
        }
        if (!hasChatModelReadyOrSelected()) {
            _uiState.update {
                it.copy(
                    isOpen = true,
                    phase = VoiceModePhase.Error,
                    readiness = readiness,
                    errorMessage = "Select a downloaded chat LLM model first before entering Hands-Free Voice Mode."
                )
            }
            return
        }
        startPipelineAsync()
    }

    fun startIfReadyAfterPermissionOrDownload() {
        if (!sessionOpen.get()) return
        val readiness = refreshReadiness()
        if (!readiness.allReady) {
            _uiState.update { it.copy(phase = VoiceModePhase.MissingAssets, readiness = readiness) }
            return
        }
        if (!hasChatModelReadyOrSelected()) {
            _uiState.update {
                it.copy(
                    phase = VoiceModePhase.Error,
                    errorMessage = "Select a downloaded chat LLM model first before starting Voice Mode."
                )
            }
            return
        }
        if (captureJob?.isActive == true) return
        startPipelineAsync()
    }

    fun downloadMissingAssets() {
        if (downloadJob?.isActive == true) return
        val readiness = refreshReadiness()
        if (readiness.allReady) {
            startIfReadyAfterPermissionOrDownload()
            return
        }
        val tts = TinyVocaManager.getInstance(appContext)
        _uiState.update {
            it.copy(
                isDownloadingAssets = true,
                downloadLabel = "Preparing voice downloads…",
                downloadProgress = 0.01f,
                errorMessage = null
            )
        }
        downloadJob = controllerScope.launch {
            try {
                val missingSpecs = buildList {
                    if (!readiness.hasSileroVad) add(VoiceModelCatalog.SILERO_VAD)
                    if (!readiness.hasWhisperStt) add(VoiceModelCatalog.WHISPER_TINY_EN)
                }
                if (missingSpecs.isNotEmpty()) {
                    val collector = launch {
                        VoiceModelCatalog.downloadStatus.collect { status ->
                            if (status.isDownloading) {
                                _uiState.update {
                                    it.copy(
                                        isDownloadingAssets = true,
                                        downloadLabel = "Downloading ${status.currentModelName}…",
                                        downloadProgress = status.fraction.coerceIn(0.01f, 0.99f)
                                    )
                                }
                            }
                        }
                    }
                    try {
                        VoiceModelCatalog.downloadMissingAssets(
                            context = appContext,
                            specs = missingSpecs,
                            bearerToken = hfTokenProvider()
                        )
                    } finally {
                        collector.cancel()
                    }
                }

                if (!tts.isModelDownloaded.value) {
                    _uiState.update {
                        it.copy(
                            isDownloadingAssets = true,
                            downloadLabel = "Downloading TinyVoca Neural TTS…",
                            downloadProgress = 0.01f
                        )
                    }
                    val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
                    tts.downloadTinyVoca(
                        onProgress = { p ->
                            _uiState.update {
                                it.copy(
                                    isDownloadingAssets = true,
                                    downloadLabel = "Downloading TinyVoca Neural TTS…",
                                    downloadProgress = p.coerceIn(0.01f, 0.99f)
                                )
                            }
                        },
                        onComplete = { deferred.complete(true) },
                        onError = { err -> deferred.completeExceptionally(IllegalStateException(err)) }
                    )
                    deferred.await()
                }

                _uiState.update {
                    it.copy(
                        isDownloadingAssets = false,
                        downloadLabel = "",
                        downloadProgress = 1f
                    )
                }
                val updated = refreshReadiness()
                if (updated.allReady && sessionOpen.get() && hasChatModelReadyOrSelected()) {
                    startPipelineAsync()
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _uiState.update {
                    it.copy(
                        isDownloadingAssets = false,
                        downloadLabel = "",
                        errorMessage = t.message ?: "Voice asset download failed."
                    )
                }
            }
        }
    }

    fun toggleMute() {
        _uiState.update { state ->
            val nextMuted = !state.isMuted
            if (nextMuted) vadTurnState.resetBargeInCounter()
            state.copy(isMuted = nextMuted, micLevel = if (nextMuted) 0f else state.micLevel)
        }
    }

    fun manualBargeInOrInterrupt() {
        val currentPhase = _uiState.value.phase
        if (currentPhase == VoiceModePhase.Transcribing || currentPhase == VoiceModePhase.Thinking || currentPhase == VoiceModePhase.Speaking) {
            triggerBargeIn()
        }
    }

    private fun startPipelineAsync() {
        val oldCapture = captureJob
        captureJob = controllerScope.launch {
            if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                _uiState.update {
                    it.copy(
                        isOpen = true,
                        phase = VoiceModePhase.Error,
                        errorMessage = "Microphone permission is required for Hands-Free Voice Mode."
                    )
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    isOpen = true,
                    phase = VoiceModePhase.Initializing,
                    errorMessage = null,
                    micLevel = 0f
                )
            }

            var localVad: SileroVadDetector? = null
            var localWhisper: WhisperOnnxRecognizer? = null
            var localRecorder: AudioRecord? = null

            try {
                oldCapture?.cancelAndJoin()
                cleanupJob?.join()
                if (!isActive || !sessionOpen.get()) return@launch

                // Lazily initialize TinyVoca ONLY when Voice Mode actively starts.
                val tts = TinyVocaManager.getInstance(appContext)
                tts.ensureInitializedOnDemand()
                if (!isActive || !sessionOpen.get()) return@launch

                localVad = SileroVadDetector(VoiceModelCatalog.sileroVadFile(appContext))
                if (!isActive || !sessionOpen.get()) return@launch

                localWhisper = WhisperOnnxRecognizer(appContext, VoiceModelCatalog.whisperDirectory(appContext))
                if (!isActive || !sessionOpen.get()) return@launch

                vadTurnState.reset()
                sentenceChunker.reset()

                val recorder = SileroVadDetector.createVoiceAudioRecord()
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    recorder.release()
                    throw IllegalStateException("Microphone hardware failed to initialize (VOICE_COMMUNICATION).")
                }
                localRecorder = recorder

                resourceMutex.withLock {
                    if (!isActive || !sessionOpen.get()) {
                        return@withLock
                    }
                    vadDetector = localVad
                    whisperRecognizer = localWhisper
                    audioRecord = localRecorder
                    localVad = null
                    localWhisper = null
                    localRecorder = null
                }

                val activeRecorder = audioRecord ?: return@launch
                val activeVad = vadDetector ?: return@launch
                activeRecorder.startRecording()

                _uiState.update {
                    it.copy(
                        phase = VoiceModePhase.Listening,
                        micLevel = 0f
                    )
                }

                val pcmShorts = ShortArray(SileroVadDetector.FRAME_SAMPLES)
                val pcmFloats = FloatArray(SileroVadDetector.FRAME_SAMPLES)
                var lastLevelEmitAt = 0L

                while (isActive && sessionOpen.get()) {
                    val read = activeRecorder.read(pcmShorts, 0, pcmShorts.size)
                    if (read <= 0) {
                        delay(8L)
                        continue
                    }
                    if (read < pcmShorts.size) continue

                    val muted = _uiState.value.isMuted
                    var sumSq = 0.0
                    for (i in 0 until read) {
                        val s = if (muted) 0f else (pcmShorts[i] / 32768.0f).coerceIn(-1f, 1f)
                        pcmFloats[i] = s
                        sumSq += (s * s).toDouble()
                    }

                    val now = System.currentTimeMillis()
                    if (now - lastLevelEmitAt >= 48L) {
                        lastLevelEmitAt = now
                        val rawRms = if (muted || read <= 0) 0.0 else sqrt(sumSq / read)
                        val visualLevel = if (muted || rawRms < 0.004) {
                            0f
                        } else {
                            (rawRms.pow(0.68) * 3.8).toFloat().coerceIn(0f, 1f)
                        }
                        _uiState.update { it.copy(micLevel = visualLevel) }
                    }

                    if (muted) continue

                    val speechProb = activeVad.predictProbability(pcmFloats)
                    val curPhase = _uiState.value.phase
                    val assistantBusy = curPhase == VoiceModePhase.Thinking ||
                        curPhase == VoiceModePhase.Speaking ||
                        curPhase == VoiceModePhase.Transcribing

                    val events = vadTurnState.processFrame(pcmFloats, speechProb, assistantBusy = assistantBusy)
                    for (ev in events) {
                        when (ev) {
                            VadTurnEvent.BargeInDetected -> triggerBargeIn()
                            VadTurnEvent.SpeechStarted -> {
                                if (_uiState.value.phase != VoiceModePhase.Listening) {
                                    _uiState.update { it.copy(phase = VoiceModePhase.Listening) }
                                }
                            }
                            is VadTurnEvent.SpeechCommitted -> handleCommittedSpeech(ev.samples)
                        }
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.e(TAG, "Voice capture pipeline error: ${t.message}", t)
                if (sessionOpen.get()) {
                    _uiState.update {
                        it.copy(
                            phase = VoiceModePhase.Error,
                            errorMessage = t.message ?: "Voice mode encountered an audio pipeline error."
                        )
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    runCatching { localRecorder?.stop() }
                    runCatching { localRecorder?.release() }
                    runCatching { localVad?.close() }
                    runCatching { localWhisper?.closeSafely() }

                    val pendingTurn = turnJob
                    pendingTurn?.cancel()
                    runCatching { whisperRecognizer?.cancelActive() }
                    runCatching { pendingTurn?.join() }
                    releaseCaptureResourcesLocked()
                }
            }
        }
    }

    private fun triggerBargeIn() {
        Log.i(TAG, "Voice barge-in triggered by user speech")
        activeVoiceRequestId = null
        isLlmStreamingForVoice = false
        turnJob?.cancel()
        turnJob = null
        whisperRecognizer?.cancelActive()
        sentenceChunker.reset()
        TinyVocaManager.getInstance(appContext).flushSpeech()
        controllerScope.launch(Dispatchers.Main.immediate) {
            abortActiveChatTurn()
        }
        _uiState.update {
            it.copy(
                phase = VoiceModePhase.Listening,
                activeSpeechChunk = "",
                streamingAssistantText = ""
            )
        }
    }

    private fun handleCommittedSpeech(samples: FloatArray) {
        if (!sessionOpen.get()) return
        val recognizer = whisperRecognizer ?: return
        turnJob?.cancel()
        turnJob = controllerScope.launch {
            _uiState.update {
                it.copy(
                    phase = VoiceModePhase.Transcribing,
                    streamingAssistantText = "",
                    activeSpeechChunk = ""
                )
            }
            try {
                val rawTranscript = recognizer.transcribe(samples)
                val cleanTranscript = rawTranscript
                    .replace(Regex("""\[[^\]]*]"""), "")
                    .trim()
                if (cleanTranscript.isBlank()) {
                    if (sessionOpen.get() && _uiState.value.phase == VoiceModePhase.Transcribing) {
                        _uiState.update { it.copy(phase = VoiceModePhase.Listening) }
                    }
                    return@launch
                }

                sentenceChunker.reset()
                isLlmStreamingForVoice = true
                _uiState.update {
                    it.copy(
                        phase = VoiceModePhase.Thinking,
                        lastUserTranscript = cleanTranscript,
                        streamingAssistantText = "",
                        turnCount = it.turnCount + 1
                    )
                }

                val reqId = withContext(Dispatchers.Main.immediate) {
                    sendTranscriptToChat(cleanTranscript)
                }
                if (reqId.isNullOrBlank()) {
                    isLlmStreamingForVoice = false
                    _uiState.update {
                        it.copy(
                            phase = VoiceModePhase.Error,
                            errorMessage = "Could not start chat turn. Ensure a chat model is selected."
                        )
                    }
                    return@launch
                }
                activeVoiceRequestId = reqId
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                isLlmStreamingForVoice = false
                Log.e(TAG, "Voice turn STT/dispatch error: ${t.message}", t)
                if (sessionOpen.get()) {
                    _uiState.update {
                        it.copy(
                            phase = VoiceModePhase.Listening,
                            errorMessage = t.message
                        )
                    }
                }
            }
        }
    }

    override fun onStreamingAnswerSnapshot(requestId: String, fullAnswerText: String) {
        if (!sessionOpen.get() || activeVoiceRequestId != requestId) return
        _uiState.update { it.copy(streamingAssistantText = fullAnswerText) }
        val completedSentences = synchronized(sentenceChunker) {
            sentenceChunker.updateSnapshot(fullAnswerText)
        }
        if (completedSentences.isNotEmpty()) {
            queueSentencesForSpeech(completedSentences)
        }
    }

    override fun onLlmTurnFinished(
        requestId: String,
        status: RequestTerminalStatus?,
        finalAnswerText: String,
        errorMessage: String?
    ) {
        if (!sessionOpen.get() || activeVoiceRequestId != requestId) return
        isLlmStreamingForVoice = false

        if (status == RequestTerminalStatus.Failed && finalAnswerText.isBlank()) {
            activeVoiceRequestId = null
            _uiState.update {
                it.copy(
                    phase = VoiceModePhase.Listening,
                    errorMessage = errorMessage ?: "Assistant reply failed."
                )
            }
            return
        }

        if (status == RequestTerminalStatus.Stopped) {
            activeVoiceRequestId = null
            return
        }

        val tailSentences = synchronized(sentenceChunker) {
            val extra = sentenceChunker.updateSnapshot(finalAnswerText)
            val tail = sentenceChunker.flushRemainder()
            if (tail != null) extra + tail else extra
        }

        if (tailSentences.isNotEmpty()) {
            queueSentencesForSpeech(tailSentences)
        } else {
            controllerScope.launch {
                delay(120L)
                val tts = TinyVocaManager.getInstance(appContext)
                if (sessionOpen.get() && !isLlmStreamingForVoice && !tts.hasPendingQueuedAudio()) {
                    activeVoiceRequestId = null
                    _uiState.update { it.copy(phase = VoiceModePhase.Listening, activeSpeechChunk = "") }
                }
            }
        }
    }

    private fun queueSentencesForSpeech(sentences: List<String>) {
        val tts = TinyVocaManager.getInstance(appContext)
        val voice = ttsVoiceProvider()
        val speed = ttsSpeedProvider()
        controllerScope.launch {
            for (sentence in sentences) {
                if (!sessionOpen.get() || activeVoiceRequestId == null) break
                _uiState.update {
                    it.copy(
                        phase = VoiceModePhase.Speaking,
                        activeSpeechChunk = sentence
                    )
                }
                tts.enqueueSentence(
                    sentence = sentence,
                    utteranceId = "voice_${UUID.randomUUID()}",
                    voice = voice,
                    speed = speed,
                    onQueueEmpty = {
                        if (sessionOpen.get() && !isLlmStreamingForVoice) {
                            activeVoiceRequestId = null
                            _uiState.update {
                                it.copy(
                                    phase = VoiceModePhase.Listening,
                                    activeSpeechChunk = ""
                                )
                            }
                        }
                    }
                )
            }
        }
    }

    private suspend fun releaseCaptureResourcesLocked() {
        resourceMutex.withLock {
            val rec = audioRecord
            audioRecord = null
            val vad = vadDetector
            vadDetector = null
            val whisp = whisperRecognizer
            whisperRecognizer = null

            runCatching { rec?.stop() }
            runCatching { rec?.release() }
            runCatching { vad?.close() }
            runCatching { whisp?.closeSafely() }
        }
    }

    override fun close() {
        val wasOpen = sessionOpen.getAndSet(false)
        val hadActiveLlmTurn = activeVoiceRequestId != null || isLlmStreamingForVoice
        activeVoiceRequestId = null
        isLlmStreamingForVoice = false

        val dlJob = downloadJob
        downloadJob = null
        val activeTurn = turnJob
        turnJob = null
        val activeCapture = captureJob
        captureJob = null
        val activeAudio = audioRecord
        val activeWhisper = whisperRecognizer

        _uiState.value = VoiceModeUiState(isOpen = false, phase = VoiceModePhase.Idle)

        dlJob?.cancel()
        activeWhisper?.cancelActive()
        activeTurn?.cancel()
        activeCapture?.cancel()
        sentenceChunker.reset()
        vadTurnState.reset()

        if (wasOpen && hadActiveLlmTurn) {
            controllerScope.launch(Dispatchers.Main.immediate) {
                runCatching { abortActiveChatTurn() }
            }
        }

        val prevCleanup = cleanupJob
        cleanupJob = cleanupScope.launch {
            if (wasOpen) {
                runCatching { TinyVocaManager.getInstance(appContext).stop() }
            }
            runCatching { prevCleanup?.join() }
            runCatching { activeAudio?.stop() }
            runCatching { activeTurn?.join() }
            runCatching { activeCapture?.join() }
            releaseCaptureResourcesLocked()
        }
    }

    fun destroy() {
        close()
        val pendingCleanup = cleanupJob
        cleanupScope.launch {
            runCatching { pendingCleanup?.join() }
            controllerScope.cancel()
        }
    }

    companion object {
        private const val TAG = "VoiceConversationCtrl"
    }
}
