package com.shounak.localmeshai.ai

import com.google.ai.edge.litertlm.ConversationConfig
import org.junit.Assert.*
import org.junit.Test

class LiteRtThinkingConfigTest {
    @Test fun thinkingIsSetBeforeCreationAndPreservesThePreface() {
        for (enabled in listOf(true, false)) {
            val base = ConversationConfig(extraContext = mapOf("enable_thinking" to enabled), prefillPrefaceOnInit = true)
            val config = LiteRtThinkingConfig.apply(base, enabled, softSwitch = false)
            assertEquals(enabled, config.thinkingConfig?.enableThinking)
            assertEquals(base.extraContext, config.extraContext)
            assertTrue(config.prefillPrefaceOnInit)
        }
    }

    @Test fun softSwitchAndUnsupportedModelsKeepTheirOriginalConfig() {
        val base = ConversationConfig()
        assertEquals(base, LiteRtThinkingConfig.apply(base, true, softSwitch = true))
        assertEquals(base, LiteRtThinkingConfig.apply(base, null, softSwitch = false))
    }
}
