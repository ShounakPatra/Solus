package com.shounak.localmeshai.ai

import org.junit.Assert.*
import org.junit.Test

class PortableHistoryBudgetTest {
    @Test fun dropsCompleteExchangeAndPreservesInstructionsAndLatestUser() {
        val turns = listOf("system" to "rules", "user" to "old", "assistant" to "answer", "user" to "new")
        val trimmed = PortableHistoryBudget.dropOldestTurn(turns)!!
        assertEquals(listOf(turns[0], turns[3]), trimmed)
        assertNull(PortableHistoryBudget.dropOldestTurn(trimmed))
    }
}
