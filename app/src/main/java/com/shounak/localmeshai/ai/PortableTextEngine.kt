package com.shounak.localmeshai.ai

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.shounak.localmeshai.models.ModelFormat
import com.shounak.localmeshai.utils.PortableModelBundle
import org.json.JSONArray
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.LongBuffer
import kotlin.math.exp
import kotlin.random.Random

/** One request at a time, including shutdown. CPU execution preserves the app's runtime ownership gate. */
internal class PortableTextEngine private constructor(private val file: File, private val format: ModelFormat, val contextTokens: Int) : AutoCloseable {
    private var tokenizer = 0L
    private var onnx: OrtSession? = null
    private var onnxDecoder: OnnxTextSession? = null
    private var tflite: Interpreter? = null
    private var promptHasSpecialTokens = false
    private var reasoningPrefill = false
    private val eos: Set<Int>
    @Volatile private var stopped = false
    private val lifecycle = kotlinx.coroutines.sync.Mutex()

    init {
        val config = PortableModelBundle.json(File(file.parentFile, "config.json"))
        val generation = File(file.parentFile, "generation_config.json").takeIf { it.isFile }?.let(PortableModelBundle::json)
        eos = when (val value = generation?.opt("eos_token_id") ?: config.get("eos_token_id")) {
            is JSONArray -> (0 until value.length()).map { value.getInt(it) }.toSet()
            is Number -> setOf(value.toInt())
            else -> error("Invalid eos_token_id")
        }
        require(eos.isNotEmpty() && eos.all { it >= 0 }) { "Invalid eos_token_id" }
    }

    companion object {
        fun open(file: File, format: ModelFormat, requestedContext: Int): PortableTextEngine {
            val caps = PortableModelBundle.inspect(file, format)
            PortableModelBundle.runtimeRestriction(file, format)?.let { throw IllegalArgumentException(it.reason) }
            val engine = PortableTextEngine(file, format, minOf(caps.contextLength, requestedContext.coerceAtLeast(2)))
            try {
                val weights = if (format == ModelFormat.Safetensors) com.shounak.localmeshai.utils.PortableModelProfile.weights(file) else emptyArray()
                engine.tokenizer = CandleBridge.create(requireNotNull(file.parent), weights, engine.contextTokens)
                when (format) {
                    ModelFormat.Onnx -> OrtSession.SessionOptions().use { options ->
                        options.setIntraOpNumThreads(2)
                        engine.onnx = OrtEnvironment.getEnvironment().createSession(file.absolutePath, options)
                        val session = requireNotNull(engine.onnx)
                        engine.onnxDecoder = OnnxTextSession(session, PortableModelBundle.json(File(file.parentFile, "config.json")))
                    }
                    ModelFormat.TensorFlowLite -> {
                        engine.tflite = Interpreter(file, Interpreter.Options().setNumThreads(2))
                        val interpreter = requireNotNull(engine.tflite)
                        val names = (0 until interpreter.inputTensorCount).map { tokenInputName(interpreter.getInputTensor(it).name()) }
                        require("input_ids" in names && names.all { it in INPUTS }) {
                            "TFLite text models must accept input_ids and optional attention_mask/position_ids, without required cache inputs"
                        }
                        for (i in names.indices) {
                            val tensor = interpreter.getInputTensor(i)
                            require(tensor.dataType() in setOf(DataType.INT32, DataType.INT64) && tensor.shape().size == 2) { "TFLite inputs must be integer [1, sequence] tensors" }
                            require(tensor.shapeSignature().contentEquals(intArrayOf(1, -1)) || tensor.shapeSignature().contentEquals(intArrayOf(-1, -1))) { "TFLite requires a dynamic sequence dimension" }
                        }
                        require(interpreter.outputTensorCount == 1) { "TFLite models must expose one logits output" }
                    }
                    ModelFormat.Safetensors -> Unit
                    else -> error("Unsupported portable runtime")
                }
                return engine
            } catch (t: Throwable) {
                runCatching { engine.close() }.exceptionOrNull()?.let(t::addSuppressed)
                throw t
            }
        }
        private val INPUTS = setOf("input_ids", "attention_mask", "position_ids")
        internal fun tokenInputName(name: String): String = name.substringAfterLast('/').removePrefix("serving_default_").substringBefore(':')
    }

