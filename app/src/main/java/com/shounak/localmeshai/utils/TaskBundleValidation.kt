package com.shounak.localmeshai.utils

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry

/** Bounded protobuf reader shared by task metadata and SentencePiece component validation. */
internal class ProtoReader(private val input: InputStream, private var remaining: Long) {
    data class Field(val number: Int, val wire: Int)
    private fun byte(): Int {
        require(remaining > 0) { "Truncated protobuf" }
        val value = input.read()
        require(value >= 0) { "Truncated protobuf" }
        remaining--
        return value
    }
    fun varint(): Long {
        var value = 0L
        for (shift in 0..63 step 7) {
            val b = byte()
            require(shift < 63 || b <= 1) { "Protobuf integer overflow" }
            value = value or ((b and 127).toLong() shl shift)
            if (b and 128 == 0) return value
        }
        error("Invalid protobuf integer")
    }
    fun next(): Field? {
        if (remaining == 0L) return null
        val tag = varint()
        require(tag > 0L && tag ushr 3 in 1..536870911L) { "Invalid protobuf field" }
        val wire = (tag and 7).toInt()
        require(wire in setOf(0, 1, 2, 5)) { "Unsupported protobuf wire type" }
        return Field((tag ushr 3).toInt(), wire)
    }
    fun bytes(limit: Int): ByteArray {
        val length = varint()
        require(length in 0..minOf(remaining, limit.toLong())) { "Invalid protobuf length" }
        val result = ByteArray(length.toInt())
        var position = 0
        while (position < result.size) {
            val read = input.read(result, position, result.size - position)
            require(read > 0) { "Truncated protobuf payload" }
            position += read
        }
        remaining -= length
        return result
    }
    private fun skip(length: Long) {
        require(length in 0..remaining) { "Invalid protobuf length" }
        var left = length
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped > 0) { remaining -= skipped; left -= skipped }
            else { byte(); left-- }
        }
    }
    fun skip(field: Field) = when (field.wire) {
        0 -> { varint(); Unit }
        1 -> skip(8)
        2 -> skip(varint())
        5 -> skip(4)
        else -> error("Invalid protobuf field")
    }
}

internal object TaskBundleValidation {
    fun metadata(zip: TaskBundleArchive, entry: ZipEntry): ByteArray {
        require(entry.size in 1..131072) { "Task metadata is empty or exceeds 128 KiB" }
        return zip.getInputStream(entry).use { stream ->
            val result = ByteArray(entry.size.toInt())
            var position = 0
            while (position < result.size) {
                val read = stream.read(result, position, result.size - position)
                require(read > 0) { "Truncated task metadata" }
                position += read
            }
            result
        }
    }

    // Inspect FlatBuffer table/vector boundaries without mapping multi-GB tensor data into Java memory.
    fun graph(zip: TaskBundleArchive, entry: ZipEntry): Boolean = runCatching {
        fun read(offset: Long, count: Int): ByteBuffer {
            require(offset >= 0 && offset <= entry.size - count)
            val data = ByteArray(count)
            zip.getInputStream(entry).use { stream ->
                var left = offset
                while (left > 0) {
                    val skipped = stream.skip(left)
                    if (skipped > 0) left -= skipped else { require(stream.read() >= 0); left-- }
                }
                var p = 0
                while (p < count) { val n = stream.read(data, p, count - p); require(n > 0); p += n }
            }
            return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        }
        fun uint(offset: Long): Long = read(offset, 4).int.toLong() and 0xffffffffL
        fun field(table: Long, index: Int): Long {
            val vtable = table - read(table, 4).int
            val header = read(vtable, 4)
            val size = header.short.toInt() and 65535
            val objectSize = header.short.toInt() and 65535
            require(size >= 4 && objectSize >= 4 && table <= entry.size - objectSize)
            val slot = 4 + index * 2
            if (slot + 2 > size) return 0
            val offset = read(vtable + slot, 2).short.toInt() and 65535
            if (offset == 0) return 0
            require(offset >= 4 && offset + 4 <= objectSize)
            return table + offset
        }
        fun vector(table: Long, index: Int, elementSize: Int): Pair<Long, Long> {
            val slot = field(table, index)
            require(slot > 0)
            val start = slot + uint(slot)
            val count = uint(start)
            require(count > 0 && count <= (entry.size - start - 4) / elementSize)
            return start + 4 to count
        }
        val header = read(0, 8)
        require((4..7).map { header[it].toInt().toChar() }.joinToString("") == "TFL3")
        val model = uint(0)
        val version = field(model, 0)
        require(version > 0 && uint(version) == 3L)
        val (subgraphs, _) = vector(model, 2, 4)
        val subgraph = subgraphs + uint(subgraphs)
        vector(subgraph, 0, 4) // tensors
        vector(subgraph, 3, 4) // operators: an encoder alone cannot substitute for the LLM role
        vector(model, 4, 4) // buffers
        true
    }.getOrDefault(false)

    fun tokenizer(zip: TaskBundleArchive, entry: ZipEntry): Boolean = runCatching {
        require(entry.size in 1..(64L * 1024 * 1024))
        zip.getInputStream(entry).buffered().use { stream ->
            if (entry.name.endsWith(".json", ignoreCase = true)) {
                require(entry.size <= 16L * 1024 * 1024)
                val json = org.json.JSONObject(String(stream.readBytes(), Charsets.UTF_8))
                val vocab = json.getJSONObject("model").get("vocab")
                return@use when (vocab) {
                    is org.json.JSONObject -> vocab.length() > 0
                    is org.json.JSONArray -> vocab.length() > 0
                    else -> false
                }
            }
            val reader = ProtoReader(stream, entry.size)
            var pieces = 0
            while (true) {
                val field = reader.next() ?: break
                if (field.number == 1 && field.wire == 2) {
                    val piece = reader.bytes(1024 * 1024)
                    val nested = ProtoReader(piece.inputStream(), piece.size.toLong())
                    var text = false
                    while (true) {
                        val part = nested.next() ?: break
                        if (part.number == 1 && part.wire == 2) text = nested.bytes(1024 * 1024).isNotEmpty()
                        else nested.skip(part)
                    }
                    require(text) { "Invalid SentencePiece entry" }
                    pieces++
                } else reader.skip(field)
            }
            pieces > 0
        }
    }.getOrDefault(false)
}
