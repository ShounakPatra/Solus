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

    @Test
    fun isThinkingSupportedDetectsGemma4Models() {
        assertTrue(ThinkingModeConfig.isThinkingSupported(effectiveId = "gemma4_e2b_litertlm"))
        assertTrue(ThinkingModeConfig.isThinkingSupported(modelPath = "/path/to/gemma-4-E4B-it.litertlm"))
        assertTrue(ThinkingModeConfig.isThinkingSupported(modelName = "Gemma 4 E2B IT"))
    }

    @Test
    fun isThinkingSupportedDetectsQwenAndDeepSeekModels() {
        assertTrue(ThinkingModeConfig.isThinkingSupported(effectiveId = "qwen3_0.5b"))
        assertTrue(ThinkingModeConfig.isThinkingSupported(modelName = "DeepSeek R1 Distill"))
    }

    @Test
    fun isThinkingSupportedHonorsExplicitFlag() {
        assertTrue(ThinkingModeConfig.isThinkingSupported(modelName = "Custom Model", supportsThinkingFlag = true))
    }

    @Test
    fun isThinkingSupportedReturnsFalseForNonThinkingModels() {
        assertFalse(ThinkingModeConfig.isThinkingSupported(modelName = "FastVLM 0.5B", effectiveId = "fastvlm_05b"))
        assertFalse(ThinkingModeConfig.isThinkingSupported(modelName = "MobileNet", effectiveId = "mobilenet_v2"))
    }

    @Test
    fun supportsTextNoThinkingSwitchDetectsAllQwen3Variants() {
        assertTrue(ThinkingModeConfig.supportsTextNoThinkingSwitch(modelName = "Qwen 3 0.6B"))
        assertTrue(ThinkingModeConfig.supportsTextNoThinkingSwitch(modelPath = "/path/to/qwen-3-0.6b.bin"))
        assertTrue(ThinkingModeConfig.supportsTextNoThinkingSwitch(effectiveId = "qwen3_0.5b"))
        assertTrue(ThinkingModeConfig.supportsTextNoThinkingSwitch(modelName = "qwen_3_1.7b"))
        assertFalse(ThinkingModeConfig.supportsTextNoThinkingSwitch(modelName = "Gemma 4 E2B IT"))
        assertFalse(ThinkingModeConfig.supportsTextNoThinkingSwitch(modelName = "DeepSeek R1 Distill"))
    }
}