    fun stop() { stopped = true }

    fun renderPrompt(messages: List<Pair<String, String>>?, fallback: String, memory: String, thinking: Boolean = false): String {
        reasoningPrefill = false
        val config = PortableModelBundle.json(File(file.parentFile, "config.json"))
        val tokenizerConfig = File(file.parentFile, "tokenizer_config.json").takeIf { it.isFile }?.let(PortableModelBundle::json) ?: org.json.JSONObject()
        val raw = messages?.takeIf { it.isNotEmpty() } ?: listOf("user" to fallback)
        val turns = if (memory.isNotBlank() && raw.none { it.first == "system" }) listOf("system" to memory) + raw else raw
        val override = config.optString("solus_prompt_format")
        val template = when (val value = tokenizerConfig.opt("chat_template")) {
            is String -> value
            is JSONArray -> (0 until value.length()).mapNotNull { value.optJSONObject(it) }.firstOrNull { it.optString("name") == "default" }?.optString("template")
            null -> null
            else -> null
        }
        if (override.isBlank() && !template.isNullOrBlank()) {
            fun special(name: String): String = when (val value = tokenizerConfig.opt(name)) {
                is String -> value
                is org.json.JSONObject -> value.optString("content")
                null -> ""
                else -> ""
            }
            val history = JSONArray().apply { turns.forEach { (role, content) -> put(org.json.JSONObject().put("role", role).put("content", content)) } }
            promptHasSpecialTokens = true
            val enabled = thinking && com.shounak.localmeshai.utils.PortableThinking.capability(config, tokenizerConfig) != com.shounak.localmeshai.models.ThinkingCapability.Unsupported
            return LlamaCppEngine.nativeRenderPortableChat(template, history.toString(), special("bos_token"), special("eos_token"), enabled).also {
                reasoningPrefill = com.shounak.localmeshai.utils.PortableThinking.hasReasoningPrefill(it)
            }
        }
        val promptFormat = override.ifBlank { when (config.optString("model_type")) {
            "qwen2" -> "chatml"
            "gpt2" -> "completion"
            "llama" -> if (tokenizerConfig.toString().contains("<|start_header_id|>")) "llama3" else "llama2"
            "mistral" -> "mistral"
            "gemma" -> "gemma"
            "phi3" -> "phi3"
            "phi" -> "phi"
            else -> "plain"
        } }
        promptHasSpecialTokens = promptFormat !in setOf("plain", "completion", "phi")
        return PortablePrompt.render(promptFormat, turns)
    }

    suspend fun generate(prompt: String, limit: Int, temperature: Float, topP: Float, topK: Int, onText: (String) -> Unit, onToken: (Int) -> Unit): String {
        lifecycle.lock()
        try {
            check(tokenizer != 0L) { "Model has been closed" }
            stopped = false
            CandleBridge.reset(tokenizer)
            onnxDecoder?.reset()
            val promptIds = if (promptHasSpecialTokens) CandleBridge.encodeRendered(tokenizer, prompt) else CandleBridge.encode(tokenizer, prompt)
            var pendingReasoningPrefix = reasoningPrefill
            return PortableTokenLoop.generate(promptIds, contextTokens, limit, eos, { stopped },
                next = { tokens, step ->
                    val logits = when (format) {
                        ModelFormat.Safetensors -> CandleBridge.forward(tokenizer, if (step == 0) promptIds else intArrayOf(tokens.last()), if (step == 0) 0 else tokens.size - 1)
                        ModelFormat.Onnx -> requireNotNull(onnxDecoder).logits(tokens)
                        ModelFormat.TensorFlowLite -> tfliteLogits(tokens)
                        else -> error("Unsupported runtime")
                    }
                    sample(logits, temperature, topP, topK)
                }, decode = { CandleBridge.decode(tokenizer, it) }, onText = { delta ->
                    if (pendingReasoningPrefix) { onText("<think>"); pendingReasoningPrefix = false }
                    onText(delta)
                }, onToken = onToken)
        } finally { onnxDecoder?.reset(); lifecycle.unlock() }
    }

