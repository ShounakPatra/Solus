package com.shounak.localmeshai.utils

import com.shounak.localmeshai.models.ThinkingCapability
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TaskBundleValidationTest {
    @get:Rule val folder = TemporaryFolder()
    private fun task(metadata: ByteArray? = null, tokenizer: ByteArray = TaskBundleFixtures.tokenizer(),
                     graph: ByteArray = TaskBundleFixtures.graph(), role: String = "TF_LITE_PREFILL_DECODE"): File {
        return folder.newFile("${System.nanoTime()}.task").also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                fun entry(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
                entry(role, graph)
                entry("TOKENIZER_MODEL", tokenizer)
                if (metadata != null) entry("METADATA", metadata)
            }
        }
    }
    private fun message(tag: ByteArray, body: ByteArray): ByteArray = tag + byteArrayOf(body.size.toByte()) + body

    // Mirrors MediaPipe AlignZipFile: zero padding before EACH local header, absolute
    // central-directory offsets, and STORED payloads aligned to four bytes. Entry.extra
    // alone does not reproduce Android's "invalid LFH signature 0" failure.
    private fun alignedTask(graph: ByteArray = TaskBundleFixtures.graph(), zip64: Boolean = false,
                            graphName: String = "TF_LITE_PREFILL_DECODE"): File {
        val file = folder.newFile("${System.nanoTime()}.task")
        RandomAccessFile(file, "rw").use { output ->
            fun short(value: Int) = output.writeShort(Integer.reverseBytes(value) ushr 16)
            fun int(value: Long) = output.writeInt(Integer.reverseBytes(value.toInt()))
            fun long(value: Long) = output.writeLong(java.lang.Long.reverseBytes(value))
            data class Item(val name: ByteArray, val data: ByteArray, val offset: Long, val crc: Long)
            val items = listOf(graphName to graph, "TOKENIZER_MODEL" to TaskBundleFixtures.tokenizer(), "METADATA" to TaskBundleFixtures.metadata()).map { (name, data) ->
                val encoded = name.toByteArray()
                repeat((4 - (output.filePointer + 30 + encoded.size) % 4).toInt()) { output.write(0) }
                val offset = output.filePointer
                val crc = CRC32().apply { update(data) }.value
                int(0x04034b50); short(20); short(0); short(0); int(0); int(crc)
                int(data.size.toLong()); int(data.size.toLong()); short(encoded.size); short(0)
                output.write(encoded); output.write(data)
                Item(encoded, data, offset, crc)
            }
            val directory = output.filePointer
            items.forEach { item ->
                int(0x02014b50); short(20); short(if (zip64) 45 else 20); short(0); short(0); int(0); int(item.crc)
                int(if (zip64) 0xffffffffL else item.data.size.toLong()); int(if (zip64) 0xffffffffL else item.data.size.toLong())
                short(item.name.size); short(if (zip64) 28 else 0); short(0); short(0); short(0); int(0); int(if (zip64) 0xffffffffL else item.offset)
                output.write(item.name)
                if (zip64) { short(1); short(24); long(item.data.size.toLong()); long(item.data.size.toLong()); long(item.offset) }
            }
            val directorySize = output.filePointer - directory
            if (zip64) {
                val record = output.filePointer
                int(0x06064b50); long(44); short(45); short(45); int(0); int(0); long(items.size.toLong()); long(items.size.toLong()); long(directorySize); long(directory)
                int(0x07064b50); int(0); long(record); int(1)
            }
            int(0x06054b50); short(0); short(0); short(if (zip64) 65535 else items.size); short(if (zip64) 65535 else items.size)
            int(if (zip64) 0xffffffffL else directorySize); int(if (zip64) 0xffffffffL else directory); short(0)
        }
        return file
    }

    @Test fun officialAlignmentBeforeHeadersIsAcceptedWithoutChangingModelBytes() {
        for (zip64 in listOf(false, true)) {
            val file = alignedTask(zip64 = zip64)
            val original = file.readBytes()
            assertArrayEquals(byteArrayOf(0, 0, 0, 0, 0x50, 0x4b, 3, 4), original.copyOf(8))
            assertEquals(com.shounak.localmeshai.models.ModelFormat.MediaPipeTask, NonGgufBundleInspector.detectFormat(file))
            val result = NonGgufBundleInspector.inspectMediaPipeTask(file)
            assertTrue(result.errorMessage, result.isValid)
            assertEquals(4096, result.capabilities.contextLength)
            TaskBundleArchive(file).use { archive ->
                val graph = archive.entries().nextElement()
                assertArrayEquals(TaskBundleFixtures.graph(), archive.getInputStream(graph).use { it.readBytes() })
            }
            assertArrayEquals(original, file.readBytes())
        }
    }

    @Test fun paddedBundlesStillRejectInvalidGraphsOffsetsAndTruncation() {
        assertFalse(NonGgufBundleInspector.inspectMediaPipeTask(alignedTask(graph = ByteArray(64))).isValid)
        val original = alignedTask().readBytes()
        val end = original.size - 22
        val directory = ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN).getInt(end + 16)
        for (mutation in listOf<(ByteArray) -> ByteArray>(
            { it.copyOf(it.size - 10) },
            { it.apply { this[1] = 1 } },
            { it.apply { ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putInt(directory + 42, size - 2) } },
            { it.apply { ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putInt(directory + 24, Int.MAX_VALUE) } },
            { it.apply { this[4] = 0 } }
        )) {
            val file = folder.newFile("${System.nanoTime()}.task").apply { writeBytes(mutation(original.copyOf())) }
            assertFalse("Malformed aligned ZIP must fail", NonGgufBundleInspector.inspectMediaPipeTask(file).isValid)
        }
    }

    @Test fun allFourAlignmentLengthsAndBoundedSeeksWork() {
        for (prefix in listOf("", "a/", "ab/", "abc/")) {
            val file = alignedTask(graphName = prefix + "TF_LITE_PREFILL_DECODE")
            assertTrue(NonGgufBundleInspector.inspectMediaPipeTask(file).isValid)
            TaskBundleArchive(file).use { archive ->
                val entry = archive.entries().nextElement()
                archive.getInputStream(entry).use { stream ->
                    assertEquals(0L, stream.skip(-1))
                    assertEquals(entry.size - 1, stream.skip(entry.size - 1))
                    assertEquals(TaskBundleFixtures.graph().last().toInt() and 255, stream.read())
                    assertEquals(-1, stream.read())
                    assertEquals(0L, stream.skip(100))
                    assertEquals(0, stream.read(ByteArray(0)))
                }
            }
        }
    }

    @Test fun contextUsesSequenceLengthRatherThanBatchSize() {
        val result = NonGgufBundleInspector.inspectMediaPipeTask(task(TaskBundleFixtures.metadata()))
        assertTrue(result.errorMessage, result.isValid)
        assertEquals(4096, result.capabilities.contextLength)
    }

    @Test fun legacyAndRoleSpecificNestedPromptTemplatesAreDecoded() {
        val template = message(byteArrayOf(26), "<think>".toByteArray())
        for (metadata in listOf(message(byteArrayOf(66), template), message(byteArrayOf(0x8a.toByte(), 1), message(byteArrayOf(18), template)))) {
            val result = NonGgufBundleInspector.inspectMediaPipeTask(task(metadata))
            assertTrue(result.errorMessage, result.isValid)
            assertEquals(ThinkingCapability.ModelControlled, result.capabilities.thinking)
        }
    }

    @Test fun malformedMetadataCannotBecomeAValidBundle() {
        for (metadata in listOf(byteArrayOf(10, 10, 1), byteArrayOf(0), byteArrayOf(10) + ByteArray(9) { 0xff.toByte() } + byteArrayOf(0x7f))) {
            assertFalse(NonGgufBundleInspector.inspectMediaPipeTask(task(metadata)).isValid)
        }
    }

    @Test fun encoderAlonePlaceholderTokenizerAndMagicOnlyGraphAreRejected() {
        assertFalse(NonGgufBundleInspector.inspectMediaPipeTask(task(role = "vision_encoder.tflite")).isValid)
        assertFalse(NonGgufBundleInspector.inspectMediaPipeTask(task(tokenizer = "vocab".toByteArray())).isValid)
        val magicOnly = ByteArray(32).apply { "TFL3".forEachIndexed { i, c -> this[4 + i] = c.code.toByte() } }
        assertFalse(NonGgufBundleInspector.inspectMediaPipeTask(task(graph = magicOnly)).isValid)
    }

    @Test fun optionalMetadataMayBeAbsentAndDoesNotInventVisionOrThinkingToggle() {
        val result = NonGgufBundleInspector.inspectMediaPipeTask(task())
        assertTrue(result.errorMessage, result.isValid)
        assertFalse(result.capabilities.supportsVision)
        assertEquals(ThinkingCapability.Unknown, result.capabilities.thinking)
        val literal = message(byteArrayOf(66), message(byteArrayOf(18), "enable_thinking".toByteArray()))
        assertEquals(ThinkingCapability.Unknown, NonGgufBundleInspector.inspectMediaPipeTask(task(literal)).capabilities.thinking)
    }
}
