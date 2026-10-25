package com.shounak.localmeshai.utils

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

object ArchiveTextExtractor {

    private const val MAX_LISTED_ENTRIES = 80
    private const val MAX_SKIPPED_ENTRIES = 24
    private const val MAX_TEXT_CHARS_PER_ENTRY = 16_000

    private val ARCHIVE_EXTENSIONS = setOf(
        "zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar", "jar", "war", "apk"
    )

    fun isArchive(fileName: String, mimeType: String = ""): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        return ext in ARCHIVE_EXTENSIONS ||
            mimeType.contains("zip", ignoreCase = true) ||
            mimeType.contains("tar", ignoreCase = true) ||
            mimeType.contains("compressed", ignoreCase = true)
    }

    fun hasPathTraversal(name: String): Boolean {
        val normalized = name.replace('\\', '/')
        return normalized.contains("../") ||
            normalized.contains("/..") ||
            normalized.startsWith("../") ||
            normalized == ".." ||
            normalized.startsWith("/") ||
            normalized.contains(":\\") ||
            normalized.contains(":/")
    }

    fun extract(
        context: Context? = null,
        bytes: ByteArray,
        archiveName: String,
        budget: DocumentExtractionBudget = DocumentExtractionBudget.DEFAULT,
        currentDepth: Int = 1,
        isCancelled: (() -> Boolean)? = null,
        sharedContext: SharedExtractionContext? = null
    ): String {
        return extractFromStream(
            context = context,
            inputStreamSupplier = { ByteArrayInputStream(bytes) },
            archiveName = archiveName,
            budget = budget,
            currentDepth = currentDepth,
            isCancelled = isCancelled,
            sharedContext = sharedContext
        )
    }

    fun extractFromStream(
        context: Context? = null,
        inputStreamSupplier: () -> InputStream,
        archiveName: String,
        budget: DocumentExtractionBudget = DocumentExtractionBudget.DEFAULT,
        currentDepth: Int = 1,
        isCancelled: (() -> Boolean)? = null,
        sharedContext: SharedExtractionContext? = null
    ): String {
        val extension = archiveName.substringAfterLast('.', "").lowercase(Locale.US)
        if (extension in ARCHIVE_EXTENSIONS && extension !in setOf("zip", "jar", "war", "apk")) {
            return "Unsupported archive format: .$extension. Extract the files first or attach a ZIP archive."
        }
        val extractionContext = sharedContext ?: SharedExtractionContext(
            budget = budget,
            currentDepth = currentDepth,
            isCancelled = isCancelled
        )

        val listedEntries = mutableListOf<String>()
        val skippedEntries = mutableListOf<String>()
        val extractedSections = StringBuilder()

        val maxArchiveBytes = budget.maxTotalUncompressedBytes.coerceAtLeast(1L)
        val maxEntryBytes = minOf(budget.maxEntryBytes, maxArchiveBytes)
        val maxEntryChars = budget.maxExtractedChars
            .coerceAtLeast(1)
            .coerceAtMost(MAX_TEXT_CHARS_PER_ENTRY)

        var entriesScanned = 0
        var foldersScanned = 0
        var filesScanned = 0
        var readableFiles = 0
        var skippedFiles = 0
        var truncatedEntries = 0
        var stoppedBecauseEntryLimit = false
        var stoppedBecauseSizeLimit = false
        var stoppedBecauseDecompressionBomb = false
        var totalUncompressedBytes = 0L

        val extractionError = runCatching {
            ZipInputStream(inputStreamSupplier()).use { zip ->
                while (true) {
                    extractionContext.checkCancellationAndDeadline()

                    val entry = zip.nextEntry ?: break
                    entriesScanned++
                    val rawName = entry.name
                    val safeName = rawName.toSafeZipEntryName()
                    val entryLabel = safeName ?: rawName.substringAfterLast('/').ifBlank { "unnamed entry" }

                    if (entry.isDirectory) {
                        foldersScanned++
                        extractionContext.onEntryScanned(isDirectory = true)
                        if (safeName != null && listedEntries.size < MAX_LISTED_ENTRIES) {
                            listedEntries.add("- $safeName/")
                        }
                        val drainOk = zip.drainCurrentEntryBounded(maxEntryBytes, extractionContext)
                        if (!drainOk) {
                            stoppedBecauseDecompressionBomb = true
                            stoppedBecauseSizeLimit = true
                            break
                        }
                        zip.closeEntry()
                        continue
                    }

                    filesScanned++
                    extractionContext.onEntryScanned(isDirectory = false)
                    if (filesScanned > budget.maxEntries) {
                        stoppedBecauseEntryLimit = true
                        break
                    }

                    // Check for path traversal or unsafe characters
                    if (safeName == null || hasPathTraversal(rawName)) {
                        skippedFiles++
                        extractionContext.onSkippedEntry()
                        if (skippedEntries.size < MAX_SKIPPED_ENTRIES) {
                            skippedEntries.add("- $entryLabel (path traversal rejected)")
                        }
                        val drainOk = zip.drainCurrentEntryBounded(maxEntryBytes, extractionContext)
                        if (!drainOk) {
                            stoppedBecauseDecompressionBomb = true
                            stoppedBecauseSizeLimit = true
                            break
                        }
                        zip.closeEntry()
                        continue
                    }

                    val entrySizeLabel = entry.size.takeIf { it >= 0L }?.let { " (${formatBytes(it)})" }.orEmpty()
                    if (listedEntries.size < MAX_LISTED_ENTRIES) {
                        listedEntries.add("- $safeName$entrySizeLabel")
                    }

                    val extension = safeName.substringAfterLast('.', "").lowercase(Locale.US)

                    // Check for nested archive
                    if (extension in ARCHIVE_EXTENSIONS) {
                        skippedFiles++
                        extractionContext.onSkippedEntry()
                        if (skippedEntries.size < MAX_SKIPPED_ENTRIES) {
                            skippedEntries.add("- $safeName$entrySizeLabel (nested archive rejected)")
                        }
                        val drainOk = zip.drainCurrentEntryBounded(maxEntryBytes, extractionContext)
                        if (!drainOk) {
                            stoppedBecauseDecompressionBomb = true
                            stoppedBecauseSizeLimit = true
                            break
                        }
                        zip.closeEntry()
                        continue
                    }

                    if (!isReadableExtension(extension)) {
                        skippedFiles++
                        extractionContext.onSkippedEntry()
                        if (skippedEntries.size < MAX_SKIPPED_ENTRIES) {
                            skippedEntries.add("- $safeName$entrySizeLabel (binary or unsupported)")
                        }
                        val drainOk = zip.drainCurrentEntryBounded(maxEntryBytes, extractionContext)
                        if (!drainOk) {
                            stoppedBecauseDecompressionBomb = true
                            stoppedBecauseSizeLimit = true
                            break
                        }
                        zip.closeEntry()
                        continue
                    }

                    val remainingArchiveBytes = minOf(maxArchiveBytes - totalUncompressedBytes, extractionContext.remainingArchiveBytes())
                    if (remainingArchiveBytes <= 0L) {
                        stoppedBecauseSizeLimit = true
                        stoppedBecauseDecompressionBomb = true
                        break
                    }

                    val readLimit = minOf(maxEntryBytes, remainingArchiveBytes)
                    val entryBytes = zip.readCurrentEntryWithinLimit(readLimit, extractionContext)
                    totalUncompressedBytes += entryBytes.bytes.size

                    if (totalUncompressedBytes > maxArchiveBytes || extractionContext.totalDecompressedBytes > maxArchiveBytes) {
                        stoppedBecauseDecompressionBomb = true
                        stoppedBecauseSizeLimit = true
                        break
                    }

                    val stopAfterThisEntry = entryBytes.exceededLimit
                    if (entryBytes.exceededLimit) {
                        truncatedEntries++
                        stoppedBecauseSizeLimit = true
                        if (entryBytes.bytes.size >= maxEntryBytes) {
                            stoppedBecauseDecompressionBomb = true
                        }
                    }

                    val entryExtractionContext = if (isOfficeExtension(extension)) {
                        extractionContext.createChildContext(isOfficeContainer = true)
                    } else {
                        extractionContext
                    }

                    val entryText = DocumentTextExtractor.extractZipEntryText(
                        context = context,
                        entryName = safeName,
                        bytes = entryBytes.bytes,
                        extractionContext = entryExtractionContext
                    ).normalizeDocumentWhitespace()

                    if (entryText.isBlank() || entryText.isUnsupportedExtractionText()) {
                        skippedFiles++
                        extractionContext.onSkippedEntry()
                        if (skippedEntries.size < MAX_SKIPPED_ENTRIES) {
                            skippedEntries.add("- $safeName$entrySizeLabel (no readable text)")
                        }
                        if (stopAfterThisEntry) {
                            break
                        }
                        zip.closeEntry()
                        continue
                    }

                    readableFiles++
                    extractedSections.append("\n\n--- ARCHIVE ENTRY: ")
                        .append(safeName)
                        .append(entrySizeLabel)
                        .append(" ---\n")
                    extractedSections.append(entryText.take(maxEntryChars).trimEnd())
                    extractionContext.recordEmittedChars(minOf(entryText.length, maxEntryChars))
                    if (entryText.length > maxEntryChars || entryBytes.exceededLimit) {
                        extractedSections.append("\n[Entry truncated to fit archive extraction budget.]")
                    }

                    if (stopAfterThisEntry || extractedSections.length >= budget.maxExtractedChars * 2) {
                        stoppedBecauseSizeLimit = true
                        break
                    }
                    zip.closeEntry()
                }
            }
        }.exceptionOrNull()

        if (extractionError != null) {
            when (extractionError) {
                is DecompressionBombException -> {
                    stoppedBecauseDecompressionBomb = true
                    stoppedBecauseSizeLimit = true
                }
                is ExtractionLimitExceededException -> {
                    stoppedBecauseEntryLimit = true
                }
                is ExtractionTimeoutException -> {
                    stoppedBecauseSizeLimit = true
                }
                is ExtractionCancelledException -> {
                    throw extractionError
                }
                else -> {
                    return "Could not extract archive $archiveName: ${extractionError.message ?: extractionError::class.java.simpleName}."
                }
            }
        }

        return buildString {
            append("Archive summary for ").append(archiveName).append("\n")
            append("Entries scanned: ").append(entriesScanned)
            append(" (").append(filesScanned).append(" files, ")
            append(foldersScanned).append(" folders). ")
            append("Readable files extracted: ").append(readableFiles).append(". ")
            append("Skipped files: ").append(skippedFiles).append(".")
            if (truncatedEntries > 0) {
                append("\nTruncated entries: ").append(truncatedEntries)
                    .append(" because of archive safety limits.")
            }
            if (stoppedBecauseEntryLimit) {
                append("\nStopped after ").append(budget.maxEntries)
                    .append(" files to keep the archive summary compact.")
            }
            if (stoppedBecauseDecompressionBomb) {
                append("\n[Decompression limit exceeded: stopped after ")
                    .append(formatBytes(totalUncompressedBytes.coerceAtLeast(extractionContext.totalDecompressedBytes)))
                    .append(" of uncompressed data to prevent decompression bomb.]")
            } else if (stoppedBecauseSizeLimit) {
                append("\nStopped after ")
                    .append(formatBytes(totalUncompressedBytes.coerceAtLeast(extractionContext.totalDecompressedBytes)))
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
                append("\n\nNo readable text files were found inside this archive. Summarize the archive from the file list and skipped-entry notes.")
            }
        }
    }

    private fun String.toSafeZipEntryName(): String? {
        if (hasPathTraversal(this)) return null
        val normalized = replace('\\', '/').trim('/')
        if (normalized.isBlank()) return null
        val parts = normalized.split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." }) return null
        return normalized
    }

    private fun isOfficeExtension(extension: String): Boolean {
        return extension in setOf("docx", "pptx", "xlsx", "odt", "ods", "odp")
    }

    private fun isReadableExtension(extension: String): Boolean {
        return isOfficeExtension(extension) || extension in setOf(
            "pdf", "html", "htm", "rtf"
        ) || DocumentTextExtractor.isPlainTextExtension(extension)
    }

    data class ZipEntryBytes(
        val bytes: ByteArray,
        val exceededLimit: Boolean
    )

    fun ZipInputStream.readCurrentEntryWithinLimit(
        maxBytes: Long,
        context: SharedExtractionContext? = null
    ): ZipEntryBytes {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            context?.checkCancellationAndDeadline()
            val toRead = minOf(buffer.size.toLong(), (maxBytes - total + 1).coerceAtLeast(1L)).toInt().coerceIn(1, buffer.size)
            val read = read(buffer, 0, toRead)
            if (read == -1) break
            total += read
            context?.recordDecompressedBytes(read.toLong())
            if (total > maxBytes) {
                val allowed = (read - (total - maxBytes)).toInt().coerceAtLeast(0)
                if (allowed > 0) {
                    output.write(buffer, 0, allowed)
                }
                if (context != null) {
                    val remainingLimit = minOf(maxBytes, context.remainingArchiveBytes())
                    drainCurrentEntryBounded(remainingLimit, context)
                }
                return ZipEntryBytes(output.toByteArray(), exceededLimit = true)
            }
            output.write(buffer, 0, read)
        }
        return ZipEntryBytes(output.toByteArray(), exceededLimit = false)
    }

    fun ZipInputStream.drainCurrentEntryBounded(
        maxBytes: Long,
        context: SharedExtractionContext
    ): Boolean {
        val buffer = ByteArray(8192)
        var entryTotal = 0L
        val effectiveLimit = minOf(maxBytes, context.remainingArchiveBytes())
        while (true) {
            context.checkCancellationAndDeadline()
            val toRead = minOf(buffer.size.toLong(), (effectiveLimit - entryTotal + 1).coerceAtLeast(1L)).toInt().coerceIn(1, buffer.size)
            val read = read(buffer, 0, toRead)
            if (read == -1) break
            entryTotal += read
            context.recordDecompressedBytes(read.toLong())
            if (entryTotal > effectiveLimit) {
                return false
            }
        }
        return true
    }

    private fun String.normalizeDocumentWhitespace(): String {
        return replace("\u0000", " ")
            .replace(Regex("""[ \t]+\n"""), "\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .replace(Regex("""[ \t]{2,}"""), " ")
            .trim()
    }

    private fun String.isUnsupportedExtractionText(): Boolean {
        val normalized = trim().lowercase(Locale.US)
        return normalized == "this file type is not text-extractable by the app yet." ||
            normalized.startsWith("could not extract")
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
}
