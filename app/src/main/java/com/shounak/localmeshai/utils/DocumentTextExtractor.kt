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

    fun extract(
        context: Context,
        uri: Uri,
        fileName: String,
        mimeType: String = "",
        budget: DocumentExtractionBudget = DocumentExtractionBudget.DEFAULT,
        isCancelled: (() -> Boolean)? = null,
        onFailure: ((String) -> Unit)? = null
    ): String {
        fun failure(message: String): String = message.also { onFailure?.invoke(it) }
        val safeInputLimit = budget.maxInputBytes.coerceAtLeast(1L)
        val sizeLimitLabel = formatBytes(safeInputLimit)
        val reportedSize = queryFileSize(context, uri)
        if (reportedSize != null && reportedSize > safeInputLimit) {
            return failure("The attached file is ${formatBytes(reportedSize)}, but the selected model can read files up to $sizeLimitLabel based on its context window.")
        }
        val readResult = readBytesWithinLimit(context, uri, safeInputLimit, isCancelled)
            ?: return failure("Could not open the attached file stream.")
        if (readResult.exceededLimit) {
            return failure("The attached file is larger than $sizeLimitLabel, which is the selected model's current context-based file limit.")
        }
        return extractBytes(
            bytes = readResult.bytes,
            fileName = fileName,
            mimeType = mimeType,
            context = context,
            budget = budget,
            isCancelled = isCancelled,
            onFailure = onFailure
        )
    }

    fun extract(
        context: Context,
        uri: Uri,
        fileName: String,
        mimeType: String = "",
        maxExtractedChars: Int = DEFAULT_MAX_EXTRACTED_CHARS,
        limitDescription: String = "$DEFAULT_MAX_EXTRACTED_CHARS characters",
        maxInputBytes: Long = DEFAULT_MAX_INPUT_BYTES
    ): String = extract(
        context = context,
        uri = uri,
        fileName = fileName,
        mimeType = mimeType,
        budget = DocumentExtractionBudget(
            maxExtractedChars = maxExtractedChars,
            maxInputBytes = maxInputBytes,
            limitDescription = limitDescription
        )
    )

    fun extractBytes(
        bytes: ByteArray,
        fileName: String,
        mimeType: String = "",
        context: Context? = null,
        budget: DocumentExtractionBudget = DocumentExtractionBudget.DEFAULT,
        extractionContext: SharedExtractionContext? = null,
        isCancelled: (() -> Boolean)? = null,
        onFailure: ((String) -> Unit)? = null
    ): String {
        fun failure(message: String): String = message.also { onFailure?.invoke(it) }
        val effectiveCancelled = isCancelled ?: extractionContext?.isCancelled
        val effectiveContext = when {
            extractionContext == null ->
                SharedExtractionContext(budget = budget, isCancelled = effectiveCancelled)
            extractionContext.isCancelled == null && effectiveCancelled != null ->
                SharedExtractionContext(
                    budget = extractionContext.budget,
                    currentDepth = extractionContext.currentDepth,
                    isCancelled = effectiveCancelled,
                    state = extractionContext.state
                )
            else ->
                extractionContext
        }
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        val safeInputLimit = budget.maxInputBytes.coerceAtLeast(1L)
        if (bytes.size > safeInputLimit) {
            val sizeLimitLabel = formatBytes(safeInputLimit)
            return failure("The attached file is ${formatBytes(bytes.size.toLong())}, but the selected model can read files up to $sizeLimitLabel based on its context window.")
        }

        val extracted = try {
            when {
                extension == "pdf" || mimeType.contains("pdf", ignoreCase = true) ->
                    if (context != null) extractPdf(context, bytes, onFailure) else failure("PDF extraction requires Context")
                extension == "docx" ->
                    extractDocx(bytes, effectiveContext)
                extension == "pptx" ->
                    extractOfficeOpenXml(bytes, "ppt/slides/", listOf("a:t"), effectiveContext)
                extension == "xlsx" ->
                    extractOfficeOpenXml(bytes, "xl/", listOf("t", "v"), effectiveContext)
                extension == "odt" || extension == "ods" || extension == "odp" ->
                    extractZipXmlEntries(bytes, listOf("content.xml"), effectiveContext)
                ArchiveTextExtractor.isArchive(fileName, mimeType) && extension in setOf("tar", "gz", "tgz", "bz2", "xz", "7z", "rar") ->
                    failure("Unsupported archive format: .$extension. Extract the files first or attach a ZIP archive.")
                // The extension or MIME type alone ("application/gzip" with no extension) does not
                // make the bytes a ZIP: ZipInputStream would report a misleading empty archive.
                ArchiveTextExtractor.isArchive(fileName, mimeType) && archiveSignature(bytes) != "zip" ->
                    failure(archiveSignature(bytes)?.let { "Unsupported archive format: $it. Extract the files first or attach a ZIP archive." }
                        ?: "Could not extract $fileName: it is not a readable ZIP archive.")
                ArchiveTextExtractor.isArchive(fileName, mimeType) ->
                    ArchiveTextExtractor.extract(
                        context = context,
                        bytes = bytes,
                        archiveName = fileName,
                        budget = budget,
                        sharedContext = effectiveContext,
                        isCancelled = effectiveCancelled,
                        onFailure = onFailure
                    )
                extension == "html" || extension == "htm" || mimeType.contains("html", ignoreCase = true) ->
                    extractHtml(bytes.decodeText())
                extension == "rtf" ->
                    extractRtf(bytes.decodeText())
                isPlainTextExtension(extension) || mimeType.startsWith("text/", ignoreCase = true) ||
                    mimeType.contains("json", ignoreCase = true) || mimeType.contains("xml", ignoreCase = true) ->
                    bytes.decodeText()
                else -> fallbackExtract(bytes, extension, onFailure)
            }
        } catch (e: DecompressionBombException) {
            return failure("Could not extract $fileName: ${e.message}")
        } catch (e: ExtractionTimeoutException) {
            return failure("Could not extract $fileName: extraction timed out to stay within safety limits.")
        } catch (e: ExtractionLimitExceededException) {
            return failure("Could not extract $fileName: ${e.message}")
        } catch (e: NestedArchiveException) {
            return failure("Could not extract $fileName: ${e.message}")
        } catch (e: ExtractionCancelledException) {
            throw e
        } catch (e: Exception) {
            return failure("Could not extract $fileName: ${e.message ?: e::class.java.simpleName}")
        }

        val normalized = extracted.normalizeDocumentWhitespace()
        val safeLimit = budget.maxExtractedChars.coerceAtLeast(1)
        val clipped = if (normalized.length > safeLimit) {
            normalized.take(safeLimit).trimEnd() +
                "\n\n[Truncated at $safeLimit characters: the document was longer than this model's input budget, so only the first ${budget.limitDescription} were sent.]"
        } else {
            normalized
        }
        return clipped.ifBlank {
            failure("No readable text could be extracted from $fileName. If this is a scanned image-only document, OCR is required before the model can analyze it.")
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

    private fun readBytesWithinLimit(
        context: Context,
        uri: Uri,
        maxInputBytes: Long,
        isCancelled: (() -> Boolean)? = null
    ): ReadBytesResult? {
        return context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                if (isCancelled?.invoke() == true) {
                    throw ExtractionCancelledException("Document reading was cancelled.")
                }
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

    private fun extractPdf(context: Context, bytes: ByteArray, onFailure: ((String) -> Unit)? = null): String {
        return runCatching {
            PDFBoxResourceLoader.init(context.applicationContext)
            PDDocument.load(ByteArrayInputStream(bytes)).use { document ->
                PDFTextStripper().apply {
                    setSortByPosition(true)
                }.getText(document)
            }
        }.getOrElse { error ->
            "Could not extract PDF text: ${error.message ?: error::class.java.simpleName}. If this PDF is scanned or image-only, OCR is required."
                .also { onFailure?.invoke(it) }
        }
    }

    private fun extractDocx(bytes: ByteArray, extractionContext: SharedExtractionContext): String {
        val entries = listOf(
            "word/document.xml",
            "word/header",
            "word/footer",
            "word/footnotes.xml",
            "word/endnotes.xml",
            "word/comments.xml"
        )
        return extractZipXmlEntries(bytes, entries, extractionContext)
    }

    private fun extractOfficeOpenXml(
        bytes: ByteArray,
        entryPrefix: String,
        textTags: List<String>,
        extractionContext: SharedExtractionContext
    ): String {
        val builder = StringBuilder()
        val maxEntryBytes = extractionContext.budget.maxEntryBytes
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                extractionContext.checkCancellationAndDeadline()
                val entry = zip.nextEntry ?: break
                extractionContext.onEntryScanned(entry.isDirectory)
                if (!entry.isDirectory && entry.name.startsWith(entryPrefix) && entry.name.endsWith(".xml")) {
                    val remaining = minOf(maxEntryBytes, extractionContext.remainingArchiveBytes())
                    if (remaining <= 0L) {
                        throw DecompressionBombException("Decompression limit exceeded on entry ${entry.name}.")
                    }
                    val entryBytes = with(ArchiveTextExtractor) {
                        zip.readCurrentEntryWithinLimit(remaining, extractionContext)
                    }
                    if (entryBytes.exceededLimit) {
                        throw DecompressionBombException("Decompression limit exceeded: XML entry ${entry.name} exceeded safety limit.")
                    }
                    val xml = entryBytes.bytes.toString(Charsets.UTF_8)
                    val text = extractXmlText(xml, textTags)
                    builder.append(text).append("\n")
                    extractionContext.recordEmittedChars(text.length + 1)
                    if (builder.length >= extractionContext.budget.maxExtractedChars * 2) {
                        break
                    }
                } else if (!entry.isDirectory) {
                    val drainOk = with(ArchiveTextExtractor) {
                        zip.drainCurrentEntryBounded(maxEntryBytes, extractionContext)
                    }
                    if (!drainOk) {
                        throw DecompressionBombException("Decompression limit exceeded on entry ${entry.name}.")
                    }
                }
                zip.closeEntry()
            }
        }
        return builder.toString()
    }

    private fun extractZipXmlEntries(
        bytes: ByteArray,
        matchingNames: List<String>,
        extractionContext: SharedExtractionContext
    ): String {
        val builder = StringBuilder()
        val maxEntryBytes = extractionContext.budget.maxEntryBytes
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                extractionContext.checkCancellationAndDeadline()
                val entry = zip.nextEntry ?: break
                extractionContext.onEntryScanned(entry.isDirectory)
                if (!entry.isDirectory && matchingNames.any { entry.name == it || entry.name.startsWith(it) }) {
                    val remaining = minOf(maxEntryBytes, extractionContext.remainingArchiveBytes())
                    if (remaining <= 0L) {
                        throw DecompressionBombException("Decompression limit exceeded on entry ${entry.name}.")
                    }
                    val entryBytes = with(ArchiveTextExtractor) {
                        zip.readCurrentEntryWithinLimit(remaining, extractionContext)
                    }
                    if (entryBytes.exceededLimit) {
                        throw DecompressionBombException("Decompression limit exceeded: XML entry ${entry.name} exceeded safety limit.")
                    }
                    val xml = entryBytes.bytes.toString(Charsets.UTF_8)
                    val text = extractXmlText(xml, listOf("w:t", "a:t", "text:p", "text:span", "t"))
                    builder.append(text).append("\n")
                    extractionContext.recordEmittedChars(text.length + 1)
                    if (builder.length >= extractionContext.budget.maxExtractedChars * 2) {
                        break
                    }
                } else if (!entry.isDirectory) {
                    val drainOk = with(ArchiveTextExtractor) {
                        zip.drainCurrentEntryBounded(maxEntryBytes, extractionContext)
                    }
                    if (!drainOk) {
                        throw DecompressionBombException("Decompression limit exceeded on entry ${entry.name}.")
                    }
                }
                zip.closeEntry()
            }
        }
        return builder.toString()
    }

    internal fun extractZipEntryText(
        context: Context?,
        entryName: String,
        bytes: ByteArray,
        extractionContext: SharedExtractionContext? = null,
        onFailure: ((String) -> Unit)? = null
    ): String {
        val extension = entryName.substringAfterLast('.', "").lowercase(Locale.US)
        val ctx = extractionContext ?: SharedExtractionContext()
        return when {
            extension == "pdf" -> if (context != null) extractPdf(context, bytes, onFailure) else "PDF extraction requires Context".also { onFailure?.invoke(it) }
            extension == "docx" -> extractDocx(bytes, ctx)
            extension == "pptx" -> extractOfficeOpenXml(bytes, "ppt/slides/", listOf("a:t"), ctx)
            extension == "xlsx" -> extractOfficeOpenXml(bytes, "xl/", listOf("t", "v"), ctx)
            extension == "odt" || extension == "ods" || extension == "odp" ->
                extractZipXmlEntries(bytes, listOf("content.xml"), ctx)
            extension == "html" || extension == "htm" -> extractHtml(bytes.decodeText())
            extension == "rtf" -> extractRtf(bytes.decodeText())
            isPlainTextExtension(extension) -> bytes.decodeText()
            else -> fallbackExtract(bytes, extension, onFailure)
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
            ) { match -> match.groupValues[1] + " " }
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

    /** Archive container named by its leading bytes, or null when no known signature matches. */
    private fun archiveSignature(bytes: ByteArray): String? {
        fun startsWith(vararg signature: Int, offset: Int = 0): Boolean =
            bytes.size >= offset + signature.size && signature.indices.all { bytes[offset + it] == signature[it].toByte() }
        return when {
            startsWith(0x50, 0x4B, 0x03, 0x04) || startsWith(0x50, 0x4B, 0x05, 0x06) || startsWith(0x50, 0x4B, 0x07, 0x08) -> "zip"
            startsWith(0x1F, 0x8B) -> "gzip"
            startsWith(0x42, 0x5A, 0x68) -> "bzip2"
            startsWith(0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00) -> "xz"
            startsWith(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> "7z"
            startsWith(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07) -> "rar"
            startsWith(0x75, 0x73, 0x74, 0x61, 0x72, offset = 257) -> "tar"
            else -> null
        }
    }

    /** Per-group RTF state; a closing brace restores the enclosing group's values. */
    private data class RtfGroupState(
        val unicodeFallbackLength: Int,
        val skipDestination: Boolean,
        val fallbackRemaining: Int,
        val inObject: Boolean
    )

    /** The charset named by `\ansicpgN`; unknown or unavailable code pages fall back to windows-1252. */
    private fun rtfCharset(codePage: Int?): java.nio.charset.Charset {
        val candidates = when (codePage) {
            null, 0, 1252 -> emptyList()
            65001 -> listOf("UTF-8")
            932 -> listOf("windows-31j", "Shift_JIS")
            936 -> listOf("GBK", "GB2312")
            949 -> listOf("x-windows-949", "EUC-KR")
            950 -> listOf("x-windows-950", "Big5")
            874 -> listOf("x-windows-874", "windows-874", "TIS-620")
            10000 -> listOf("x-MacRoman", "MacRoman")
            else -> listOf("windows-$codePage", "cp$codePage", "IBM$codePage")
        }
        return candidates.firstNotNullOfOrNull { name ->
            runCatching { java.nio.charset.Charset.forName(name) }.getOrNull()
        } ?: charset("windows-1252")
    }

    private fun extractRtf(text: String): String {
        val output = StringBuilder()
        val groups = java.util.ArrayDeque<RtfGroupState>()
        var unicodeFallbackLength = 1
        var fallbackRemaining = 0
        var skipDestination = false
        // Inside \object everything is skipped except its \result, the text Word shows for it.
        var inObject = false
        var codePage = rtfCharset(null)
        // \'hh bytes are decoded together, so a double-byte code page character stays whole.
        val pendingBytes = java.io.ByteArrayOutputStream()
        var index = 0
        fun flushBytes() {
            if (pendingBytes.size() == 0) return
            output.append(String(pendingBytes.toByteArray(), codePage))
            pendingBytes.reset()
        }
        fun appendCharacter(character: Char) {
            if (skipDestination) return
            if (fallbackRemaining > 0) fallbackRemaining-- else {
                flushBytes()
                output.append(character)
            }
        }
        fun appendByte(byte: Int) {
            if (skipDestination) return
            if (fallbackRemaining > 0) fallbackRemaining-- else pendingBytes.write(byte)
        }
        while (index < text.length) {
            when (val character = text[index++]) {
                '{' -> {
                    flushBytes()
                    groups.push(RtfGroupState(unicodeFallbackLength, skipDestination, fallbackRemaining, inObject))
                }
                '}' -> if (groups.isNotEmpty()) {
                    flushBytes()
                    val previous = groups.pop()
                    unicodeFallbackLength = previous.unicodeFallbackLength
                    skipDestination = previous.skipDestination
                    fallbackRemaining = previous.fallbackRemaining
                    inObject = previous.inObject
                }
                '\r', '\n' -> Unit // Source wrapping is not an RTF paragraph.
                '\\' -> {
                    if (index >= text.length) break
                    val symbol = text[index++]
                    when {
                        symbol == '\\' || symbol == '{' || symbol == '}' -> appendCharacter(symbol)
                        symbol == '\'' && index + 2 <= text.length -> {
                            val byte = text.substring(index, index + 2).toIntOrNull(16)
                            index += 2
                            if (byte != null && byte in 0..255) appendByte(byte)
                        }
                        symbol.isLetter() -> {
                            val start = index - 1
                            while (index < text.length && text[index].isLetter()) index++
                            val word = text.substring(start, index)
                            val numberStart = index
                            if (index < text.length && text[index] == '-') index++
                            while (index < text.length && text[index].isDigit()) index++
                            val number = text.substring(numberStart, index).toIntOrNull()
                            if (index < text.length && text[index] == ' ') index++
                            when (word) {
                                "uc" -> unicodeFallbackLength = (number ?: 1).coerceAtLeast(0)
                                "u" -> if (number != null) {
                                    if (!skipDestination) {
                                        flushBytes()
                                        output.append((number and 0xFFFF).toChar())
                                    }
                                    fallbackRemaining = unicodeFallbackLength
                                }
                                "ansicpg" -> {
                                    flushBytes()
                                    codePage = rtfCharset(number)
                                }
                                "par", "line" -> appendCharacter('\n')
                                "tab" -> appendCharacter('\t')
                                "bin" -> index += (number ?: 0).coerceIn(0, text.length - index)
                                "object" -> {
                                    skipDestination = true
                                    inObject = true
                                }
                                "result" -> if (inObject) skipDestination = false
                                "fonttbl", "colortbl", "stylesheet", "info", "pict", "themedata",
                                "datastore", "xmlnstbl", "listtable", "listoverridetable", "latentstyles",
                                "generator", "fldinst" -> skipDestination = true
                            }
                        }
                        symbol == '*' -> skipDestination = true
                        symbol == '~' -> appendCharacter(' ')
                        symbol == '_' -> appendCharacter('-')
                    }
                }
                else -> appendCharacter(character)
            }
        }
        flushBytes()
        return output.toString()
    }

    private fun stripXmlTags(text: String): String {
        return decodeXmlEntities(text.replace(Regex("""<[^>]+>"""), " "))
    }

    private fun fallbackExtract(bytes: ByteArray, extension: String, onFailure: ((String) -> Unit)? = null): String {
        val text = bytes.decodeText()
        val printableRatio = text.count { it == '\n' || it == '\r' || it == '\t' || !it.isISOControl() }
            .toFloat() / text.length.coerceAtLeast(1)
        return if (printableRatio > 0.82f) {
            if (extension in setOf("xml", "xhtml", "svg")) stripXmlTags(text) else text
        } else {
            "This file type is not text-extractable by the app yet.".also { onFailure?.invoke(it) }
        }
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
        // Decode once: &amp;lt; is literal &lt;, not a second markup layer.
        return Regex("""&(#x[0-9a-fA-F]+|#\d+|nbsp|amp|lt|gt|quot|apos);""").replace(text) { match ->
            when (val entity = match.groupValues[1]) {
                "nbsp" -> " "
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                else -> {
                    val codePoint = if (entity.startsWith("#x")) entity.drop(2).toIntOrNull(16)
                        else entity.drop(1).toIntOrNull()
                    if (codePoint != null && Character.isValidCodePoint(codePoint) && codePoint !in 0xD800..0xDFFF) {
                        String(Character.toChars(codePoint))
                    } else match.value
                }
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        val kilobyte = 1024.0
        val megabyte = kilobyte * 1024.0
        return when {
            bytes >= megabyte -> {
                val value = bytes / megabyte
                String.format(Locale.US, if (value >= 10) "%.0f MiB" else "%.1f MiB", value)
            }
            bytes >= kilobyte -> {
                val value = bytes / kilobyte
                String.format(Locale.US, if (value >= 10) "%.0f KiB" else "%.1f KiB", value)
            }
            else -> "$bytes B"
        }
    }

    internal fun isPlainTextExtension(extension: String): Boolean {
        return extension in setOf(
            "txt", "md", "markdown", "csv", "tsv", "json", "xml", "yaml", "yml",
            "log", "ini", "properties", "gradle", "kt", "java", "py", "js", "ts",
            "css", "scss", "c", "cpp", "h", "hpp", "rs", "go", "swift", "sql"
        )
    }
}
