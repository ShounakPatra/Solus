package com.shounak.localmeshai.ai

import org.tensorflow.lite.DataType
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Separates export-layout validation from the Android interpreter for regression tests. */
internal class TfliteTextPlan(
    val inputs: List<Tensor>, outputs: List<Tensor>, architecture: String, vocabulary: Int
) {
    data class Tensor(val name: String, val shape: IntArray, val signature: IntArray, val type: DataType)
    val names = inputs.map { tensor ->
        val name = PortableTextEngine.tokenInputName(tensor.name)
        if (inputs.size == 1 && architecture == "gpt2" && name == "input_1") "input_ids" else name
    }
    val fixedSequence: Int?
    val logitsIndex: Int

    init {
        require("input_ids" in names && names.distinct().size == names.size &&
            names.all { it in setOf("input_ids", "attention_mask", "position_ids") }) {
            "Unsupported TFLite text inputs: ${inputs.joinToString { it.name }}. Expected token IDs with optional attention/position inputs; required cache inputs need a different adapter."
        }
        inputs.forEach {
            require(it.type in setOf(DataType.INT32, DataType.INT64) && it.shape.size == 2 &&
                it.signature.size == 2 && it.signature[0] in intArrayOf(1, -1) &&
                (it.signature[1] == -1 || it.signature[1] > 0)) {
                "TFLite token inputs must be integer [1, sequence] tensors"
            }
        }
        val fixed = inputs.map { it.signature[1] }.filter { it > 0 }.distinct()
        require(fixed.size <= 1) { "TFLite token inputs have conflicting sequence lengths" }
        fixedSequence = fixed.singleOrNull()
        require(fixedSequence == null || fixedSequence > 1) { "A one-token TFLite export requires a cache-aware adapter" }
        val candidates = outputs.indices.filter { index ->
            val tensor = outputs[index]
            tensor.type == DataType.FLOAT32 && tensor.shape.size in 2..3 && tensor.shape[0] == 1 &&
                tensor.shape.last() in 1..1_000_000 && (vocabulary <= 0 || tensor.shape.last() == vocabulary)
        }
        require(candidates.size == 1) { "TFLite must expose one identifiable float logits tensor; cache outputs may be present" }
        logitsIndex = candidates.single()
        if (fixedSequence != null) {
            val shape = outputs[logitsIndex].shape
            require(shape.size == 3 && shape[1] == fixedSequence) {
                "Fixed-sequence TFLite exports must expose per-position logits to avoid sampling from padding"
            }
        }
    }

    fun sequenceLength(tokens: Int): Int {
        require(tokens > 0 && (fixedSequence == null || tokens <= fixedSequence)) { "Prompt exceeds TFLite export context (${fixedSequence ?: "dynamic"} tokens)" }
        return fixedSequence ?: tokens
    }

    fun input(index: Int, tokens: List<Int>, padToken: Int): ByteBuffer {
        val sequence = sequenceLength(tokens.size)
        val type = inputs[index].type
        return ByteBuffer.allocateDirect(sequence * if (type == DataType.INT64) 8 else 4).order(ByteOrder.nativeOrder()).apply {
            repeat(sequence) { position ->
                val value = when (names[index]) {
                    "input_ids" -> tokens.getOrElse(position) { padToken }
                    "attention_mask" -> if (position < tokens.size) 1 else 0
                    "position_ids" -> position
                    else -> error("Unsupported token input")
                }
                if (type == DataType.INT64) putLong(value.toLong()) else putInt(value)
            }
            rewind()
        }
    }

    fun logitsOffset(shape: IntArray, tokens: Int): Int {
        require(shape.size in 2..3 && shape[0] == 1 && shape.last() in 1..1_000_000) { "Invalid TFLite logits shape" }
        val row = if (shape.size == 3 && shape[1] != 1) {
            require(tokens in 1..shape[1]) { "Logits do not cover the token sequence" }
            tokens - 1
        } else 0
        return row * shape.last() * 4
    }
}
