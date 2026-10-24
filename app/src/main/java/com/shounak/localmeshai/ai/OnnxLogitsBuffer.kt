package com.shounak.localmeshai.ai

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** ORT writes logits into native storage; only the final vocabulary row enters the Java heap. */
internal class OnnxLogitsBuffer : AutoCloseable {
    private var buffer: ByteBuffer? = null
    private val androidRuntime = System.getProperty("java.vm.name").orEmpty().contains("Dalvik", true)

    fun tensor(environment: OrtEnvironment, shape: LongArray, type: OnnxJavaType): OnnxTensor {
        val elements = shape.fold(1L) { size, dimension -> Math.multiplyExact(size, dimension) }
        val bytes = Math.multiplyExact(elements, if (type == OnnxJavaType.FLOAT) 4L else 2L)
        require(bytes in 1..(512L * 1024 * 1024)) {
            "This ONNX export needs too much logits memory. Shorten the conversation or use a KV-cached/last-token-logits export."
        }
        if ((buffer?.capacity() ?: 0) < bytes) {
            close()
            // Android's allocateDirect can itself allocate a large non-moving Java array.
            buffer = (if (androidRuntime) LlamaCppEngine.nativeAllocateTensorBuffer(bytes)
                else ByteBuffer.allocateDirect(bytes.toInt())).order(ByteOrder.nativeOrder())
        }
        val view = requireNotNull(buffer).duplicate().order(ByteOrder.nativeOrder()).apply { clear(); limit(bytes.toInt()) }
        return if (type == OnnxJavaType.FLOAT) OnnxTensor.createTensor(environment, view.asFloatBuffer(), shape)
            else OnnxTensor.createTensor(environment, view.asShortBuffer(), shape, type)
    }

    fun lastRow(shape: LongArray, type: OnnxJavaType): FloatArray {
        val vocabulary = shape.last().toInt()
        val count = shape.fold(1L) { size, dimension -> size * dimension }.toInt()
        val view = requireNotNull(buffer).duplicate().order(ByteOrder.nativeOrder())
        return if (type == OnnxJavaType.FLOAT) {
            val floats = view.asFloatBuffer().apply { position(count - vocabulary) }
            FloatArray(vocabulary).also(floats::get)
        } else {
            val shorts = view.asShortBuffer().apply { position(count - vocabulary) }
            FloatArray(vocabulary) {
                val bits = shorts.get().toInt() and 0xffff
                if (type == OnnxJavaType.BFLOAT16) Float.fromBits(bits shl 16) else halfToFloat(bits)
            }
        }
    }

    override fun close() {
        buffer?.let { if (androidRuntime) LlamaCppEngine.nativeFreeTensorBuffer(it) }
        buffer = null
    }

    private fun halfToFloat(bits: Int): Float {
        val sign = (bits and 0x8000) shl 16
        val exponent = (bits ushr 10) and 31
        val mantissa = bits and 1023
        return when (exponent) {
            0 -> (if (bits and 0x8000 != 0) -1f else 1f) * mantissa * (1f / 16777216f)
            31 -> Float.fromBits(sign or 0x7f800000 or (mantissa shl 13))
            else -> Float.fromBits(sign or ((exponent + 112) shl 23) or (mantissa shl 13))
        }
    }
}
