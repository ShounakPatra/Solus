package com.shounak.localmeshai.ai.tools

import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.tool
import com.shounak.localmeshai.utils.ThinkingModeConfig
import org.junit.Assert.*
import org.junit.Test

class VisionToolConfigTest {
    @Test fun addingToolsPreservesNativeThinkingAndPrefillConfiguration() {
        val base = ConversationConfig(
            thinkingConfig = com.google.ai.edge.litertlm.ThinkingConfig(enableThinking = true),
            channels = emptyList(),
            prefillPrefaceOnInit = true
        )
        val providers = listOf(tool(CalculatorTool()))
        assertEquals(base.copy(tools = providers, automaticToolCalling = true), LocalTools.conversationConfig(base, providers))
    }

    @Test fun addingToolsPreservesThinkingControlsAndRestoredConversationMessages() {
        val context = ThinkingModeConfig.liteRtExtraContext(isDefaultThinkingModel = true, thinkingMode = false)
        val messages = listOf(Message.of(com.google.ai.edge.litertlm.Content.Text("Previous question")))
        val base = ConversationConfig(initialMessages = messages, extraContext = context)
        val providers = listOf(tool(CalculatorTool()), tool(CurrentDateTimeTool()))
        val config = LocalTools.conversationConfig(base, providers)
        assertEquals(messages, config.initialMessages)
        assertEquals(context, config.extraContext)
        assertEquals(providers, config.tools)
        assertTrue(config.automaticToolCalling)
    }
    @Test fun defaultVisionConversationCanAddToolsWithoutThinkingOverrides() {
        val config = LocalTools.conversationConfig(null, listOf(tool(CalculatorTool())))
        assertEquals(1, config.tools.size)
        assertTrue(config.initialMessages.isEmpty())
        assertTrue(config.extraContext.isEmpty())
    }
}
