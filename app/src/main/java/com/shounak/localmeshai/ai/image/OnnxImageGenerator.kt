package com.shounak.localmeshai.ai.image

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.shounak.localmeshai.utils.OnnxT2iGraphSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.security.SecureRandom
import java.util.Random
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

data class OnnxImageGenerationResult(
    val imagePath: String,
    val seed: Long,
    val width: Int,
    val height: Int,
    val elapsedMs: Long,
    val modelId: String,
    val modelName: String,
    val executionProvider: String
)

/**
 * 100% offline single-graph ONNX Text-to-Image engine.
 * Loads models directly from local filesystem paths so external `.onnx.data` weights are mapped
 * natively by ONNX Runtime without overflowing the JVM heap, and uses XNNPACK with clean CPU EP fallback.
 */
class OnnxImageGenerator private constructor(private val appContext: Context) {
    private val sessionMutex = Mutex()
    private val activeRunOptions = AtomicReference<OrtSession.RunOptions?>(null)
    private val activeCancelFlag = AtomicReference<java.util.concurrent.atomic.AtomicBoolean?>(null)
    private val trimScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var cachedModelId: String? = null
    private var cachedModelPath: String? = null
    private var cachedBackendPref: String? = null
    private var cachedSession: OrtSession? = null
    private var cachedTokenizer: T5UnigramTokenizer? = null
    private var cachedExecutionProvider: String = "CPU"

    private val _isSessionLoaded = MutableStateFlow(false)
    val isSessionLoaded: StateFlow<Boolean> = _isSessionLoaded.asStateFlow()

    fun cancelActive() {
        activeCancelFlag.get()?.set(true)
        activeRunOptions.get()?.let { runOpts ->
            runCatching { runOpts.setTerminate(true) }
        }
    }

