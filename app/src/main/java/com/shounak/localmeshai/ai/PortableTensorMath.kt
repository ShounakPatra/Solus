package com.shounak.localmeshai.ai

import kotlin.math.roundToInt

internal object PortableTensorMath {
    fun quantize(value: Float, scale: Float, zero: Int, unsigned: Boolean): Int {
        require(value.isFinite() && scale.isFinite() && scale > 0) { "Invalid quantized tensor scale/value" }
        return (value / scale + zero).roundToInt().coerceIn(if (unsigned) 0 else -128, if (unsigned) 255 else 127)
    }
    fun dequantize(value: Int, scale: Float, zero: Int): Float {
        require(scale.isFinite() && scale > 0) { "Invalid quantized tensor scale" }
        return (value-zero)*scale
    }
    fun ctc(logits: FloatArray, shape: LongArray, vocabulary: Map<Int, String>, blank: Int, delimiter: String): String {
        require(shape.size == 3 && shape[0] == 1L && shape[1] > 0 && shape[2] in 1..1_000_000)
        val classes = shape[2].toInt()
        require(shape[1] * classes == logits.size.toLong()) { "Invalid CTC logits shape" }
        var previous = -1
        return buildString {
            for (frame in 0 until shape[1].toInt()) {
                val start = frame*classes
                val token = (0 until classes).filter { logits[start+it].isFinite() }.maxByOrNull { logits[start+it] }
                    ?: error("CTC produced no finite scores")
                if (token != blank && token != previous) append(vocabulary[token] ?: error("Missing CTC vocabulary token $token"))
                previous = token
            }
        }.replace(delimiter, " ").trim()
    }
}
