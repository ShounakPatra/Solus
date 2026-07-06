package com.shounak.localmeshai.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipInputStream

object DocumentTextExtractor {
    private const val DEFAULT_MAX_EXTRACTED_CHARS = 64_000
    private const val DEFAULT_MAX_INPUT_BYTES = 8L * 1024L * 1024L
    private const val MAX_ZIP_ENTRIES = 120
    private const val MAX_ZIP_LISTED_ENTRIES = 80
    private const val MAX_ZIP_SKIPPED_ENTRIES = 24
    private const val MAX_ZIP_ENTRY_BYTES = 12L * 1024L * 1024L
    private const val MAX_ZIP_TOTAL_UNCOMPRESSED_BYTES = 96L * 1024L * 1024L
    private const val MAX_ZIP_TEXT_CHARS_PER_ENTRY = 16_000

    fun extract(
        context: Context,
        uri: Uri,
        fileName: String,
        mimeType: String = "",
        maxExtractedChars: Int = DEFAULT_MAX_EXTRACTED_CHARS,
        limitDescription: String = "$DEFAULT_MAX_EXTRACTED_CHARS characters",
        maxInputBytes: Long = DEFAULT_MAX_INPUT_BYTES
    ): String {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        val safeInputLimit = maxInputBytes.coerceAtLeast(1L)
        val sizeLimitLabel = formatBytes(safeInputLimit)
        val reportedSize = queryFileSize(context, uri)
        if (reportedSize != null && reportedSize > safeInputLimit) {
            return "The attached file is ${formatBytes(reportedSize)}, but the selected model can read files up to $sizeLimitLabel based on its context window."
        }
        val readResult = readBytesWithinLimit(context, uri, safeInputLimit)
            ?: return "Could not open the attached file stream."
        if (readResult.exceededLimit) {
            return "The attached file is larger than $sizeLimitLabel, which is the selected model's current context-based file limit."
        }
        val bytes = readResult.bytes

        val extracted = when {
            extension == "pdf" || mimeType.contains("pdf", ignoreCase = true) ->
                extractPdf(context, bytes)
            extension == "docx" ->
                extractDocx(bytes)
            extension == "pptx" ->
                extractOfficeOpenXml(bytes, "ppt/slides/", listOf("a:t"))
            extension == "xlsx" ->
                extractOfficeOpenXml(bytes, "xl/", listOf("t", "v"))
            extension == "odt" || extension == "ods" || extension == "odp" ->
                extractZipXmlEntries(bytes, listOf("content.xml"))
            extension == "zip" || mimeType.contains("zip", ignoreCase = true) ->
                extractZipArchive(
                    context = context,
                    bytes = bytes,
                    archiveName = fileName,
                    maxExtractedChars = maxExtractedChars,
                    maxInputBytes = safeInputLimit
                )
            extension == "html" || extension == "htm" || mimeType.contains("html", ignoreCase = true) ->
                extractHtml(bytes.decodeText())
            extension == "rtf" ->
                extractRtf(bytes.decodeText())
            isPlainTextExtension(extension) || mimeType.startsWith("text/", ignoreCase = true) ||
                mimeType.contains("json", ignoreCase = true) || mimeType.contains("xml", ignoreCase = true) ->
                bytes.decodeText()
            else -> fallbackExtract(bytes, extension)
        }

        val normalized = extracted.normalizeDocumentWhitespace()
        val safeLimit = maxExtractedChars.coerceAtLeast(1)
        val clipped = if (normalized.length > safeLimit) {
            normalized.take(safeLimit).trimEnd() +
                "\n\n[The document was longer than this model's input budget, so only the first $limitDescription were sent.]"
        } else {
            normalized
        }
        return clipped.ifBlank {
                "No readable text could be extracted from $fileName. If this is a scanned image-only document, OCR is required before the model can analyze it."
        }
    }

