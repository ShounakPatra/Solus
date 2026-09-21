package com.shounak.localmeshai.rag.chunking

import java.util.UUID

/**
 * Represents a discrete, semantically continuous chunk of text extracted from a document.
 */
data class TextChunk(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val index: Int,
    val startChar: Int,
    val endChar: Int
)

/**
 * Intelligent text chunker that partitions documents along semantic boundaries
 * (paragraphs, markdown sections, sentences, and words) with configurable overlap.
 */
class TextChunker(
    val chunkSize: Int = DEFAULT_CHUNK_SIZE,
    val chunkOverlap: Int = DEFAULT_CHUNK_OVERLAP
) {
    init {
        require(chunkSize > 50) { "chunkSize must be greater than 50 characters" }
        require(chunkOverlap >= 0 && chunkOverlap < chunkSize) { "chunkOverlap must be non-negative and smaller than chunkSize" }
    }

    /**
     * Splits [documentText] into a list of [TextChunk] objects.
     */
    fun chunk(documentText: String): List<TextChunk> {
        val trimmed = documentText.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.length <= chunkSize) {
            return listOf(
                TextChunk(
                    id = UUID.randomUUID().toString(),
                    text = trimmed,
                    index = 0,
                    startChar = 0,
                    endChar = trimmed.length
                )
            )
        }

        val chunks = mutableListOf<TextChunk>()
        var start = 0
        var chunkIndex = 0

        while (start < trimmed.length) {
            val potentialEnd = minOf(start + chunkSize, trimmed.length)

            if (potentialEnd == trimmed.length) {
                // Last chunk
                val chunkText = trimmed.substring(start, potentialEnd).trim()
                if (chunkText.isNotEmpty()) {
                    chunks.add(
                        TextChunk(
                            id = UUID.randomUUID().toString(),
                            text = chunkText,
                            index = chunkIndex++,
                            startChar = start,
                            endChar = potentialEnd
                        )
                    )
                }
                break
            }

            // Find best boundary to break before potentialEnd
            val breakPoint = findBreakPoint(trimmed, start, potentialEnd)
            val chunkText = trimmed.substring(start, breakPoint).trim()
            if (chunkText.isNotEmpty()) {
                chunks.add(
                    TextChunk(
                        id = UUID.randomUUID().toString(),
                        text = chunkText,
                        index = chunkIndex++,
                        startChar = start,
                        endChar = breakPoint
                    )
                )
            }

            // Compute next start index taking overlap into account
            val nextStart = breakPoint - chunkOverlap
            start = if (nextStart > start) nextStart else breakPoint
            if (start >= trimmed.length) break
        }

        return chunks
    }

    /**
     * Finds the most natural semantic breaking point within the target window.
     * Hierarchy:
     * 1. Paragraph boundary (\n\n)
     * 2. Line boundary (\n)
     * 3. Sentence boundary (. , ? , ! )
     * 4. Clause boundary (;, :)
     * 5. Word boundary (' ')
     */
    private fun findBreakPoint(text: String, start: Int, end: Int): Int {
        val searchWindow = (end - (chunkSize * 0.35f).toInt()).coerceAtLeast(start + 10)
        val slice = text.substring(searchWindow, end)

        // 1. Paragraph break
        val paraIdx = slice.lastIndexOf("\n\n")
        if (paraIdx != -1) {
            return searchWindow + paraIdx + 2
        }

        // 2. Line break
        val lineIdx = slice.lastIndexOf('\n')
        if (lineIdx != -1) {
            return searchWindow + lineIdx + 1
        }

        // 3. Sentence break
        for (pattern in listOf(". ", "? ", "! ")) {
            val sentIdx = slice.lastIndexOf(pattern)
            if (sentIdx != -1) {
                return searchWindow + sentIdx + pattern.length
            }
        }

        // 4. Clause break
        for (delimiter in listOf("; ", ": ", ", ")) {
            val clauseIdx = slice.lastIndexOf(delimiter)
            if (clauseIdx != -1) {
                return searchWindow + clauseIdx + delimiter.length
            }
        }

        // 5. Word boundary
        val spaceIdx = slice.lastIndexOf(' ')
        if (spaceIdx != -1) {
            return searchWindow + spaceIdx + 1
        }

        // Hard boundary fallback if no delimiter was found
        return end
    }

    companion object {
        const val DEFAULT_CHUNK_SIZE = 450
        const val DEFAULT_CHUNK_OVERLAP = 60
    }
}
