package com.shounak.localmeshai.utils

import com.shounak.localmeshai.models.ThinkingCapability
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PortableThinkingTest {
    @Test fun templateControlsDetermineThinkingRatherThanModelFilename() {
        assertEquals(ThinkingCapability.Toggleable, PortableThinking.capability(JSONObject(),JSONObject().put("chat_template","{% if enable_thinking %}<think>{% endif %}")))
        assertEquals(ThinkingCapability.ModelControlled, PortableThinking.capability(JSONObject(),JSONObject().put("chat_template","Assistant:<think>")))
        assertEquals(ThinkingCapability.Unsupported, PortableThinking.capability(JSONObject().put("model_type","qwen2"),JSONObject()))
        assertEquals(ThinkingCapability.Unsupported, PortableThinking.capability(JSONObject().put("solus_thinking","unsupported"),JSONObject().put("chat_template","<think>")))
    }
    @Test fun reasoningPrefillRequiresAnOpenTerminalTag() {
        assertTrue(PortableThinking.hasReasoningPrefill("Assistant:<think>\n"))
        assertFalse(PortableThinking.hasReasoningPrefill("<think>\n</think>\n"))
        assertFalse(PortableThinking.hasReasoningPrefill("Say <think> in your answer"))
    }
}
