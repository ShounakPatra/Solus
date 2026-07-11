package com.shounak.localmeshai.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingModeConfigTest {
    @Test
    fun defaultThinkingModelReceivesHardDisableAtRequestLevel() {
        val context = ThinkingModeConfig.liteRtExtraContext(
            isDefaultThinkingModel = true,
            thinkingMode = false
        )

        assertTrue(context.containsKey(ThinkingModeConfig.ENABLE_THINKING_KEY))
        assertEquals(false, context[ThinkingModeConfig.ENABLE_THINKING_KEY])
    }

    @Test
    fun thinkingCanBeExplicitlyReenabledForDefaultThinkingModel() {
        val context = ThinkingModeConfig.liteRtExtraContext(
            isDefaultThinkingModel = true,
            thinkingMode = true
        )

        assertEquals(true, context[ThinkingModeConfig.ENABLE_THINKING_KEY])
    }

    @Test
    fun ordinaryModelDoesNotReceiveUnsupportedTemplateVariables() {
        val context = ThinkingModeConfig.liteRtExtraContext(
            isDefaultThinkingModel = false,
            thinkingMode = false
        )

        assertTrue(context.isEmpty())
        assertFalse(context.containsKey(ThinkingModeConfig.ENABLE_THINKING_KEY))
    }
}
