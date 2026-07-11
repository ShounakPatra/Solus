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
}
