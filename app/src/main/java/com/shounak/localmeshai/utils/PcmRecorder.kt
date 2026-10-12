package com.shounak.localmeshai.utils

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

enum class PcmRecorderState {
    IDLE,
    RECORDING,
    PAUSED,
    STOPPED,
    ERROR
}

enum class PcmRecorderErrorType {
    PERMISSION_DENIED,
    HARDWARE_UNAVAILABLE,
    FORMAT_UNSUPPORTED,
    INITIALIZATION_FAILED,
    RECORDING_TOO_SHORT,
    SILENCE_OR_ZERO_ONLY,
    RECORDING_FAILED
}

sealed class PcmRecordingResult {
    data class Success(
        val wavBytes: ByteArray,
        val sampleRateHz: Int,
        val durationMs: Long,
        val cacheFile: File? = null
    ) : PcmRecordingResult()

    data class Failure(
        val errorType: PcmRecorderErrorType,
        val message: String,
        val cause: Throwable? = null
    ) : PcmRecordingResult()
}

interface AudioRecordSource {
    val state: Int
    val recordingState: Int
    fun startRecording()
    fun stop()
    fun read(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int
    fun release()
}

class AndroidAudioRecordSource(private val audioRecord: AudioRecord) : AudioRecordSource {
    override val state: Int get() = audioRecord.state
    override val recordingState: Int get() = audioRecord.recordingState
    override fun startRecording() = audioRecord.startRecording()
    override fun stop() = audioRecord.stop()
    override fun read(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int =
        audioRecord.read(audioData, offsetInBytes, sizeInBytes)
    override fun release() = audioRecord.release()
}

fun interface AudioRecordSourceFactory {
    fun create(
        audioSource: Int,
        sampleRateHz: Int,
        channelConfig: Int,
        audioFormat: Int,
        bufferSizeInBytes: Int
    ): AudioRecordSource
}

fun interface PermissionChecker {
    fun hasPermission(permission: String): Boolean
}

class PcmRecorder(
    private val context: Context? = null,
    private val sampleRateHz: Int = AudioUtils.TARGET_SAMPLE_RATE,
    private val minDurationMs: Long = 200L,
    private val permissionChecker: PermissionChecker = PermissionChecker { permission ->
        context?.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    },
    private val audioRecordFactory: AudioRecordSourceFactory = AudioRecordSourceFactory { audioSource, sampleRate, channelConfig, audioFormat, bufferSize ->
        @SuppressLint("MissingPermission")
        val ar = AudioRecord(audioSource, sampleRate, channelConfig, audioFormat, bufferSize)
        AndroidAudioRecordSource(ar)
    },
    private val minBufferSizeProvider: (Int, Int, Int) -> Int = { sampleRate, channelConfig, audioFormat ->
        AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    },
    private val uiDispatcher: CoroutineDispatcher? = null
) {
    @Volatile
    var state: PcmRecorderState = PcmRecorderState.IDLE
        private set

    private val mutex = Mutex()
    private var activeRecorder: AudioRecordSource? = null

    suspend fun record(
        maxDurationMs: Long = 30_000L,
        isStopRequested: () -> Boolean = { false },
        isCancelled: () -> Boolean = { false },
        isPaused: () -> Boolean = { false },
        onLevel: suspend (inputLevel: Float, speechActivity: Float, elapsedMs: Long) -> Unit = { _, _, _ -> }
    ): PcmRecordingResult = withContext(Dispatchers.IO) {
        val acquired = mutex.tryLock()
        if (!acquired) {
            return@withContext PcmRecordingResult.Failure(
                PcmRecorderErrorType.RECORDING_FAILED,
                "A recording session is already active or transitioning."
            )
        }

        try {
            state = PcmRecorderState.IDLE

            // 1. Permission check
            if (!permissionChecker.hasPermission(Manifest.permission.RECORD_AUDIO)) {
                state = PcmRecorderState.ERROR
                return@withContext PcmRecordingResult.Failure(
                    PcmRecorderErrorType.PERMISSION_DENIED,
                    "Microphone permission is required to record audio."
                )
            }

            // 2. Format validation & buffer size check
            val minBufferSize = minBufferSizeProvider(
                sampleRateHz,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBufferSize <= 0) {
                state = PcmRecorderState.ERROR
                return@withContext PcmRecordingResult.Failure(
                    PcmRecorderErrorType.FORMAT_UNSUPPORTED,
                    "Audio recording format (16 kHz mono 16-bit PCM) is not supported by this device."
                )
            }

            val readChunkSize = max(minBufferSize, 1600)
            val recorder = try {
                audioRecordFactory.create(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRateHz,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    readChunkSize * 2
                )
            } catch (e: Throwable) {
                state = PcmRecorderState.ERROR
                return@withContext PcmRecordingResult.Failure(
                    PcmRecorderErrorType.HARDWARE_UNAVAILABLE,
                    "Microphone hardware is busy or unavailable.",
                    e
                )
            }

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                state = PcmRecorderState.ERROR
                return@withContext PcmRecordingResult.Failure(
                    PcmRecorderErrorType.INITIALIZATION_FAILED,
                    "Could not initialize microphone recorder."
                )
            }

            activeRecorder = recorder
            state = PcmRecorderState.RECORDING

            val initialCapacity = (sampleRateHz.toLong() * 2L * (maxDurationMs / 1000L).coerceAtLeast(1L))
                .coerceIn(1024L, 10L * 1024L * 1024L).toInt()
            val output = ByteArrayOutputStream(initialCapacity)
            var finalElapsedMs = 0L
            var negativeReadErrorCode: Int? = null

            try {
                val buffer = ByteArray(readChunkSize)
                var speechActivity = 0f
                var lastMeterUpdateMs = 0L

                try {
                    recorder.startRecording()
                } catch (e: Throwable) {
                    state = PcmRecorderState.ERROR
                    return@withContext PcmRecordingResult.Failure(
                        PcmRecorderErrorType.HARDWARE_UNAVAILABLE,
                        "Failed to start recording: microphone may be in use by another app.",
                        e
                    )
                }

                if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    state = PcmRecorderState.ERROR
                    return@withContext PcmRecordingResult.Failure(
                        PcmRecorderErrorType.HARDWARE_UNAVAILABLE,
                        "Microphone is busy or not in recording state."
                    )
                }

                var totalRecordedBytes = 0L
                var consecutiveZeroReads = 0
                val maxConsecutiveZeroReads = 30
                val targetDispatcher = uiDispatcher ?: Dispatchers.Main

                while (!isStopRequested() && !isCancelled() && finalElapsedMs < maxDurationMs) {
                    currentCoroutineContext().ensureActive()
                    if (isPaused()) {
                        state = PcmRecorderState.PAUSED
                        recorder.read(buffer, 0, buffer.size)
                        consecutiveZeroReads = 0
                        delay(30L)
                        continue
                    }
                    state = PcmRecorderState.RECORDING
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        consecutiveZeroReads = 0
                        totalRecordedBytes += read
                        val elapsedMs = (totalRecordedBytes * 1000L) / (sampleRateHz * 2L)
                        finalElapsedMs = elapsedMs
                        output.write(buffer, 0, read)
                        val inputLevel = AudioUtils.pcm16RmsLevel(buffer, read)
                        speechActivity = if (inputLevel > 0.08f) {
                            (speechActivity + 0.18f).coerceAtMost(1f)
                        } else {
                            (speechActivity - 0.08f).coerceAtLeast(0f)
                        }
                        if (elapsedMs - lastMeterUpdateMs >= 45L || elapsedMs >= maxDurationMs) {
                            withContext(targetDispatcher) {
                                onLevel(inputLevel, speechActivity, elapsedMs)
                            }
                            lastMeterUpdateMs = elapsedMs
                        }
                    } else if (read < 0) {
                        negativeReadErrorCode = read
                        break
                    } else {
                        // read == 0: bound stalls and avoid busy-spinning
                        consecutiveZeroReads++
                        if (consecutiveZeroReads >= maxConsecutiveZeroReads) {
                            break
                        }
                        delay(10L)
                    }
                }
            } finally {
                runCatching { recorder.stop() }
                recorder.release()
                activeRecorder = null
                state = PcmRecorderState.STOPPED
            }

