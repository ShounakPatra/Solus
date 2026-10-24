package com.shounak.localmeshai.ai.tools

import com.shounak.localmeshai.utils.AppSettingsData
import org.junit.Assert.*
import org.junit.Test

class ConversationToolSettingsTest {
    @Test fun enablingAndDisablingEitherFeatureInvalidatesTheConversation() {
        val defaults = AppSettingsData()
        for (before in listOf(false, true)) {
            val tools = ConversationToolSettings.from(defaults.copy(enableToolCalling = before))
            assertTrue(tools.requiresRefresh(ConversationToolSettings.from(defaults.copy(enableToolCalling = !before))))
            val rag = ConversationToolSettings.from(defaults.copy(enableToolCalling = true, enableRag = before))
            assertTrue(rag.requiresRefresh(ConversationToolSettings.from(defaults.copy(enableToolCalling = true, enableRag = !before))))
        }
    }

    @Test fun unrelatedSettingsAndRetrievalTuningDoNotDiscardNativeHistory() {
        val settings = AppSettingsData(enableToolCalling = true, enableRag = true)
        val applied = ConversationToolSettings.from(settings)
        assertFalse(applied.requiresRefresh(ConversationToolSettings.from(settings.copy(
            enableDarkMode = false, ragTopK = 5, ragMinSimilarity = 0.6f
        ))))
    }

    @Test fun activeTurnKeepsItsSnapshotAndNextRequestSeesPendingChanges() {
        var settings = AppSettingsData(enableToolCalling = true, enableRag = false)
        val activeTurn = ConversationToolSettings.from(settings)
        settings = settings.copy(enableRag = true)
        assertFalse(activeTurn.documentSearch)
        assertTrue(activeTurn.requiresRefresh(ConversationToolSettings.from(settings)))
        val nextTurn = ConversationToolSettings.from(settings)
        assertTrue(nextTurn.documentSearch)
        assertFalse(nextTurn.requiresRefresh(ConversationToolSettings.from(settings)))
    }

    @Test fun changeDuringRecreationIsNotMistakenForAnAppliedSetting() {
        var settings = AppSettingsData(enableToolCalling = true)
        val creating = ConversationToolSettings.from(settings)
        settings = settings.copy(enableToolCalling = false)
        assertTrue(creating.requiresRefresh(ConversationToolSettings.from(settings)))
        settings = settings.copy(enableToolCalling = true)
        assertFalse(creating.requiresRefresh(ConversationToolSettings.from(settings)))
    }
    @Test fun disabledAndUnselectedToolsDoNotDiscardHistoryOrChangeAvailableSchemas() {
        val disabled = AppSettingsData(enableToolCalling = false)
        val before = ConversationToolSettings.from(disabled)
        val after = ConversationToolSettings.from(disabled.copy(enableRag = true, enabledToolSkills = setOf("qr_code")))
        assertFalse(before.requiresRefresh(after))
        val context = android.content.ContextWrapper(null)
        assertTrue(LocalTools.forConversation(context, before) { false }.isEmpty())
        assertTrue(LocalTools.forConversation(context, after) { false }.isEmpty())
        val calculator = disabled.copy(enableToolCalling = true, enabledToolSkills = setOf("calculator"))
        val first = ConversationToolSettings.from(calculator)
        val second = ConversationToolSettings.from(calculator.copy(enableRag = true))
        assertFalse(first.requiresRefresh(second))
        assertEquals(listOf("calculator"), LocalTools.forConversation(context, second) { false }.map { it.name })
    }

}
