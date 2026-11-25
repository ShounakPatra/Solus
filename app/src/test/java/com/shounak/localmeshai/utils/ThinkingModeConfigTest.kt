package com.shounak.localmeshai.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingModeConfigTest {
    @Test
    fun liteRtExtraContextAlwaysPassesEnableThinking() {
        assertEquals(mapOf("enable_thinking" to true), ThinkingModeConfig.liteRtExtraContext(true))
        assertEquals(mapOf("enable_thinking" to false), ThinkingModeConfig.liteRtExtraContext(false))
        assertEquals(10, ThinkingModeConfig.CONFIG_VERSION)
    }

    @Test
    fun toggleVisibilityUsesSimpleTemplateMarkerCheckWithNullFallback() {
        assertTrue(ThinkingModeConfig.isToggleAvailableForTemplate("{% if enable_thinking %}<think>{% endif %}"))
        assertTrue(ThinkingModeConfig.isToggleAvailableForTemplate("{{ bos_token }}<think>\n"))
        assertTrue(ThinkingModeConfig.isToggleAvailableForTemplate("{{ bos_token }}<|channel>thought\n"))
        assertTrue(ThinkingModeConfig.isToggleAvailableForTemplate("{{ bos_token }}<|thought|>"))
        assertFalse(ThinkingModeConfig.isToggleAvailableForTemplate("{{ bos_token }}{% for m in messages %}{{ m['content'] }}{% endfor %}"))
        assertTrue(ThinkingModeConfig.isToggleAvailableForTemplate(null))
        assertTrue(ThinkingModeConfig.isToggleAvailableForTemplate("   "))
    }
}
