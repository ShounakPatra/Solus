package com.shounak.localmeshai.rag

import com.shounak.localmeshai.features.FeatureTestPreferences
import com.shounak.localmeshai.rag.embedding.EmbeddingModels
import com.shounak.localmeshai.utils.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingSettingsTest {
    @Test fun laterAndSuccessfulModelSelectionSurviveReload() {
        val prefs = FeatureTestPreferences()
        val settings = AppSettings(prefs)
        settings.updateSettings { it.copy(embeddingModelPromptSkipped = true) }
        settings.commitEmbeddingModel(EmbeddingModels.BGE_SMALL_EN_V15.id)
        val reloaded = AppSettings(prefs).settings.value
        assertTrue(reloaded.embeddingModelPromptSkipped)
        assertEquals(EmbeddingModels.BGE_SMALL_EN_V15.id, reloaded.ragEmbeddingModel)
        assertTrue(reloaded.enabledToolSkills.contains("calculator"))
    }
}
