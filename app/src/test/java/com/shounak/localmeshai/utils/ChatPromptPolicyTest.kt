package com.shounak.localmeshai.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ChatPromptPolicyTest {
    @Test
    fun ordinaryMediaPipeModelsKeepTheirHistoryPrompt() {
        val history = "User: first\nAssistant: answer\nUser: follow-up\nAssistant:"
        assertEquals(
            history,
            ChatPromptPolicy.mediaPipeBasePrompt(history, "follow-up")
        )
    }

    @Test
    fun blankHistoryFallsBackToMemoryAndUserTextWithoutThinkingInstructions() {
        val memoryBlock = "[User Memory & Preferences]\n- [Fact] My dog's name is Cooper\n[End of User Memory]"
        val result = ChatPromptPolicy.mediaPipeBasePrompt(
            fullHistoryPrompt = "",
            rawUserText = "What is my dog's name?",
            memoryContext = memoryBlock
        )

        assertEquals("$memoryBlock\n\nWhat is my dog's name?", result)
        assertFalse(result.contains("<think>"))
        assertFalse(result.contains("Do not emit"))
    }
}