    suspend fun prepareModel(model: InstalledOnnxT2iModel): String = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        val cancelFlag = java.util.concurrent.atomic.AtomicBoolean(false)
        activeCancelFlag.set(cancelFlag)
        val env = OrtEnvironment.getEnvironment()
        sessionMutex.withLock {
            try {
                if (cancelFlag.get()) throw CancellationException("Image model loading cancelled")
                val (_, _, provider) = ensureSessionLocked(env, model)
                if (cancelFlag.get() || !coroutineContext[Job]?.isActive.let { it != false }) {
                    closeSessionLocked()
                    throw CancellationException("Image model loading cancelled")
                }
                provider
            } finally {
                activeCancelFlag.compareAndSet(cancelFlag, null)
            }
        }
    }

    suspend fun unload() {
        sessionMutex.withLock {
            closeSessionLocked()
        }
    }

    fun onTrimMemory() {
        cancelActive()
        trimScope.launch {
            unload()
        }
    }

    suspend fun generate(
        model: InstalledOnnxT2iModel,
        prompt: String,
        seed: Long = nextRandomSeed(),
        releaseAfterGeneration: Boolean = false,
        onStageChanged: (stage: String) -> Unit = {},
        savePng: suspend (pngBytes: ByteArray, seed: Long) -> String
    ): OnnxImageGenerationResult = withContext(Dispatchers.Default) {
        val cleanPrompt = prompt.trim()
        require(cleanPrompt.isNotEmpty()) { "Prompt must not be blank." }

        coroutineContext.ensureActive()

        val startMs = System.currentTimeMillis()
        val env = OrtEnvironment.getEnvironment()
        val graphSpec = model.graphSpec
        val cancelFlag = java.util.concurrent.atomic.AtomicBoolean(false)
        activeCancelFlag.set(cancelFlag)

        sessionMutex.withLock {
            try {
                if (cancelFlag.get()) throw CancellationException("Image generation cancelled")
                onStageChanged("Loading ${model.name}…")
                val (session, tokenizer, providerName) = ensureSessionLocked(env, model)
                if (cancelFlag.get()) {
                    closeSessionLocked()
                    throw CancellationException("Image generation cancelled")
                }
                coroutineContext.ensureActive()

                onStageChanged("Generating ${graphSpec.imageWidth}×${graphSpec.imageHeight} image…")
                val encoded = tokenizer.encodePadded(cleanPrompt, graphSpec.sequenceLength)

                val noiseCount = graphSpec.noiseShape.fold(1L) { acc, d -> acc * maxOf(1L, d) }.toInt()
                val noiseValues = generateGaussianNoise(
                    count = noiseCount,
                    seed = seed,
                    scale = model.defaultNoiseScale
                )

                val runOptions = OrtSession.RunOptions()
                if (cancelFlag.get()) runCatching { runOptions.setTerminate(true) }
                activeRunOptions.set(runOptions)
                val inputs = LinkedHashMap<String, OnnxTensor>(3)

                try {
                    if (cancelFlag.get()) throw CancellationException("Image generation cancelled")
                    inputs[graphSpec.inputIdsName] = createIntTensor(
                        env = env,
                        values = encoded.inputIds,
                        shape = longArrayOf(1L, graphSpec.sequenceLength.toLong()),
                        elemType = graphSpec.inputIdsElemType
                    )

                    val maskName = graphSpec.attentionMaskName
                    if (maskName != null) {
                        inputs[maskName] = createIntTensor(
                            env = env,
                            values = encoded.attentionMask,
                            shape = longArrayOf(1L, graphSpec.sequenceLength.toLong()),
                            elemType = graphSpec.attentionMaskElemType ?: graphSpec.inputIdsElemType
                        )
                    }

                    inputs[graphSpec.noiseInputName] = OnnxTensor.createTensor(
                        env,
                        FloatBuffer.wrap(noiseValues),
                        graphSpec.noiseShape.toLongArray()
                    )

                    coroutineContext.ensureActive()
                    val result = session.run(inputs, setOf(graphSpec.outputName), runOptions)
                    result.use { outputs ->
                        coroutineContext.ensureActive()
                        val outValue = outputs.get(graphSpec.outputName).orElse(null)
                            ?: outputs.firstOrNull()?.value
                            ?: error("ONNX image model produced no output tensor")
                        val outTensor = outValue as? OnnxTensor
                            ?: error("Unexpected ONNX output type: ${outValue.javaClass.simpleName}")
                        val outInfo = outTensor.info
                        val shape = outInfo.shape

                        val width: Int
                        val height: Int
                        val isNhwc: Boolean
                        if (shape.size == 4 && shape[3] == 3L && shape[1] != 3L) {
                            height = shape[1].toInt().coerceAtLeast(1)
                            width = shape[2].toInt().coerceAtLeast(1)
                            isNhwc = true
                        } else {
                            height = shape.getOrNull(2)?.toInt()?.coerceAtLeast(1) ?: graphSpec.imageHeight
                            width = shape.getOrNull(3)?.toInt()?.coerceAtLeast(1) ?: graphSpec.imageWidth
                            isNhwc = false
                        }

                        val floatOut = extractOutputFloats(outTensor, height * width * 3)
                        val pixels = if (isNhwc) {
                            nhwcFloatToArgbPixels(floatOut, width, height)
                        } else {
                            nchwFloatToArgbPixels(floatOut, width, height)
                        }

                        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
                        val pngBytes = try {
                            ByteArrayOutputStream(width * height).use { baos ->
                                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)) {
                                    "Failed to encode generated bitmap as PNG"
                                }
                                baos.toByteArray()
                            }
                        } finally {
                            bitmap.recycle()
                        }

                        coroutineContext.ensureActive()
                        val savedPath = savePng(pngBytes, seed)
                        val elapsedMs = System.currentTimeMillis() - startMs

                        OnnxImageGenerationResult(
                            imagePath = savedPath,
                            seed = seed,
                            width = width,
                            height = height,
                            elapsedMs = elapsedMs,
                            modelId = model.id,
                            modelName = model.name,
                            executionProvider = providerName
                        )
                    }
                } finally {
                    activeCancelFlag.compareAndSet(cancelFlag, null)
                    activeRunOptions.compareAndSet(runOptions, null)
                    runCatching { runOptions.close() }
                    inputs.values.forEach { tensor -> runCatching { tensor.close() } }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (t.message?.contains("Terminate", ignoreCase = true) == true) {
                    throw CancellationException("Image generation cancelled", t)
                }
                throw t
            } finally {
                if (releaseAfterGeneration) {
                    closeSessionLocked()
                }
            }
        }
    }

    private fun ensureSessionLocked(
        env: OrtEnvironment,
        model: InstalledOnnxT2iModel
    ): Triple<OrtSession, T5UnigramTokenizer, String> {
        val pref = com.shounak.localmeshai.utils.AppSettings.getInstance(appContext).settings.value.llamaBackendPreference.uppercase()
        val currentSession = cachedSession
        val currentTok = cachedTokenizer
        if (currentSession != null && currentTok != null &&
            cachedModelId == model.id &&
            cachedModelPath == model.onnxFile.absolutePath &&
            cachedBackendPref == pref
        ) {
            return Triple(currentSession, currentTok, cachedExecutionProvider)
        }

        closeSessionLocked()

        val tokenizerSource = model.vocabFile ?: model.tokenizerJsonFile
            ?: error("Missing tokenizer vocabulary (vocab.json or tokenizer.json) for ${model.name}")
        val tokenizer = T5UnigramTokenizer.fromFile(tokenizerSource)

        val (session, epName) = createSessionWithBackendFallback(env, model.onnxFile.absolutePath, pref)
        cachedModelId = model.id
        cachedModelPath = model.onnxFile.absolutePath
        cachedBackendPref = pref
        cachedSession = session
        cachedTokenizer = tokenizer
        cachedExecutionProvider = epName
        _isSessionLoaded.value = true
        return Triple(session, tokenizer, epName)
    }

    private fun createSessionWithBackendFallback(
        env: OrtEnvironment,
        onnxPath: String,
        preferredBackend: String
    ): Pair<OrtSession, String> {
        val threads = com.shounak.localmeshai.ai.RuntimeExecutionPolicy.resolve(appContext).cpuThreads.coerceIn(1, 6)
        val allowNpu = preferredBackend == "NPU" || preferredBackend == "AUTO"
        val npuCapability = com.shounak.localmeshai.ai.NpuCapabilityDetector.detect(appContext, ONNX_T2I_NPU_GUARD_ID)
        if (allowNpu && npuCapability.onnx.supported && !com.shounak.localmeshai.utils.InitCrashGuard.isNpuBlocked(appContext, ONNX_T2I_NPU_GUARD_ID)) {
            val qnnOpts = buildBaseSessionOptions(threads)
            val qnnAdded = runCatching {
                val addQnn = qnnOpts.javaClass.getMethod("addQnn", Map::class.java)
                addQnn.invoke(qnnOpts, mapOf("backend_path" to "libQnnHtp.so"))
            }.isSuccess
            if (qnnAdded) {
                com.shounak.localmeshai.utils.InitCrashGuard.markNpuInitStarted(appContext, ONNX_T2I_NPU_GUARD_ID)
                try {
                    val session = env.createSession(onnxPath, qnnOpts)
                    com.shounak.localmeshai.utils.InitCrashGuard.markNpuInitSucceeded(appContext, ONNX_T2I_NPU_GUARD_ID)
                    return session to "Qualcomm NPU"
                } catch (t: Throwable) {
                    com.shounak.localmeshai.utils.InitCrashGuard.markNpuInitFailed(appContext, ONNX_T2I_NPU_GUARD_ID)
                    Log.w(TAG, "ORT QNN NPU init failed; falling back: ${t.message}")
                } finally {
                    runCatching { qnnOpts.close() }
                }
            } else {
                runCatching { qnnOpts.close() }
            }
        }

        if (preferredBackend == "NPU" && npuCapability.isNpuFlavorBuild &&
            npuCapability.vendor != com.shounak.localmeshai.ai.NpuVendor.NONE &&
            !com.shounak.localmeshai.utils.InitCrashGuard.isNpuBlocked(appContext, ONNX_T2I_NNAPI_GUARD_ID)
        ) {
            val nnapiOpts = buildBaseSessionOptions(threads)
            val nnapiAdded = runCatching {
                val noArg = nnapiOpts.javaClass.methods.firstOrNull { it.name == "addNnapi" && it.parameterCount == 0 }
                if (noArg != null) {
                    noArg.invoke(nnapiOpts)
                    true
                } else {
                    false
                }
            }.getOrDefault(false)
            if (nnapiAdded) {
                com.shounak.localmeshai.utils.InitCrashGuard.markNpuInitStarted(appContext, ONNX_T2I_NNAPI_GUARD_ID)
                try {
                    val session = env.createSession(onnxPath, nnapiOpts)
                    com.shounak.localmeshai.utils.InitCrashGuard.markNpuInitSucceeded(appContext, ONNX_T2I_NNAPI_GUARD_ID)
                    return session to npuCapability.primaryBadgeLabel
                } catch (t: Throwable) {
                    com.shounak.localmeshai.utils.InitCrashGuard.markNpuInitFailed(appContext, ONNX_T2I_NNAPI_GUARD_ID)
                    Log.w(TAG, "ORT NNAPI NPU init failed; falling back to XNNPACK: ${t.message}")
                } finally {
                    runCatching { nnapiOpts.close() }
                }
            } else {
                runCatching { nnapiOpts.close() }
            }
        }

        val xnnpackOpts = buildBaseSessionOptions(threads)
        val xnnpackAdded = runCatching {
            xnnpackOpts.addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
        }.isSuccess
        if (xnnpackAdded) {
            try {
                return env.createSession(onnxPath, xnnpackOpts) to "XNNPACK"
            } catch (t: Throwable) {
                Log.w(TAG, "XNNPACK session init failed; falling back to CPU EP: ${t.message}")
            } finally {
                runCatching { xnnpackOpts.close() }
            }
        } else {
            runCatching { xnnpackOpts.close() }
        }

        val cpuOpts = buildBaseSessionOptions(threads)
        return try {
            env.createSession(onnxPath, cpuOpts) to "CPU"
        } finally {
            runCatching { cpuOpts.close() }
        }
    }

    private fun buildBaseSessionOptions(threads: Int = DEFAULT_CPU_THREADS): OrtSession.SessionOptions {
        return OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(threads)
            setInterOpNumThreads(1)
            setMemoryPatternOptimization(true)
        }
    }

    private fun closeSessionLocked() {
        runCatching { cachedSession?.close() }
        cachedSession = null
        cachedTokenizer = null
        cachedModelId = null
        cachedModelPath = null
        cachedBackendPref = null
        _isSessionLoaded.value = false
    }

    private fun createIntTensor(
        env: OrtEnvironment,
        values: LongArray,
        shape: LongArray,
        elemType: Int
    ): OnnxTensor {
        return if (elemType == ELEM_INT32) {
            val ints = IntArray(values.size) { values[it].toInt() }
            OnnxTensor.createTensor(env, IntBuffer.wrap(ints), shape)
        } else {
            OnnxTensor.createTensor(env, LongBuffer.wrap(values), shape)
        }
    }

    private fun extractOutputFloats(tensor: OnnxTensor, expectedCount: Int): FloatArray {
        val fb = tensor.floatBuffer
        if (fb != null) {
            fb.rewind()
            val arr = FloatArray(minOf(expectedCount, fb.remaining()))
            fb.get(arr)
            return arr
        }
        val sb = tensor.shortBuffer
        if (sb != null) {
            sb.rewind()
            val count = minOf(expectedCount, sb.remaining())
            val out = FloatArray(count)
            for (i in 0 until count) {
                out[i] = float16ToFloat(sb.get())
            }
            return out
        }
        error("Unsupported ONNX image output tensor buffer type")
    }

    companion object {
        private const val TAG = "OnnxImageGenerator"
        private const val ONNX_T2I_NPU_GUARD_ID = "onnx_t2i_qnn"
        private const val ONNX_T2I_NNAPI_GUARD_ID = "onnx_t2i_nnapi"
        private const val DEFAULT_CPU_THREADS = 4
        private const val ELEM_INT32 = 6

        @Volatile
        private var instance: OnnxImageGenerator? = null

        fun getInstance(context: Context): OnnxImageGenerator {
            return instance ?: synchronized(this) {
                instance ?: OnnxImageGenerator(context.applicationContext).also { instance = it }
            }
        }

        fun releaseIfInitialized() {
            instance?.onTrimMemory()
        }

        fun nextRandomSeed(): Long = (SecureRandom().nextLong() and Long.MAX_VALUE)

        /**
         * Deterministic Box-Muller Gaussian noise `(mu=0, sigma=1) * scale`.
         */
        fun generateGaussianNoise(count: Int, seed: Long, scale: Float = 2.0f): FloatArray {
            require(count >= 0) { "Noise element count must be non-negative" }
            val out = FloatArray(count)
            val rng = Random(seed)
            var i = 0
            while (i < count) {
                val u1 = maxOf(1e-7, rng.nextDouble())
                val u2 = rng.nextDouble()
                val radius = sqrt(-2.0 * ln(u1))
                val theta = 2.0 * PI * u2
                out[i] = (radius * cos(theta)).toFloat() * scale
                i++
                if (i < count) {
                    out[i] = (radius * kotlin.math.sin(theta)).toFloat() * scale
                    i++
                }
            }
            return out
        }

        /**
         * Converts `[1, 3, H, W]` NCHW `float32` image values in `[-1, 1]` to packed `ARGB_8888` pixels.
         */
        fun nchwFloatToArgbPixels(output: FloatArray, width: Int, height: Int): IntArray {
            val plane = width * height
            require(output.size >= plane * 3) { "Output tensor too small for ${width}x${height} RGB" }
            val pixels = IntArray(plane)
            for (idx in 0 until plane) {
                val r = channelToByteInt(output[idx])
                val g = channelToByteInt(output[plane + idx])
                val b = channelToByteInt(output[2 * plane + idx])
                pixels[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            return pixels
        }

        /**
         * Converts `[1, H, W, 3]` NHWC `float32` image values in `[-1, 1]` to packed `ARGB_8888` pixels.
         */
        fun nhwcFloatToArgbPixels(output: FloatArray, width: Int, height: Int): IntArray {
            val plane = width * height
            require(output.size >= plane * 3) { "Output tensor too small for ${width}x${height} NHWC RGB" }
            val pixels = IntArray(plane)
            for (idx in 0 until plane) {
                val base = idx * 3
                val r = channelToByteInt(output[base])
                val g = channelToByteInt(output[base + 1])
                val b = channelToByteInt(output[base + 2])
                pixels[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            return pixels
        }

        internal fun channelToByteInt(value: Float): Int {
            if (!value.isFinite()) return 0
            return (value.coerceIn(-1f, 1f) * 127.5f + 128f).toInt().coerceIn(0, 255)
        }

        internal fun float16ToFloat(h: Short): Float {
            val bits = h.toInt() and 0xFFFF
            val sign = (bits and 0x8000) shl 16
            val exp = (bits ushr 10) and 0x1F
            val mant = bits and 0x03FF
            val fBits = when (exp) {
                0 -> {
                    if (mant == 0) sign
                    else {
                        var m = mant
                        var e = 127 - 15 + 1
                        while ((m and 0x0400) == 0) {
                            m = m shl 1
                            e--
                        }
                        m = m and 0x03FF
                        sign or (e shl 23) or (m shl 13)
                    }
                }
                0x1F -> sign or 0x7F800000 or (mant shl 13)
                else -> sign or ((exp + 127 - 15) shl 23) or (mant shl 13)
            }
            return java.lang.Float.intBitsToFloat(fBits)
        }
    }
}
