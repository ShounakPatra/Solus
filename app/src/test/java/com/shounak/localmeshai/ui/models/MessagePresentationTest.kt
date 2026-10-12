package com.shounak.localmeshai.ui.models

import com.shounak.localmeshai.utils.ModelAnswerSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MessagePresentationTest {

    @Before
    fun setUp() {
        MessagePresentationCache.clear()
    }

    @Test
    fun testUserMessagePresentationIsPlain() {
        val presentation = MessagePresentationCache.getOrCompute(
            text = "Hello, what is 2 + 2?",
            isUser = true,
            isStreaming = false
        )

        assertEquals("Hello, what is 2 + 2?", presentation.displayText)
        assertEquals(1, presentation.segments.size)
        assertTrue(presentation.segments.first() is ModelAnswerSegment.Text)
        assertNull(presentation.parsedContent.thinkingText)
        assertFalse(presentation.hasRichBlock)
        assertFalse(presentation.showStreamingDots)
    }

    @Test
    fun testAssistantMessageExtractsThinkingBlocks() {
        val text = "<think>Let me calculate 2+2.</think>The answer is 4."
        val presentation = MessagePresentationCache.getOrCompute(
            text = text,
            isUser = false,
            isStreaming = false
        )

        assertEquals("The answer is 4.", presentation.displayText.trim())
        assertEquals("Let me calculate 2+2.", presentation.parsedContent.thinkingText?.trim())
        assertFalse(presentation.parsedContent.isThinkingActive)
    }

    @Test
    fun testAssistantMessageDetectsCodeBlocks() {
        val text = "Here is the code:\n```kotlin\nval x = 42\n```"
        val presentation = MessagePresentationCache.getOrCompute(
            text = text,
            isUser = false,
            isStreaming = false
        )

        assertTrue(presentation.hasRichBlock)
        val codeSegment = presentation.segments.filterIsInstance<ModelAnswerSegment.Code>().firstOrNull()
        assertNotNull(codeSegment)
        assertEquals("val x = 42", codeSegment?.code?.trim())
        assertEquals("kotlin", codeSegment?.language)
    }

    @Test
    fun testCacheHitReturnsSamePresentation() {
        val text = "Cache test string"
        val first = MessagePresentationCache.getOrCompute(text, false, false)
        val second = MessagePresentationCache.getOrCompute(text, false, false)

        assertEquals(first.displayText, second.displayText)
        assertEquals(first.segments.size, second.segments.size)
    }

    @Test
    fun testStreamingPlaceholderDots() {
        val presentation = MessagePresentationCache.getOrCompute(
            text = "Generating…",
            isUser = false,
            isStreaming = true
        )

        assertTrue(presentation.showStreamingDots)
    }

    @Test
    fun testHashCollision_DistinctMessagesWithSameHashCodeDoNotCollide() {
        // "FB" and "Ea" have identical Java String hashCodes (2236)
        val s1 = "FB"
        val s2 = "Ea"
        assertEquals(s1.hashCode(), s2.hashCode())

        val p1 = MessagePresentationCache.getOrCompute(text = s1, isUser = false, isStreaming = false)
        val p2 = MessagePresentationCache.getOrCompute(text = s2, isUser = false, isStreaming = false)

        assertEquals("FB", p1.displayText)
        assertEquals("Ea", p2.displayText)
        org.junit.Assert.assertNotEquals(p1.displayText, p2.displayText)

        // Cache lookup for s1 still returns s1, not corrupted by s2
        val p1Cached = MessagePresentationCache.getOrCompute(text = s1, isUser = false, isStreaming = false)
        assertEquals("FB", p1Cached.displayText)
    }

    @Test
    fun testEmptyMessageId_DistinctTextsDoNotCollide() {
        val p1 = MessagePresentationCache.getOrCompute(messageId = "", text = "Alpha distinct text", isUser = false, isStreaming = false)
        val p2 = MessagePresentationCache.getOrCompute(messageId = "", text = "Beta distinct text", isUser = false, isStreaming = false)

        assertEquals("Alpha distinct text", p1.displayText)
        assertEquals("Beta distinct text", p2.displayText)
    }

    @Test
    fun testPreservesReasoningTextWithoutDropping() {
        val p = MessagePresentationCache.getOrCompute(
            messageId = "msg-1",
            text = "Answer content",
            isUser = false,
            isStreaming = false,
            reasoningText = "Detailed reasoning chain"
        )

        assertEquals("Answer content", p.displayText)
        assertEquals("Detailed reasoning chain", p.reasoningText)
        assertEquals("Detailed reasoning chain", p.parsedContent.thinkingText)
    }
}
