package com.shounak.localmeshai.ai

import ai.onnxruntime.*
import com.shounak.localmeshai.models.ModelTask
import com.shounak.localmeshai.utils.PortableModelBundle
import com.shounak.localmeshai.utils.PortableModelProfile
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer

/** Complete exported pipelines; component graphs and processor assets remain in one leased generation. */
internal class OnnxMediaPipeline private constructor(private val root: File, private val task: ModelTask) : AutoCloseable {
    private val sessions = mutableMapOf<String, OrtSession>()
    private var tokenizer = 0L
    private val environment = OrtEnvironment.getEnvironment()
    private val config = File(root, "config.json").takeIf { it.isFile }?.let(PortableModelBundle::json) ?: JSONObject()

    companion object {
        fun open(primary: File, task: ModelTask): OnnxMediaPipeline {
            val root = PortableModelProfile.root(primary)
            val pipeline = OnnxMediaPipeline(root, task)
            fun component(vararg names: String): File = names.asSequence().flatMap { name ->
                sequenceOf(PortableModelProfile.component(root, name), File(primary.parentFile, name.substringAfterLast('/')))
            }.firstOrNull { it.isFile } ?: error("Missing ONNX pipeline component: ${names.first()}")
            try {
                val components = when (task) {
                    ModelTask.SpeechRecognition -> mapOf("encoder" to component("encoder_model.onnx", "onnx/encoder_model.onnx"),
                        "decoder" to component("decoder_model.onnx", "onnx/decoder_model.onnx"))
                    ModelTask.ImageGeneration -> mapOf("text" to component("text_encoder/model.onnx"),
                        "unet" to component("unet/model.onnx"), "vae" to component("vae_decoder/model.onnx"))
                    else -> error("Unsupported ONNX pipeline task")
                }
                for ((name, file) in components) OrtSession.SessionOptions().use { options ->
                    options.setIntraOpNumThreads(2)
                    pipeline.sessions[name] = OrtEnvironment.getEnvironment().createSession(file.absolutePath, options)
                }
                val tokenizerDirectory = if (task == ModelTask.ImageGeneration) File(root, "tokenizer") else root
                pipeline.tokenizer = CandleBridge.create(tokenizerDirectory.absolutePath, emptyArray(), if (task == ModelTask.ImageGeneration) 77 else 448)
                return pipeline
            } catch (failure: Throwable) { runCatching { pipeline.close() }.onFailure(failure::addSuppressed); throw failure }
        }
    }

