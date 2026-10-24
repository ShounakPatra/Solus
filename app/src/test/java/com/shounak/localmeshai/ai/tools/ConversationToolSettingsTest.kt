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
            val rag = ConversationToolSettings.from(defaults.copy(enableRag = before))
            assertTrue(rag.requiresRefresh(ConversationToolSettings.from(defaults.copy(enableRag = !before))))
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
}
