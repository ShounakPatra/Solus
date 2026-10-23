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
internal class PortableTextEngine private constructor(private val file: File, private val format: ModelFormat, contextTokens: Int) : AutoCloseable {
    var contextTokens: Int = contextTokens
        private set
    private var tokenizer = 0L
    private var onnx: OrtSession? = null
    private var onnxDecoder: OnnxTextSession? = null
    private var tflite: Interpreter? = null
    private var tflitePlan: TfliteTextPlan? = null
    private var promptHasSpecialTokens = false
    private var reasoningPrefill = false
    private val eos: Set<Int>
    @Volatile private var stopped = false
    private val lifecycle = kotlinx.coroutines.sync.Mutex()

    init {
        val config = PortableModelBundle.json(File(file.parentFile, "config.json"))
        val generation = File(file.parentFile, "generation_config.json").takeIf { it.isFile }?.let(PortableModelBundle::json)
        eos = PortableModelBundle.eosTokens(config, generation)
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
                        fun describe(tensor: org.tensorflow.lite.Tensor) = TfliteTextPlan.Tensor(
                            tensor.name(), tensor.shape(), tensor.shapeSignature().takeIf { it.isNotEmpty() } ?: tensor.shape(), tensor.dataType())
                        val config = PortableModelBundle.json(File(file.parentFile, "config.json"))
                        val plan = TfliteTextPlan((0 until interpreter.inputTensorCount).map { describe(interpreter.getInputTensor(it)) },
                            (0 until interpreter.outputTensorCount).map { describe(interpreter.getOutputTensor(it)) },
                            config.optString("model_type"), config.optInt("vocab_size"))
                        engine.tflitePlan = plan
                        plan.fixedSequence?.let { engine.contextTokens = minOf(engine.contextTokens, it) }
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
        internal fun tokenInputName(name: String): String = name.substringAfterLast('/').removePrefix("serving_default_").substringBefore(':')
    }

    fun stop() { stopped = true }

    private var promptTurns: List<Pair<String, String>> = emptyList()
    private var promptMemory = ""
    private var promptThinking = false

    fun renderPrompt(messages: List<Pair<String, String>>?, fallback: String, memory: String, thinking: Boolean = false): String {
        promptTurns = messages?.takeIf { it.isNotEmpty() } ?: listOf("user" to fallback)
        promptMemory = memory
        promptThinking = thinking
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
            fun encode(text: String) = if (promptHasSpecialTokens) CandleBridge.encodeRendered(tokenizer, text) else CandleBridge.encode(tokenizer, text)
            var promptIds = encode(prompt)
            var turns = promptTurns
            val budget = contextTokens - minOf(limit.coerceAtLeast(1), maxOf(1, contextTokens / 4))
            while (promptIds.size > budget) {
                val trimmed = PortableHistoryBudget.dropOldestTurn(turns) ?: break
                turns = trimmed
                promptIds = encode(renderPrompt(turns, "", promptMemory, promptThinking))
            }
            require(promptIds.size < contextTokens) { "Your message and system instructions exceed the model context ($contextTokens tokens). Shorten them or choose a larger context." }
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

    private fun tfliteLogits(tokens: List<Int>): FloatArray {
        val interpreter = requireNotNull(tflite)
        val plan = requireNotNull(tflitePlan)
        val sequence = plan.sequenceLength(tokens.size)
        for (i in plan.inputs.indices) {
            if (plan.inputs[i].signature.any { it == -1 }) interpreter.resizeInput(i, intArrayOf(1, sequence), true)
        }
        interpreter.allocateTensors()
        val inputs = Array<Any>(interpreter.inputTensorCount) { i -> plan.input(i, tokens, eos.first()) }
        val output = interpreter.getOutputTensor(plan.logitsIndex)
        val shape = output.shape()
        val offset = plan.logitsOffset(shape, tokens.size)
        require(output.dataType() == DataType.FLOAT32 && output.numBytes() in 4..(256 * 1024 * 1024)) { "Logits exceed the supported output size" }
        val buffer = ByteBuffer.allocateDirect(output.numBytes()).order(ByteOrder.nativeOrder())
        interpreter.runForMultipleInputsOutputs(inputs, mutableMapOf<Int, Any>(plan.logitsIndex to buffer))
        buffer.position(offset)
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
