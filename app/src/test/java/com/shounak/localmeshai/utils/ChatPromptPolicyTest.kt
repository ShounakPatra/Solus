package com.shounak.localmeshai.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPromptPolicyTest {
    @Test
    fun nativeGemmaTaskReceivesOnlyTheRawCurrentUserTurn() {
        val result = ChatPromptPolicy.mediaPipeBasePrompt(
            fullHistoryPrompt = "User: Explain quantum computing\nAssistant: ...\nUser: hi\nAssistant:",
            rawUserText = "hi",
            useNativeGemmaTaskTemplate = true
        )

        assertEquals("hi", result)
    }

    @Test
    fun nativeGemmaRetryDoesNotInjectCompetingRoleLabels() {
        val result = ChatPromptPolicy.nativeGemmaRetryPrompt("Explain quantum computing")

        assertTrue(result.endsWith("Explain quantum computing"))
        assertFalse(result.contains("User:"))
        assertFalse(result.contains("Assistant:"))
        assertFalse(result.contains("Answer:"))
    }

    @Test
    fun ordinaryMediaPipeModelsKeepTheirHistoryPrompt() {
        val history = "User: first\nAssistant: answer\nUser: follow-up\nAssistant:"
        assertEquals(
            history,
            ChatPromptPolicy.mediaPipeBasePrompt(history, "follow-up", false)
        )
    }

    @Test
    fun nativeGemmaTaskIncludesMemoryContextWhenProvided() {
        val memoryBlock = "[User Memory & Preferences]\n- [Fact] My dog's name is Cooper\n[End of User Memory]"
        val result = ChatPromptPolicy.mediaPipeBasePrompt(
            fullHistoryPrompt = "$memoryBlock\n\nUser: hi\nAssistant:",
            rawUserText = "What is my dog's name?",
            useNativeGemmaTaskTemplate = true,
            memoryContext = memoryBlock
        )

        assertEquals("$memoryBlock\n\nWhat is my dog's name?", result)
    }

    @Test
    fun nativeGemmaRetryIncludesMemoryContextWhenProvided() {
        val memoryBlock = "[User Memory & Preferences]\n- [Fact] My dog's name is Cooper\n[End of User Memory]"
        val result = ChatPromptPolicy.nativeGemmaRetryPrompt(
            rawUserText = "What is my dog's name?",
            memoryContext = memoryBlock
        )

        assertTrue(result.contains(memoryBlock))
        assertTrue(result.endsWith("What is my dog's name?"))
    }
}
