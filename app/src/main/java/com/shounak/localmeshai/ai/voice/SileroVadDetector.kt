package com.shounak.localmeshai.ai.voice

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * ONNX Runtime wrapper for Silero VAD (`silero_vad.onnx`, 16 kHz, 512-sample chunks).
 * Inspects graph inputs dynamically to support both Silero VAD v5 (`state` + 64-sample context)
 * and v4 (`h` + `c`) seamlessly from local app-private storage.
 */
class SileroVadDetector(private val modelFile: File) : AutoCloseable {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val sessionLock = Any()
    private val closed = AtomicBoolean(false)
    private val usesCombinedState: Boolean
    private var stateBuffer: FloatArray
    private var hBuffer = FloatArray(2 * 1 * 64)
    private var cBuffer = FloatArray(2 * 1 * 64)
    private var contextTail = FloatArray(CONTEXT_SAMPLES_V5)

    init {
        require(modelFile.isFile) { "Silero VAD model file not found: ${modelFile.absolutePath}" }
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = try {
            env.createSession(modelFile.absolutePath, opts)
        } finally {
            runCatching { opts.close() }
        }
        usesCombinedState = session.inputNames.any { it.equals("state", ignoreCase = true) }
        val stateDim = ((session.inputInfo["state"]?.info as? TensorInfo)?.shape?.getOrNull(2)?.toInt())
            ?.takeIf { it > 0 } ?: 128
        stateBuffer = FloatArray(2 * 1 * stateDim)
    }

    fun resetStates() {
        stateBuffer.fill(0f)
        hBuffer.fill(0f)
        cBuffer.fill(0f)
        contextTail.fill(0f)
    }

    /**
     * Evaluates a single 512-sample (32 ms @ 16 kHz) frame and returns speech probability in [0, 1].
     */
    fun predictProbability(frame512: FloatArray): Float {
        require(frame512.size == FRAME_SAMPLES) { "Silero VAD requires $FRAME_SAMPLES samples per frame" }
        synchronized(sessionLock) {
            if (closed.get()) return 0f
            val owned = ArrayList<OnnxTensor>(4)
            try {
            val audioInput = if (usesCombinedState) {
                val combined = FloatArray(CONTEXT_SAMPLES_V5 + FRAME_SAMPLES)
                System.arraycopy(contextTail, 0, combined, 0, CONTEXT_SAMPLES_V5)
                System.arraycopy(frame512, 0, combined, CONTEXT_SAMPLES_V5, FRAME_SAMPLES)
                System.arraycopy(frame512, FRAME_SAMPLES - CONTEXT_SAMPLES_V5, contextTail, 0, CONTEXT_SAMPLES_V5)
                combined
            } else {
                frame512
            }

            val inputName = session.inputNames.firstOrNull { it.equals("input", ignoreCase = true) }
                ?: session.inputNames.first()
            val srName = session.inputNames.firstOrNull { it.equals("sr", ignoreCase = true) }

            val feed = LinkedHashMap<String, OnnxTensor>(4)
            feed[inputName] = OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(audioInput),
                longArrayOf(1L, audioInput.size.toLong())
            ).also(owned::add)

            if (usesCombinedState) {
                val stateDim = (stateBuffer.size / 2).toLong()
                feed["state"] = OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(stateBuffer),
                    longArrayOf(2L, 1L, stateDim)
                ).also(owned::add)
            } else {
                feed["h"] = OnnxTensor.createTensor(env, FloatBuffer.wrap(hBuffer), longArrayOf(2L, 1L, 64L)).also(owned::add)
                feed["c"] = OnnxTensor.createTensor(env, FloatBuffer.wrap(cBuffer), longArrayOf(2L, 1L, 64L)).also(owned::add)
            }

            if (srName != null) {
                val srType = (session.inputInfo[srName]?.info as? TensorInfo)?.type
                val srTensor = if (srType == OnnxJavaType.INT64 || srType == null) {
                    OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(SAMPLE_RATE_HZ.toLong())), longArrayOf(1L))
                } else {
                    OnnxTensor.createTensor(env, java.nio.IntBuffer.wrap(intArrayOf(SAMPLE_RATE_HZ)), longArrayOf(1L))
                }
                feed[srName] = srTensor.also(owned::add)
            }

            session.run(feed).use { results ->
                val probName = session.outputNames.firstOrNull { it.equals("output", ignoreCase = true) }
                    ?: session.outputNames.first()
                val outTensor = results.get(probName).orElse(null) as? OnnxTensor
                val prob = outTensor?.floatBuffer?.let { buf ->
                    if (buf.hasRemaining()) buf.get() else 0f
                } ?: 0f

                if (usesCombinedState) {
                    val stateOutName = session.outputNames.firstOrNull {
                        it.contains("state", ignoreCase = true)
                    }
                    val stateOut = stateOutName?.let { results.get(it).orElse(null) as? OnnxTensor }
                    stateOut?.floatBuffer?.let { buf ->
                        val count = minOf(stateBuffer.size, buf.remaining())
                        buf.get(stateBuffer, 0, count)
                    }
                } else {
                    (results.get("hn").orElse(null) as? OnnxTensor)?.floatBuffer?.let { buf ->
                        val count = minOf(hBuffer.size, buf.remaining())
                        buf.get(hBuffer, 0, count)
                    }
                    (results.get("cn").orElse(null) as? OnnxTensor)?.floatBuffer?.let { buf ->
                        val count = minOf(cBuffer.size, buf.remaining())
                        buf.get(cBuffer, 0, count)
                    }
                }
                return prob.coerceIn(0f, 1f)
            }
            } finally {
                owned.forEach { runCatching { it.close() } }
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(sessionLock) {
            runCatching { session.close() }
        }
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val FRAME_SAMPLES = 512
        private const val CONTEXT_SAMPLES_V5 = 64

        /**
         * Creates an AudioRecord configured with [MediaRecorder.AudioSource.VOICE_COMMUNICATION]
         * for hardware acoustic echo cancellation at 16 kHz mono PCM16.
         */
        @SuppressLint("MissingPermission")
        fun createVoiceAudioRecord(): AudioRecord {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(FRAME_SAMPLES * 4)
            return AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            )
        }

        fun rmsLevel(frame: FloatArray): Float {
            if (frame.isEmpty()) return 0f
            var sum = 0.0
            for (v in frame) sum += (v * v).toDouble()
            return sqrt(sum / frame.size).toFloat().coerceIn(0f, 1f)
        }
    }
}
