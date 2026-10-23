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
    private val config = PortableModelProfile.config(primary)
    private fun imageProcessor(): JSONObject = File(root, "preprocessor_config.json").takeIf { it.isFile }
        ?.let(PortableModelBundle::json) ?: if (format == ModelFormat.TensorFlowLite)
            TfliteExportInspector.file(primary).processor ?: JSONObject() else JSONObject()
    private var nativeHandle = 0L
    private var onnx: OrtSession? = null
    private var onnxPipeline: OnnxMediaPipeline? = null
    private var mediaGraph: PortableGraph? = null
    private var graphTokenizer = 0L
    private val lifecycle = Mutex()
    @Volatile private var stopped = false
    var generatedImage: File? = null
        private set

    companion object {
        fun open(context: Context, primary: File, format: ModelFormat): PortableMediaEngine {
            val root = PortableModelProfile.root(primary)
            val pipeline = File(root, "model_index.json").takeIf { it.isFile }?.let(PortableModelBundle::json)
            val config = PortableModelProfile.config(primary)
            val task = PortableModelProfile.task(config, pipeline)
            require(task !in setOf(ModelTask.Chat, ModelTask.Unknown)) { "Select a supported image, speech or diffusion pipeline" }
            val engine = PortableMediaEngine(context.applicationContext, primary, format, task)
            try {
                when (format) {
                    ModelFormat.Safetensors -> engine.nativeHandle = CandleBridge.createMedia(root.absolutePath,
                        if (task == ModelTask.ImageGeneration) emptyArray() else PortableModelProfile.weights(primary), task.id)
                    ModelFormat.Onnx -> {
                        if (task == ModelTask.VisionLanguage || PortableModelProfile.isCtc(config)) engine.mediaGraph = PortableGraph(primary, format)
                        else if (task != ModelTask.ImageClassification) engine.onnxPipeline = OnnxMediaPipeline.open(primary, task)
                        else OrtSession.SessionOptions().use { options ->
                            options.setIntraOpNumThreads(2)
                            engine.onnx = OrtEnvironment.getEnvironment().createSession(primary.absolutePath, options)
                        }
                    }
                    ModelFormat.TensorFlowLite -> engine.mediaGraph = PortableGraph(primary, format)
                    else -> error("Unsupported media format")
                }
                engine.mediaGraph?.requireTask(task)
                if (task == ModelTask.VisionLanguage && format != ModelFormat.Safetensors)
                    engine.graphTokenizer = CandleBridge.create(root.path, emptyArray(), 2048)
                return engine
            } catch (failure: Throwable) { runCatching { engine.close() }.onFailure(failure::addSuppressed); throw failure }
        }
    }

    fun stop() { stopped = true; if (nativeHandle != 0L) runCatching { CandleBridge.stopMedia(nativeHandle) } }
    private fun checkActive() { if (stopped) throw CancellationException("Media generation cancelled") }

    suspend fun generate(bitmap: Bitmap?, audio: File?, prompt: String, thinking: Boolean = false): String {
        lifecycle.lock()
        try {
            stopped = false
            generatedImage = null
            if (nativeHandle != 0L) CandleBridge.resetMedia(nativeHandle)
            checkActive()
            return when (task) {
                ModelTask.VisionLanguage -> {
                    require(bitmap != null && !bitmap.isRecycled) { "Attach an image to ask about it" }
                    require(audio == null) { "This vision-language pipeline accepts an image and text" }
                    val processor = imageProcessor()
                    if (nativeHandle != 0L) {
                        val size = config.optJSONObject("vision_config")?.optInt("image_size", 224) ?: 224
                        CandleBridge.answerImage(nativeHandle, imagePixels(bitmap, size, size, processor), prompt.ifBlank { "Describe the image." })
                    } else answerGraphImage(bitmap, prompt.ifBlank { "Describe the image." }, processor, thinking)
                }
                ModelTask.ImageClassification -> {
                    require(bitmap != null && !bitmap.isRecycled) { "Attach an image to classify" }
                    val processor = imageProcessor()
                    val session = onnx
                    val imageSize = config.optInt("image_size", 224)
                    val logits = if (mediaGraph != null) {
                        val (name, pixels) = graphImage(bitmap, processor)
                        val result = requireNotNull(mediaGraph).run(mapOf(name to pixels))
                        require(result.shape.size == 2 && result.shape[0] == 1L) { "Expected classification logits [1, classes]" }
                        result.values
                    } else if (session == null) {
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
                    val processor = imageProcessor()
                    val rate = if (PortableModelProfile.isCtc(config)) processor.optInt("sampling_rate", 16_000) else 16_000
                    require(rate in 8_000..48_000) { "Unsupported speech sample rate" }
                    val pcm = AudioUtils.decodeAudioToFloatPcm(context, Uri.fromFile(audio), maxDurationMs = 30_000, targetSampleRate = rate)
                    checkActive()
                    (if (mediaGraph != null) transcribeCtc(pcm, processor) else onnxPipeline?.transcribe(pcm, ::checkActive)
                        ?: CandleBridge.transcribeAudio(nativeHandle, pcm)).also { checkActive() }
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

    private fun graphImage(bitmap: Bitmap, processor: JSONObject): Pair<String, PortableGraph.Value> {
        val input = requireNotNull(mediaGraph).inputs.singleOrNull { !it.integer && it.shape.size == 4 }
            ?: error("Expected one image tensor plus optional integer text tensors")
        val nhwc = config.optString("solus_image_layout") == "nhwc" || input.shape.last() == 3L
        val defaultSize = config.optJSONObject("vision_config")?.optInt("image_size", 224) ?: config.optInt("image_size", 224)
        val height = input.shape[if (nhwc) 1 else 2].takeIf { it > 0 }?.toInt() ?: defaultSize
        val width = input.shape[if (nhwc) 2 else 3].takeIf { it > 0 }?.toInt() ?: defaultSize
        require(input.shape[if (nhwc) 3 else 1] in longArrayOf(-1,3)) { "Only RGB image graphs are supported" }
        val chw = imagePixels(bitmap, width, height, processor)
        val pixels = if (nhwc) FloatArray(chw.size) { index -> chw[(index % 3)*width*height + index/3] } else chw
        return input.name to PortableGraph.Value(pixels, if (nhwc) longArrayOf(1,height.toLong(),width.toLong(),3) else longArrayOf(1,3,height.toLong(),width.toLong()))
    }

    private fun transcribeCtc(pcm: FloatArray, processor: JSONObject): String {
        require(PortableModelProfile.isCtc(config) && pcm.isNotEmpty()) { "A CTC waveform export and nonempty audio are required" }
        val graph = requireNotNull(mediaGraph)
        val input = graph.inputs.singleOrNull { !it.integer && it.name != "attention_mask" && it.shape.size in 1..2 } ?: error("Expected one waveform input_values tensor")
        val length = input.shape.last().takeIf { it > 0 }?.toInt() ?: pcm.size
        require(length >= pcm.size && length <= processor.optInt("sampling_rate",16_000)*30) { "Audio exceeds the speech graph's fixed input window" }
        val mean = pcm.sumOf { it.toDouble() } / pcm.size
        val variance = pcm.sumOf { (it-mean)*(it-mean) } / pcm.size
        val normalize = processor.optBoolean("do_normalize", true)
        val samples = FloatArray(length) { index -> if (index >= pcm.size) 0f else if (normalize) ((pcm[index]-mean)/kotlin.math.sqrt(variance+1e-7)).toFloat() else pcm[index] }
        val shape = if (input.shape.size == 1) longArrayOf(length.toLong()) else longArrayOf(1,length.toLong())
        val masks = graph.inputs.filter { it.integer }.associate { tensor ->
            require(tensor.name == "attention_mask") { "Unsupported speech input ${tensor.name}" }
            tensor.name to (IntArray(length) { if (it < pcm.size) 1 else 0 } to shape)
        }
        val floats = mutableMapOf(input.name to PortableGraph.Value(samples, shape))
        graph.inputs.firstOrNull { it.name == "attention_mask" && !it.integer }?.let { mask ->
            val maskShape = if (mask.shape.size == 1) longArrayOf(length.toLong()) else longArrayOf(1,length.toLong())
            floats[mask.name] = PortableGraph.Value(FloatArray(length) { if (it < pcm.size) 1f else 0f }, maskShape)
        }
        checkActive()
        val result = graph.run(floats, masks)
        checkActive()
        val vocab = PortableModelBundle.json(File(root,"vocab.json"))
        val tokens = vocab.keys().asSequence().associate { key -> vocab.getInt(key) to key }
        return PortableTensorMath.ctc(result.values, result.shape, tokens, config.optInt("pad_token_id",0), processor.optString("word_delimiter_token","|"))
    }

    private fun answerGraphImage(bitmap: Bitmap, prompt: String, processor: JSONObject, thinking: Boolean): String {
        val graph = requireNotNull(mediaGraph)
        val (name, pixels) = graphImage(bitmap, processor)
        val tokenizerConfig = File(root,"tokenizer_config.json").takeIf { it.isFile }?.let(PortableModelBundle::json) ?: JSONObject()
        val template = PortablePrompt.template(tokenizerConfig)
        fun special(key: String): String = (tokenizerConfig.opt(key) as? String)
            ?: (tokenizerConfig.opt(key) as? JSONObject)?.optString("content").orEmpty()
        val enabled = thinking && PortableThinking.capability(config,tokenizerConfig) != com.shounak.localmeshai.models.ThinkingCapability.Unsupported
        val rendered = if (!template.isNullOrBlank()) LlamaCppEngine.nativeRenderPortableChat(template,
            org.json.JSONArray().put(JSONObject().put("role","user").put("content",prompt)).toString(),special("bos_token"),special("eos_token"),enabled)
            else config.optString("solus_vision_prompt", "{prompt}\n").replace("{prompt}", prompt)
        val tokens = (if (!template.isNullOrBlank()) CandleBridge.encodeRendered(graphTokenizer, rendered) else CandleBridge.encode(graphTokenizer, rendered)).toMutableList()
        val reasoningPrefix = if (PortableThinking.hasReasoningPrefill(rendered)) "<think>" else ""
        val imageId = config.optInt("image_token_index", -1)
        if (imageId >= 0 && imageId !in tokens) {
            val vision = config.optJSONObject("vision_config")
            val size = vision?.optInt("image_size",0) ?: 0
            val patch = vision?.optInt("patch_size",0) ?: 0
            val inferred = if (size > 0 && patch > 0 && size % patch == 0) (size/patch)*(size/patch) else 1
            val count = config.optInt("image_seq_length", config.optInt("solus_image_token_count", inferred))
            require(count in 1..4096)
            tokens.addAll(0,List(count) { imageId })
        }
        val generation = File(root,"generation_config.json").takeIf { it.isFile }?.let(PortableModelBundle::json)
        val eosValue = sequenceOf(generation?.opt("eos_token_id"),config.opt("eos_token_id"),config.optJSONObject("text_config")?.opt("eos_token_id"))
            .firstOrNull { it is Number || it is org.json.JSONArray } ?: error("Missing vision decoder EOS token")
        val eos = if (eosValue is org.json.JSONArray) (0 until eosValue.length()).map { eosValue.getInt(it) }.toSet() else setOf((eosValue as Number).toInt())
        val context = config.optInt("max_position_embeddings", config.optJSONObject("text_config")?.optInt("max_position_embeddings",2048) ?: 2048).coerceAtMost(16384)
        require(tokens.isNotEmpty() && tokens.size < context) { "Image and prompt exceed model context" }
        val generated = mutableListOf<Int>()
        repeat(minOf(256, context-tokens.size).coerceAtLeast(0)) {
            checkActive()
            val integers = graph.inputs.filter { it.integer }.associate { input ->
                val values = when (input.name) {
                    "input_ids" -> tokens.toIntArray()
                    "attention_mask" -> IntArray(tokens.size) { 1 }
                    "position_ids" -> IntArray(tokens.size) { it }
                    else -> error("This fused vision decoder requires an adapter for input ${input.name}")
                }
                input.name to (values to longArrayOf(1,tokens.size.toLong()))
            }
            require("input_ids" in integers) { "Expected a fused pixel_values + input_ids vision decoder" }
            val result = graph.run(mapOf(name to pixels), integers)
            require(result.shape.size in 2..3 && result.shape[0] == 1L && result.shape.last() in 1..1_000_000)
            val vocab = result.shape.last().toInt()
            val offset = result.values.size-vocab
            val token = (0 until vocab).filter { result.values[offset+it].isFinite() }.maxByOrNull { result.values[offset+it] } ?: error("No finite vision decoder scores")
            if (token in eos) return reasoningPrefix + CandleBridge.decode(graphTokenizer, generated.toIntArray())
            tokens.add(token); generated.add(token)
        }
        checkActive()
        return reasoningPrefix + CandleBridge.decode(graphTokenizer, generated.toIntArray())
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
            mediaGraph?.close(); mediaGraph = null
            if (graphTokenizer != 0L) { CandleBridge.close(graphTokenizer); graphTokenizer = 0L }
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