    private fun input(name: String, tokens: List<Int>): LongArray = LongArray(tokens.size) {
        when (name) { "input_ids" -> tokens[it].toLong(); "attention_mask" -> 1L; "position_ids" -> it.toLong(); else -> error("Unsupported input $name") }
    }

    private fun tfliteLogits(tokens: List<Int>): FloatArray {
        val interpreter = requireNotNull(tflite)
        for (i in 0 until interpreter.inputTensorCount) interpreter.resizeInput(i, intArrayOf(1, tokens.size), true)
        interpreter.allocateTensors()
        val inputs = Array<Any>(interpreter.inputTensorCount) { i ->
            val tensor = interpreter.getInputTensor(i)
            val values = input(tokenInputName(tensor.name()), tokens)
            ByteBuffer.allocateDirect(values.size * if (tensor.dataType() == DataType.INT64) 8 else 4).order(ByteOrder.nativeOrder()).apply {
                values.forEach { if (tensor.dataType() == DataType.INT64) putLong(it) else putInt(it.toInt()) }; rewind()
            }
        }
        val output = interpreter.getOutputTensor(0)
        val shape = output.shape()
        require(output.dataType() == DataType.FLOAT32 && ((shape.size == 3 && shape[0] == 1 && shape[1] in intArrayOf(1, tokens.size)) || (shape.size == 2 && shape[0] == 1))) { "Expected float logits [1, sequence, vocabulary] or [1, vocabulary]" }
        require(shape.last() in 1..1_000_000 && output.numBytes() in 4..(256 * 1024 * 1024)) { "Logits exceed the supported output size" }
        val buffer = ByteBuffer.allocateDirect(output.numBytes()).order(ByteOrder.nativeOrder())
        interpreter.runForMultipleInputsOutputs(inputs, mutableMapOf<Int, Any>(0 to buffer))
        buffer.position(output.numBytes() - shape.last() * 4)
        return FloatArray(shape.last()) { buffer.float }
    }

    override fun close() {
        stopped = true
        // The caller's runtime gate waits for generation to finish before shutdown.
        check(lifecycle.tryLock()) { "Cannot close portable model during generation" }
        try {
            onnxDecoder?.close(); onnxDecoder = null
            onnx?.close(); onnx = null
            tflite?.close(); tflite = null
            if (tokenizer != 0L) { CandleBridge.close(tokenizer); tokenizer = 0L }
        } finally { lifecycle.unlock() }
    }

    internal fun sample(logits: FloatArray, temperature: Float, topP: Float, topK: Int): Int {
        require(logits.isNotEmpty() && logits.any { it.isFinite() }) { "Model produced no finite logits" }
        val candidates = logits.indices.filter { logits[it].isFinite() }.sortedByDescending { logits[it] }.take(if (topK > 0) topK else logits.size)
        if (temperature <= 0f) return candidates.first()
        val max = logits[candidates.first()]
        val probabilities = candidates.map { exp(((logits[it] - max) / temperature).toDouble()) }
        val total = probabilities.sum()
        var cumulative = 0.0
        val count = probabilities.indexOfFirst { cumulative += it / total; cumulative >= topP.coerceIn(0.01f, 1f) }.let { if (it < 0) probabilities.size else it + 1 }
        var draw = Random.nextDouble() * probabilities.take(count).sum()
        for (i in 0 until count) { draw -= probabilities[i]; if (draw <= 0) return candidates[i] }
        return candidates[count - 1]
    }
}
