package com.shounak.localmeshai.rag

import com.shounak.localmeshai.rag.embedding.VectorEmbedding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorEmbeddingTest {

    @Test
    fun testDotProduct_IdenticalVectors() {
        val a = floatArrayOf(1f, 2f, 3f)
        val b = floatArrayOf(1f, 2f, 3f)
        val dot = VectorEmbedding.dotProduct(a, b)
        assertEquals(14f, dot, 1e-5f)
    }

    @Test
    fun testDotProduct_OrthogonalVectors() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(0f, 1f, 0f)
        val dot = VectorEmbedding.dotProduct(a, b)
        assertEquals(0f, dot, 1e-5f)
    }

    @Test
    fun testMagnitude_UnitVector() {
        val a = floatArrayOf(0f, 3f, 4f)
        val mag = VectorEmbedding.magnitude(a)
        assertEquals(5f, mag, 1e-5f)
    }

    @Test
    fun testL2Normalize_ProducesUnitMagnitude() {
        val a = floatArrayOf(2f, -3f, 6f)
        val norm = VectorEmbedding.l2Normalize(a)
        val mag = VectorEmbedding.magnitude(norm)
        assertEquals(1.0f, mag, 1e-5f)
    }

    @Test
    fun testL2Normalize_ZeroVectorDoesNotThrow() {
        val zero = floatArrayOf(0f, 0f, 0f)
        val norm = VectorEmbedding.l2Normalize(zero)
        assertEquals(0f, VectorEmbedding.magnitude(norm), 1e-5f)
    }

    @Test
    fun testCosineSimilarity_IdenticalVectorsEqualsOne() {
        val a = floatArrayOf(1.5f, 2.5f, -3.5f)
        val sim = VectorEmbedding.cosineSimilarity(a, a)
        assertEquals(1.0f, sim, 1e-4f)
    }

    @Test
    fun testCosineSimilarity_OppositeVectorsEqualsMinusOne() {
        val a = floatArrayOf(1f, 2f, 3f)
        val b = floatArrayOf(-1f, -2f, -3f)
        val sim = VectorEmbedding.cosineSimilarity(a, b)
        assertEquals(-1.0f, sim, 1e-4f)
    }

    @Test
    fun testCosineSimilarity_OrthogonalVectorsEqualsZero() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(0f, 1f, 0f)
        val sim = VectorEmbedding.cosineSimilarity(a, b)
        assertEquals(0.0f, sim, 1e-4f)
    }

    @Test
    fun testVectorEmbedding_InstanceMethods() {
        val embA = VectorEmbedding(floatArrayOf(1f, 0f, 0f))
        val embB = VectorEmbedding(floatArrayOf(1f, 0f, 0f))
        assertEquals(1.0f, embA.cosineSimilarity(embB), 1e-4f)
        assertEquals(1.0f, embA.magnitude(), 1e-4f)
        assertEquals(embA, embB)
    }
}
