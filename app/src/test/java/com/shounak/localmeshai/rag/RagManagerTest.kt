package com.shounak.localmeshai.rag

import com.shounak.localmeshai.rag.embedding.UniversalSemanticEmbeddingEngine
import com.shounak.localmeshai.rag.store.InMemoryRagVectorStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RagManagerTest {

    @Test
    fun testIngestTextAndRetrieve_EndToEnd() = runBlocking {
        val manager = RagManager(
            context = null,
            embeddingEngine = UniversalSemanticEmbeddingEngine(),
            vectorStore = InMemoryRagVectorStore(null)
        )

        val documentText = """
            Quarterly Financial Report Q3 2026.
            Solus Technologies announced strong operating profit of 25.4 million dollars in Q3.
            User adoption increased by 45 percent across Android on-device deployments.
            Capital expenditures remained focused on neural network acceleration and Vulkan graphics shaders.
        """.trimIndent()

        val ingestion = manager.ingestText("Solus_Q3_Report.txt", documentText)
        assertTrue(ingestion.success)
        assertTrue(ingestion.chunkCount >= 1)
        assertEquals("Solus_Q3_Report.txt", ingestion.documentName)

        val retrieval = manager.retrieve("What was the operating profit in Q3?", topK = 2, minScore = 0.25f)
        assertTrue("Expected matching context to be retrieved", retrieval.hasContext)
        assertEquals(1, retrieval.sourceNames.size)
        assertEquals("Solus_Q3_Report.txt", retrieval.sourceNames[0])
        assertTrue("Match score should be positive", retrieval.topMatchScore > 0.25f)

        val augmentedPrompt = manager.buildAugmentedPrompt("What was the operating profit in Q3?", retrieval)
        assertTrue(augmentedPrompt.contains("[Relevant Document Context]"))
        assertTrue(augmentedPrompt.contains("Solus_Q3_Report.txt"))
        assertTrue(augmentedPrompt.contains("What was the operating profit in Q3?"))
    }

    @Test
    fun testRetrieveWithNoIndexedDocuments_ReturnsEmpty() = runBlocking {
        val manager = RagManager(
            context = null,
            embeddingEngine = UniversalSemanticEmbeddingEngine(),
            vectorStore = InMemoryRagVectorStore(null)
        )

        val retrieval = manager.retrieve("Any query when store is empty")
        assertFalse(retrieval.hasContext)
        assertTrue(retrieval.matches.isEmpty())

        val prompt = manager.buildAugmentedPrompt("Direct query", retrieval)
        assertEquals("Direct query", prompt)
    }

    @Test
    fun testClearKnowledgeBase() = runBlocking {
        val manager = RagManager(
            context = null,
            embeddingEngine = UniversalSemanticEmbeddingEngine(),
            vectorStore = InMemoryRagVectorStore(null)
        )

        manager.ingestText("Doc1.txt", "Some relevant content here.")
        assertTrue(manager.totalIndexedChunks > 0)

        manager.clearKnowledgeBase()
        assertEquals(0, manager.totalIndexedChunks)
        assertTrue(manager.indexedDocuments.isEmpty())
    }
}
