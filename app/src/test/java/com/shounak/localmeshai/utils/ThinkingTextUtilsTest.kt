package com.shounak.localmeshai.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class ThinkingTextUtilsTest {
    @Test
    fun finalResponseOrReasoningReturnsAnswerAfterCompleteThinkBlock() {
        val output = "<think>internal reasoning</think>\n\nThe answer is 42."

        assertEquals("The answer is 42.", ThinkingTextUtils.finalResponseOrReasoning(output))
    }

    @Test
    fun finalResponseOrReasoningHandlesTemplatePrefilledOpeningTag() {
        val output = "internal reasoning</think>\n\nThe answer is 42."

        assertEquals("The answer is 42.", ThinkingTextUtils.finalResponseOrReasoning(output))
    }

    @Test
    fun finalResponseOrReasoningPreservesUnclosedReasoningInsteadOfReturningBlank() {
        val output = "<think>still working through the answer"

        assertEquals(
            "still working through the answer",
            ThinkingTextUtils.finalResponseOrReasoning(output)
        )
    }

    @Test
    fun finalResponseOrReasoningPreservesClosingOnlyReasoningWhenNoAnswerExists() {
        val output = "useful partial reasoning</think>"

        assertEquals("useful partial reasoning", ThinkingTextUtils.finalResponseOrReasoning(output))
    }
}
