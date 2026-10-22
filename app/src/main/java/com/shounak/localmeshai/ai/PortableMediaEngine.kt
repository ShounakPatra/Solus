package com.shounak.localmeshai.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import ai.onnxruntime.*
import com.shounak.localmeshai.models.ModelFormat
import com.shounak.localmeshai.models.ModelTask
import com.shounak.localmeshai.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.exp

/** Independent media pipelines. Classification and transcription are never presented as VLM chat. */
internal class PortableMediaEngine private constructor(
    private val context: Context, private val primary: File, private val format: ModelFormat,
    val task: ModelTask
) : AutoCloseable {
    private val root = PortableModelProfile.root(primary)
    private val config = File(root, "config.json").takeIf { it.isFile }?.let(PortableModelBundle::json) ?: JSONObject()
    private var nativeHandle = 0L
    private var onnx: OrtSession? = null
    private var onnxPipeline: OnnxMediaPipeline? = null
    private val lifecycle = Mutex()
    @Volatile private var stopped = false
    var generatedImage: File? = null
        private set

    companion object {
        fun open(context: Context, primary: File, format: ModelFormat): PortableMediaEngine {
            val root = PortableModelProfile.root(primary)
            val pipeline = File(root, "model_index.json").takeIf { it.isFile }?.let(PortableModelBundle::json)
            val config = File(root, "config.json").takeIf { it.isFile }?.let(PortableModelBundle::json) ?: JSONObject()
            val task = PortableModelProfile.task(config, pipeline)
            require(task !in setOf(ModelTask.Chat, ModelTask.Unknown)) { "Select a supported image, speech or diffusion pipeline" }
            val engine = PortableMediaEngine(context.applicationContext, primary, format, task)
            try {
                when (format) {
                    ModelFormat.Safetensors -> engine.nativeHandle = CandleBridge.createMedia(root.absolutePath,
                        if (task == ModelTask.ImageGeneration) emptyArray() else PortableModelProfile.weights(primary), task.id)
                    ModelFormat.Onnx -> {
                        if (task != ModelTask.ImageClassification) engine.onnxPipeline = OnnxMediaPipeline.open(primary, task)
                        else OrtSession.SessionOptions().use { options ->
                            options.setIntraOpNumThreads(2)
                            engine.onnx = OrtEnvironment.getEnvironment().createSession(primary.absolutePath, options)
                        }
                    }
                    else -> error("Unsupported media format")
                }
                return engine
            } catch (failure: Throwable) { runCatching { engine.close() }.onFailure(failure::addSuppressed); throw failure }
        }
    }

    fun stop() { stopped = true; if (nativeHandle != 0L) runCatching { CandleBridge.stopMedia(nativeHandle) } }
    private fun checkActive() { if (stopped) throw CancellationException("Media generation cancelled") }

    suspend fun generate(bitmap: Bitmap?, audio: File?, prompt: String): String {
        lifecycle.lock()
        try {
            stopped = false
            generatedImage = null
            if (nativeHandle != 0L) CandleBridge.resetMedia(nativeHandle)
            checkActive()
            return when (task) {
                ModelTask.ImageClassification -> {
                    require(bitmap != null && !bitmap.isRecycled) { "Attach an image to classify" }
                    val processor = File(root, "preprocessor_config.json").takeIf { it.isFile }?.let(PortableModelBundle::json) ?: JSONObject()
                    val session = onnx
                    val imageSize = config.optInt("image_size", 224)
                    val logits = if (session == null) {
                        CandleBridge.classifyImage(nativeHandle, imagePixels(bitmap, imageSize, imageSize, processor))
                    } else {
                        val name = session.inputNames.singleOrNull() ?: error("Image classifier must expose one pixel tensor")
                        val info = session.inputInfo.getValue(name).info as? TensorInfo ?: error("Expected image tensor")
                        require(info.type == OnnxJavaType.FLOAT && info.shape.size == 4 && info.shape[1] == 3L) { "Expected float32 NCHW [1, 3, height, width] image input" }
                        val height = info.shape[2].takeIf { it > 0 }?.toInt() ?: imageSize
                        val width = info.shape[3].takeIf { it > 0 }?.toInt() ?: imageSize
                        val pixels = imagePixels(bitmap, width, height, processor)
                        OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), FloatBuffer.wrap(pixels), longArrayOf(1, 3, height.toLong(), width.toLong())).use { tensor ->
                            val outputName = if ("logits" in session.outputNames) "logits" else session.outputNames.singleOrNull() ?: error("Ambiguous classifier output")
                            session.run(mapOf(name to tensor), setOf(outputName)).use { result ->
                                val output = result.get(outputName).orElse(null) as? OnnxTensor ?: error("Missing classifier logits")
                                require(output.info.shape.size == 2 && output.info.shape[0] == 1L) { "Expected classification scores [1, classes]" }
                                val buffer = requireNotNull(output.floatBuffer)
                                FloatArray(buffer.remaining()).also(buffer::get)
                            }
                        }
                    }
                    checkActive()
                    classification(logits, config.optJSONObject("id2label"))
                }
                ModelTask.SpeechRecognition -> {
                    require(audio?.isFile == true) { "Attach an audio recording to transcribe" }
                    val pcm = AudioUtils.decodeAudioToFloatPcm(context, Uri.fromFile(audio), maxDurationMs = 30_000, targetSampleRate = 16_000)
                    checkActive()
                    (onnxPipeline?.transcribe(pcm, ::checkActive) ?: CandleBridge.transcribeAudio(nativeHandle, pcm)).also { checkActive() }
                }
                ModelTask.ImageGeneration -> {
                    require(prompt.isNotBlank()) { "Describe the image to generate" }
                    require(bitmap == null && audio == null) { "This pipeline generates images from text; image editing needs an img2img adapter" }
                    val pixels = onnxPipeline?.generateImage(prompt, ::checkActive) ?: CandleBridge.generateImage(nativeHandle, prompt)
                    checkActive()
                    require(pixels.size == 3 * 256 * 256 && pixels.all { it.isFinite() }) { "Invalid generated image" }
                    val plane = 256 * 256
                    val colors = IntArray(plane) { i -> Color.rgb(
                        (pixels[i].coerceIn(0f, 1f)*255).toInt(),
                        (pixels[plane+i].coerceIn(0f, 1f)*255).toInt(),
                        (pixels[2*plane+i].coerceIn(0f, 1f)*255).toInt()) }
                    val output = File(context.cacheDir, "generated_${java.util.UUID.randomUUID()}.png")
                    val image = Bitmap.createBitmap(colors, 256, 256, Bitmap.Config.ARGB_8888)
                    try { output.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
                    finally { image.recycle() }
                    generatedImage = output
                    "Generated image (256 × 256)."
                }
                else -> error("Unsupported media task")
            }
        } catch (failure: Exception) {
            if (stopped) throw CancellationException("Media generation cancelled").also { it.initCause(failure) }
            throw failure
        } finally { lifecycle.unlock() }
    }

    private fun imagePixels(bitmap: Bitmap, width: Int, height: Int, processor: JSONObject): FloatArray {
        require(width in 1..4096 && height in 1..4096)
        val centerCrop = processor.optBoolean("do_center_crop", false)
        val edge = processor.optJSONObject("size")?.optInt("shortest_edge", minOf(width, height)) ?: minOf(width, height)
        require(edge in 1..4096) { "Invalid processor resize size" }
        val ratio = if (centerCrop) maxOf(edge.toDouble() / minOf(bitmap.width, bitmap.height), width.toDouble() / bitmap.width, height.toDouble() / bitmap.height) else 0.0
        val scaledWidth = if (centerCrop) (bitmap.width * ratio).toInt().coerceAtLeast(width) else width
        val scaledHeight = if (centerCrop) (bitmap.height * ratio).toInt().coerceAtLeast(height) else height
        require(scaledWidth.toLong() * scaledHeight <= 16_777_216) { "Processor resize exceeds the supported image size" }
        val scaled = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
        val resized = if (centerCrop) Bitmap.createBitmap(scaled, (scaledWidth-width)/2, (scaledHeight-height)/2, width, height) else scaled
        try {
            val pixels = IntArray(width * height)
            resized.getPixels(pixels, 0, width, 0, 0, width, height)
            val mean = processor.optJSONArray("image_mean")
            val std = processor.optJSONArray("image_std")
            val scale = if (processor.optBoolean("do_rescale", true)) processor.optDouble("rescale_factor", 1.0/255) else 1.0
            return FloatArray(pixels.size*3) { index ->
                val channel = index / pixels.size
                val rgb = pixels[index % pixels.size]
                val value = when (channel) { 0 -> Color.red(rgb); 1 -> Color.green(rgb); else -> Color.blue(rgb) } * scale
                if (processor.optBoolean("do_normalize", true)) {
                    val average = mean?.optDouble(channel, 0.5) ?: 0.5
                    val divisor = std?.optDouble(channel, 0.5) ?: 0.5
                    require(divisor.isFinite() && divisor > 0) { "Invalid image normalization" }
                    ((value-average)/divisor).toFloat()
                } else value.toFloat()
            }
        } finally {
            if (resized !== bitmap) resized.recycle()
            if (scaled !== bitmap && scaled !== resized) scaled.recycle()
        }
    }

    override fun close() {
        stop()
        check(lifecycle.tryLock()) { "Cannot close a running media pipeline" }
        try {
            onnxPipeline?.close(); onnxPipeline = null
            onnx?.close(); onnx = null
            if (nativeHandle != 0L) { CandleBridge.closeMedia(nativeHandle); nativeHandle = 0 }
        } finally { lifecycle.unlock() }
    }

    internal fun classification(logits: FloatArray, labels: JSONObject?): String {
        require(logits.isNotEmpty() && logits.all { it.isFinite() }) { "Invalid classification scores" }
        val max = logits.max()
        val weights = logits.map { exp((it - max).toDouble()) }
        val total = weights.sum()
        return "Image classification:\n" + logits.indices.sortedByDescending { logits[it] }.take(5).joinToString("\n") { index ->
            "${labels?.optString(index.toString(), "Class $index") ?: "Class $index"}: ${"%.1f".format(java.util.Locale.ROOT, weights[index]/total*100)}%"
        }
    }
}
