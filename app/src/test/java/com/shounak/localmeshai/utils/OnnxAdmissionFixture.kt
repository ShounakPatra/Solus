package com.shounak.localmeshai.utils

internal object OnnxAdmissionFixture {
    private fun number(value: Int): ByteArray {
        var left = value
        return java.io.ByteArrayOutputStream().apply {
            do { val byte = left and 127; left = left ushr 7; write(byte or if (left > 0) 128 else 0) } while (left > 0)
        }.toByteArray()
    }
    private fun field(id: Int, bytes: ByteArray) = number(id * 8 + 2) + number(bytes.size) + bytes
    private fun scalar(id: Int, value: Int) = number(id * 8) + number(value)
    private fun tensor(name: String, fixed: Int) = field(1, name.toByteArray()) + field(2,
        field(1, scalar(1, 7) + field(2, field(1, scalar(1, 1)) + field(1,
            if (fixed < 0) field(2, "sequence".toByteArray()) else scalar(1, fixed)))))
    fun model(fixed: Int): ByteArray {
        val graph = java.io.ByteArrayOutputStream()
        // Real protobuf order, across more than 32 64-KiB probe pages.
        repeat(48) { graph.write(field(5, field(9, ByteArray(65536)))) }
        graph.write(field(11, tensor("input_ids", fixed)))
        graph.write(field(12, tensor("logits", -1)))
        return scalar(1, 8) + field(7, graph.toByteArray())
    }
}
