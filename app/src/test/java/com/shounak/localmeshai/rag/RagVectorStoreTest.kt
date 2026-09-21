package com.shounak.localmeshai.rag

import com.shounak.localmeshai.rag.embedding.VectorEmbedding
import com.shounak.localmeshai.rag.store.InMemoryRagVectorStore
import com.shounak.localmeshai.rag.store.RagChunkRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RagVectorStoreTest {

    @Test
    fun testAddAndSearch_ReturnsRankedResults() {
        val store = InMemoryRagVectorStore(null)

        val v1 = VectorEmbedding.l2Normalize(floatArrayOf(1f, 0f, 0f))
        val v2 = VectorEmbedding.l2Normalize(floatArrayOf(0.7f, 0.7f, 0f))
        val v3 = VectorEmbedding.l2Normalize(floatArrayOf(0f, 1f, 0f))

        val chunks = listOf(
            RagChunkRecord("1", "docA", "Report.pdf", 0, "First chunk", v1),
            RagChunkRecord("2", "docA", "Report.pdf", 1, "Second chunk", v2),
            RagChunkRecord("3", "docB", "Notes.txt", 0, "Third chunk", v3)
        )

        store.addChunks(chunks)
        assertEquals(3, store.getTotalChunkCount())

        val query = VectorEmbedding.l2Normalize(floatArrayOf(1f, 0f, 0f))
        val results = store.search(query, topK = 2, minSimilarity = 0.5f)

        assertEquals(2, results.size)
        // Chunk 1 has similarity 1.0 (highest)
        assertEquals("1", results[0].chunk.id)
        assertEquals(1.0f, results[0].similarity, 1e-4f)

        // Chunk 2 has similarity ~0.707
        assertEquals("2", results[1].chunk.id)
        assertTrue(results[1].similarity > 0.6f)
    }

    @Test
    fun testMinSimilarity_FiltersLowScoringChunks() {
        val store = InMemoryRagVectorStore(null)

        val v1 = VectorEmbedding.l2Normalize(floatArrayOf(1f, 0f, 0f))
        val v2 = VectorEmbedding.l2Normalize(floatArrayOf(0f, 1f, 0f)) // Orthogonal: similarity = 0.0

        val chunks = listOf(
            RagChunkRecord("1", "docA", "Report.pdf", 0, "Chunk 1", v1),
            RagChunkRecord("2", "docA", "Report.pdf", 1, "Chunk 2", v2)
        )
        store.addChunks(chunks)

        val query = VectorEmbedding.l2Normalize(floatArrayOf(1f, 0f, 0f))
        val results = store.search(query, topK = 5, minSimilarity = 0.5f)

        assertEquals(1, results.size)
        assertEquals("1", results[0].chunk.id)
    }

    @Test
    fun testRemoveDocument_DeletesOnlyTargetDoc() {
        val store = InMemoryRagVectorStore(null)
        val v = floatArrayOf(1f, 0f)

        store.addChunks(
            listOf(
                RagChunkRecord("1", "doc1", "Doc1.txt", 0, "C1", v),
                RagChunkRecord("2", "doc1", "Doc1.txt", 1, "C2", v),
                RagChunkRecord("3", "doc2", "Doc2.txt", 0, "C3", v)
            )
        )
        assertEquals(3, store.getTotalChunkCount())

        val removed = store.removeDocument("doc1")
        assertTrue(removed)
        assertEquals(1, store.getTotalChunkCount())
        assertEquals("doc2", store.getDocuments().first().documentId)
    }

    @Test
    fun testClearAll_EmptiesStore() {
        val store = InMemoryRagVectorStore(null)
        store.addChunks(
            listOf(
                RagChunkRecord("1", "doc1", "Doc1.txt", 0, "C1", floatArrayOf(1f))
            )
        )
        assertEquals(1, store.getTotalChunkCount())

        store.clearAll()
        assertEquals(0, store.getTotalChunkCount())
        assertTrue(store.getDocuments().isEmpty())
    }
}
