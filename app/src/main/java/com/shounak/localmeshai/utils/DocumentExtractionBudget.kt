package com.shounak.localmeshai.utils

import java.util.concurrent.CancellationException

/**
 * Bounds and safety constraints for document and archive text extraction.
 */
data class DocumentExtractionBudget(
    val maxExtractedChars: Int = 64_000,
    val maxInputBytes: Long = 8L * 1024L * 1024L,
    val maxEntries: Int = 120,
    val maxEntryBytes: Long = 12L * 1024L * 1024L,
    val maxTotalUncompressedBytes: Long = 96L * 1024L * 1024L,
    val maxArchiveDepth: Int = 1,
    val timeoutMillis: Long = 30_000L,
    val limitDescription: String = "$maxExtractedChars characters"
) {
    companion object {
        val DEFAULT = DocumentExtractionBudget()

        val RAG_KNOWLEDGE_BASE = DocumentExtractionBudget(
            maxExtractedChars = 200_000,
            maxInputBytes = 20L * 1024L * 1024L,
            limitDescription = "200,000 characters for RAG knowledge base"
        )

        val ATTACHED_DOCUMENT = DocumentExtractionBudget(
            maxExtractedChars = 200_000,
            maxInputBytes = 20L * 1024L * 1024L,
            limitDescription = "200,000 characters for attached documents"
        )
    }
}

sealed class DocumentExtractionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class DecompressionBombException(message: String) : DocumentExtractionException(message)
class ExtractionLimitExceededException(message: String) : DocumentExtractionException(message)
class ExtractionTimeoutException(message: String) : DocumentExtractionException(message)
class ExtractionCancelledException(message: String = "Document extraction was cancelled.") : CancellationException(message)
class NestedArchiveException(message: String) : DocumentExtractionException(message)

/**
 * Shared extraction context that tracks actual decompressed bytes, entry count,
 * depth, emitted characters, deadline, and cancellation across nested containers.
 */
class SharedExtractionContext(
    val budget: DocumentExtractionBudget = DocumentExtractionBudget.DEFAULT,
    val currentDepth: Int = 1,
    val isCancelled: (() -> Boolean)? = null,
    internal val state: State = State(
        deadlineNanos = System.nanoTime() + (budget.timeoutMillis * 1_000_000L).coerceAtLeast(1_000_000L)
    )
) {
    class State(val deadlineNanos: Long) {
        var totalDecompressedBytes: Long = 0L
        var totalEntriesScanned: Int = 0
        var totalFoldersScanned: Int = 0
        var totalFilesScanned: Int = 0
        var totalSkippedEntries: Int = 0
        var totalEmittedChars: Int = 0
    }

    val totalDecompressedBytes: Long get() = state.totalDecompressedBytes
    val totalEntriesScanned: Int get() = state.totalEntriesScanned
    val totalFoldersScanned: Int get() = state.totalFoldersScanned
    val totalFilesScanned: Int get() = state.totalFilesScanned
    val totalSkippedEntries: Int get() = state.totalSkippedEntries
    val totalEmittedChars: Int get() = state.totalEmittedChars

    fun checkCancellationAndDeadline() {
        if (isCancelled?.invoke() == true) {
            throw ExtractionCancelledException("Document extraction was cancelled.")
        }
        if (System.nanoTime() > state.deadlineNanos) {
            throw ExtractionTimeoutException("Extraction timed out after ${budget.timeoutMillis}ms.")
        }
    }

    fun onEntryScanned(isDirectory: Boolean) {
        checkCancellationAndDeadline()
        state.totalEntriesScanned++
        if (isDirectory) {
            state.totalFoldersScanned++
        } else {
            state.totalFilesScanned++
        }
        if (state.totalEntriesScanned > budget.maxEntries || state.totalFilesScanned > budget.maxEntries) {
            throw ExtractionLimitExceededException("Archive entry limit exceeded (${budget.maxEntries} entries).")
        }
    }

    fun onSkippedEntry() {
        checkCancellationAndDeadline()
        state.totalSkippedEntries++
    }

    fun recordDecompressedBytes(bytes: Long) {
        checkCancellationAndDeadline()
        if (bytes <= 0L) return
        state.totalDecompressedBytes += bytes
        if (state.totalDecompressedBytes > budget.maxTotalUncompressedBytes) {
            throw DecompressionBombException(
                "Decompression limit exceeded: stopped after ${state.totalDecompressedBytes} bytes of uncompressed data to prevent decompression bomb."
            )
        }
    }

    fun recordEmittedChars(chars: Int) {
        checkCancellationAndDeadline()
        if (chars > 0) {
            state.totalEmittedChars += chars
        }
    }

    fun remainingArchiveBytes(): Long {
        return (budget.maxTotalUncompressedBytes - state.totalDecompressedBytes).coerceAtLeast(0L)
    }

    fun remainingChars(): Int {
        return (budget.maxExtractedChars - state.totalEmittedChars).coerceAtLeast(0)
    }

    fun createChildContext(isOfficeContainer: Boolean = false): SharedExtractionContext {
        if (!isOfficeContainer && currentDepth >= budget.maxArchiveDepth) {
            throw NestedArchiveException("Nested archive rejected: depth $currentDepth reaches or exceeds maximum allowed depth (${budget.maxArchiveDepth}).")
        }
        return SharedExtractionContext(
            budget = budget,
            currentDepth = currentDepth + 1,
            isCancelled = isCancelled,
            state = state
        )
    }
}
