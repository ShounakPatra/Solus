package com.shounak.localmeshai.utils

import com.shounak.localmeshai.rag.chunking.TextChunker
import com.shounak.localmeshai.rag.embedding.UniversalSemanticEmbeddingEngine
import com.shounak.localmeshai.rag.embedding.VectorEmbedding

internal object AttachedDocumentContext {
    fun isExtractionFailure(text: String): Boolean = text.isBlank() || listOf(
        "Error reading file:", "No readable text could be extracted", "Could not ",
        "The attached file is ", "Unsupported file", "Error extracting",
        "Decompression limit exceeded"
    ).any { text.startsWith(it, ignoreCase = true) } ||
        text.contains("OCR is required", ignoreCase = true) ||
        text.contains("decompression bomb", ignoreCase = true)

    /** Small inputs stay whole; long inputs use request-local retrieval without persistent writes. */
    fun select(text: String, query: String, budget: Int): String {
        if (text.length <= budget) return text
        if (budget <= 0) return ""
        val truncationMarker = when {
            text.contains("[Truncated at ") -> "[Truncated at "
            text.contains("[The document was longer than ") -> "[The document was longer than "
            else -> null
        }
        val truncationNotice = truncationMarker?.let { marker ->
            text.substringAfter(marker).let { "$marker$it" }.trim()
        }

        val cleanText = if (truncationMarker != null) {
            text.substringBefore(truncationMarker).trim()
        } else text

        val chunks = TextChunker().chunk(cleanText)
        if (chunks.isEmpty()) return text.take(budget)

        val engine = UniversalSemanticEmbeddingEngine()
        val overview = query.isBlank() || Regex("(?i)\\b(summar\\w*|overview|describe|explain.*document)\\b").containsMatchIn(query)
        val indices = if (overview) {
            // Cover the document instead of returning only its first pages.
            val samples = minOf(8, chunks.size)
            (0 until samples).map { if (samples == 1) 0 else it * (chunks.size - 1) / (samples - 1) }
        } else {
            val queryEmbedding = engine.embedSync(query)
            chunks.mapIndexed { index, chunk -> index to VectorEmbedding.cosineSimilarity(queryEmbedding, engine.embedSync(chunk.text)) }
                .sortedByDescending { it.second }.take(8).map { it.first }.sorted()
        }
        val selected = indices.map { chunks[it].text }
        val reservedForNotice = if (truncationNotice != null) truncationNotice.length + 4 else 0
        val effectiveBudget = (budget - reservedForNotice).coerceAtLeast(1)
        val perChunk = (effectiveBudget / selected.size.coerceAtLeast(1)).coerceAtLeast(1)
        val result = selected.joinToString("\n\n") { it.take(perChunk) }.take(effectiveBudget)
        return if (truncationNotice != null) {
            "$result\n\n$truncationNotice".take(budget)
        } else {
            result
        }
    }
}
