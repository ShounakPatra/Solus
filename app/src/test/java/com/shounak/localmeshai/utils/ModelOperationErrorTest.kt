package com.shounak.localmeshai.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class ModelOperationErrorTest {

    @Test
    fun testSniffValidGguf() {
        val ggufBytes = byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte(), 0, 0, 0, 0)
        val result = FileHeaderSniffer.sniffHeader(ggufBytes, ggufBytes.size.toLong(), "model.gguf")
        assertTrue("Expected ValidGguf but was $result", result is FileHeaderSniffer.SniffResult.ValidGguf)
    }

    @Test
    fun testSniffEmptyFile() {
        val result = FileHeaderSniffer.sniffHeader(ByteArray(0), 0L, "empty.gguf")
        assertTrue("Expected EmptyOrDamaged for 0 bytes", result is FileHeaderSniffer.SniffResult.EmptyOrDamaged)
    }

    @Test
    fun testSniffXmlFile() {
        val xmlText = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<map>\n    <string name=\"pref\">value</string>\n</map>"
        val bytes = xmlText.toByteArray(Charsets.UTF_8)
        val result = FileHeaderSniffer.sniffHeader(bytes, bytes.size.toLong(), "com.liskovsoft.sharedutils.prefs.GlobalPreferences.xml")
        assertTrue("Expected NonModel for XML file", result is FileHeaderSniffer.SniffResult.NonModel)
        assertEquals("an XML file", (result as FileHeaderSniffer.SniffResult.NonModel).fileTypeDescription)
    }

    @Test
    fun testSniffRenamedXmlToGguf() {
        val xmlText = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<map>\n    <string name=\"pref\">value</string>\n</map>"
        val bytes = xmlText.toByteArray(Charsets.UTF_8)
        val result = FileHeaderSniffer.sniffHeader(bytes, bytes.size.toLong(), "GlobalPreferences.gguf")
        assertTrue("Expected NonModel for renamed XML ending in .gguf", result is FileHeaderSniffer.SniffResult.NonModel)
        assertEquals("an XML file", (result as FileHeaderSniffer.SniffResult.NonModel).fileTypeDescription)
    }

    @Test
    fun testSniffPdfFile() {
        val pdfBytes = "%PDF-1.4\n1 0 obj\n<<\n>>\nendobj".toByteArray(Charsets.US_ASCII)
        val result = FileHeaderSniffer.sniffHeader(pdfBytes, pdfBytes.size.toLong(), "document.pdf")
        assertTrue("Expected NonModel for PDF", result is FileHeaderSniffer.SniffResult.NonModel)
        assertEquals("a PDF document", (result as FileHeaderSniffer.SniffResult.NonModel).fileTypeDescription)
    }

    @Test
    fun testSniffPngFile() {
        val pngBytes = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
        val result = FileHeaderSniffer.sniffHeader(pngBytes, pngBytes.size.toLong(), "image.png")
        assertTrue("Expected NonModel for PNG", result is FileHeaderSniffer.SniffResult.NonModel)
        assertEquals("a PNG image", (result as FileHeaderSniffer.SniffResult.NonModel).fileTypeDescription)
    }

    @Test
    fun testSniffZipFileRequiresFullTaskValidation() {
        val zipBytes = byteArrayOf(0x50.toByte(), 0x4B.toByte(), 0x03.toByte(), 0x04.toByte(), 0x14, 0x00)
        val result = FileHeaderSniffer.sniffHeader(zipBytes, zipBytes.size.toLong(), "archive.zip")
        assertTrue("ZIP headers are only candidates until full container validation", result is FileHeaderSniffer.SniffResult.ValidMediaPipeTask)
        val archive = java.io.File.createTempFile("not-a-task", ".zip")
        try {
            java.util.zip.ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("notes.txt"))
                zip.write("not a model".toByteArray())
                zip.closeEntry()
            }
            assertFalse(NonGgufBundleInspector.inspectMediaPipeTask(archive).isValid)
        } finally {
            archive.delete()
        }
    }

    @Test
    fun testSniffJsonFile() {
        val jsonText = "{\n  \"model_type\": \"llama\",\n  \"vocab_size\": 32000\n}"
        val bytes = jsonText.toByteArray(Charsets.UTF_8)
        val result = FileHeaderSniffer.sniffHeader(bytes, bytes.size.toLong(), "config.json")
        assertTrue("Expected NonModel for JSON", result is FileHeaderSniffer.SniffResult.NonModel)
        assertEquals("a JSON file", (result as FileHeaderSniffer.SniffResult.NonModel).fileTypeDescription)
    }

    @Test
    fun testSniffDamagedGgufFile() {
        // Binary content that does not have GGUF magic, named .gguf
        val binaryCorrupted = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x00, 0x10, 0x20, 0x30)
        val result = FileHeaderSniffer.sniffHeader(binaryCorrupted, binaryCorrupted.size.toLong(), "corrupt.gguf")
        assertTrue("Expected EmptyOrDamaged for corrupted binary .gguf", result is FileHeaderSniffer.SniffResult.EmptyOrDamaged)
    }

    @Test
    fun testUserTitleAndActionText() {
        val nonModelErr = ModelOperationError(
            reason = ModelErrorReason.NonModelFile,
            stage = ModelOperationStage.SelectionValidation,
            affectedFilenames = listOf("prefs.xml"),
            detectedFileType = "an XML file"
        )
        assertEquals("Choose a supported model", nonModelErr.userTitle)
        assertEquals("Choose model files", nonModelErr.actionText)
        assertTrue(nonModelErr.userMessage.contains("“prefs.xml” is an XML file, not a supported model file"))

        val damagedErr = ModelOperationError(
            reason = ModelErrorReason.EmptyOrDamagedHeader,
            stage = ModelOperationStage.Inspection,
            affectedFilenames = listOf("corrupted.gguf")
        )
        assertEquals("This model file can’t be read", damagedErr.userTitle)
        assertEquals("Choose another file", damagedErr.actionText)

        val unsuppErr = ModelOperationError(
            reason = ModelErrorReason.UnsupportedFormat,
            stage = ModelOperationStage.Inspection,
            architectureOrFormat = "mpt"
        )
        assertEquals("This model isn’t supported yet", unsuppErr.userTitle)
        assertEquals("Choose another model", unsuppErr.actionText)
        assertTrue(unsuppErr.userMessage.contains("“mpt”"))

        val shardsErr = ModelOperationError(
            reason = ModelErrorReason.MissingShards,
            stage = ModelOperationStage.SelectionValidation,
            foundCount = 2,
            requiredCount = 4,
            missingParts = listOf(2, 3)
        )
        assertEquals("Some model parts are missing", shardsErr.userTitle)
        assertEquals("Select all model parts", shardsErr.actionText)
        assertTrue(shardsErr.userMessage.contains("found 2 of 4 parts"))
        assertTrue(shardsErr.userMessage.contains("Missing parts: 3, 4"))

        val unrelatedErr = ModelOperationError(
            reason = ModelErrorReason.UnrelatedModelsSelected,
            stage = ModelOperationStage.SelectionValidation
        )
        assertEquals("Select one model at a time", unrelatedErr.userTitle)
        assertEquals("Choose model files", unrelatedErr.actionText)

        val projErr = ModelOperationError(
            reason = ModelErrorReason.ProjectorWithoutMain,
            stage = ModelOperationStage.SelectionValidation
        )
        assertEquals("Main model file missing", projErr.userTitle)
        assertEquals("Choose model files", projErr.actionText)

        val regErr = ModelOperationError(
            reason = ModelErrorReason.RegistrationFailed,
            stage = ModelOperationStage.Registration
        )
        assertEquals("Couldn’t save this model", regErr.userTitle)
        assertEquals("Choose files again", regErr.actionText)

        val remErr = ModelOperationError(
            reason = ModelErrorReason.RemovalFailed,
            stage = ModelOperationStage.Removal,
            diagnosticDetails = "Disk I/O error"
        )
        assertEquals("Couldn’t remove this model", remErr.userTitle)
        assertEquals("Retry", remErr.actionText)
        assertTrue(remErr.userMessage.contains("Disk I/O error"))
    }

    @Test
    fun testSanitizeDiagnosticDetails() {
        val raw = """
            Authentication failed with Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.xyz
            Downloading from https://huggingface.co using hf_12345678901234567890abcdef
            Configuration: password=mysecretpassword123;
            java.lang.IllegalStateException: Corrupt header
                at com.shounak.localmeshai.utils.LocalModelImporter.import(LocalModelImporter.kt:42)
                at com.shounak.localmeshai.Main.main(Main.java:10)
            Caused by: java.io.IOException: Stream closed
        """.trimIndent()

        val sanitized = ModelOperationError.sanitizeDiagnosticDetails(raw)
        assertNotNull(sanitized)
        assertTrue("Bearer auth should be redacted", sanitized!!.contains("[REDACTED_AUTH]"))
        assertTrue("HF token should be redacted", sanitized.contains("[REDACTED_HF_TOKEN]"))
        assertTrue("Password should be redacted", sanitized.contains("password=[REDACTED]"))
        org.junit.Assert.assertFalse("Stack traces with 'at ' should be scrubbed", sanitized.contains("at com.shounak"))
        org.junit.Assert.assertFalse("Caused by should be scrubbed", sanitized.contains("Caused by:"))
        org.junit.Assert.assertFalse(".kt: line references should be scrubbed", sanitized.contains(".kt:"))
        org.junit.Assert.assertFalse(".java: line references should be scrubbed", sanitized.contains(".java:"))
    }
}
