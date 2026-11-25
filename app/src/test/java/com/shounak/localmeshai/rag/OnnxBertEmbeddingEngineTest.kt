package com.shounak.localmeshai.rag

import com.shounak.localmeshai.rag.embedding.EmbeddingModels
import com.shounak.localmeshai.rag.embedding.VectorEmbedding
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs the real potion-base-2M model when SOLUS_POTION_DIR points at a folder containing
 * model.onnx and vocab.txt; skipped otherwise so CI stays offline.
 */
class OnnxBertEmbeddingEngineTest {
    private val dir = (System.getenv("SOLUS_POTION_DIR") ?: System.getenv("SOLUS_BGE_DIR"))?.let(::File)

    @Test
    fun paraphrasedQueryRanksTheRightPassageFirst() = runBlocking {
        val spec = EmbeddingModels.POTION_BASE_2M
        assumeTrue(dir != null && EmbeddingModels.isInstalled(dir, spec))
        val engine = EmbeddingModels.createEngine(dir!!, spec)
        try {
            val passages = listOf(
                "The car would not start because the battery was dead, so we called roadside assistance.",
                "Photosynthesis converts sunlight, water and carbon dioxide into glucose and oxygen in plant leaves.",
                "The Treaty of Versailles was signed in 1919, formally ending the First World War."
            )
            val vectors = engine.embedBatch(passages)
            assertEquals(64, vectors.first().size)
            val queries = listOf("my vehicle won't turn on", "how do plants make food", "when did WW1 end")
            queries.forEachIndexed { expected, query ->
                val q = engine.embedQuery(query)
                val scores = vectors.map { VectorEmbedding.cosineSimilarity(q, it) }
                assertEquals(query, expected, scores.indices.maxByOrNull { scores[it] })
                assertTrue("$query should clear the default 0.25 threshold", engine.calibrate(scores[expected]) >= 0.25f)
            }
            val unrelated = engine.embedQuery("best pizza recipe")
            assertTrue(vectors.all { engine.calibrate(VectorEmbedding.cosineSimilarity(unrelated, it)) < 0.30f })
        } finally {
            engine.release()
        }
    }
}
