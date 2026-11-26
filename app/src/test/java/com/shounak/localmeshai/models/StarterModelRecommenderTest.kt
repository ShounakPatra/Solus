package com.shounak.localmeshai.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class StarterModelRecommenderTest {

    @Test
    fun lowRamDeviceSelectsUltraLightStarterModelWithoutToken() {
        val rec = StarterModelRecommender.recommend(
            totalRamGb = 4.0,
            availableRamMb = 1_200L
        )
        assertNotNull(rec)
        assertEquals("smollm2_135m_gguf", rec!!.model.id)
        assertFalse(rec.model.requiresHuggingFaceToken)
    }

    @Test
    fun midRamDeviceSelectsCompactStarterModel() {
        val rec = StarterModelRecommender.recommend(
            totalRamGb = 6.0,
            availableRamMb = 2_200L
        )
        assertNotNull(rec)
        assertEquals("smollm2_360m_gguf", rec!!.model.id)
        assertFalse(rec.model.requiresHuggingFaceToken)
    }

    @Test
    fun eightGbDeviceSelectsShaVerifiedOneBillionStarterModel() {
        val rec = StarterModelRecommender.recommend(
            totalRamGb = 8.0,
            availableRamMb = 3_800L
        )
        assertNotNull(rec)
        assertEquals("llama32_1b_gguf", rec!!.model.id)
        assertFalse(rec.model.requiresHuggingFaceToken)
        assertFalse(rec.model.sha256.isNullOrBlank())
    }
}
