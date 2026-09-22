package com.shounak.localmeshai.models

import com.shounak.localmeshai.ui.screens.SizeTier
import com.shounak.localmeshai.ui.screens.downloadTime
import com.shounak.localmeshai.ui.screens.matches
import com.shounak.localmeshai.ui.screens.searchRelevance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSearchAndSortingTest {

    private fun createTestModel(
        id: String,
        name: String,
        status: ModelStatus = ModelStatus.NotDownloaded,
        downloadedAt: Long = 0L,
        description: String = "Test model description",
        backend: String = "llama.cpp",
        fileName: String = "$id.gguf",
        isRecommended: Boolean = false,
        localPath: String? = null
    ): ModelInfo {
        return ModelInfo(
            id = id,
            name = name,
            size = "2.0 GB",
            status = status,
            type = ModelType.Text,
            fileName = fileName,
            description = description,
            backend = backend,
            deviceTarget = "CPU / Vulkan",
            downloadedAt = downloadedAt,
            isRecommended = isRecommended,
            localPath = localPath
        )
    }

    @Test
    fun matchesFindsModelsByMultiWordQueries() {
        val qwen = createTestModel("qwen25_7b", "Qwen 2.5 7B Instruct")
        val deepseek = createTestModel("deepseek_r1", "DeepSeek R1 Distill Qwen 1.5B")
        val llama = createTestModel("llama32_1b", "Llama 3.2 1B Instruct")

        // Multi-word non-contiguous search
        assertTrue(qwen.matches("qwen 7b", SizeTier.All))
        assertTrue(deepseek.matches("deepseek r1", SizeTier.All))
        assertTrue(deepseek.matches("qwen 1.5b", SizeTier.All))
        assertTrue(llama.matches("llama 1b", SizeTier.All))

        // Single word search
        assertTrue(qwen.matches("qwen", SizeTier.All))
        assertTrue(llama.matches("instruct", SizeTier.All))

        // Normalized search (no space)
        assertTrue(llama.matches("llama3.2", SizeTier.All))

        // Non-matching query
        assertFalse(llama.matches("qwen", SizeTier.All))
        assertFalse(qwen.matches("mistral", SizeTier.All))
    }

    @Test
    fun searchRelevanceRanksCloserMatchesHigher() {
        val exactMatch = createTestModel("qwen25_7b", "Qwen 2.5 7B")
        val prefixMatch = createTestModel("qwen25_7b_inst", "Qwen 2.5 7B Instruct")
        val containsMatch = createTestModel("deepseek_qwen", "DeepSeek R1 Distill Qwen 1.5B")
        val descOnlyMatch = createTestModel("other_model", "Other Model", description = "Powered by qwen architecture")

        val query = "qwen"
        val exactScore = exactMatch.searchRelevance(query)
        val prefixScore = prefixMatch.searchRelevance(query)
        val containsScore = containsMatch.searchRelevance(query)
        val descScore = descOnlyMatch.searchRelevance(query)

        assertTrue("Prefix match should score higher than inner contains match", prefixScore > containsScore)
        assertTrue("Name match should score higher than description match", containsScore > descScore)
    }

    @Test
    fun searchRelevanceIsStrictlyTextMatchOnlyNoDownloadedPriority() {
        val remoteModel = createTestModel("llama32_1b", "Llama 3.2 1B", status = ModelStatus.NotDownloaded)
        val downloadedModel = createTestModel("llama32_1b_local", "Llama 3.2 1B", status = ModelStatus.Available, localPath = "/tmp/llama.gguf")

        val query = "llama"
        assertEquals(
            "Downloaded model and remote model with identical name must have identical search relevance (no downloaded priority)",
            remoteModel.searchRelevance(query),
            downloadedModel.searchRelevance(query)
        )
    }

    @Test
    fun mostRecentlyDownloadedModelAppearsAtTop() {
        val now = System.currentTimeMillis()
        val olderDownload = createTestModel(
            id = "model_old",
            name = "Model Old",
            status = ModelStatus.Available,
            downloadedAt = now - 60_000L, // 1 minute ago
            localPath = "/data/model_old.gguf"
        )
        val newerDownload = createTestModel(
            id = "model_new",
            name = "Model New",
            status = ModelStatus.Available,
            downloadedAt = now, // Just now
            localPath = "/data/model_new.gguf"
        )
        val oldestDownload = createTestModel(
            id = "model_oldest",
            name = "Model Oldest",
            status = ModelStatus.Available,
            downloadedAt = now - 3600_000L, // 1 hour ago
            localPath = "/data/model_oldest.gguf"
        )

        val downloadedList = listOf(oldestDownload, olderDownload, newerDownload)
        val sortedList = downloadedList.sortedByDescending { it.downloadTime() }

        assertEquals("Newer download must be first", "model_new", sortedList[0].id)
        assertEquals("Older download must be second", "model_old", sortedList[1].id)
        assertEquals("Oldest download must be last", "model_oldest", sortedList[2].id)
    }

    @Test
    fun searchResultsUnifiedListHasNoDivision() {
        val query = "qwen"
        val models = listOf(
            createTestModel("deepseek_r1", "DeepSeek R1 Distill Qwen 1.5B", status = ModelStatus.NotDownloaded),
            createTestModel("qwen25_7b", "Qwen 2.5 7B Instruct", status = ModelStatus.Available, downloadedAt = 1000L, localPath = "/data/qwen.gguf"),
            createTestModel("llama32_1b", "Llama 3.2 1B Instruct", status = ModelStatus.Available, downloadedAt = 2000L, localPath = "/data/llama.gguf")
        )

        // Filter and sort unified search results strictly by text match relevance
        val matchingModels = models
            .filter { it.matches(query, SizeTier.All) }
            .sortedWith(
                compareByDescending<ModelInfo> { it.searchRelevance(query) }
                    .thenBy { it.name }
            )

        // Llama does not match 'qwen', so only Qwen and DeepSeek match
        assertEquals(2, matchingModels.size)
        // Qwen 2.5 7B Instruct matches prefix (score 800), DeepSeek contains Qwen (score 600)
        assertEquals("qwen25_7b", matchingModels[0].id)
        assertEquals("deepseek_r1", matchingModels[1].id)
    }
}
