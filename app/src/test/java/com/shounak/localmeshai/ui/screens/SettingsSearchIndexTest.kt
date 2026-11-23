package com.shounak.localmeshai.ui.screens

import com.shounak.localmeshai.models.ModelInfo
import com.shounak.localmeshai.models.ModelStatus
import com.shounak.localmeshai.models.ModelType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SettingsSearchIndexTest {

    @Before
    fun setUp() {
        SettingsSearchIndex.clearDynamicSettings()
    }

    @After
    fun tearDown() {
        SettingsSearchIndex.clearDynamicSettings()
    }

    @Test
    fun testEverySubmenuIsCoveredInIndex() {
        val allSettings = SettingsSearchIndex.getAllSettings()
        assertFalse("Search index should not be empty", allSettings.isEmpty())

        val coveredSubmenus = allSettings.map { it.targetSubMenu }.toSet()

        for (subMenu in SettingsSubMenu.entries) {
            assertTrue(
                "Submenu $subMenu must have at least one indexed entry in SettingsSearchIndex",
                coveredSubmenus.contains(subMenu)
            )
        }
    }

    @Test
    fun testEveryIndexedSettingHasValidMetadata() {
        val allSettings = SettingsSearchIndex.getAllSettings()

        for (item in allSettings) {
            assertTrue("Item title cannot be blank: ${item.id}", item.title.isNotBlank())
            assertTrue("Item subtitle cannot be blank: ${item.title}", item.subtitle.isNotBlank())
            assertTrue("Item keywords cannot be blank: ${item.title}", item.keywords.isNotBlank())
            assertTrue("Item category cannot be blank: ${item.title}", item.category.isNotBlank())
            assertNotNull("Item targetSubMenu cannot be null: ${item.title}", item.targetSubMenu)
            assertTrue("Item category must contain ' > ': ${item.category}", item.category.contains(" > "))
        }
    }

    @Test
    fun testCommonSearchQueriesReturnExpectedSettings() {
        val queriesToExpectedSubmenus = mapOf(
            "dark mode" to SettingsSubMenu.APPEARANCE,
            "vulkan" to SettingsSubMenu.ENGINE,
            "temperature" to SettingsSubMenu.CHAT_DEFAULTS,
            "top p" to SettingsSubMenu.CHAT_DEFAULTS,
            "voice" to SettingsSubMenu.VOICE,
            "tinyvoca" to SettingsSubMenu.VOICE,
            "persona" to SettingsSubMenu.PERSONAS,
            "memory" to SettingsSubMenu.MEMORY,
            "reduce motion" to SettingsSubMenu.ACCESSIBILITY,
            "calculator" to SettingsSubMenu.AGENT_TOOLS,
            "workflows" to SettingsSubMenu.WORKFLOWS,
            "web search" to SettingsSubMenu.WEB_SEARCH,
            "tavily" to SettingsSubMenu.WEB_SEARCH,
            "brave" to SettingsSubMenu.WEB_SEARCH,
            "rag" to SettingsSubMenu.RAG,
            "neural" to SettingsSubMenu.RAG,
            "telemetry" to SettingsSubMenu.TELEMETRY,
            "thermal" to SettingsSubMenu.TELEMETRY,
            "ram" to SettingsSubMenu.TELEMETRY,
            "hugging face" to SettingsSubMenu.PROVIDERS,
            "storage" to SettingsSubMenu.STORAGE,
            "cache" to SettingsSubMenu.STORAGE,
            "backup" to SettingsSubMenu.BACKUP,
            "privacy" to SettingsSubMenu.PRIVACY,
            "crash" to SettingsSubMenu.TROUBLESHOOTING,
            "diagnostics" to SettingsSubMenu.TROUBLESHOOTING,
            "about" to SettingsSubMenu.ABOUT,
            "check for updates" to SettingsSubMenu.ABOUT
        )

        for ((query, expectedSubMenu) in queriesToExpectedSubmenus) {
            val results = SettingsSearchIndex.search(query)
            assertFalse("Search for '$query' should return results", results.isEmpty())
            val topResult = results.first()
            assertEquals(
                "Top result for '$query' ('${topResult.title}') should route to $expectedSubMenu",
                expectedSubMenu,
                topResult.targetSubMenu
            )
        }
    }

    @Test
    fun testMultiTokenSearchQuery() {
        val voiceSpeedResults = SettingsSearchIndex.search("voice speed")
        assertFalse(voiceSpeedResults.isEmpty())
        assertEquals(SettingsSubMenu.VOICE, voiceSpeedResults.first().targetSubMenu)

        val gpuBackendResults = SettingsSearchIndex.search("gpu backend")
        assertFalse(gpuBackendResults.isEmpty())
        assertEquals(SettingsSubMenu.ENGINE, gpuBackendResults.first().targetSubMenu)

        val ragSemanticResults = SettingsSearchIndex.search("rag semantic")
        assertFalse(ragSemanticResults.isEmpty())
        assertEquals(SettingsSubMenu.RAG, ragSemanticResults.first().targetSubMenu)

        val braveKeyResults = SettingsSearchIndex.search("brave key")
        assertFalse(braveKeyResults.isEmpty())
        assertEquals(SettingsSubMenu.WEB_SEARCH, braveKeyResults.first().targetSubMenu)
    }

    @Test
    fun testEmptyAndBlankQueriesReturnEmptyList() {
        assertTrue(SettingsSearchIndex.search("").isEmpty())
        assertTrue(SettingsSearchIndex.search("   ").isEmpty())
        assertTrue(SettingsSearchIndex.search("\t\n").isEmpty())
    }

    @Test
    fun testNonMatchingQueryReturnsEmptyList() {
        val results = SettingsSearchIndex.search("xyzzyNonExistent12345SettingTerm")
        assertTrue("Non-matching query should return empty list", results.isEmpty())
    }

    @Test
    fun testDynamicModelRegistrationAndSearch() {
        val dummyModel = ModelInfo(
            id = "deepseek_r1_1_5b_q4",
            name = "DeepSeek R1 1.5B (GGUF)",
            size = "1.2 GB",
            status = ModelStatus.Available,
            type = ModelType.Text,
            fileName = "deepseek-r1-1.5b-q4.gguf",
            description = "Reasoning model",
            backend = "GGUF",
            deviceTarget = "GPU"
        )

        SettingsSearchIndex.syncAvailableModels(listOf(dummyModel))

        val results = SettingsSearchIndex.search("deepseek r1")
        assertFalse("Dynamic model should be findable in search", results.isEmpty())
        val match = results.firstOrNull { it.id == "model_profile_${dummyModel.id}" }
        assertNotNull("Dynamic model profile setting must be present", match)
        assertEquals("Model: ${dummyModel.name}", match?.title)
        assertEquals(SettingsSubMenu.MODEL_PROFILES, match?.targetSubMenu)
    }

    @Test
    fun testEnsureIndexSynchronized() {
        val syncResult = SettingsSearchIndex.ensureIndexSynchronized(context = null)
        assertTrue(syncResult.totalIndexedCount >= SettingsSearchIndex.BUILT_IN_SETTINGS.size)
        assertTrue(syncResult.currentVersionCode >= 1)
    }
}