    private fun integer(session: OrtSession, name: String, values: IntArray, shape: LongArray): OnnxTensor {
        val info = session.inputInfo.getValue(name).info as? TensorInfo ?: error("Expected tensor $name")
        return when (info.type) {
            OnnxJavaType.INT64 -> OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(values.size) { values[it].toLong() }), shape)
            OnnxJavaType.INT32 -> OnnxTensor.createTensor(environment, IntBuffer.wrap(values), shape)
            else -> error("Unsupported integer input type ${info.type} for $name")
        }
    }
    private fun floating(session: OrtSession, name: String, values: FloatArray, shape: LongArray): OnnxTensor {
        val info = session.inputInfo.getValue(name).info as? TensorInfo ?: error("Expected tensor $name")
        require(info.type == OnnxJavaType.FLOAT) { "This pipeline requires float32 graph inputs; export float32 or quantized-weight graphs" }
        return OnnxTensor.createTensor(environment, FloatBuffer.wrap(values), shape)
    }
    private fun vector(result: OrtSession.Result, name: String): FloatArray {
        val output = result.get(name).orElse(null) as? OnnxTensor ?: error("Missing output $name")
        val buffer = requireNotNull(output.floatBuffer) { "Expected floating output $name" }
        return FloatArray(buffer.remaining()).also(buffer::get)
    }
    private fun output(session: OrtSession, vararg candidates: String): String = candidates.firstOrNull { it in session.outputNames }
        ?: session.outputNames.singleOrNull() ?: error("Ambiguous ONNX pipeline outputs")

    fun transcribe(samples: FloatArray, checkActive: () -> Unit): String {
        check(task == ModelTask.SpeechRecognition)
        val encoder = sessions.getValue("encoder")
        val decoder = sessions.getValue("decoder")
        val melBins = config.optInt("num_mel_bins", 80)
        val mel = CandleBridge.whisperFeatures(samples, melBins)
        checkActive()
        val featureName = encoder.inputNames.singleOrNull() ?: error("Whisper encoder requires one input_features tensor")
        floating(encoder, featureName, mel, longArrayOf(1, melBins.toLong(), 3000)).use { features ->
            encoder.run(mapOf(featureName to features)).use { encoded ->
                val encodedName = output(encoder, "last_hidden_state", "encoder_hidden_states")
                val hidden = encoded.get(encodedName).orElse(null) as? OnnxTensor ?: error("Missing Whisper encoder output")
                val tokens = mutableListOf<Int>()
                fun special(name: String): Int = CandleBridge.tokenId(tokenizer, name).also { require(it >= 0) { "Missing Whisper token $name" } }
                val start = special("<|startoftranscript|>")
                val eos = special("<|endoftext|>")
                val noTimestamps = special("<|notimestamps|>")
                tokens.add(start)
                if (config.optInt("vocab_size", 51865) >= 51865) {
                    val logits = decodeSpeech(decoder, tokens, hidden)
                    val language = CandleBridge.languageTokens(tokenizer).filter { it in logits.indices }
                        .maxByOrNull { logits[it] } ?: error("Tokenizer has no Whisper language tokens")
                    tokens.add(language)
                }
                tokens.add(special("<|transcribe|>")); tokens.add(noTimestamps)
                val prefix = tokens.size
                val suppressed = config.optJSONArray("suppress_tokens")?.let { values -> (0 until values.length()).map { values.getInt(it) }.toSet() }.orEmpty()
                repeat((config.optInt("max_target_positions", 448) - prefix).coerceIn(1, 448)) {
                    checkActive()
                    val logits = decodeSpeech(decoder, tokens, hidden)
                    val next = logits.indices.filter { index -> logits[index].isFinite() && index <= noTimestamps && index !in suppressed }.maxByOrNull { logits[it] }
                        ?: error("Whisper decoder produced no valid token")
                    if (next == eos) return CandleBridge.decode(tokenizer, tokens.drop(prefix).toIntArray())
                    tokens.add(next)
                }
                return CandleBridge.decode(tokenizer, tokens.drop(prefix).toIntArray())
            }
        }
    }
    private fun decodeSpeech(decoder: OrtSession, tokens: List<Int>, hidden: OnnxTensor): FloatArray {
        val owned = mutableListOf<OnnxTensor>()
        try {
            val feed = decoder.inputNames.associateWith { name ->
                if (name == "encoder_hidden_states") hidden else {
                    val values = when (name) {
                        "input_ids", "decoder_input_ids" -> tokens.toIntArray()
                        "decoder_attention_mask", "attention_mask" -> IntArray(tokens.size) { 1 }
                        "encoder_attention_mask" -> IntArray(hidden.info.shape[1].toInt()) { 1 }
                        "position_ids" -> IntArray(tokens.size) { it }
                        else -> error("Whisper needs a no-cache decoder_model.onnx export; unsupported input $name")
                    }
                    integer(decoder, name, values, longArrayOf(1, values.size.toLong())).also(owned::add)
                }
            }
            decoder.run(feed, setOf("logits")).use { result ->
                val tensor = result.get("logits").orElse(null) as? OnnxTensor ?: error("Missing decoder logits")
                val vocabulary = tensor.info.shape.last().toInt()
                require(vocabulary in 1..1_000_000)
                val buffer = requireNotNull(tensor.floatBuffer)
                buffer.position(buffer.limit() - vocabulary)
                return FloatArray(vocabulary).also(buffer::get)
            }
        } finally { owned.forEach { it.close() } }
    }

    fun generateImage(prompt: String, checkActive: () -> Unit): FloatArray {
        check(task == ModelTask.ImageGeneration)
        val text = sessions.getValue("text")
        val unet = sessions.getValue("unet")
        val vae = sessions.getValue("vae")
        val eos = CandleBridge.tokenId(tokenizer, "<|endoftext|>").also { require(it >= 0) }
        fun encode(promptText: String): FloatArray {
            val raw = CandleBridge.encode(tokenizer, promptText)
            val tokens = IntArray(77) { raw.getOrElse(it) { eos } }
            tokens[76] = eos
            val name = text.inputNames.singleOrNull() ?: error("Expected CLIP input_ids")
            integer(text, name, tokens, longArrayOf(1, 77)).use { ids ->
                val output = output(text, "last_hidden_state")
                text.run(mapOf(name to ids), setOf(output)).use { return vector(it, output) }
            }
        }
        checkActive()
        val unconditional = encode("")
        val conditional = encode(prompt)
        require(unconditional.size == conditional.size && conditional.size % 77 == 0)
        val hidden = conditional.size / 77
        require(hidden in setOf(768, 1024)) { "Only SD1/SD2 CLIP encoders are supported" }
        val embeddings = unconditional + conditional
        val random = java.util.Random()
        var latents = FloatArray(4*32*32) { random.nextGaussian().toFloat() }
        val scheduler = PortableModelProfile.component(root, "scheduler/scheduler_config.json").takeIf { it.isFile }?.let(PortableModelBundle::json)
        val vPrediction = scheduler?.optString("prediction_type", if (hidden == 1024) "v_prediction" else "epsilon") == "v_prediction"
        for (index in 0 until 20) {
            checkActive()
            val owned = mutableListOf<OnnxTensor>()
            val prediction = try {
                val feed = unet.inputNames.associateWith { name -> when (name) {
                    "sample" -> floating(unet, name, latents + latents, longArrayOf(2, 4, 32, 32))
                    "encoder_hidden_states" -> floating(unet, name, embeddings, longArrayOf(2, 77, hidden.toLong()))
                    "timestep" -> {
                        val info = unet.inputInfo.getValue(name).info as TensorInfo
                        val shape = if (info.shape.isEmpty()) longArrayOf() else longArrayOf(1)
                        if (info.type in setOf(OnnxJavaType.INT64, OnnxJavaType.INT32)) integer(unet, name, intArrayOf((19-index)*50+1), shape)
                        else floating(unet, name, floatArrayOf(((19-index)*50+1).toFloat()), shape)
                    }
                    else -> error("Unsupported SD1/SD2 UNet input $name")
                }.also(owned::add) }
                val output = output(unet, "out_sample", "sample")
                unet.run(feed, setOf(output)).use { vector(it, output) }
            } finally { owned.forEach { it.close() } }
            latents = CandleBridge.diffusionStep(latents, prediction, index, vPrediction)
        }
        checkActive()
        val input = vae.inputNames.singleOrNull() ?: error("Expected VAE latent_sample")
        floating(vae, input, FloatArray(latents.size) { latents[it]/0.18215f }, longArrayOf(1, 4, 32, 32)).use { tensor ->
            val output = output(vae, "sample", "out_sample")
            vae.run(mapOf(input to tensor), setOf(output)).use { result ->
                checkActive()
                val image = vector(result, output)
                require(image.size == 3*256*256)
                return FloatArray(image.size) { image[it]/2+0.5f }
            }
        }
    }

    override fun close() {
        sessions.values.forEach { it.close() }; sessions.clear()
        if (tokenizer != 0L) { CandleBridge.close(tokenizer); tokenizer = 0 }
    }
}
