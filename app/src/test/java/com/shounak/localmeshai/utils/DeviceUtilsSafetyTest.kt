package com.shounak.localmeshai.utils

import com.shounak.localmeshai.models.ModelCatalog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceUtilsSafetyTest {
    @Test
    fun smallModelsAllowedOnLowRamDevice() {
        val qwen05b = ModelCatalog.defaultModels.single { it.id == "qwen25_05b_gguf" }
        val smollm135m = ModelCatalog.defaultModels.single { it.id == "smollm2_135m_gguf" }

        // 3.8 GB RAM device
        val (allowed05, _) = DeviceUtils.checkModelSafetyProfileForRam(3.8, qwen05b)
        assertTrue(allowed05)

        val (allowed135, _) = DeviceUtils.checkModelSafetyProfileForRam(3.8, smollm135m)
        assertTrue(allowed135)
    }

    @Test
    fun largeModelsBlockedOnLowRamDevice() {
        val qwen7b = ModelCatalog.defaultModels.single { it.id == "qwen25_7b_gguf" }
        val llama8b = ModelCatalog.defaultModels.single { it.id == "llama31_8b_gguf" }

        // 4.0 GB RAM device
        val (allowed7b, reason7b) = DeviceUtils.checkModelSafetyProfileForRam(4.0, qwen7b)
        assertFalse(allowed7b)
        assertTrue(reason7b.contains("blocked by default", ignoreCase = true))

        val (allowed8b, reason8b) = DeviceUtils.checkModelSafetyProfileForRam(4.0, llama8b)
        assertFalse(allowed8b)
        assertTrue(reason8b.contains("blocked by default", ignoreCase = true))
    }

    @Test
    fun largeModelsAllowedOnHighRamDevice() {
        val qwen7b = ModelCatalog.defaultModels.single { it.id == "qwen25_7b_gguf" }

        // 12.0 GB RAM device
        val (allowed7b, _) = DeviceUtils.checkModelSafetyProfileForRam(12.0, qwen7b)
        assertTrue(allowed7b)
    }
}
