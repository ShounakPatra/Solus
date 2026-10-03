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

    @Test
    fun extractFinalAnswerOnlyReturnsAnswerAndStripsThinking() {
        val outputWithBoth = "<think>internal reasoning</think>\n\nThe answer is 42."
        assertEquals("The answer is 42.", ThinkingTextUtils.extractFinalAnswerOnly(outputWithBoth))

        val outputPrefilledTag = "internal reasoning</think>\n\nThe answer is 42."
        assertEquals("The answer is 42.", ThinkingTextUtils.extractFinalAnswerOnly(outputPrefilledTag))

        val outputOnlyThinking = "internal reasoning</think>"
        assertEquals("", ThinkingTextUtils.extractFinalAnswerOnly(outputOnlyThinking))

        val outputCompleteBlockOnly = "<think>internal reasoning</think>"
        assertEquals("", ThinkingTextUtils.extractFinalAnswerOnly(outputCompleteBlockOnly))

        val outputUnclosed = "<think>still working through the answer"
        assertEquals("", ThinkingTextUtils.extractFinalAnswerOnly(outputUnclosed))

        val outputThoughtTag = "<thought>deep thinking</thought>\n\nDirect answer here."
        assertEquals("Direct answer here.", ThinkingTextUtils.extractFinalAnswerOnly(outputThoughtTag))

        val outputReasoningTag = "<reasoning>analyzing prompt</reasoning>\n\nHere is the answer."
        assertEquals("Here is the answer.", ThinkingTextUtils.extractFinalAnswerOnly(outputReasoningTag))
    }

    @Test
    fun extractFinalAnswerOnlyExtractsAnswerFromUntaggedMonologue() {
        val monologueWithQuote = "Okay, the user greeted me with 'hi'. Let me respond. Maybe say something like, \"Hello! How can I help?\""
        assertEquals("Hello! How can I help?", ThinkingTextUtils.extractFinalAnswerOnly(monologueWithQuote))

        val monologueWithFinalAnswer = "Okay, the user asked for 2+2. Let me calculate. Final answer: 4"
        assertEquals("4", ThinkingTextUtils.extractFinalAnswerOnly(monologueWithFinalAnswer))

        // When the quote was cut off mid-sentence with a dangling conjunction (as in user screenshot), reject it
        val cutOff = "Okay, the user greeted me with \"hi,\" so I should respond naturally. Maybe say something like, \"Hi! I love pizza and"
        assertEquals("", ThinkingTextUtils.extractFinalAnswerOnly(cutOff))
    }
}
