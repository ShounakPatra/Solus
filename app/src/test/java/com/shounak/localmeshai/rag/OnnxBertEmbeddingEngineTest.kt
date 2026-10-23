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
 * Runs the real bge-small int8 model when SOLUS_BGE_DIR points at a folder containing
 * model_quantized.onnx and vocab.txt; skipped otherwise so CI stays offline.
 */
class OnnxBertEmbeddingEngineTest {
    private val dir = System.getenv("SOLUS_BGE_DIR")?.let(::File)

    @Test
    fun paraphrasedQueryRanksTheRightPassageFirst() = runBlocking {
        assumeTrue(dir != null && EmbeddingModels.isInstalled(dir, EmbeddingModels.BGE_SMALL_EN_V15))
        val engine = EmbeddingModels.createEngine(dir!!, EmbeddingModels.BGE_SMALL_EN_V15)
        try {
            val passages = listOf(
                "The car would not start because the battery was dead, so we called roadside assistance.",
                "Photosynthesis converts sunlight, water and carbon dioxide into glucose and oxygen in plant leaves.",
                "The Treaty of Versailles was signed in 1919, formally ending the First World War."
            )
            val vectors = engine.embedBatch(passages)
            assertEquals(384, vectors.first().size)
            val queries = listOf("my vehicle won't turn on", "how do plants make food", "when did WW1 end")
            queries.forEachIndexed { expected, query ->
                val q = engine.embedQuery(query)
                val scores = vectors.map { VectorEmbedding.cosineSimilarity(q, it) }
                assertEquals(query, expected, scores.indices.maxByOrNull { scores[it] })
                assertTrue("$query should clear the default 0.35 threshold", engine.calibrate(scores[expected]) >= 0.35f)
            }
            val unrelated = engine.embedQuery("best pizza recipe")
            assertTrue(vectors.all { engine.calibrate(VectorEmbedding.cosineSimilarity(unrelated, it)) < 0.35f })
        } finally {
            engine.release()
        }
    }
}
