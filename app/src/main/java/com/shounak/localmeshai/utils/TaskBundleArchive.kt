package com.shounak.localmeshai.utils

import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * MediaPipe's AlignZipFile writes zero padding BEFORE local headers, including the first.
 * Android ZipFile rejects that preamble. Read aligned STORED bundles by their absolute
 * central-directory offsets instead, without copying weights or scanning tensor data.
 * Ordinary ZIPs retain the platform reader (including support for compressed entries).
 */
internal class TaskBundleArchive(private val file: File) : Closeable {
    private data class StoredEntry(val entry: ZipEntry, val dataOffset: Long)
    private val platform: ZipFile?
    private val stored: Map<String, StoredEntry>

    init {
        val padded = RandomAccessFile(file, "r").use { it.length() >= 4 && it.read() == 0 }
        if (padded) {
            platform = null
            stored = readAlignedDirectory()
        } else {
            platform = ZipFile(file)
            stored = emptyMap()
        }
    }

    fun entries() = platform?.entries() ?: Collections.enumeration(stored.values.map { it.entry })

    fun getInputStream(entry: ZipEntry): InputStream {
        platform?.let { return it.getInputStream(entry) }
        val item = requireNotNull(stored[entry.name]) { "Unknown task entry" }
        val input = RandomAccessFile(file, "r")
        try { input.seek(item.dataOffset) } catch (e: Exception) { input.close(); throw e }
        return object : InputStream() {
            private var remaining = item.entry.size
            override fun read(): Int {
                if (remaining == 0L) return -1
                val value = input.read()
                check(value >= 0) { "Truncated task entry" }
                remaining--
                return value
            }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
                if (length == 0) return 0
                if (remaining == 0L) return -1
                val count = input.read(bytes, offset, minOf(length.toLong(), remaining).toInt())
                check(count > 0) { "Truncated task entry" }
                remaining -= count
                return count
            }
            override fun skip(count: Long): Long {
                val skipped = count.coerceIn(0, remaining)
                input.seek(input.filePointer + skipped)
                remaining -= skipped
                return skipped
            }
            override fun close() = input.close()
        }
    }

    override fun close() { platform?.close() }

    private fun readAlignedDirectory(): Map<String, StoredEntry> = RandomAccessFile(file, "r").use { input ->
        val length = input.length()
        fun bytes(offset: Long, size: Int): ByteBuffer {
            require(size >= 0 && offset >= 0 && offset <= length - size) { "Task ZIP offset out of bounds" }
            val data = ByteArray(size)
            input.seek(offset)
            input.readFully(data)
            return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        }
        fun ByteBuffer.u16(offset: Int) = getShort(offset).toInt() and 0xffff
        fun ByteBuffer.u32(offset: Int) = getInt(offset).toLong() and 0xffffffffL
        val tailSize = minOf(length, 65557L).toInt()
        val tail = bytes(length - tailSize, tailSize)
        val endIndex = (tailSize - 22 downTo 0).firstOrNull {
            tail.getInt(it) == 0x06054b50 && it + 22 + tail.u16(it + 20) == tailSize
        } ?: error("Missing task ZIP end record")
        val end = length - tailSize + endIndex
        require(tail.u16(endIndex + 4) == 0 && tail.u16(endIndex + 6) == 0) { "Multi-disk task ZIP unsupported" }
        var count = tail.u16(endIndex + 10).toLong()
        require(tail.u16(endIndex + 8).toLong() == count)
        var size = tail.u32(endIndex + 12)
        var start = tail.u32(endIndex + 16)
        var directoryLimit = end
        if (count == 65535L || size == 0xffffffffL || start == 0xffffffffL) {
            val locator = bytes(end - 20, 20)
            require(locator.getInt(0) == 0x07064b50 && locator.getInt(4) == 0 && locator.getInt(16) == 1)
            val zip64Offset = locator.getLong(8)
            val record = bytes(zip64Offset, 56)
            require(record.getInt(0) == 0x06064b50 && record.getLong(4) >= 44)
            require(record.getLong(4) <= end - 20 - zip64Offset - 12)
            require(record.getInt(16) == 0 && record.getInt(20) == 0)
            count = record.getLong(32)
            require(record.getLong(24) == count)
            size = record.getLong(40)
            start = record.getLong(48)
            directoryLimit = zip64Offset
        }
        require(count in 1..4096 && size >= 0 && start >= 0 && start <= directoryLimit - size) { "Invalid task ZIP directory" }
        val result = linkedMapOf<String, StoredEntry>()
        var position = start
        var firstLocal = Long.MAX_VALUE
        repeat(count.toInt()) {
            require(position <= start + size - 46)
            val header = bytes(position, 46)
            require(header.getInt(0) == 0x02014b50) { "Invalid task ZIP directory entry" }
            val flags = header.u16(8)
            require(flags and 1 == 0 && header.u16(10) == ZipEntry.STORED && flags and 8 == 0) { "Aligned task entries must be unencrypted and stored" }
            val nameSize = header.u16(28)
            val extraSize = header.u16(30)
            val recordSize = 46L + nameSize + extraSize + header.u16(32)
            require(recordSize <= start + size - position && header.u16(34) == 0)
            val nameBytes = bytes(position + 46, nameSize).array()
            val name = String(nameBytes, if (flags and 0x800 != 0) Charsets.UTF_8 else charset("CP437"))
            require(name.isNotEmpty() && !result.containsKey(name)) { "Duplicate or empty task entry" }
            var compressed = header.u32(20)
            var uncompressed = header.u32(24)
            var local = header.u32(42)
            if (compressed == 0xffffffffL || uncompressed == 0xffffffffL || local == 0xffffffffL) {
                val extra = bytes(position + 46 + nameSize, extraSize)
                var p = 0
                var found = false
                while (p <= extraSize - 4) {
                    val tag = extra.u16(p)
                    val fieldSize = extra.u16(p + 2)
                    require(fieldSize <= extraSize - p - 4)
                    if (tag == 1) {
                        var cursor = p + 4
                        fun value(): Long {
                            require(cursor <= p + 4 + fieldSize - 8)
                            return extra.getLong(cursor).also { cursor += 8; require(it >= 0) }
                        }
                        if (uncompressed == 0xffffffffL) uncompressed = value()
                        if (compressed == 0xffffffffL) compressed = value()
                        if (local == 0xffffffffL) local = value()
                        found = true
                        break
                    }
                    p += 4 + fieldSize
                }
                require(found) { "Missing ZIP64 task sizes" }
            }
            require(compressed == uncompressed && local >= 1 && local <= start - 30)
            firstLocal = minOf(firstLocal, local)
            val localHeader = bytes(local, 30)
            require(localHeader.getInt(0) == 0x04034b50 && localHeader.u16(6) == flags && localHeader.u16(8) == ZipEntry.STORED)
            require(localHeader.u16(26) == nameSize && bytes(local + 30, nameSize).array().contentEquals(nameBytes))
            val dataOffset = local + 30 + nameSize + localHeader.u16(28)
            require(dataOffset <= start && compressed <= start - dataOffset) { "Truncated task entry" }
            val entry = ZipEntry(name).apply { method = ZipEntry.STORED; this.size = uncompressed; compressedSize = compressed; crc = header.u32(16) }
            result[name] = StoredEntry(entry, dataOffset)
            position += recordSize
        }
        require(position == start + size) { "Invalid task ZIP directory length" }
        // Only zero alignment padding is accepted, never arbitrary executable preambles.
        require(firstLocal <= 4096 && bytes(0, firstLocal.toInt()).array().all { it == 0.toByte() }) { "Invalid task alignment prefix" }
        result
    }
}
