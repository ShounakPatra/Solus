package com.shounak.localmeshai.ai.voice

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.util.Log
import com.shounak.localmeshai.ai.CandleBridge
import com.shounak.localmeshai.ai.RuntimeExecutionPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 100% offline quantized Whisper ONNX speech recognizer (`Xenova/whisper-tiny.en`).
 * Reuses Solus's native Rust [CandleBridge] for mel spectrograms & tokenizer decode, and
 * dynamically adapts to merged KV-cache ONNX decoders (`decoder_model_merged_quantized.onnx`).
 */
class WhisperOnnxRecognizer(
    private val context: Context,
    private val modelDir: File
) : AutoCloseable {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val runMutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val activeRunOptions = AtomicReference<OrtSession.RunOptions?>(null)
    private val activeCancel = AtomicReference<AtomicBoolean?>(null)

    private val configJson: JSONObject = File(modelDir, "config.json")
        .takeIf { it.isFile }
        ?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
        ?: JSONObject()

    private val encoderSession: OrtSession
    private val decoderSession: OrtSession
    private val tokenizerHandle: Long

    init {
        val encoderFile = listOf("encoder_model_quantized.onnx", "encoder_model.onnx")
            .map { File(modelDir, it) }
            .firstOrNull { it.isFile }
            ?: error("Whisper encoder ONNX missing in ${modelDir.absolutePath}")
        val decoderFile = listOf(
            "decoder_model_merged_quantized.onnx",
            "decoder_model_quantized.onnx",
            "decoder_model.onnx"
        ).map { File(modelDir, it) }
            .firstOrNull { it.isFile }
            ?: error("Whisper decoder ONNX missing in ${modelDir.absolutePath}")
        require(File(modelDir, "tokenizer.json").isFile) {
            "Whisper tokenizer.json missing in ${modelDir.absolutePath}"
        }

        val threads = RuntimeExecutionPolicy.resolve(context).cpuThreads.coerceIn(1, 4)
        encoderSession = createOptimizedSession(encoderFile, threads)
        decoderSession = try {
            createOptimizedSession(decoderFile, threads)
        } catch (t: Throwable) {
            runCatching { encoderSession.close() }
            throw t
        }
        tokenizerHandle = try {
            CandleBridge.create(modelDir.absolutePath, emptyArray(), 448)
        } catch (t: Throwable) {
            runCatching { encoderSession.close() }
            runCatching { decoderSession.close() }
            throw t
        }
    }

    private fun createOptimizedSession(file: File, threads: Int): OrtSession {
        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(threads)
            setInterOpNumThreads(1)
            setMemoryPatternOptimization(true)
            runCatching { addXnnpack(mapOf("intra_op_num_threads" to threads.toString())) }
        }
        return try {
            env.createSession(file.absolutePath, opts)
        } finally {
            runCatching { opts.close() }
        }
    }

    fun cancelActive() {
        activeCancel.get()?.set(true)
        activeRunOptions.get()?.let { runCatching { it.setTerminate(true) } }
    }

    suspend fun transcribe(samples16k: FloatArray): String = runMutex.withLock {
        if (closed.get()) throw CancellationException("Whisper recognizer closed")
        if (samples16k.isEmpty()) return@withLock ""
        val cappedSamples = if (samples16k.size > MAX_WHISPER_SAMPLES) {
            samples16k.copyOfRange(samples16k.size - MAX_WHISPER_SAMPLES, samples16k.size)
        } else {
            samples16k
        }

        val cancelFlag = AtomicBoolean(false)
        activeCancel.set(cancelFlag)
        val runOptions = OrtSession.RunOptions()
        activeRunOptions.set(runOptions)

        try {
            val melBins = configJson.optInt("num_mel_bins", 80)
            val mel = CandleBridge.whisperFeatures(cappedSamples, melBins)
            if (cancelFlag.get()) throw CancellationException("Transcription cancelled")

            val featureName = encoderSession.inputNames.firstOrNull { it.contains("input", ignoreCase = true) }
                ?: encoderSession.inputNames.single()
            val melTensor = OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(mel),
                longArrayOf(1L, melBins.toLong(), 3000L)
            )
            try {
                encoderSession.run(mapOf(featureName to melTensor), encoderSession.outputNames, runOptions).use { encoded ->
                    if (cancelFlag.get()) throw CancellationException("Transcription cancelled")
                    val encOutName = listOf("last_hidden_state", "encoder_hidden_states")
                        .firstOrNull { it in encoderSession.outputNames }
                        ?: encoderSession.outputNames.first()
                    val encoderHidden = encoded.get(encOutName).orElse(null) as? OnnxTensor
                        ?: error("Missing Whisper encoder hidden output")

                    val sot = CandleBridge.tokenId(tokenizerHandle, "<|startoftranscript|>")
                    val eos = CandleBridge.tokenId(tokenizerHandle, "<|endoftext|>")
                    val transcribeTok = CandleBridge.tokenId(tokenizerHandle, "<|transcribe|>")
                    val noTimestamps = CandleBridge.tokenId(tokenizerHandle, "<|notimestamps|>")
                    require(sot >= 0 && eos >= 0 && noTimestamps >= 0) {
                        "Required Whisper special tokens missing from tokenizer.json"
                    }

                    val tokens = ArrayList<Int>(64)
                    tokens.add(sot)
                    val vocabSize = configJson.optInt("vocab_size", 51864)
                    if (vocabSize >= 51865) {
                        val langLogits = runDecoderStep(tokens, encoderHidden, runOptions)
                        val langToken = CandleBridge.languageTokens(tokenizerHandle)
                            .filter { it in langLogits.indices }
                            .maxByOrNull { langLogits[it] }
                        if (langToken != null) tokens.add(langToken)
                    }
                    if (transcribeTok >= 0) tokens.add(transcribeTok)
                    tokens.add(noTimestamps)

                    val prefixSize = tokens.size
                    val suppressSet = configJson.optJSONArray("suppress_tokens")?.let { arr ->
                        (0 until arr.length()).map { arr.getInt(it) }.toHashSet()
                    }.orEmpty()
                    val maxGenSteps = (configJson.optInt("max_target_positions", 448) - prefixSize).coerceIn(1, 224)

                    for (step in 0 until maxGenSteps) {
                        if (cancelFlag.get()) throw CancellationException("Transcription cancelled")
                        val logits = runDecoderStep(tokens, encoderHidden, runOptions)
                        var bestIdx = -1
                        var bestVal = Float.NEGATIVE_INFINITY
                        val upper = minOf(logits.size - 1, noTimestamps)
                        for (idx in 0..upper) {
                            if (idx in suppressSet) continue
                            val v = logits[idx]
                            if (v.isFinite() && v > bestVal) {
                                bestVal = v
                                bestIdx = idx
                            }
                        }
                        if (bestIdx < 0 || bestIdx == eos) break
                        tokens.add(bestIdx)
                    }

                    if (cancelFlag.get()) throw CancellationException("Transcription cancelled")
                    val generated = tokens.drop(prefixSize).toIntArray()
                    return@withLock if (generated.isEmpty()) {
                        ""
                    } else {
                        CandleBridge.decode(tokenizerHandle, generated).trim()
                    }
                }
            } finally {
                runCatching { melTensor.close() }
            }
        } catch (t: Throwable) {
            if (cancelFlag.get() || t is CancellationException || t.message?.contains("Terminate", ignoreCase = true) == true) {
                throw CancellationException("Transcription cancelled")
            }
            Log.e(TAG, "Whisper ONNX transcription failed: ${t.message}", t)
            throw t
        } finally {
            activeCancel.compareAndSet(cancelFlag, null)
            activeRunOptions.compareAndSet(runOptions, null)
            runCatching { runOptions.close() }
        }
    }

    private fun runDecoderStep(
        tokens: List<Int>,
        encoderHidden: OnnxTensor,
        runOptions: OrtSession.RunOptions
    ): FloatArray {
        val owned = ArrayList<OnnxTensor>(decoderSession.inputNames.size)
        try {
            val encSeqLen = encoderHidden.info.shape.getOrNull(1)?.toInt()?.coerceAtLeast(1) ?: 1500
            val feed = LinkedHashMap<String, OnnxTensor>(decoderSession.inputNames.size)

            for (name in decoderSession.inputNames) {
                when {
                    name == "encoder_hidden_states" -> {
                        feed[name] = encoderHidden
                    }
                    name == "input_ids" || name == "decoder_input_ids" -> {
                        feed[name] = createIntTensor(name, tokens.toIntArray(), longArrayOf(1L, tokens.size.toLong()))
                            .also(owned::add)
                    }
                    name == "decoder_attention_mask" || name == "attention_mask" -> {
                        val mask = IntArray(tokens.size) { 1 }
                        feed[name] = createIntTensor(name, mask, longArrayOf(1L, tokens.size.toLong()))
                            .also(owned::add)
                    }
                    name == "encoder_attention_mask" -> {
                        val encMask = IntArray(encSeqLen) { 1 }
                        feed[name] = createIntTensor(name, encMask, longArrayOf(1L, encSeqLen.toLong()))
                            .also(owned::add)
                    }
                    name == "position_ids" -> {
                        val pos = IntArray(tokens.size) { it }
                        feed[name] = createIntTensor(name, pos, longArrayOf(1L, tokens.size.toLong()))
                            .also(owned::add)
                    }
                    name == "use_cache_branch" -> {
                        val boolBuf = ByteBuffer.wrap(byteArrayOf(0))
                        feed[name] = OnnxTensor.createTensor(env, boolBuf, longArrayOf(1L), OnnxJavaType.BOOL)
                            .also(owned::add)
                    }
                    name.startsWith("past_key_values") -> {
                        feed[name] = createEmptyPastKvTensor(name).also(owned::add)
                    }
                    else -> {
                        error("Unsupported Whisper decoder input tensor: $name")
                    }
                }
            }

            val outputName = if ("logits" in decoderSession.outputNames) "logits" else decoderSession.outputNames.first()
            decoderSession.run(feed, setOf(outputName), runOptions).use { result ->
                val logitsTensor = result.get(outputName).orElse(null) as? OnnxTensor
                    ?: error("Missing Whisper decoder logits output")
                val vocabSize = logitsTensor.info.shape.last().toInt()
                val buf = requireNotNull(logitsTensor.floatBuffer) { "Expected float32 decoder logits" }
                buf.position((buf.limit() - vocabSize).coerceAtLeast(0))
                return FloatArray(vocabSize).also(buf::get)
            }
        } finally {
            owned.forEach { runCatching { it.close() } }
        }
    }

    private fun createIntTensor(name: String, values: IntArray, shape: LongArray): OnnxTensor {
        val type = (decoderSession.inputInfo[name]?.info as? TensorInfo)?.type
        return if (type == OnnxJavaType.INT32) {
            OnnxTensor.createTensor(env, IntBuffer.wrap(values), shape)
        } else {
            val longs = LongArray(values.size) { values[it].toLong() }
            OnnxTensor.createTensor(env, LongBuffer.wrap(longs), shape)
        }
    }

    private fun createEmptyPastKvTensor(name: String): OnnxTensor {
        val info = decoderSession.inputInfo[name]?.info as? TensorInfo
        val rawShape = info?.shape ?: longArrayOf(1L, 6L, 0L, 64L)
        val heads = rawShape.getOrNull(1)?.takeIf { it > 0L } ?: 6L
        val headDim = rawShape.getOrNull(3)?.takeIf { it > 0L } ?: 64L
        val shape = longArrayOf(1L, heads, 0L, headDim)
        return OnnxTensor.createTensor(env, FloatBuffer.allocate(0), shape)
    }

    suspend fun closeSafely() {
        if (!closed.compareAndSet(false, true)) return
        cancelActive()
        runMutex.withLock {
            runCatching { encoderSession.close() }
            runCatching { decoderSession.close() }
            runCatching { CandleBridge.close(tokenizerHandle) }
        }
    }

    override fun close() {
        if (closed.get()) return
        runBlocking {
            closeSafely()
        }
    }

    companion object {
        private const val TAG = "WhisperOnnxRecognizer"
        const val MAX_WHISPER_SAMPLES = 16_000 * 30
    }
}
