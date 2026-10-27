package com.shounak.localmeshai.models

import com.shounak.localmeshai.ui.screens.ModelSourceFilter
import com.shounak.localmeshai.ui.screens.SizeTier
import com.shounak.localmeshai.ui.screens.downloadTime
import com.shounak.localmeshai.ui.screens.downloadedSectionSortRank
import com.shounak.localmeshai.ui.screens.isPinnedDownloadCard
import com.shounak.localmeshai.ui.screens.matches
import com.shounak.localmeshai.ui.screens.searchRelevance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSearchAndSortingTest {
    @Test fun newestActiveDownloadLeadsRegardlessOfCatalogOrderAndProgress() {
        val older = createTestModel("older", "Older", ModelStatus.Downloading).copy(downloadStartedAt = 100L)
        val newer = createTestModel("newer", "Newer", ModelStatus.Downloading).copy(downloadStartedAt = 101L)
        val ready = createTestModel("ready", "Ready", ModelStatus.Available)
        val ordered = com.shounak.localmeshai.ui.screens.orderDownloadedModels(
            listOf(older.copy(progress = 0.9f), ready, newer.copy(progress = 0.1f)), mapOf("ready" to 200L))
        assertEquals(listOf("newer", "older", "ready"), ordered.map { it.id })
    }

    @Test fun activeDownloadsLeadWithoutReorderingTheOtherCards() {
        val current = createTestModel("current", "Current", ModelStatus.Available, 20L)
        val previous = createTestModel("previous", "Previous", ModelStatus.Available, 10L)
        val failed = createTestModel("failed", "Failed", ModelStatus.Failed, 40L)
        val other = createTestModel("other", "Other", ModelStatus.Available, 30L)
        val paused = createTestModel("paused", "Paused", ModelStatus.Paused)
        val used = mapOf("current" to 200L, "previous" to 100L)
        val unchanged = listOf(other, previous, failed, paused, current)
        val first = createTestModel("first", "First", ModelStatus.Downloading)
        val second = createTestModel("second", "Second", ModelStatus.Downloading)
        val ordered = com.shounak.localmeshai.ui.screens.orderDownloadedModels(
            unchanged + first + second, used)
        assertEquals(listOf("first", "second"), ordered.take(2).map { it.id })
        assertEquals(
            com.shounak.localmeshai.ui.screens.orderDownloadedModels(unchanged, used),
            ordered.drop(2))
    }

    @Test fun completedDownloadsJoinRecencyAndLaterLoadsStillTakePriority() {
        val current = createTestModel("current", "Current", ModelStatus.Available, 10L)
        val previous = createTestModel("previous", "Previous", ModelStatus.Available, 20L)
        val first = createTestModel("first", "First", ModelStatus.Available, 300L)
        val second = createTestModel("second", "Second", ModelStatus.Available, 400L)
        val failed = createTestModel("failed", "Failed", ModelStatus.Failed)
        val models = listOf(previous, failed, second, first, current)
        val usage = mapOf("current" to 200L, "previous" to 100L, "first" to 301L, "second" to 401L)
        fun order(used: Map<String, Long>) =
            com.shounak.localmeshai.ui.screens.orderDownloadedModels(models, used).map { it.id }
        assertEquals(listOf("second", "first", "current", "previous", "failed"), order(usage))
        assertEquals(listOf("previous", "second", "first", "current", "failed"),
            order(usage + ("previous" to 500L)))
    }

    @Test fun parameterFiltersUseDeclaredCountsInsteadOfDownloadSize() {
        val unknown = createTestModel("unknown", "Custom model").copy(size = "8.0 GB")
        assertFalse(unknown.matches("", SizeTier.Large))
        assertTrue(unknown.matches("", SizeTier.All))
        assertTrue(createTestModel("tiny", "SmolLM 135M").matches("", SizeTier.Small))
        assertTrue(createTestModel("mid", "Qwen 6.999B").matches("", SizeTier.Medium))
        assertFalse(createTestModel("boundary", "Qwen 7B").matches("", SizeTier.Medium))
        assertTrue(createTestModel("boundary", "Qwen 7B").matches("", SizeTier.Large))
        assertTrue(createTestModel("large", "Model 70B").matches("", SizeTier.Huge))
    }
    @Test fun unavailableModelsCannotUseRecencyOrNewDownloadsToJumpAboveAvailableModels() {
        val ready = createTestModel("ready", "Ready", ModelStatus.Available, 10L)
        val failed = createTestModel("failed", "Failed", ModelStatus.Failed, 300L)
        val blocked = createTestModel("blocked", "Blocked", ModelStatus.Blocked, 400L)
        val sorted = com.shounak.localmeshai.ui.screens.orderDownloadedModels(
            listOf(blocked, failed, ready), mapOf("ready" to 20L, "failed" to 500L, "blocked" to 600L))
        assertEquals(listOf("ready", "failed", "blocked"), sorted.map { it.id })
        assertEquals("ready", com.shounak.localmeshai.ui.screens.orderDownloadedModels(
            listOf(blocked, ready), emptyMap()).first().id)
    }

    @Test fun downloadedOrderRemembersLastTwoLoadsAndPromotesNewDownloads() {
        val previous = createTestModel("previous", "Previous", ModelStatus.Available, 10L)
        val current = createTestModel("current", "Current", ModelStatus.Available, 20L)
        val other = createTestModel("other", "Other", ModelStatus.Available, 30L)
        val failed = createTestModel("failed", "Failed", ModelStatus.Failed, 40L)
        val models = listOf(other, previous, failed, current)
        val used = mapOf("previous" to 100L, "current" to 200L)
        fun order(items: List<ModelInfo>, usage: Map<String, Long>) =
            com.shounak.localmeshai.ui.screens.orderDownloadedModels(items, usage).map { it.id }
        // Unloading does not erase recency; no active selection is needed to restore this order.
        assertEquals(listOf("current", "previous", "failed", "other"), order(models, used))
        val downloaded = other.copy(downloadedAt = 300L)
        assertEquals(listOf("other", "current", "previous", "failed"), order(listOf(downloaded, previous, failed, current), used))
        assertEquals(listOf("previous", "current", "failed", "other"), order(models, used + ("previous" to 400L)))
        assertEquals("other", order(listOf(previous, current, other), emptyMap()).first())
        assertFalse(order(listOf(current, other), used).contains("previous"))
    }


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

    @Test
    fun searchMatchingModelsReflectsDownloadingStateAndProgress() {
        val query = "qwen"
        val models = mutableListOf(
            createTestModel("qwen25_7b", "Qwen 2.5 7B Instruct", status = ModelStatus.NotDownloaded),
            createTestModel("deepseek_r1", "DeepSeek R1 Distill Qwen 1.5B", status = ModelStatus.NotDownloaded)
        )

        // When download starts for qwen25_7b
        val downloadingModel = models[0].copy(
            status = ModelStatus.Downloading,
            progress = 0.45f,
            downloadedBytes = 900_000_000L,
            totalBytes = 2_000_000_000L
        )
        models[0] = downloadingModel

        val matchingModels = models
            .filter { it.matches(query, SizeTier.All) }
            .sortedWith(
                compareByDescending<ModelInfo> { it.searchRelevance(query) }
                    .thenBy { it.name }
            )

        val target = matchingModels.first { it.id == "qwen25_7b" }
        assertEquals(ModelStatus.Downloading, target.status)
        assertEquals(0.45f, target.progress, 0.001f)
        assertEquals(900_000_000L, target.downloadedBytes)
    }

    @Test
    fun searchMatchingModelsReflectsOfflineErrorMessage() {
        val query = "qwen"
        val models = mutableListOf(
            createTestModel("qwen25_7b", "Qwen 2.5 7B Instruct", status = ModelStatus.NotDownloaded)
        )

        // When download is tapped with no internet connected
        val offlineErrorMsg = "No internet connection. Please connect to Wi-Fi or mobile data to download models."
        models[0] = models[0].copy(
            status = ModelStatus.NotDownloaded,
            errorMessage = offlineErrorMsg
        )

        val matchingModels = models
            .filter { it.matches(query, SizeTier.All) }
            .sortedWith(
                compareByDescending<ModelInfo> { it.searchRelevance(query) }
                    .thenBy { it.name }
            )

        val target = matchingModels.first { it.id == "qwen25_7b" }
        assertEquals(offlineErrorMsg, target.errorMessage)

        val classified = com.shounak.localmeshai.utils.ErrorClassifier.classify(target.errorMessage)
        org.junit.Assert.assertNotNull(classified)
        assertEquals(com.shounak.localmeshai.utils.ErrorCategory.NoInternet, classified?.category)
        assertEquals("No Internet Connection", classified?.headline)
    }

    @Test
    fun downloadedSectionSortRankFailedHasTopPriority() {
        val failedModel = createTestModel("m_failed", "Failed Model", status = ModelStatus.Failed)
        val downloadingModel = createTestModel("m_dl", "Downloading Model", status = ModelStatus.Downloading)
        val pausedModel = createTestModel("m_paused", "Paused Model", status = ModelStatus.Paused)
        val availableModel = createTestModel("m_avail", "Available Model", status = ModelStatus.Available, localPath = "/path")
        val blockedModel = createTestModel("m_blocked", "Blocked Model", status = ModelStatus.Blocked, localPath = "/path")

        assertEquals(0, failedModel.downloadedSectionSortRank())
        assertEquals(1, downloadingModel.downloadedSectionSortRank())
        assertEquals(2, pausedModel.downloadedSectionSortRank())
        assertEquals(3, availableModel.downloadedSectionSortRank())
        assertEquals(3, blockedModel.downloadedSectionSortRank())
    }

    @Test
    fun downloadedModelsSectionPutsFailedModelAtVeryTop() {
        val now = System.currentTimeMillis()
        val availableModel = createTestModel(
            id = "model_avail",
            name = "Available Model",
            status = ModelStatus.Available,
            downloadedAt = now - 1000L,
            localPath = "/data/model_avail.gguf"
        )
        val downloadingModel = createTestModel(
            id = "model_dl",
            name = "Downloading Model",
            status = ModelStatus.Downloading
        )
        val pausedModel = createTestModel(
            id = "model_paused",
            name = "Paused Model",
            status = ModelStatus.Paused,
            downloadedAt = now - 5000L
        )
        val failedModel = createTestModel(
            id = "model_failed",
            name = "Failed Model",
            status = ModelStatus.Failed,
            downloadedAt = now - 10000L
        )

        val models = listOf(availableModel, downloadingModel, pausedModel, failedModel)
        val downloadedModels = models
            .filter { it.isPinnedDownloadCard() }
            .sortedWith(
                compareBy<ModelInfo> { it.downloadedSectionSortRank() }
                    .thenByDescending { it.downloadTime() }
            )

        assertEquals("Failed model must be placed at the very top", "model_failed", downloadedModels[0].id)
        assertEquals("Downloading model is second", "model_dl", downloadedModels[1].id)
        assertEquals("Paused model is third", "model_paused", downloadedModels[2].id)
        assertEquals("Available model is fourth", "model_avail", downloadedModels[3].id)
    }

    @Test
    fun failedModelIsAlwaysPinnedInDownloadedSection() {
        val failedWithBytes = createTestModel("m1", "Failed With Bytes", status = ModelStatus.Failed).copy(downloadedBytes = 1024L)
        val failedZeroBytes = createTestModel("m2", "Failed Zero Bytes", status = ModelStatus.Failed).copy(downloadedBytes = 0L)

        assertTrue(failedWithBytes.isPinnedDownloadCard())
        assertTrue(failedZeroBytes.isPinnedDownloadCard())
    }

    @Test
    fun matchesFindsCustomModelsByImportedKeywords() {
        val customModel = createTestModel("custom_model_1", "FineTuned Chat", isRecommended = false)
        val standardModel = createTestModel("standard_model_1", "FineTuned Chat", isRecommended = false)

        assertTrue(customModel.isCustom)
        assertFalse(standardModel.isCustom)

        assertTrue(customModel.matches("imported", SizeTier.All))
        assertTrue(customModel.matches("import", SizeTier.All))
        assertTrue(customModel.matches("chat imported", SizeTier.All))

        assertFalse(standardModel.matches("imported", SizeTier.All))
        assertFalse(standardModel.matches("import", SizeTier.All))
    }

    @Test
    fun testModelSourceFilterImportedIncludesOnlyCustomModelsAcrossAllStatuses() {
        val customNotDownloaded = createTestModel("custom_nd", "Custom ND", status = ModelStatus.NotDownloaded)
        val customDownloading = createTestModel("custom_dl", "Custom DL", status = ModelStatus.Downloading)
        val customAvailable = createTestModel("custom_av", "Custom Avail", status = ModelStatus.Available, localPath = "/path/to/custom.gguf")
        val customPaused = createTestModel("custom_ps", "Custom Paused", status = ModelStatus.Paused)
        val customFailed = createTestModel("custom_fl", "Custom Failed", status = ModelStatus.Failed)

        val catalogNotDownloaded = createTestModel("llama32_1b", "Llama 3.2 1B", status = ModelStatus.NotDownloaded)
        val catalogAvailable = createTestModel("qwen25_7b", "Qwen 2.5 7B", status = ModelStatus.Available, localPath = "/path/to/qwen.gguf")

        val allModels = listOf(
            customNotDownloaded, customDownloading, customAvailable, customPaused, customFailed,
            catalogNotDownloaded, catalogAvailable
        )

        // All models filter preserves everything
        val allFiltered = when (ModelSourceFilter.All) {
            ModelSourceFilter.All -> allModels
            ModelSourceFilter.Imported -> allModels.filter { it.isCustom }
        }
        assertEquals(7, allFiltered.size)

        // Imported filter retains ONLY custom models across every download status
        val importedFiltered = when (ModelSourceFilter.Imported) {
            ModelSourceFilter.All -> allModels
            ModelSourceFilter.Imported -> allModels.filter { it.isCustom }
        }
        assertEquals(5, importedFiltered.size)
        assertTrue(importedFiltered.all { it.isCustom })
        assertTrue(importedFiltered.contains(customNotDownloaded))
        assertTrue(importedFiltered.contains(customDownloading))
        assertTrue(importedFiltered.contains(customAvailable))
        assertTrue(importedFiltered.contains(customPaused))
        assertTrue(importedFiltered.contains(customFailed))
        assertFalse(importedFiltered.contains(catalogNotDownloaded))
        assertFalse(importedFiltered.contains(catalogAvailable))
    }

    @Test
    fun testModelSourceFilterAppliesBeforeSectionPartitioningIncludingPinnedDownloads() {
        val catalogDownloaded = createTestModel("qwen25_7b", "Qwen 2.5 7B", status = ModelStatus.Available, localPath = "/models/qwen.gguf", downloadedAt = 1000L)
        val customDownloaded = createTestModel("custom_model", "Custom Model", status = ModelStatus.Available, localPath = "/models/custom.gguf", downloadedAt = 2000L)
        val customCatalogCandidate = createTestModel("custom_unpinned", "Custom Not Downloaded", status = ModelStatus.NotDownloaded)

        val visibleModels = listOf(catalogDownloaded, customDownloaded, customCatalogCandidate)

        // Under ModelSourceFilter.Imported, filter applies BEFORE section partitioning:
        val filtered = visibleModels.filter { it.isCustom }

        val downloadedSection = filtered.filter { it.isPinnedDownloadCard() }
        assertEquals("Only custom downloaded models appear in downloaded section", 1, downloadedSection.size)
        assertEquals("custom_model", downloadedSection.first().id)

        val unpinnedSection = filtered.filter { !it.isPinnedDownloadCard() }
        assertEquals("Only custom unpinned models appear in unpinned section", 1, unpinnedSection.size)
        assertEquals("custom_unpinned", unpinnedSection.first().id)
    }

    @Test
    fun testModelSourceFilterCombinesWithSearchAndSizeTier() {
        // Parameter filters use the declared count, independently of file size.
        val customSmall = createTestModel("custom_small", "Custom Chat Small 1B").copy(size = "1.2 GB")
        val customLarge = createTestModel("custom_large", "Custom Chat Large 8B").copy(size = "8.0 GB")
        val catalogSmall = createTestModel("catalog_small", "Catalog Chat Small 1B").copy(size = "1.2 GB")

        val allModels = listOf(customSmall, customLarge, catalogSmall)

        // 1. Filter by Imported source
        val sourceFiltered = allModels.filter { it.isCustom }
        assertEquals(listOf(customSmall, customLarge), sourceFiltered)

        // 2. Combine with search "chat" and SizeTier.Small (<3B params)
        val searchAndTier = sourceFiltered.filter { it.matches("chat", SizeTier.Small) }
        assertEquals(1, searchAndTier.size)
        assertEquals("custom_small", searchAndTier.first().id)

        // 3. SizeTier.Large (7B to below 12B params)
        val searchAndTierLarge = sourceFiltered.filter { it.matches("chat", SizeTier.Large) }
        assertEquals(1, searchAndTierLarge.size)
        assertEquals("custom_large", searchAndTierLarge.first().id)

        // 4. Search mismatch results in empty
        val searchMismatch = sourceFiltered.filter { it.matches("nonexistent", SizeTier.Small) }
        assertTrue(searchMismatch.isEmpty())
    }
}

