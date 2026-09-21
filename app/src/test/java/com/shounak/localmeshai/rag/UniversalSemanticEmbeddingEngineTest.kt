package com.shounak.localmeshai.rag

import com.shounak.localmeshai.rag.embedding.UniversalSemanticEmbeddingEngine
import com.shounak.localmeshai.rag.embedding.VectorEmbedding
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UniversalSemanticEmbeddingEngineTest {

    private val engine = UniversalSemanticEmbeddingEngine()

    @Test
    fun testEmbeddingDimension_Is384() = runBlocking {
        val emb = engine.embed("Solus private on-device artificial intelligence")
        assertEquals(384, emb.size)
    }

    @Test
    fun testEmbedding_ProducesUnitLengthVector() = runBlocking {
        val emb = engine.embed("Semantic vector similarity retrieval")
        val mag = VectorEmbedding.magnitude(emb)
        assertEquals(1.0f, mag, 1e-4f)
    }

    @Test
    fun testEmbedding_IsDeterministic() = runBlocking {
        val text = "Retrieval-Augmented Generation test query"
        val emb1 = engine.embed(text)
        val emb2 = engine.embed(text)
        val sim = VectorEmbedding.cosineSimilarity(emb1, emb2)
        assertEquals(1.0f, sim, 1e-5f)
    }

    @Test
    fun testEmptyOrBlankString_ReturnsZeroVector() = runBlocking {
        val embEmpty = engine.embed("")
        val embBlank = engine.embed("    ")
        assertEquals(0.0f, VectorEmbedding.magnitude(embEmpty), 1e-5f)
        assertEquals(0.0f, VectorEmbedding.magnitude(embBlank), 1e-5f)
    }

    @Test
    fun testSemanticDiscrimination_RelatedScoresHigherThanUnrelated() = runBlocking {
        val financialQuery = engine.embed("What was the company's quarterly revenue and profit in Q3?")
        val financialDoc = engine.embed("The company reported quarterly sales of 45 million and net profit grew 12% in Q3.")
        val cookingDoc = engine.embed("To bake a delicious chocolate fudge cake, mix cocoa powder with fresh strawberries.")

        val financialSim = VectorEmbedding.cosineSimilarity(financialQuery, financialDoc)
        val cookingSim = VectorEmbedding.cosineSimilarity(financialQuery, cookingDoc)

        assertTrue(
            "Expected financial doc similarity ($financialSim) to be substantially higher than cooking doc similarity ($cookingSim)",
            financialSim > cookingSim + 0.25f
        )
        assertTrue("Financial match should have positive similarity", financialSim > 0.40f)
    }

    @Test
    fun testBatchEmbedding() = runBlocking {
        val texts = listOf("First document", "Second document", "Third document")
        val batch = engine.embedBatch(texts)
        assertEquals(3, batch.size)
        assertTrue(batch.all { it.size == 384 })
    }
}
