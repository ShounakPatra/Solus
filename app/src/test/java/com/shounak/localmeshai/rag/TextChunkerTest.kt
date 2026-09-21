package com.shounak.localmeshai.rag

import com.shounak.localmeshai.rag.chunking.TextChunker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextChunkerTest {

    @Test
    fun testEmptyOrBlankText_ReturnsEmptyList() {
        val chunker = TextChunker()
        assertTrue(chunker.chunk("").isEmpty())
        assertTrue(chunker.chunk("   \n\t  ").isEmpty())
    }

    @Test
    fun testShortText_ReturnsSingleChunk() {
        val chunker = TextChunker(chunkSize = 200, chunkOverlap = 30)
        val text = "Solus runs private local AI models on your phone."
        val chunks = chunker.chunk(text)
        assertEquals(1, chunks.size)
        assertEquals(text, chunks[0].text)
        assertEquals(0, chunks[0].index)
    }

    @Test
    fun testLongText_SplitsIntoMultipleChunksWithOverlap() {
        val chunker = TextChunker(chunkSize = 100, chunkOverlap = 25)
        val text = "The quick brown fox jumps over the lazy dog. " +
            "Artificial intelligence on device enables privacy, security, and low latency. " +
            "Local neural network inference without cloud servers gives users complete data sovereignty."

        val chunks = chunker.chunk(text)
        assertTrue("Should produce multiple chunks, got ${chunks.size}", chunks.size >= 3)
        assertTrue(chunks.all { it.text.length <= 120 })

        // Check sequential index ordering
        for (i in chunks.indices) {
            assertEquals(i, chunks[i].index)
        }
    }

    @Test
    fun testParagraphBoundaries_PreferredForSplitting() {
        val chunker = TextChunker(chunkSize = 60, chunkOverlap = 10)
        val p1 = "First paragraph discussing the company overview."
        val p2 = "Second paragraph detailing financial earnings and results."
        val fullText = "$p1\n\n$p2"

        val chunks = chunker.chunk(fullText)
        assertTrue(chunks.size >= 2)
        assertTrue(chunks[0].text.contains("First paragraph"))
        assertTrue(chunks[1].text.contains("Second paragraph"))
    }
}
