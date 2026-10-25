package com.shounak.localmeshai.utils

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocumentExtractionSafetyTest {

    @Test
    fun officeEntitiesAreDecodedOnceAndPreserveSupplementaryCharacters() {
        val zip = createZip(mapOf("word/document.xml" to
            "<w:document><w:p><w:t>&lt;example&gt; &amp;lt; &#x1F600; &#128512; &#xD800;</w:t></w:p></w:document>".toByteArray()))
        val result = DocumentTextExtractor.extractBytes(zip, "entities.docx")
        assertEquals("<example> &lt; 😀 😀 &#xD800;", result)
    }

    @Test
    fun rtfUnicodeEscapesPreserveTextAndSkipFallbackCharacters() {
        val rtf = """{\rtf1\ansi\uc1 \u2476?\u2494?\u2434?\u2482?\u2494? \u-10179?\u-8704?\par {\uc0\u2344}\u2350?\tab \\ \{literal\}}"""
        val result = DocumentTextExtractor.extractBytes(rtf.toByteArray(), "unicode.rtf")
        assertEquals("বাংলা 😀\nनम\t\\ {literal}", result)
    }

    @Test
    fun nonZipArchivesReportUnsupportedWithoutOpeningTheStream() {
        val result = ArchiveTextExtractor.extractFromStream(
            inputStreamSupplier = { error("Unsupported formats must not reach the ZIP reader") },
            archiveName = "documents.7z"
        )
        assertTrue(result.startsWith("Unsupported archive format: .7z"))
    }

    private fun createZip(entries: Map<String, ByteArray>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            for ((name, data) in entries) {
                val entry = ZipEntry(name)
                zos.putNextEntry(entry)
                zos.write(data)
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    private fun createZipWithDirs(dirs: List<String>, files: Map<String, ByteArray> = emptyMap()): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            for (dir in dirs) {
                val entry = ZipEntry(if (dir.endsWith("/")) dir else "$dir/")
                zos.putNextEntry(entry)
                zos.closeEntry()
            }
            for ((name, data) in files) {
                val entry = ZipEntry(name)
                zos.putNextEntry(entry)
                zos.write(data)
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    @Test
    fun testArchiveWithPathTraversal_RejectedAndNotExtracted() {
        val zipBytes = createZip(
            mapOf(
                "../../etc/passwd" to "root:x:0:0:root:/root:/bin/bash".toByteArray(),
                "..\\windows\\system32\\cmd.exe" to "malicious command payload".toByteArray(),
                "docs/safe.txt" to "Legitimate document content that should be extracted.".toByteArray()
            )
        )

        val result = ArchiveTextExtractor.extract(
            context = null,
            bytes = zipBytes,
            archiveName = "traversal_attack.zip",
            budget = DocumentExtractionBudget.DEFAULT
        )

        assertTrue(result.contains("path traversal rejected"))
        assertFalse(result.contains("root:x:0:0"))
        assertFalse(result.contains("malicious command payload"))
        assertTrue(result.contains("Legitimate document content"))
    }

    @Test
    fun testNestedArchive_RejectedAndNotRecursivelyExtracted() {
        val innerZipBytes = createZip(
            mapOf("inner_secret.txt" to "Super confidential nested text".toByteArray())
        )

        val outerZipBytes = createZip(
            mapOf(
                "nested.zip" to innerZipBytes,
                "outer.txt" to "Top level text that is completely safe.".toByteArray()
            )
        )

        val result = ArchiveTextExtractor.extract(
            context = null,
            bytes = outerZipBytes,
            archiveName = "outer_bundle.zip",
            budget = DocumentExtractionBudget.DEFAULT
        )

        assertTrue(result.contains("nested archive rejected"))
        assertFalse(result.contains("Super confidential nested text"))
        assertTrue(result.contains("Top level text that is completely safe."))
    }

    @Test
    fun testDecompressionBombExceedingSizeLimit_AbortsGracefully() {
        // Budget with strict 2 KB total uncompressed bytes limit and 1 KB entry limit
        val strictBudget = DocumentExtractionBudget(
            maxTotalUncompressedBytes = 2048L,
            maxEntryBytes = 1024L,
            maxExtractedChars = 4000
        )

        val largePayload = "Repetitive decompression bomb payload content. ".repeat(200).toByteArray()
        val zipBytes = createZip(
            mapOf(
                "bomb.txt" to largePayload
            )
        )

        val result = ArchiveTextExtractor.extract(
            context = null,
            bytes = zipBytes,
            archiveName = "bomb.zip",
            budget = strictBudget
        )

        assertTrue(
            "Expected decompression bomb or safety size limit notice",
            result.contains("Decompression limit exceeded") ||
                result.contains("stay within safety limits") ||
                result.contains("archive safety limits")
        )
    }

    @Test
    fun testFileExceedingMaxInputBytes_RejectedBeforeReading() {
        val budget = DocumentExtractionBudget(
            maxInputBytes = 500L,
            limitDescription = "500 B"
        )

        val oversizedBytes = ByteArray(1200) { 'A'.code.toByte() }
        val result = DocumentTextExtractor.extractBytes(
            bytes = oversizedBytes,
            fileName = "large_file.txt",
            budget = budget
        )

        assertTrue(result.contains("The attached file is 1.2 KB, but the selected model can read files up to 500 B"))
    }

    @Test
    fun testTextTruncatedAtCharacterLimitWithIndicator() {
        val budget = DocumentExtractionBudget(
            maxExtractedChars = 150,
            limitDescription = "150 characters"
        )

        val originalText = "A".repeat(500)
        val result = DocumentTextExtractor.extractBytes(
            bytes = originalText.toByteArray(Charsets.UTF_8),
            fileName = "notes.txt",
            budget = budget
        )

        assertTrue(result.startsWith("A".repeat(150)))
        assertTrue(result.contains("[Truncated at 150 characters"))
    }

    @Test
    fun testAttachedDocumentContext_PropagatesTruncationNotice() {
        val budget = DocumentExtractionBudget(
            maxExtractedChars = 100,
            limitDescription = "100 characters"
        )

        val longText = "Important specification details for the system architecture. ".repeat(10)
        val extracted = DocumentTextExtractor.extractBytes(
            bytes = longText.toByteArray(Charsets.UTF_8),
            fileName = "spec.txt",
            budget = budget
        )

        assertTrue(extracted.contains("[Truncated at 100 characters"))

        val selected = AttachedDocumentContext.select(
            text = extracted,
            query = "What is the system architecture?",
            budget = 250
        )

        assertTrue("Context selection should propagate truncation indicator", selected.contains("[Truncated at 100 characters"))
    }

    @Test
    fun testCompressedOfficeBomb_AbortsGracefully() {
        val strictBudget = DocumentExtractionBudget(
            maxTotalUncompressedBytes = 1024L,
            maxEntryBytes = 512L,
            maxExtractedChars = 2000
        )

        val bombXml = ("<w:document><w:body><w:p><w:r><w:t>" +
            "Large repeated XML content that easily exceeds limits. ".repeat(100) +
            "</w:t></w:r></w:p></w:body></w:document>").toByteArray(Charsets.UTF_8)

        val docxBytes = createZip(
            mapOf("word/document.xml" to bombXml)
        )

        val result = DocumentTextExtractor.extractBytes(
            bytes = docxBytes,
            fileName = "office_bomb.docx",
            budget = strictBudget
        )

        assertTrue(
            "Expected decompression bomb notice for office bomb",
            result.contains("Could not extract office_bomb.docx") &&
                (result.contains("Decompression limit exceeded") || result.contains("safety limit"))
        )
        assertTrue(AttachedDocumentContext.isExtractionFailure(result))
    }

    @Test
    fun testUnknownEntrySizes_BoundedDecompression() {
        val strictBudget = DocumentExtractionBudget(
            maxTotalUncompressedBytes = 1500L,
            maxEntryBytes = 1000L,
            maxExtractedChars = 3000
        )

        // ZipEntry created with default unknown size (-1)
        val payload = "Streaming data chunk with unknown header size. ".repeat(100).toByteArray()
        val zipBytes = createZip(
            mapOf("unknown_size_entry.txt" to payload)
        )

        val result = ArchiveTextExtractor.extract(
            context = null,
            bytes = zipBytes,
            archiveName = "unknown_size.zip",
            budget = strictBudget
        )

        assertTrue(
            result.contains("Decompression limit exceeded") ||
                result.contains("safety limits")
        )
    }

    @Test
    fun testLargeSkippedBinary_BoundedDrainAborts() {
        val strictBudget = DocumentExtractionBudget(
            maxTotalUncompressedBytes = 2048L,
            maxEntryBytes = 1024L,
            maxExtractedChars = 4000
        )

        val binaryPayload = ByteArray(10_000) { 0x7F }
        val zipBytes = createZip(
            mapOf("malicious.exe" to binaryPayload)
        )

        val result = ArchiveTextExtractor.extract(
            context = null,
            bytes = zipBytes,
            archiveName = "binary_bomb.zip",
            budget = strictBudget
        )

        assertTrue(
            "Large skipped binary must be boundedly drained and trigger limit stop",
            result.contains("Decompression limit exceeded") ||
                result.contains("safety limits") ||
                result.contains("binary or unsupported")
        )
    }

    @Test
    fun testExcessiveDirectories_StopsAtLimit() {
        val strictBudget = DocumentExtractionBudget(
            maxEntries = 15
        )

        val dirs = (1..50).map { "folder_$it" }
        val zipBytes = createZipWithDirs(dirs)

        val result = ArchiveTextExtractor.extract(
            context = null,
            bytes = zipBytes,
            archiveName = "many_dirs.zip",
            budget = strictBudget
        )

        assertTrue(
            "Must stop when entry limit is reached",
            result.contains("Stopped after 15") ||
                result.contains("Archive entry limit exceeded") ||
                result.contains("Entries scanned: 16") ||
                result.contains("Entries scanned: 15")
        )
    }

    @Test
    fun testNestedOfficeDocument_ExtractedWithinSharedBudget() {
        val innerXml = ("<w:document><w:body><w:p><w:r><w:t>" +
            "Special confidential project blueprint inside nested docx." +
            "</w:t></w:r></w:p></w:body></w:document>").toByteArray(Charsets.UTF_8)

        val docxBytes = createZip(
            mapOf("word/document.xml" to innerXml)
        )

        val outerZipBytes = createZip(
            mapOf("spec.docx" to docxBytes)
        )

        val result = ArchiveTextExtractor.extract(
            context = null,
            bytes = outerZipBytes,
            archiveName = "container.zip",
            budget = DocumentExtractionBudget.DEFAULT
        )

        assertTrue(result.contains("Special confidential project blueprint inside nested docx."))
    }

    @Test
    fun testAggregateBudgetExhaustionAcrossNestedOfficeFile() {
        val strictBudget = DocumentExtractionBudget(
            maxTotalUncompressedBytes = 1500L,
            maxEntryBytes = 2000L,
            maxExtractedChars = 4000
        )

        val innerXml = ("<w:document><w:body><w:p><w:r><w:t>" +
            "A".repeat(2500) +
            "</w:t></w:r></w:p></w:body></w:document>").toByteArray(Charsets.UTF_8)

        val docxBytes = createZip(
            mapOf("word/document.xml" to innerXml)
        )

        val outerZipBytes = createZip(
            mapOf("nested_bomb.docx" to docxBytes)
        )

        val result = ArchiveTextExtractor.extract(
            context = null,
            bytes = outerZipBytes,
            archiveName = "outer_with_docx_bomb.zip",
            budget = strictBudget
        )

        assertTrue(
            result.contains("Decompression limit exceeded") ||
                result.contains("safety limits") ||
                result.contains("Could not extract")
        )
    }

    @Test(expected = CancellationException::class)
    fun testExtractionCancellation_PromptlyAborts() {
        val zipBytes = createZip(
            mapOf("file1.txt" to "content 1".toByteArray(), "file2.txt" to "content 2".toByteArray())
        )

        ArchiveTextExtractor.extract(
            context = null,
            bytes = zipBytes,
            archiveName = "cancelled.zip",
            budget = DocumentExtractionBudget.DEFAULT,
            isCancelled = { true }
        )
    }
}
