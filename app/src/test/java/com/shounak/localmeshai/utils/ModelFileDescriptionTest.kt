package com.shounak.localmeshai.utils

import com.shounak.localmeshai.models.*
import org.junit.Assert.*
import org.junit.Test

class ModelFileDescriptionTest {
    @Test fun oldWeightOnlyDescriptionUsesPublishedCompanionCount() {
        val artifacts = listOf("model.safetensors", "config.json", "tokenizer.json", "tokenizer_config.json").map {
            ModelArtifact(ArtifactRole.Main, it, ArtifactSource.PersistedUri("content://test/$it"), 1)
        }
        val model = ModelInfo("id", "model", "1 MB", ModelStatus.Available, ModelType.Text,
            "model.safetensors", description = "Hugging Face model from author/repo (1 file)", backend = "", deviceTarget = "",
            bundle = ModelBundle("id", "model", artifacts, format = ModelFormat.Safetensors))
        assertEquals("Hugging Face model from author/repo (1 model file, 3 companion files)", model.displayDescription)
        assertFalse(model.copy(bundle = null).displayDescription.endsWith("(1 file)"))
    }
}
