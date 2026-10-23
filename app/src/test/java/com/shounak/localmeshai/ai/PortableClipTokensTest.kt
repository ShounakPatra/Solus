package com.shounak.localmeshai.ai

import org.junit.Assert.*
import org.junit.Test

class PortableClipTokensTest {
    @Test fun sd2RetainsEosBeforeZeroPadding() {
        val result = OnnxMediaPipeline.clipTokens(intArrayOf(49406, 42, 49407), 49407, 0)
        assertEquals(77, result.size)
        assertArrayEquals(intArrayOf(49406, 42, 49407), result.take(3).toIntArray())
        assertTrue(result.drop(3).all { it == 0 })
    }
    @Test fun longPromptsEndWithEosWhileSd1PadsWithEos() {
        assertEquals(49407, OnnxMediaPipeline.clipTokens(IntArray(100) { 42 }, 49407, 0).last())
        assertTrue(OnnxMediaPipeline.clipTokens(intArrayOf(49406, 49407), 49407, 49407).drop(1).all { it == 49407 })
    }
}
