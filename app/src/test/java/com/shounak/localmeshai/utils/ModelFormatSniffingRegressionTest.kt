package com.shounak.localmeshai.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelFormatSniffingRegressionTest {

    private val ggufMagicBytes = byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte(), 0x03, 0, 0, 0)

    @Test
    fun validGgufNamedBinIsRecognizedAsValidGguf() {
        val result = FileHeaderSniffer.sniffHeader(ggufMagicBytes, 1024L, "qwen_model.bin")
        assertTrue("Valid GGUF named .bin must be recognized as ValidGguf", result is FileHeaderSniffer.SniffResult.ValidGguf)
    }

    @Test
    fun validGgufExtensionlessIsRecognizedAsValidGguf() {
        val result = FileHeaderSniffer.sniffHeader(ggufMagicBytes, 1024L, "my_custom_model")
        assertTrue("Valid GGUF without extension must be recognized as ValidGguf", result is FileHeaderSniffer.SniffResult.ValidGguf)
    }

    @Test
    fun validGgufNamedTaskIsRecognizedAsValidGguf() {
        val result = FileHeaderSniffer.sniffHeader(ggufMagicBytes, 1024L, "model.task")
        assertTrue("Valid GGUF header must be authoritative even if named .task", result is FileHeaderSniffer.SniffResult.ValidGguf)
    }

    @Test
    fun validTaskFileReturnsValidMediaPipeTask() {
        val fakeTaskHeader = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x08, 0x00) // ZIP header commonly used by .task
        val result = FileHeaderSniffer.sniffHeader(fakeTaskHeader, 2048L, "gemma_classifier.task")
        assertTrue("Valid .task ZIP file must yield ValidMediaPipeTask", result is FileHeaderSniffer.SniffResult.ValidMediaPipeTask)
    }

    @Test
    fun validLitertlmFileWithLitertlmMagicReturnsValidLiteRtLm() {
        val magic = "LITERTLM".toByteArray(Charsets.US_ASCII)
        val result = FileHeaderSniffer.sniffHeader(magic, 4096L, "deepseek_r1.litertlm")
        assertTrue("Valid LITERTLM magic .litertlm file must yield ValidLiteRtLm", result is FileHeaderSniffer.SniffResult.ValidLiteRtLm)
    }

    @Test
    fun rawTfl3FileNamedLitertlmReturnsNonModel() {
        val fakeLitertHeader = byteArrayOf(0, 0, 0, 0, 'T'.code.toByte(), 'F'.code.toByte(), 'L'.code.toByte(), '3'.code.toByte())
        val result = FileHeaderSniffer.sniffHeader(fakeLitertHeader, 4096L, "deepseek_r1.litertlm")
        assertTrue("Raw TFL3 .litertlm file must yield NonModel", result is FileHeaderSniffer.SniffResult.NonModel)
    }

    @Test
    fun zipArchiveNamedLitertlmRequiresDeepTaskValidation() {
        val fakeZip = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0, 0, 0, 0)
        val result = FileHeaderSniffer.sniffHeader(fakeZip, 4096L, "fake_model.litertlm")
        assertTrue("ZIP candidate must undergo deep task validation regardless of its filename", result is FileHeaderSniffer.SniffResult.ValidMediaPipeTask)
    }

    @Test
    fun corruptZipTaskReturnsNonModel() {
        val zipWithWord = "PK\u0003\u0004word/document.xml".toByteArray(Charsets.ISO_8859_1)
        val result = FileHeaderSniffer.sniffHeader(zipWithWord, 2048L, "docx_renamed.task")
        assertTrue("Docx renamed to .task must return NonModel", result is FileHeaderSniffer.SniffResult.NonModel)
    }

    @Test
    fun modelOperationErrorForUnsupportedStorageFormatHasClearActionAndMessage() {
        val err = ModelOperationError(
            reason = ModelErrorReason.UnsupportedStorageFormat,
            stage = ModelOperationStage.Inspection,
            architectureOrFormat = "pytorch",
            affectedFilenames = listOf("model.pt")
        )
        assertEquals("Unsupported model format", err.userTitle)
        assertEquals("Choose model files", err.actionText)
        assertEquals(
            "This model format (pytorch) is not supported on this device.",
            err.userMessage
        )
    }

    @Test
    fun damagedGgufFilePreservesEmptyOrDamagedResult() {
        val corruptBytes = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)
        val result = FileHeaderSniffer.sniffHeader(corruptBytes, 1024L, "damaged_model.gguf")
        assertTrue("Corrupted .gguf must return EmptyOrDamaged", result is FileHeaderSniffer.SniffResult.EmptyOrDamaged)
    }

    @Test
    fun xmlFileNamedGgufReturnsNonModel() {
        val xmlText = "<?xml version=\"1.0\" encoding=\"utf-8\"?><resources></resources>"
        val bytes = xmlText.toByteArray(Charsets.UTF_8)
        val result = FileHeaderSniffer.sniffHeader(bytes, bytes.size.toLong(), "renamed.gguf")
        assertTrue("Renamed XML must return NonModel", result is FileHeaderSniffer.SniffResult.NonModel)
    }
}
