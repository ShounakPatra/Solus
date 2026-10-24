package com.shounak.localmeshai.ai

import ai.onnxruntime.*
import org.json.JSONObject
import java.nio.*

/** Owns cached ORT results until the following decode completes. Never closes borrowed cache tensors. */
internal class OnnxTextSession(private val session: OrtSession, private val config: JSONObject) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val inputs = session.inputInfo.mapValues { (_, node) -> node.info as? TensorInfo ?: error("ONNX text inputs must be tensors") }
    private val caches = inputs.keys.filter { it.startsWith("past_key_values.") }
    private val outputs = caches.associateWith { name ->
        val suffix = name.removePrefix("past_key_values.")
        listOf("present.$suffix", "present_key_values.$suffix", name).firstOrNull { it in session.outputNames }
            ?: error("Missing ONNX cache output for $name")
    }
    private var retained: OrtSession.Result? = null
    private var pastLength = 0
    private val singleTokenInput: Boolean
    private val logitsInfo = session.outputInfo.getValue("logits").info as TensorInfo
    private val logitsBuffer = OnnxLogitsBuffer()
    private var outputIsSingleRow: Boolean? = null
    private var learnedVocabulary: Long? = null

    init {
        require("input_ids" in inputs && "logits" in session.outputNames) { "This graph is not a causal text decoder: input_ids and logits are required" }
        val known = setOf("input_ids", "attention_mask", "position_ids", "token_type_ids", "cache_position", "use_cache_branch", "past_sequence_length")
        require(inputs.keys.all { it in known || it in caches }) { "Unsupported ONNX decoder inputs: ${inputs.keys - known - caches.toSet()}" }
        val ids = inputs.getValue("input_ids")
        require(ids.type in integerTypes && ids.shape.size == 2 && ids.shape[0] in longArrayOf(-1, 1)) { "input_ids must be INT32/INT64 [1, sequence]" }
        require(ids.shape[1] < 0 || ids.shape[1] == 1L) { "Fixed ONNX sequence lengths greater than one require a model-specific padding adapter" }
        singleTokenInput = ids.shape[1] == 1L
        require(!singleTokenInput || caches.isNotEmpty()) { "A single-token decoder requires KV-cache inputs to process a prompt" }
        for (name in caches) {
            val info = inputs.getValue(name)
            require(info.type in floatTypes && info.shape.size in 4..5) { "Unsupported cache layout for $name: expected [batch, heads, past, head_dim] or [2, batch, heads, past, head_dim]" }
        }
    }

    fun reset() { retained?.close(); retained = null; pastLength = 0; logitsBuffer.close() }

    fun logits(tokens: List<Int>): FloatArray {
        require(tokens.isNotEmpty())
        if (caches.isEmpty()) return run(tokens, tokens.size)
        require(tokens.size > pastLength) { "ONNX cache position is ahead of input" }
        if (singleTokenInput) {
            var result = FloatArray(0)
            while (pastLength < tokens.size) result = run(listOf(tokens[pastLength]), pastLength + 1,
                copyLogits = pastLength == tokens.lastIndex)
            return result
        }
        return run(tokens.subList(pastLength, tokens.size), tokens.size)
    }

    private fun run(tokens: List<Int>, total: Int, copyLogits: Boolean = true): FloatArray {
        val owned = mutableListOf<OnnxTensor>()
        val feed = mutableMapOf<String, OnnxTensor>()
        var result: OrtSession.Result? = null
        try {
            for ((name, info) in inputs) {
                val borrowed = if (name in caches && retained != null) retained!!.get(outputs.getValue(name)).orElse(null) as? OnnxTensor else null
                if (borrowed != null) { feed[name] = borrowed; continue }
                val tensor = when {
                    name in caches -> emptyCache(info)
                    name == "use_cache_branch" -> {
                        require(info.type == OnnxJavaType.BOOL && (info.shape.isEmpty() || info.shape.contentEquals(longArrayOf(1)))) { "use_cache_branch must be a scalar or [1] bool" }
                        if (info.shape.isEmpty()) OnnxTensor.createTensor(environment, pastLength > 0)
                        else OnnxTensor.createTensor(environment, booleanArrayOf(pastLength > 0))
                    }
                    name == "cache_position" || name == "past_sequence_length" -> {
                        val values = if (name == "past_sequence_length") longArrayOf(pastLength.toLong()) else LongArray(tokens.size) { (pastLength + it).toLong() }
                        val shape = if (info.shape.isEmpty()) longArrayOf() else longArrayOf(values.size.toLong())
                        integerTensor(info.type, values, shape)
                    }
                    else -> {
                        val length = if (name == "attention_mask") total else tokens.size
                        val values = LongArray(length) { index -> when (name) {
                            "input_ids" -> tokens[index].toLong()
                            "attention_mask" -> 1L
                            "position_ids" -> (if (caches.isEmpty()) index else pastLength + index).toLong()
                            "token_type_ids" -> 0L
                            else -> error("Unsupported token input")
                        } }
                        require(info.shape.size == 2 && info.shape[0] in longArrayOf(-1, 1) && (info.shape[1] < 0 || info.shape[1] == length.toLong())) { "Unsupported shape for $name" }
                        integerTensor(info.type, values, longArrayOf(1, length.toLong()))
                    }
                }
                owned.add(tensor)
                feed[name] = tensor
            }
            var smallProbeValues: FloatArray? = null
            // Dynamic output metadata can mean full-sequence OR last-token-only logits. Learn it
            // without copying a full tensor. Warm-up usually resolves this on a tiny prompt.
            if ((logitsInfo.shape.size == 3 && logitsInfo.shape[1] < 0 && outputIsSingleRow == null) ||
                (logitsInfo.shape.last() < 1 && learnedVocabulary == null)) {
                result = session.run(feed, (outputs.values + "logits").toSet())
                val probe = result.get("logits").orElse(null) as? OnnxTensor ?: error("Missing logits tensor")
                val probeShape = validatedLogitsShape(probe, tokens.size)
                learnedVocabulary = probeShape.last()
                if ((tokens.size > 1 || singleTokenInput) && probeShape.size == 3) outputIsSingleRow = probeShape[1] == 1L
                if (probeShape.size == 2 || probeShape[1] == 1L) {
                    val buffer = requireNotNull(probe.floatBuffer)
                    smallProbeValues = FloatArray(probeShape.last().toInt()).also(buffer::get)
                } else {
                    result.close()
                    result = null
                }
            }
            if (result == null) {
                val outputShape = logitsInfo.shape.copyOf()
                require(outputShape.size in 2..3 && outputShape[0] in longArrayOf(-1, 1)) { "Unsupported ONNX logits layout" }
                outputShape[0] = 1
                if (outputShape.size == 3 && outputShape[1] < 0) outputShape[1] = if (outputIsSingleRow == true) 1 else tokens.size.toLong()
                if (outputShape.last() < 0) outputShape[outputShape.lastIndex] = learnedVocabulary ?: config.optInt("vocab_size").toLong()
                require(outputShape.last() in 1..1_000_000) { "Cannot determine ONNX logits vocabulary size" }
                val pinnedLogits = logitsBuffer.tensor(environment, outputShape, logitsInfo.type).also(owned::add)
                result = session.run(feed, outputs.values.toSet(), mapOf("logits" to pinnedLogits))
            }
            val logits = result.get("logits").orElse(null) as? OnnxTensor ?: error("Missing logits tensor")
            val shape = validatedLogitsShape(logits, tokens.size)
            val values = if (copyLogits) smallProbeValues ?: logitsBuffer.lastRow(shape, logits.info.type) else FloatArray(0)
            if (caches.isNotEmpty()) {
                outputs.values.forEach { require(result.get(it).orElse(null) is OnnxTensor) { "Missing cache tensor $it" } }
                retained?.close()
                retained = result
                result = null
                pastLength = total
            }
            return values
        } finally { result?.close(); owned.forEach { it.close() } }
    }

    private fun validatedLogitsShape(logits: OnnxTensor, sequence: Int): LongArray = logits.info.shape.also { shape ->
        require(logits.info.type in floatTypes && ((shape.size == 3 && shape[0] == 1L && shape[1] in longArrayOf(1, sequence.toLong())) ||
            (shape.size == 2 && shape[0] == 1L))) { "Expected floating logits [1, sequence, vocabulary] or [1, vocabulary]" }
        require(shape.last() in 1..1_000_000) { "Unsupported logits vocabulary" }
    }

    private fun integerTensor(type: OnnxJavaType, values: LongArray, shape: LongArray): OnnxTensor = when (type) {
        OnnxJavaType.INT64 -> OnnxTensor.createTensor(environment, LongBuffer.wrap(values), shape)
        OnnxJavaType.INT32 -> OnnxTensor.createTensor(environment, IntBuffer.wrap(IntArray(values.size) { values[it].toInt() }), shape)
        else -> error("Unsupported token tensor type $type; expected INT32 or INT64")
    }

    private fun emptyCache(info: TensorInfo): OnnxTensor {
        val shape = info.shape.copyOf()
        val sequenceAxis = shape.size - 2
        val heads = config.optInt("num_key_value_heads", config.optInt("num_attention_heads", config.optInt("n_head", 0)))
        val attentionHeads = config.optInt("num_attention_heads", config.optInt("n_head", 0))
        val hidden = config.optInt("hidden_size", config.optInt("n_embd", 0))
        for (axis in shape.indices) shape[axis] = when {
            axis == sequenceAxis -> 0
            shape[axis] > 0 -> shape[axis]
            axis == shape.lastIndex -> config.optInt("head_dim", if (attentionHeads > 0) hidden / attentionHeads else 0).toLong()
            axis == sequenceAxis - 1 -> heads.toLong()
            shape.size == 5 && axis == 0 -> 2
            else -> 1
        }
        require(shape.withIndex().all { (axis, size) -> axis == sequenceAxis || size > 0 }) { "Cannot infer KV-cache dimensions from model config" }
        return when (info.type) {
            OnnxJavaType.FLOAT -> OnnxTensor.createTensor(environment, FloatBuffer.allocate(0), shape)
            else -> OnnxTensor.createTensor(environment, ShortBuffer.allocate(0), shape, info.type)
        }
    }

    override fun close() = reset()
    companion object {
        private val integerTypes = setOf(OnnxJavaType.INT64, OnnxJavaType.INT32)
        private val floatTypes = setOf(OnnxJavaType.FLOAT, OnnxJavaType.FLOAT16, OnnxJavaType.BFLOAT16)
    }
}
