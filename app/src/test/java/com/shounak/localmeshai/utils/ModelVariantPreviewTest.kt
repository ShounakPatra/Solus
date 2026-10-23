package com.shounak.localmeshai.utils

import org.junit.Assert.*
import org.junit.Test

class ModelVariantPreviewTest {
    @Test fun previewIncludesCompanionsAndDeduplicatesRepositoryPaths() {
        val weights = RepoFile("model.safetensors", 1000)
        val config = RepoFile("config.json", 20)
        val tokenizer = RepoFile("tokenizer.json", 100)
        val variant = ModelVariant("test", listOf(weights), companionFiles = listOf(config, tokenizer, config))
        assertEquals(listOf(weights, config, tokenizer), variant.downloadFiles)
        assertEquals(1120L, variant.downloadFiles.sumOf { it.sizeBytes })
    }
}
