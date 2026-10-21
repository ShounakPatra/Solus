package com.shounak.localmeshai.utils

import com.shounak.localmeshai.models.ThinkingCapability
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
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
