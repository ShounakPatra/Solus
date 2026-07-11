package com.shounak.localmeshai.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {
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
}
