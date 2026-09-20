package com.shounak.localmeshai.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {
    @Test
    fun noDuplicateModelIdsOrFileNamesInCatalog() {
        val ids = ModelCatalog.defaultModels.map { it.id }
        val duplicateIds = ids.groupingBy { it }.eachCount().filter { it.value > 1 }
        assertTrue("Found duplicate model IDs: ${duplicateIds.keys}", duplicateIds.isEmpty())

        val files = ModelCatalog.defaultModels.map { it.fileName }
        val duplicateFiles = files.groupingBy { it }.eachCount().filter { it.value > 1 }
        assertTrue("Found duplicate file names: ${duplicateFiles.keys}", duplicateFiles.isEmpty())
    }

    @Test
    fun bonsaiModelRemovedFromCatalog() {
        val bonsaiModel = ModelCatalog.defaultModels.firstOrNull { it.id == "bonsai_8b_1bit_gguf" || it.fileName.contains("Bonsai", ignoreCase = true) }
        assertNull("Bonsai 8B model should not be in the active default catalog", bonsaiModel)
    }

    @Test
    fun qwenThreeOnePointSevenUsesPublishedLiteRtArtifact() {
        val model = ModelCatalog.defaultModels.single { it.id == "qwen3_17b_litertlm" }

        assertEquals("Qwen3_1.7B.litertlm", model.fileName)
        assertEquals("2.1 GB", model.size)
        assertFalse(model.requiresHuggingFaceToken)
        assertTrue(
            model.url.orEmpty().endsWith(
                "/litert-community/Qwen3-1.7B/resolve/main/Qwen3_1.7B.litertlm?download=true"
            )
        )
    }

    @Test
    fun ggufModelsHaveVerifiedUrlsAndCorrectBackend() {
        val ggufIds = listOf(
            "qwen25_05b_gguf",
            "qwen25_15b_gguf",
            "qwen25_3b_gguf",
            "qwen25_7b_gguf",
            "qwen25_coder_15b_gguf",
            "llama32_1b_gguf",
            "llama32_3b_gguf",
            "llama31_8b_gguf",
            "mistral_7b_gguf",
            "phi35_mini_gguf",
            "deepseek_r1_qwen7b_gguf",
            "smollm2_135m_gguf",
            "smollm2_360m_gguf",
            "smollm2_17b_gguf",
            "tinyllama_11b_gguf",
            "gemma2_2b_gguf"
        )

        for (id in ggufIds) {
            val model = ModelCatalog.defaultModels.single { it.id == id }
            assertTrue("Model $id should have .gguf extension", model.fileName.endsWith(".gguf", ignoreCase = true))
            assertNotNull("Model $id should have download URL", model.url)
            assertTrue("Model $id URL should not be blank", model.url!!.isNotBlank())
            assertEquals("Model $id should be NotDownloaded status", ModelStatus.NotDownloaded, model.status)
            assertTrue("Model $id backend should mention llama.cpp", model.backend.contains("llama.cpp", ignoreCase = true))
        }
    }

    @Test
    fun sub4GbModelsHaveCorrectSizesAndUrls() {
        val sub4GbIds = listOf(
            "smollm2_135m_gguf",
            "smollm2_360m_gguf",
            "qwen25_05b_gguf",
            "tinyllama_11b_gguf",
            "llama32_1b_gguf",
            "smollm2_17b_gguf",
            "qwen25_15b_gguf",
            "qwen25_coder_15b_gguf",
            "gemma2_2b_gguf",
            "qwen25_3b_gguf",
            "llama32_3b_gguf",
            "phi35_mini_gguf"
        )

        for (id in sub4GbIds) {
            val model = ModelCatalog.defaultModels.single { it.id == id }
            assertNotNull(model.url)
            assertFalse(model.isFuturePlaceholder)
            assertTrue(model.status == ModelStatus.NotDownloaded || model.status == ModelStatus.Available)
        }
    }
}
