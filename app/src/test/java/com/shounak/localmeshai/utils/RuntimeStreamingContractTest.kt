package com.shounak.localmeshai.utils

import com.shounak.localmeshai.ai.InferenceResponseController
import com.shounak.localmeshai.ai.ResponseFinalizer
import com.shounak.localmeshai.ai.StructuredResponseSnapshot
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeStreamingContractTest {

    @Test
    fun testAttemptIsolationDropsCallbacksFromPriorAttempt(): Unit = runBlocking {
        val updates = mutableListOf<String>()
        val publisher = StreamingResponsePublisher(
            scope = this,
            transform = { it },
            publish = { synchronized(updates) { updates.add(it) } }
        )

        // Attempt 1 receives some tokens
        publisher.appendDelta("Attempt 1 token")
        assertEquals(1, publisher.currentAttempt)

        // Transition to Attempt 2 (e.g. Vulkan -> CPU fallback)
        publisher.beginAttempt(2)
        assertEquals(2, publisher.currentAttempt)

        // Stale Attempt 1 delta arrives via delayed callback
        publisher.appendDelta("Stale Attempt 1 delta", attemptId = 1)
        assertEquals("", publisher.rawText()) // Must be empty because Attempt 1 was discarded

        // Valid Attempt 2 deltas arrive
        publisher.appendDelta("Attempt 2 token", attemptId = 2)
        assertEquals("Attempt 2 token", publisher.rawText())

        val finished = publisher.finish()
        assertEquals("Attempt 2 token", finished)
    }

    @Test
    fun testImmediateFirstTokenPublicationFollowedByDebouncedStream(): Unit = runBlocking {
        val channel = Channel<String>(Channel.UNLIMITED)
        val publisher = StreamingResponsePublisher(
            scope = this,
            transform = { it },
            publish = { channel.trySend(it) },
            intervalNanos = 50_000_000L // 50ms interval
        )

        // First non-empty token publishes immediately
        publisher.appendDelta("First")
        val firstPublished = withTimeout(200) { channel.receive() }
        assertEquals("First", firstPublished)

        // Subsequent rapid deltas are conflated within 50ms window
        publisher.appendDelta(" token")
        publisher.appendDelta(" stream")
        delay(65)

        val batchPublished = withTimeout(200) { channel.receive() }
        assertEquals("First token stream", batchPublished)

        publisher.finish()
    }

    @Test
    fun testAuthoritativeFinalSnapshotOverridesAccumulatedDeltas(): Unit = runBlocking {
        val channel = Channel<String>(Channel.UNLIMITED)
        val publisher = StreamingResponsePublisher(
            scope = this,
            transform = { it },
            publish = { channel.trySend(it) }
        )

        publisher.appendDelta("Partial raw text")
        withTimeout(200) { channel.receive() }

        // Finish with authoritative final snapshot
        val authoritativeFinal = "Authoritative cleaned final answer"
        val result = publisher.finish(finalText = authoritativeFinal)
        assertEquals(authoritativeFinal, result)

        val terminalPublished = withTimeout(200) { channel.receive() }
        assertEquals(authoritativeFinal, terminalPublished)
    }

    @Test
    fun testRejectsCallbacksAfterTermination(): Unit = runBlocking {
        val channel = Channel<String>(Channel.UNLIMITED)
        val publisher = StreamingResponsePublisher(
            scope = this,
            transform = { it },
            publish = { channel.trySend(it) }
        )

        publisher.appendDelta("Finished content")
        val firstPublished = withTimeout(200) { channel.receive() }
        assertEquals("Finished content", firstPublished)

        val finished = publisher.finish()
        assertEquals("Finished content", finished)

        // Delayed engine callback arrives post-finish
        publisher.appendDelta("Zombie delta")
        assertEquals("Finished content", publisher.rawText())

        // Ensure no new updates were published
        assertTrue(channel.isEmpty)
    }

    @Test
    fun testReasoningChannelDoesNotFallBackToAnswerText(): Unit {
        val controller = InferenceResponseController(
            thinkingMode = true,
            requestId = "req-reasoning-isolation",
            initialAttemptId = 1
        )

        // Feed only reasoning tokens inside thinking tags
        controller.appendDelta("<think>Step 1: thinking through problem\nStep 2: verifying constraints</think>")

        val reasoning = controller.getReasoningText()
        val answer = controller.getFinalAnswer()
        assertEquals("Step 1: thinking through problem\nStep 2: verifying constraints", reasoning)
        assertEquals("", answer)

        // Finalize without any answer tokens
        val finalized = controller.finalizeResponse(isStopped = false)
        assertTrue("Reasoning-only response must not be treated as Completed answer", finalized !is com.shounak.localmeshai.ai.FinalizedResponse.Completed)
        assertTrue("Must be categorized as Failed", finalized is com.shounak.localmeshai.ai.FinalizedResponse.Failed)
        val failed = finalized as com.shounak.localmeshai.ai.FinalizedResponse.Failed
        assertEquals(com.shounak.localmeshai.ai.EmptyResponseReason.ReasoningOnly, failed.emptyReason)
    }
}
