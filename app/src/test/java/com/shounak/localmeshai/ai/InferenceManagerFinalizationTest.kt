package com.shounak.localmeshai.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InferenceManagerFinalizationTest {

    @Test
    fun testNormalCompletionReturnsFinalizedResponseWithCleanAnswer() {
        val finalized = ResponseFinalizer.finalize(
            requestId = "req-mgr-1",
            attemptId = 1,
            rawAnswer = "Complete answer text",
            rawReasoning = "Internal reasoning",
            isStopped = false,
            sdkError = null
        )

        assertTrue(finalized is FinalizedResponse.Completed)
        assertEquals("req-mgr-1", finalized.requestId)
        assertEquals(1, finalized.attemptId)
        assertEquals("Complete answer text", finalized.answerText)
        assertEquals("Internal reasoning", finalized.reasoningText)
        assertEquals(RequestTerminalStatus.Completed, finalized.status)
        assertNull(finalized.error)
    }

    @Test
    fun testUserStoppedInferencePreservesAcceptedPartialAnswer() {
        val finalized = ResponseFinalizer.finalize(
            requestId = "req-mgr-stop",
            attemptId = 1,
            rawAnswer = "Partial answer emitted so far",
            rawReasoning = "Reasoning step 1",
            isStopped = true,
            sdkError = null
        )

        assertTrue(finalized is FinalizedResponse.Stopped)
        assertEquals("req-mgr-stop", finalized.requestId)
        assertEquals("Partial answer emitted so far", finalized.answerText)
        assertEquals("Reasoning step 1", finalized.reasoningText)
        assertEquals(RequestTerminalStatus.Stopped, finalized.status)
        assertNull(finalized.error)
    }

    @Test
    fun testNativeFailurePreservesPartialAnswerAndRecordsError() {
        val error = IllegalStateException("GPU device lost: recovering on CPU")
        val finalized = ResponseFinalizer.finalize(
            requestId = "req-mgr-fail",
            attemptId = 1,
            rawAnswer = "Partial output before crash",
            rawReasoning = "Thought before crash",
            isStopped = false,
            sdkError = error
        )

        assertTrue(finalized is FinalizedResponse.Failed)
        assertEquals("req-mgr-fail", finalized.requestId)
        assertEquals("Partial output before crash", finalized.answerText)
        assertEquals("GPU device lost: recovering on CPU", finalized.error)
        assertEquals(RequestTerminalStatus.Failed, finalized.status)
    }

    @Test
    fun testReasoningOnlyOutputIsStrictlyRejectedAsAnswer() {
        val finalized = ResponseFinalizer.finalize(
            requestId = "req-mgr-no-answer",
            attemptId = 1,
            rawAnswer = "",
            rawReasoning = "I thought a lot but gave no answer.",
            isStopped = false,
            sdkError = null
        )

        assertTrue(finalized is FinalizedResponse.Failed)
        assertEquals("", finalized.answerText)
        assertEquals(EmptyResponseReason.ReasoningOnly, (finalized as FinalizedResponse.Failed).emptyReason)
        assertEquals(ResponseFinalizer.FAILED_NO_ANSWER_MESSAGE, finalized.error)
    }

    @Test
    fun testPublisherAcceptedAnswerFallbackPreservedOnEmptyTerminalChunk() {
        val finalized = ResponseFinalizer.finalize(
            requestId = "req-mgr-fallback",
            attemptId = 1,
            rawAnswer = "   ",
            rawReasoning = "",
            isStopped = false,
            sdkError = null,
            acceptedAnswerFallback = "Answer streamed via publisher"
        )

        assertTrue(finalized is FinalizedResponse.Completed)
        assertEquals("Answer streamed via publisher", finalized.answerText)
        assertEquals(RequestTerminalStatus.Completed, finalized.status)
    }
}
