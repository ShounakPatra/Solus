package com.shounak.localmeshai.ai

import kotlin.math.ln
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class PortableSamplerTest {
    @Test fun greedyChoosesArgmaxWithStableTiesAndIgnoresNonFiniteLogits() {
        val logits = floatArrayOf(Float.NaN, 2f, Float.POSITIVE_INFINITY, 5f, 5f, Float.NEGATIVE_INFINITY)
        assertEquals(3, PortableSampler.sample(logits, 0f, 0.95f, 0))
        assertEquals(3, PortableSampler.sample(logits, -1f, 0.95f, 40))
        val large = FloatArray(150_000) { it.toFloat() }
        assertEquals(149_999, PortableSampler.sample(large, 0f, 1f, 0))
        assertArrayEquals(intArrayOf(149_999, 149_998, 149_997, 149_996), PortableSampler.candidates(large, 4))
    }

    @Test fun primitiveHeapMatchesFullSortForVariedVocabulariesAndLimits() {
        val random = Random(41)
        for (size in listOf(0, 1, 4, 40, 1000)) {
            val logits = FloatArray(size) { if (it % 19 == 0) Float.NaN else random.nextInt(-20, 20).toFloat() }
            val reference = logits.indices.filter { logits[it].isFinite() }.sortedByDescending { logits[it] }
            for (limit in listOf(0, 1, 4, 40, size + 5)) {
                assertArrayEquals(reference.take(limit).toIntArray(), PortableSampler.candidates(logits, limit))
            }
        }
    }

    @Test fun topKAndNucleusDoNotSelectExcludedTokens() {
        val logits = floatArrayOf(-100f, 4f, 5f, 3f, Float.NaN)
        val random = Random(12)
        repeat(100) {
            assertTrue(PortableSampler.sample(logits, 1f, 1f, 2, random) in setOf(1, 2))
            assertEquals(2, PortableSampler.sample(logits, 1f, 0.01f, 0, random))
            assertEquals(2, PortableSampler.sample(logits, 1f, 1f, 1, random))
        }
    }

    @Test fun unrestrictedSoftmaxKeepsItsDistributionWithoutSorting() {
        val logits = floatArrayOf(ln(0.2).toFloat(), ln(0.3).toFloat(), ln(0.5).toFloat(), Float.NaN)
        for (topK in listOf(0, 3)) {
            val random = Random(57)
            val counts = IntArray(4)
            repeat(10_000) { counts[PortableSampler.sample(logits, 1f, 1f, topK, random)]++ }
            assertEquals(0.2, counts[0] / 10_000.0, 0.025)
            assertEquals(0.3, counts[1] / 10_000.0, 0.025)
            assertEquals(0.5, counts[2] / 10_000.0, 0.025)
            assertEquals(0, counts[3])
        }
    }

    @Test fun invalidLogitsFailClearlyAndExtremeFiniteValuesStayStable() {
        for (logits in listOf(floatArrayOf(), floatArrayOf(Float.NaN, Float.NEGATIVE_INFINITY))) {
            for (temperature in listOf(0f, 1f)) for (topK in listOf(0, 1)) {
                val error = assertThrows(IllegalArgumentException::class.java) {
                    PortableSampler.sample(logits, temperature, 1f, topK)
                }
                assertTrue(error.message.orEmpty().contains("no finite logits"))
            }
        }
        for (topK in listOf(0, 2)) {
            assertEquals(1, PortableSampler.sample(floatArrayOf(-Float.MAX_VALUE, Float.MAX_VALUE), Float.MIN_VALUE, 1f, topK, Random(1)))
        }
    }
}
