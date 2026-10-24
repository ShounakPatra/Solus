package com.shounak.localmeshai.utils

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Tiny forward-built schema tables; tensor payloads are intentionally absent. */
internal object TfliteFixture {
    /** Representative of the probed Qwen seq128 export's decode/cache signature. */
    fun cachedText(): ByteArray = Builder("TFL3").build { b ->
        b.table(mapOf(0 to { 3 }, 2 to { b.tables(listOf { b.table(mapOf(
            0 to { b.tables(listOf(
                { b.tensor("decode_tokens", intArrayOf(1,1), 2) },
                { b.tensor("decode_input_pos", intArrayOf(1), 2) },
                { b.tensor("decode_kv_cache_k_0", intArrayOf(1,2,1280,64), 0) },
                { b.tensor("logits", intArrayOf(1,1,151936), 0) })) },
            1 to { b.vector(intArrayOf(0,1,2)) }, 2 to { b.vector(intArrayOf(3)) }), setOf(0,1,2)) }) }), setOf(2))
    }
    fun ctc(): ByteArray = Builder("TFL3").build { b ->
        b.table(mapOf(0 to { 3 }, 2 to { b.tables(listOf { b.table(mapOf(
            0 to { b.tables(listOf({ b.tensor("input_values", intArrayOf(1,-1), 0) },
                { b.tensor("logits", intArrayOf(1,1,32), 0) })) },
            1 to { b.vector(intArrayOf(0)) }, 2 to { b.vector(intArrayOf(1)) }), setOf(0,1,2)) }) }), setOf(2))
    }
    fun text(custom: Boolean = false, inputType: Int = 2): ByteArray = Builder("TFL3").build { b ->
        b.table(mapOf(0 to { 3 }, 1 to { b.tables(listOf { b.table(if (custom) mapOf(1 to { b.string("LlmCustom") }) else emptyMap(), setOf(1)) }) },
            2 to { b.tables(listOf { b.table(mapOf(
                0 to { b.tables(listOf(
                    { b.tensor("input_ids", intArrayOf(1, -1), inputType) },
                    { b.tensor("logits", intArrayOf(1, 1, 32), 0) })) },
                1 to { b.vector(intArrayOf(0)) }, 2 to { b.vector(intArrayOf(1)) }), setOf(0,1,2)) }) }), setOf(1,2))
    }

    fun classifier(): ByteArray {
        val metadata = Builder("M001").build { m ->
            m.table(mapOf(3 to { m.tables(listOf { m.table(mapOf(
                2 to { m.tables(listOf { m.table(mapOf(
                    3 to { m.table(mapOf(0 to { 2 }, 1 to { m.table(mapOf(0 to { 1 })) }), setOf(1)) },
                    4 to { m.tables(listOf { m.table(mapOf(0 to { 1 }, 1 to {
                        m.table(mapOf(0 to { m.vector(intArrayOf(127.5f.toBits())) },
                            1 to { m.vector(intArrayOf(127.5f.toBits())) }), setOf(0,1))
                    }), setOf(1)) }) }), setOf(3,4)) }) },
                3 to { m.tables(listOf { m.table(emptyMap()) }) }), setOf(2,3)) }) }), setOf(3))
        }
        return Builder("TFL3").build { b -> b.table(mapOf(
            0 to { 3 }, 2 to { b.tables(listOf { b.table(mapOf(
                0 to { b.tables(listOf({ b.tensor("image", intArrayOf(1,224,224,3), 0) },
                    { b.tensor("scores", intArrayOf(1,1000), 0) })) },
                1 to { b.vector(intArrayOf(0)) }, 2 to { b.vector(intArrayOf(1)) }), setOf(0,1,2)) }) },
            4 to { b.tables(listOf { b.table(mapOf(0 to { b.blob(metadata) }), setOf(0)) }) },
            6 to { b.tables(listOf { b.table(mapOf(0 to { b.string("TFLITE_METADATA") }, 1 to { 0 }), setOf(0)) }) }
        ), setOf(2,4,6)) }
    }

    private class Builder(identifier: String) {
        private val bytes = ByteBuffer.allocate(16384).order(ByteOrder.LITTLE_ENDIAN)
        init { bytes.putInt(0); bytes.put(identifier.toByteArray()) }
        fun build(root: (Builder) -> Int): ByteArray {
            bytes.putInt(0, root(this)); return bytes.array().copyOf(bytes.position())
        }
        fun table(fields: Map<Int, () -> Int>, refs: Set<Int> = emptySet()): Int {
            val count = (fields.keys.maxOrNull() ?: -1) + 1
            val vt = bytes.position(); bytes.putShort((4 + 2 * count).toShort()); bytes.putShort((4 + count * 4).toShort())
            repeat(count) { bytes.putShort(if (it in fields) (4 + 4 * it).toShort() else 0) }
            val table = bytes.position(); bytes.putInt(table - vt); repeat(count) { bytes.putInt(0) }
            fields.forEach { (index, value) ->
                val address = table + 4 + 4 * index; val data = value()
                bytes.putInt(address, if (index in refs) data - address else data)
            }
            return table
        }
        fun vector(values: IntArray): Int = bytes.position().also { bytes.putInt(values.size); values.forEach(bytes::putInt) }
        fun blob(values: ByteArray): Int = bytes.position().also { bytes.putInt(values.size); bytes.put(values) }
        fun string(value: String): Int = blob(value.toByteArray()).also { bytes.put(0) }
        fun tables(values: List<() -> Int>): Int {
            val vector = bytes.position(); bytes.putInt(values.size); repeat(values.size) { bytes.putInt(0) }
            values.forEachIndexed { index, value -> val address = vector + 4 + index * 4; bytes.putInt(address, value() - address) }
            return vector
        }
        fun tensor(name: String, shape: IntArray, type: Int): Int = table(mapOf(
            0 to { vector(shape.map { if (it < 0) 1 else it }.toIntArray()) }, 1 to { type },
            3 to { string(name) }, 7 to { vector(shape) }), setOf(0,3,7))
    }
}
