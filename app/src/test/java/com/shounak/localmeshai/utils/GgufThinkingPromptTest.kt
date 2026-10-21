package com.shounak.localmeshai.utils

import org.junit.Assert.*
import org.junit.Test

class GgufThinkingPromptTest {
    private val history = listOf(
        "system" to "Be helpful.", "user" to "Hello", "assistant" to "Hi!",
        "user" to "Explain quantum computing"
    )

    @Test fun directModeSwitchOnlyChangesCurrentUserTurnAndIsIdempotent() {
        val actual = NoThinkingPromptUtils.wrapLatestUserTurn(history, enabled = true)
        assertEquals(history.dropLast(1), actual.dropLast(1))
        assertEquals("Explain quantum computing\n\n/no_think", actual.last().second)
        assertEquals(actual, NoThinkingPromptUtils.wrapLatestUserTurn(actual, enabled = true))
        assertEquals("Explain quantum computing", history.last().second)
    }

    @Test fun thinkingModeAndModelsWithoutSwitchKeepOriginalMessages() {
        assertSame(history, NoThinkingPromptUtils.wrapLatestUserTurn(history, enabled = false))
        val systemOnly = listOf("system" to "Be helpful.")
        assertEquals(systemOnly, NoThinkingPromptUtils.wrapLatestUserTurn(systemOnly, enabled = true))
    }
}