            if (negativeReadErrorCode != null) {
                state = PcmRecorderState.ERROR
                return@withContext PcmRecordingResult.Failure(
                    PcmRecorderErrorType.RECORDING_FAILED,
                    "Microphone read failed with hardware error code: $negativeReadErrorCode"
                )
            }

            if (isCancelled()) {
                return@withContext PcmRecordingResult.Failure(
                    PcmRecorderErrorType.RECORDING_FAILED,
                    "Recording was cancelled."
                )
            }

            val rawPcmBytes = output.toByteArray()

            // 3. Check duration
            val recordedDurationMs = if (finalElapsedMs > 0) finalElapsedMs else {
                (rawPcmBytes.size.toLong() * 1000L) / (sampleRateHz * 2L)
            }

            val minBytes = (sampleRateHz * 2L * minDurationMs) / 1000L
            if (recordedDurationMs < minDurationMs || rawPcmBytes.size < minBytes) {
                return@withContext PcmRecordingResult.Failure(
                    PcmRecorderErrorType.RECORDING_TOO_SHORT,
                    "Recording was too short (must be at least ${minDurationMs}ms)."
                )
            }

            // 4. Check all-zeros / silence
            if (isAllZeros(rawPcmBytes)) {
                return@withContext PcmRecordingResult.Failure(
                    PcmRecorderErrorType.SILENCE_OR_ZERO_ONLY,
                    "Recording contained only silence or zeros."
                )
            }

            // 5. Generate WAV with accurate header
            val wavBytes = AudioUtils.createWavFromPcm16(rawPcmBytes, sampleRateHz, 1)

            val cacheFile = if (context != null) {
                val cacheDir = File(context.cacheDir, "multimodal_inputs").apply { mkdirs() }
                AudioUtils.pruneInferenceCache(cacheDir)
                val file = File(cacheDir, "mic_recording_${System.currentTimeMillis()}.wav")
                file.writeBytes(wavBytes)
                file
            } else null

            PcmRecordingResult.Success(
                wavBytes = wavBytes,
                sampleRateHz = sampleRateHz,
                durationMs = recordedDurationMs,
                cacheFile = cacheFile
            )
        } finally {
            mutex.unlock()
        }
    }

    companion object {
        fun isAllZeros(pcmBytes: ByteArray): Boolean {
            if (pcmBytes.isEmpty()) return true
            for (b in pcmBytes) {
                if (b != 0.toByte()) return false
            }
            return true
        }
    }
}
