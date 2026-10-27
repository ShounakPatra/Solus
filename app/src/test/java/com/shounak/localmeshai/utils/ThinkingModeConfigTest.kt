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
    fun isThinkingSupportedHonorsExplicitFlag() {
        assertTrue(ThinkingModeConfig.isThinkingSupported(modelName = "Custom Model", supportsThinkingFlag = true))
    }

    @Test
    fun isThinkingSupportedReturnsFalseWithoutFlagOrCapability() {
        assertFalse(ThinkingModeConfig.isThinkingSupported(modelName = "FastVLM 0.5B", effectiveId = "fastvlm_05b"))
        assertFalse(ThinkingModeConfig.isThinkingSupported(modelName = "MobileNet", effectiveId = "mobilenet_v2"))
        assertFalse(ThinkingModeConfig.isThinkingSupported(effectiveId = "qwen3_0.5b"))
        assertFalse(ThinkingModeConfig.isThinkingSupported(modelName = "DeepSeek R1 Distill"))
        assertFalse(ThinkingModeConfig.isThinkingSupported(effectiveId = "gemma4_e2b_litertlm"))
    }

    @Test
    fun supportsTextNoThinkingSwitchReturnsFalseWithoutToggleableCapability() {
        assertFalse(ThinkingModeConfig.supportsTextNoThinkingSwitch(modelName = "Qwen 3 0.6B"))
        assertFalse(ThinkingModeConfig.supportsTextNoThinkingSwitch(modelPath = "/path/to/qwen-3-0.6b.bin"))
        assertFalse(ThinkingModeConfig.supportsTextNoThinkingSwitch(effectiveId = "qwen3_0.5b"))
        assertFalse(ThinkingModeConfig.supportsTextNoThinkingSwitch(modelName = "Gemma 4 E2B IT"))
        assertFalse(ThinkingModeConfig.supportsTextNoThinkingSwitch(modelName = "DeepSeek R1 Distill"))
    }

    @Test
    fun resolveRequestThinkingFollowsTruthfulCapabilityRules() {
        // Toggleable honors user preference
        assertTrue(ThinkingModeConfig.resolveRequestThinking(com.shounak.localmeshai.models.ThinkingCapability.Toggleable, userPreference = true))
        assertFalse(ThinkingModeConfig.resolveRequestThinking(com.shounak.localmeshai.models.ThinkingCapability.Toggleable, userPreference = false))

        // ModelControlled always enables thinking regardless of user preference
        assertTrue(ThinkingModeConfig.resolveRequestThinking(com.shounak.localmeshai.models.ThinkingCapability.ModelControlled, userPreference = false))
        assertTrue(ThinkingModeConfig.resolveRequestThinking(com.shounak.localmeshai.models.ThinkingCapability.ModelControlled, userPreference = true))

        // Unsupported always disables thinking
        assertFalse(ThinkingModeConfig.resolveRequestThinking(com.shounak.localmeshai.models.ThinkingCapability.Unsupported, userPreference = true))
        assertFalse(ThinkingModeConfig.resolveRequestThinking(com.shounak.localmeshai.models.ThinkingCapability.Unsupported, userPreference = false))

        // Unknown always disables thinking
        assertFalse(ThinkingModeConfig.resolveRequestThinking(com.shounak.localmeshai.models.ThinkingCapability.Unknown, userPreference = true))
        assertFalse(ThinkingModeConfig.resolveRequestThinking(com.shounak.localmeshai.models.ThinkingCapability.Unknown, userPreference = false))
    }

    @Test
    fun resolveThinkingCapability_nonGguf_preservesTruthfulStates() {
        val bundleModelControlled = com.shounak.localmeshai.models.ModelBundle(
            id = "custom_litert_1",
            displayName = "Custom LiteRT",
            mainArtifacts = listOf(
                com.shounak.localmeshai.models.ModelArtifact(
                    role = com.shounak.localmeshai.models.ArtifactRole.Main,
                    displayName = "model.litertlm",
                    source = com.shounak.localmeshai.models.ArtifactSource.AppManaged("/data/user/0/com.shounak.localmeshai/files/models/model.litertlm"),
                    sizeBytes = 1000L,
                    lastModified = 1000L
                )
            ),
            capabilities = com.shounak.localmeshai.models.ModelCapabilities(
                thinking = com.shounak.localmeshai.models.ThinkingCapability.ModelControlled,
                thinkingDetectorVersion = ThinkingModeConfig.DETECTOR_VERSION,
                thinkingArtifactIdentity = "/data/user/0/com.shounak.localmeshai/files/models/model.litertlm"
            ),
            backend = "LiteRT-LM GPU",
            format = com.shounak.localmeshai.models.ModelFormat.LiteRtLm
        )

        val cap = ThinkingModeConfig.resolveThinkingCapability(
            id = "custom_litert_1",
            name = "Custom LiteRT",
            bundle = bundleModelControlled,
            fileName = "model.litertlm"
        )
        assertEquals(com.shounak.localmeshai.models.ThinkingCapability.ModelControlled, cap)
    }

    @Test
    fun resolveThinkingCapability_nonGgufCustom_defaultsToUnknownWhenUnspecified() {
        val bundleUnknown = com.shounak.localmeshai.models.ModelBundle(
            id = "custom_litert_2",
            displayName = "Custom LiteRT",
            mainArtifacts = listOf(
                com.shounak.localmeshai.models.ModelArtifact(
                    role = com.shounak.localmeshai.models.ArtifactRole.Main,
                    displayName = "model.litertlm",
                    source = com.shounak.localmeshai.models.ArtifactSource.AppManaged("/data/user/0/com.shounak.localmeshai/files/models/model.litertlm"),
                    sizeBytes = 1000L,
                    lastModified = 1000L
                )
            ),
            capabilities = com.shounak.localmeshai.models.ModelCapabilities(
                thinking = com.shounak.localmeshai.models.ThinkingCapability.Unknown,
                thinkingDetectorVersion = ThinkingModeConfig.DETECTOR_VERSION,
                thinkingArtifactIdentity = "/data/user/0/com.shounak.localmeshai/files/models/model.litertlm"
            ),
            backend = "LiteRT-LM GPU",
            format = com.shounak.localmeshai.models.ModelFormat.LiteRtLm
        )

        val cap = ThinkingModeConfig.resolveThinkingCapability(
            id = "custom_litert_2",
            name = "Custom LiteRT",
            bundle = bundleUnknown,
            fileName = "model.litertlm",
            isCustom = true
        )
        assertEquals(com.shounak.localmeshai.models.ThinkingCapability.Unknown, cap)
    }

    @Test
    fun resolveThinkingCapability_supportFlagDoesNotProveRuntimeControl() {
        val cap = ThinkingModeConfig.resolveThinkingCapability(
            bundle = null,
            id = "custom_model_1",
            name = "Custom General Model",
            fileName = "custom.gguf",
            isCustom = true,
            supportsThinkingFlag = true
        )
        assertEquals(com.shounak.localmeshai.models.ThinkingCapability.ModelControlled, cap)
    }

    @Test
    fun resolveThinkingCapability_customModelWithoutInspection_resolvesUnknown() {
        val cap = ThinkingModeConfig.resolveThinkingCapability(
            bundle = null,
            id = "custom_model_2",
            name = "DeepSeek R1 Distill Q4",
            fileName = "deepseek-r1.gguf",
            isCustom = true,
            supportsThinkingFlag = false
        )
        assertEquals(com.shounak.localmeshai.models.ThinkingCapability.Unknown, cap)
    }

    @Test
    fun resolveThinkingCapability_modelInfoSupportFlagDoesNotProveRuntimeControl() {
        val model = com.shounak.localmeshai.models.ModelInfo(
            id = "custom_model_3",
            name = "My Fine-tuned Model",
            size = "4.0 GB",
            status = com.shounak.localmeshai.models.ModelStatus.Available,
            type = com.shounak.localmeshai.models.ModelType.Text,
            fileName = "model.gguf",
            description = "Custom model",
            backend = "llama.cpp GGUF",
            deviceTarget = "All",
            supportsThinkingMode = true
        )
        val cap = ThinkingModeConfig.resolveThinkingCapability(model)
        assertEquals(com.shounak.localmeshai.models.ThinkingCapability.ModelControlled, cap)
        assertEquals(com.shounak.localmeshai.models.ThinkingCapability.ModelControlled, model.thinkingCapability)
    }

    @Test
    fun reasoningIsDisplayedWheneverTheRuntimeIsAskedToThink() {
        val caps = com.shounak.localmeshai.models.ThinkingCapability.values()
        for (cap in caps) for (preference in listOf(true, false)) {
            val requested = ThinkingModeConfig.resolveRequestThinking(cap, preference)
            val shown = ThinkingModeConfig.displaysReasoning(cap, preference)
            // Never think without showing it, and never hide reasoning the user asked for.
            if (requested) assertTrue("$cap/$preference", shown)
            if (preference) assertTrue("$cap/$preference", shown)
        }
        assertTrue(ThinkingModeConfig.displaysReasoning(com.shounak.localmeshai.models.ThinkingCapability.ModelControlled, false))
        assertFalse(ThinkingModeConfig.displaysReasoning(com.shounak.localmeshai.models.ThinkingCapability.Toggleable, false))
        assertTrue(ThinkingModeConfig.displaysReasoning(com.shounak.localmeshai.models.ThinkingCapability.Toggleable, true))
        assertFalse(ThinkingModeConfig.displaysReasoning(com.shounak.localmeshai.models.ThinkingCapability.Unsupported, false))
    }

    @Test
    fun initialConversationBuiltFromThePreferenceNeedsNoThinkingResetOnTheFirstTurn() {
        // VisionInferenceManager now creates its first LiteRT-LM conversation with
        // liteRtExtraContext(capability, preference), the same context resetConversation uses, and
        // needsResetForThinkingMode compares that enable_thinking with resolveRequestThinking. They must agree
        // for every thinking-capable model and preference, or each first turn after a load rebuilds the
        // conversation.
        val thinkingCaps = listOf(
            com.shounak.localmeshai.models.ThinkingCapability.Toggleable,
            com.shounak.localmeshai.models.ThinkingCapability.ModelControlled
        )
        for (cap in thinkingCaps) for (preference in listOf(true, false)) {
            val initial = ThinkingModeConfig.liteRtExtraContext(capability = cap, userPreference = preference)
            val requested = ThinkingModeConfig.resolveRequestThinking(cap, preference)
            assertEquals("$cap/$preference", requested, initial[ThinkingModeConfig.ENABLE_THINKING_KEY])
            // The VM passes the already-resolved flag back in; that must give the same context.
            assertEquals("$cap/$preference resolved", initial, ThinkingModeConfig.liteRtExtraContext(cap, requested))
        }
        // The previous fixed initial context (enable_thinking=false) disagreed with every ModelControlled request.
        val legacyInitial = ThinkingModeConfig.liteRtExtraContext(isDefaultThinkingModel = true, thinkingMode = false)
        assertTrue(legacyInitial[ThinkingModeConfig.ENABLE_THINKING_KEY] !=
            ThinkingModeConfig.resolveRequestThinking(com.shounak.localmeshai.models.ThinkingCapability.ModelControlled, false))
        // Models without a thinking switch get no thinking context, so there is nothing to mismatch.
        assertTrue(ThinkingModeConfig.liteRtExtraContext(com.shounak.localmeshai.models.ThinkingCapability.Unsupported, true).isEmpty())
        assertTrue(ThinkingModeConfig.liteRtExtraContext(com.shounak.localmeshai.models.ThinkingCapability.Unknown, true).isEmpty())
    }
}