    private fun queryFileSize(context: Context, uri: Uri): Long? {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index != -1 && !cursor.isNull(index)) {
                    return cursor.getLong(index).takeIf { it >= 0L }
                }
            }
        }
        return runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.length.takeIf { it >= 0L }
            }
        }.getOrNull()
    }

    private data class ReadBytesResult(
        val bytes: ByteArray,
        val exceededLimit: Boolean
    )

    private fun readBytesWithinLimit(context: Context, uri: Uri, maxInputBytes: Long): ReadBytesResult? {
        return context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                total += read
                if (total > maxInputBytes) {
                    return@use ReadBytesResult(ByteArray(0), exceededLimit = true)
                }
                output.write(buffer, 0, read)
            }
            ReadBytesResult(output.toByteArray(), exceededLimit = false)
        }
    }

    private fun extractPdf(context: Context, bytes: ByteArray): String {
        return runCatching {
            PDFBoxResourceLoader.init(context)
            PDDocument.load(ByteArrayInputStream(bytes)).use { document ->
                PDFTextStripper().apply {
                    setSortByPosition(true)
                }.getText(document)
            }
        }.getOrElse { error ->
            "Could not extract PDF text: ${error.message ?: error::class.java.simpleName}. If this PDF is scanned or image-only, OCR is required."
        }
    }

    private fun extractDocx(bytes: ByteArray): String {
        val entries = listOf(
            "word/document.xml",
            "word/header",
            "word/footer",
            "word/footnotes.xml",
            "word/endnotes.xml",
            "word/comments.xml"
        )
        return extractZipXmlEntries(bytes, entries)
    }

    private fun extractOfficeOpenXml(
        bytes: ByteArray,
        entryPrefix: String,
        textTags: List<String>
    ): String {
        val builder = StringBuilder()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                if (!entry.isDirectory && entry.name.startsWith(entryPrefix) && entry.name.endsWith(".xml")) {
                    val xml = zip.readBytes().toString(Charsets.UTF_8)
                    builder.append(extractXmlText(xml, textTags)).append("\n")
                }
            }
        }
        return builder.toString()
    }

    private fun extractZipXmlEntries(bytes: ByteArray, matchingNames: List<String>): String {
        val builder = StringBuilder()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                if (!entry.isDirectory && matchingNames.any { entry.name == it || entry.name.startsWith(it) }) {
                    val xml = zip.readBytes().toString(Charsets.UTF_8)
                    builder.append(extractXmlText(xml, listOf("w:t", "a:t", "text:p", "text:span", "t"))).append("\n")
                }
            }
        }
        return builder.toString()
    }

    private fun extractZipArchive(
        context: Context,
        bytes: ByteArray,
        archiveName: String,
        maxExtractedChars: Int,
        maxInputBytes: Long
    ): String {
        val listedEntries = mutableListOf<String>()
        val skippedEntries = mutableListOf<String>()
        val extractedSections = StringBuilder()
        val maxArchiveBytes = (maxInputBytes * 4)
            .coerceAtMost(MAX_ZIP_TOTAL_UNCOMPRESSED_BYTES)
            .coerceAtLeast(1L)
        val maxEntryBytes = minOf(MAX_ZIP_ENTRY_BYTES, maxArchiveBytes)
        val maxEntryChars = maxExtractedChars
            .coerceAtLeast(1)
            .coerceAtMost(MAX_ZIP_TEXT_CHARS_PER_ENTRY)

        var entriesScanned = 0
        var foldersScanned = 0
        var filesScanned = 0
        var readableFiles = 0
        var skippedFiles = 0
        var truncatedEntries = 0
        var stoppedBecauseEntryLimit = false
        var stoppedBecauseSizeLimit = false
        var totalUncompressedBytes = 0L

        val extractionError = runCatching {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entriesScanned++
                    val safeName = entry.name.toSafeZipEntryName()
                    val entryLabel = safeName ?: entry.name.substringAfterLast('/').ifBlank { "unnamed entry" }

                    if (entry.isDirectory) {
                        foldersScanned++
                        if (safeName != null && listedEntries.size < MAX_ZIP_LISTED_ENTRIES) {
                            listedEntries.add("- $safeName/")
                        }
                        zip.closeEntry()
                        continue
                    }

                    filesScanned++
                    if (filesScanned > MAX_ZIP_ENTRIES) {
                        stoppedBecauseEntryLimit = true
                        zip.closeEntry()
                        break
                    }

                    if (safeName == null) {
                        skippedFiles++
                        if (skippedEntries.size < MAX_ZIP_SKIPPED_ENTRIES) {
                            skippedEntries.add("- $entryLabel (unsafe entry name)")
                        }
                        zip.closeEntry()
                        continue
                    }

                    val entrySizeLabel = entry.size.takeIf { it >= 0L }?.let { " (${formatBytes(it)})" }.orEmpty()
                    if (listedEntries.size < MAX_ZIP_LISTED_ENTRIES) {
                        listedEntries.add("- $safeName$entrySizeLabel")
                    }

                    val extension = safeName.substringAfterLast('.', "").lowercase(Locale.US)
                    if (!isZipReadableExtension(extension)) {
                        skippedFiles++
                        if (skippedEntries.size < MAX_ZIP_SKIPPED_ENTRIES) {
                            skippedEntries.add("- $safeName$entrySizeLabel (binary or unsupported)")
                        }
                        if (entry.size > maxEntryBytes) {
                            stoppedBecauseSizeLimit = true
                            break
                        }
                        zip.closeEntry()
                        continue
                    }

                    val remainingArchiveBytes = maxArchiveBytes - totalUncompressedBytes
                    if (remainingArchiveBytes <= 0L) {
                        stoppedBecauseSizeLimit = true
                        zip.closeEntry()
                        break
                    }

                    val readLimit = minOf(maxEntryBytes, remainingArchiveBytes)
                    val entryBytes = zip.readCurrentEntryWithinLimit(readLimit)
                    totalUncompressedBytes += entryBytes.bytes.size
                    val stopAfterThisEntry = entryBytes.exceededLimit
                    if (entryBytes.exceededLimit) {
                        truncatedEntries++
                        stoppedBecauseSizeLimit = true
                    }

                    val entryText = extractZipEntryText(context, safeName, entryBytes.bytes)
                        .normalizeDocumentWhitespace()
                    if (entryText.isBlank() || entryText.isUnsupportedExtractionText()) {
                        skippedFiles++
                        if (skippedEntries.size < MAX_ZIP_SKIPPED_ENTRIES) {
                            skippedEntries.add("- $safeName$entrySizeLabel (no readable text)")
                        }
                        if (stopAfterThisEntry) {
                            break
                        }
                        zip.closeEntry()
                        continue
                    }

                    readableFiles++
                    extractedSections.append("\n\n--- ZIP ENTRY: ")
                        .append(safeName)
                        .append(entrySizeLabel)
                        .append(" ---\n")
                    extractedSections.append(entryText.take(maxEntryChars).trimEnd())
                    if (entryText.length > maxEntryChars || entryBytes.exceededLimit) {
                        extractedSections.append("\n[Entry truncated to fit this model's ZIP extraction budget.]")
                    }

                    if (stopAfterThisEntry || extractedSections.length >= maxExtractedChars * 2) {
                        stoppedBecauseSizeLimit = true
                        break
                    }
                    zip.closeEntry()
                }
            }
        }.exceptionOrNull()

        if (extractionError != null) {
            return "Could not extract ZIP archive $archiveName: ${extractionError.message ?: extractionError::class.java.simpleName}."
        }

        return buildString {
            append("ZIP archive summary for ").append(archiveName).append("\n")
            append("Entries scanned: ").append(entriesScanned)
            append(" (").append(filesScanned).append(" files, ")
            append(foldersScanned).append(" folders). ")
            append("Readable files extracted: ").append(readableFiles).append(". ")
            append("Skipped binary/unsupported files: ").append(skippedFiles).append(".")
            if (truncatedEntries > 0) {
                append("\nTruncated entries: ").append(truncatedEntries)
                    .append(" because of context or archive safety limits.")
            }
            if (stoppedBecauseEntryLimit) {
                append("\nStopped after ").append(MAX_ZIP_ENTRIES)
                    .append(" files to keep the archive summary compact.")
            }
            if (stoppedBecauseSizeLimit) {
                append("\nStopped after ")
                    .append(formatBytes(totalUncompressedBytes))
                    .append(" of uncompressed archive data to stay within safety limits.")
            }

            if (listedEntries.isNotEmpty()) {
                append("\n\nFile list:")
                append("\n")
                append(listedEntries.joinToString("\n"))
                if (filesScanned + foldersScanned > listedEntries.size) {
                    append("\n- ...")
                }
            }

            if (skippedEntries.isNotEmpty()) {
                append("\n\nSkipped entries:")
                append("\n")
                append(skippedEntries.joinToString("\n"))
                if (skippedFiles > skippedEntries.size) {
                    append("\n- ...")
                }
            }

            if (extractedSections.isNotBlank()) {
                append("\n\nReadable contents extracted from archive:")
                append(extractedSections)
            } else {
                append("\n\nNo readable text files were found inside this ZIP. Summarize the archive from the file list and skipped-entry notes.")
            }
        }
    }

    private data class ZipEntryBytes(
        val bytes: ByteArray,
        val exceededLimit: Boolean
    )

    private fun ZipInputStream.readCurrentEntryWithinLimit(maxBytes: Long): ZipEntryBytes {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read == -1) break
            val nextTotal = total + read
            if (nextTotal > maxBytes) {
                val allowed = (read - (nextTotal - maxBytes)).toInt().coerceAtLeast(0)
                if (allowed > 0) {
                    output.write(buffer, 0, allowed)
                }
                return ZipEntryBytes(output.toByteArray(), exceededLimit = true)
            }
            output.write(buffer, 0, read)
            total = nextTotal
        }
        return ZipEntryBytes(output.toByteArray(), exceededLimit = false)
    }

    private fun extractZipEntryText(context: Context, entryName: String, bytes: ByteArray): String {
        val extension = entryName.substringAfterLast('.', "").lowercase(Locale.US)
        return when {
            extension == "pdf" -> extractPdf(context, bytes)
            extension == "docx" -> extractDocx(bytes)
            extension == "pptx" -> extractOfficeOpenXml(bytes, "ppt/slides/", listOf("a:t"))
            extension == "xlsx" -> extractOfficeOpenXml(bytes, "xl/", listOf("t", "v"))
            extension == "odt" || extension == "ods" || extension == "odp" ->
                extractZipXmlEntries(bytes, listOf("content.xml"))
            extension == "html" || extension == "htm" -> extractHtml(bytes.decodeText())
            extension == "rtf" -> extractRtf(bytes.decodeText())
            isPlainTextExtension(extension) -> bytes.decodeText()
            else -> fallbackExtract(bytes, extension)
        }
    }

    private fun extractXmlText(xml: String, textTags: List<String>): String {
        var normalized = xml
            .replace(Regex("""<w:tab\b[^>]*/>"""), "\t")
            .replace(Regex("""<(w:br|w:cr)\b[^>]*/>"""), "\n")
            .replace(Regex("""</(w:p|a:p|text:p)>"""), "\n")

        textTags.forEach { tag ->
            val escapedTag = Regex.escape(tag)
            normalized = normalized.replace(
                Regex("""<${escapedTag}\b[^>]*>(.*?)</${escapedTag}>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            ) { match -> decodeXmlEntities(match.groupValues[1]) + " " }
        }
        return stripXmlTags(normalized)
    }

    private fun extractHtml(text: String): String {
        return text
            .replace(Regex("""(?is)<(script|style)\b.*?</\1>"""), " ")
            .replace(Regex("""(?i)<br\s*/?>"""), "\n")
            .replace(Regex("""(?i)</(p|div|section|article|li|tr|h[1-6])>"""), "\n")
            .let(::stripXmlTags)
    }

    private fun extractRtf(text: String): String {
        return text
            .replace(Regex("""\\'[0-9a-fA-F]{2}""")) { match ->
                match.value.substring(2).toInt(16).toChar().toString()
            }
            .replace(Regex("""\\[a-zA-Z]+\d* ?"""), " ")
            .replace(Regex("""[{}]"""), " ")
    }

    private fun stripXmlTags(text: String): String {
        return decodeXmlEntities(text.replace(Regex("""<[^>]+>"""), " "))
    }

    private fun fallbackExtract(bytes: ByteArray, extension: String): String {
        val text = bytes.decodeText()
        val printableRatio = text.count { it == '\n' || it == '\r' || it == '\t' || !it.isISOControl() }
            .toFloat() / text.length.coerceAtLeast(1)
        return if (printableRatio > 0.82f) {
            if (extension in setOf("xml", "xhtml", "svg")) stripXmlTags(text) else text
        } else {
            "This file type is not text-extractable by the app yet."
        }
    }

    private fun String.toSafeZipEntryName(): String? {
        val normalized = replace('\\', '/').trim('/')
        if (normalized.isBlank()) return null
        val parts = normalized.split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." }) return null
        if (normalized.startsWith("/") || normalized.contains(":")) return null
        return normalized
    }

    private fun isZipReadableExtension(extension: String): Boolean {
        return extension in setOf(
            "pdf", "docx", "pptx", "xlsx", "odt", "ods", "odp",
            "html", "htm", "rtf"
        ) || isPlainTextExtension(extension)
    }

    private fun String.isUnsupportedExtractionText(): Boolean {
        val normalized = trim().lowercase(Locale.US)
        return normalized == "this file type is not text-extractable by the app yet." ||
            normalized.startsWith("could not extract pdf text")
    }

    private fun ByteArray.decodeText(): String {
        val utf8 = toString(Charsets.UTF_8)
        return if (utf8.count { it == '\uFFFD' } > utf8.length / 20) {
            toString(Charsets.ISO_8859_1)
        } else {
            utf8
        }
    }

    private fun String.normalizeDocumentWhitespace(): String {
        return replace("\u0000", " ")
            .replace(Regex("""[ \t]+\n"""), "\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .replace(Regex("""[ \t]{2,}"""), " ")
            .trim()
    }

    private fun decodeXmlEntities(text: String): String {
        return text
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace(Regex("""&#(\d+);""")) { match ->
                match.groupValues[1].toIntOrNull()?.toChar()?.toString().orEmpty()
            }
            .replace(Regex("""&#x([0-9a-fA-F]+);""")) { match ->
                match.groupValues[1].toIntOrNull(16)?.toChar()?.toString().orEmpty()
            }
    }

    private fun formatBytes(bytes: Long): String {
        val kilobyte = 1024.0
        val megabyte = kilobyte * 1024.0
        return when {
            bytes >= megabyte -> {
                val value = bytes / megabyte
                String.format(Locale.US, if (value >= 10) "%.0f MB" else "%.1f MB", value)
            }
            bytes >= kilobyte -> {
                val value = bytes / kilobyte
                String.format(Locale.US, if (value >= 10) "%.0f KB" else "%.1f KB", value)
            }
            else -> "$bytes B"
        }
    }

    private fun isPlainTextExtension(extension: String): Boolean {
        return extension in setOf(
            "txt", "md", "markdown", "csv", "tsv", "json", "xml", "yaml", "yml",
            "log", "ini", "properties", "gradle", "kt", "java", "py", "js", "ts",
            "css", "scss", "c", "cpp", "h", "hpp", "rs", "go", "swift", "sql"
        )
    }
}
