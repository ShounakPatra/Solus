package com.shounak.localmeshai.utils

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A tiny schema-correct FLOAT32 ADD graph; parser tests do not claim to run an LLM. */
internal object TaskBundleFixtures {
    fun graph(): ByteArray {
        val data = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        var position = 8
        fun align() { position = (position + 3) and -4 }
        fun table(slots: Int): Int {
            align()
            val vtable = position
            data.putShort(vtable, (4 + slots * 2).toShort())
            data.putShort(vtable + 2, (4 + slots * 4).toShort())
            position += 4 + slots * 2
            align()
            val table = position
            data.putInt(table, table - vtable)
            repeat(slots) { data.putShort(vtable + 4 + it * 2, (4 + it * 4).toShort()) }
            position += 4 + slots * 4
            return table
        }
        fun vector(values: IntArray): Int {
            align()
            val start = position
            data.putInt(start, values.size)
            values.forEachIndexed { index, value -> data.putInt(start + 4 + index * 4, value) }
            position += 4 + values.size * 4
            return start
        }
        fun link(slot: Int, destination: Int) { data.putInt(slot, destination - slot) }
        fun slot(table: Int, field: Int) = table + 4 + field * 4
        val model = table(5)
        data.putInt(0, model)
        "TFL3".forEachIndexed { i, ch -> data.put(4 + i, ch.code.toByte()) }
        data.putInt(slot(model, 0), 3)
        val opcodes = vector(intArrayOf(0)); link(slot(model, 1), opcodes)
        link(opcodes + 4, table(0)) // ADD is builtin opcode zero.
        val subgraphs = vector(intArrayOf(0)); link(slot(model, 2), subgraphs)
        val subgraph = table(4); link(subgraphs + 4, subgraph)
        val tensors = vector(intArrayOf(0, 0, 0)); link(slot(subgraph, 0), tensors)
        repeat(3) { index ->
            val tensor = table(3); link(tensors + 4 + index * 4, tensor)
            link(slot(tensor, 0), vector(intArrayOf(1)))
            // FLOAT32 and buffer zero are scalar defaults.
        }
        link(slot(subgraph, 1), vector(intArrayOf(0, 1)))
        link(slot(subgraph, 2), vector(intArrayOf(2)))
        val operators = vector(intArrayOf(0)); link(slot(subgraph, 3), operators)
        val operator = table(5); link(operators + 4, operator)
        link(slot(operator, 1), vector(intArrayOf(0, 1)))
        link(slot(operator, 2), vector(intArrayOf(2)))
        data.put(slot(operator, 3), 11) // BuiltinOptions.AddOptions
        link(slot(operator, 4), table(0))
        val buffers = vector(intArrayOf(0)); link(slot(model, 4), buffers)
        link(buffers + 4, table(0))
        // Description is optional; remove its offset slot rather than encoding a null offset.
        val modelVtable = model - data.getInt(model)
        data.putShort(modelVtable + 4 + 3 * 2, 0)
        return data.array().copyOf(position)
    }

    fun tokenizer(): ByteArray = byteArrayOf(10, 9, 10, 5) + "<unk>".toByteArray() + byteArrayOf(24, 2)
    fun metadata(): ByteArray = byteArrayOf(10, 5, 8, 1, 16, 0x80.toByte(), 0x20)
}
