package com.shounak.localmeshai.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseFinalizerTest {

    @Test
    fun testSuccessfulNonBlankAnswerCompletes() {
        val result = ResponseFinalizer.finalize(
            requestId = "req-1",
            attemptId = 1,
            rawAnswer = "Here is the answer.",
            rawReasoning = "Thinking carefully..."
        )

        assertTrue(result is FinalizedResponse.Completed)
        assertEquals("req-1", result.requestId)
        assertEquals(1, result.attemptId)
        assertEquals("Here is the answer.", result.answerText)
        assertEquals("Thinking carefully...", result.reasoningText)
        assertEquals(RequestTerminalStatus.Completed, result.status)
        assertNull(result.error)
        assertNull(result.emptyReason)

        val execResult = result.toExecutionResult()
        assertEquals("Here is the answer.", execResult.text)
        assertEquals(RequestTerminalStatus.Completed, execResult.status)
        assertNull(execResult.error)
    }

    @Test
    fun testStoppedInferenceYieldsStoppedWithPartialAnswer() {
        val result = ResponseFinalizer.finalize(
            requestId = "req-stop",
            attemptId = 1,
            rawAnswer = "Partial answer before interrupt",
            rawReasoning = "Partial thoughts",
            isStopped = true
        )

        assertTrue(result is FinalizedResponse.Stopped)
        assertEquals("req-stop", result.requestId)
        assertEquals("Partial answer before interrupt", result.answerText)
        assertEquals("Partial thoughts", result.reasoningText)
        assertEquals(RequestTerminalStatus.Stopped, result.status)
        assertNull(result.error)

        val execResult = result.toExecutionResult()
        assertEquals("Partial answer before interrupt", execResult.text)
        assertEquals(RequestTerminalStatus.Stopped, execResult.status)
    }

    @Test
    fun testSdkErrorPreservesPartialTextAndSetsFailed() {
        val error = RuntimeException("Native out-of-memory exception")
        val result = ResponseFinalizer.finalize(
            requestId = "req-err",
            attemptId = 2,
            rawAnswer = "Accumulated before crash",
            sdkError = error
        )

        assertTrue(result is FinalizedResponse.Failed)
        assertEquals("req-err", result.requestId)
        assertEquals(2, result.attemptId)
        assertEquals("Accumulated before crash", result.answerText)
        assertEquals("Native out-of-memory exception", result.error)
        assertEquals(RequestTerminalStatus.Failed, result.status)

        val execResult = result.toExecutionResult()
        assertEquals("Accumulated before crash", execResult.text)
        assertEquals(RequestTerminalStatus.Failed, execResult.status)
        assertEquals("Native out-of-memory exception", execResult.error)
    }

    @Test
    fun testFallbackTextPreservedWhenRawAnswerIsBlank() {
        val result = ResponseFinalizer.finalize(
            requestId = "req-fallback",
            attemptId = 1,
            rawAnswer = "   ",
            acceptedAnswerFallback = "Retained answer from publisher"
        )

        assertTrue(result is FinalizedResponse.Completed)
        assertEquals("Retained answer from publisher", result.answerText)
        assertEquals(RequestTerminalStatus.Completed, result.status)
    }

    @Test
    fun testBlankAnswerWithReasoningYieldsReasoningOnlyFailure() {
        val result = ResponseFinalizer.finalize(
            requestId = "req-reasoning-only",
            attemptId = 1,
            rawAnswer = "",
            rawReasoning = "Let me think about this image. It has a cat."
        )

        assertTrue(result is FinalizedResponse.Failed)
        assertEquals("", result.answerText)
        assertEquals("Let me think about this image. It has a cat.", result.reasoningText)
        assertEquals(ResponseFinalizer.FAILED_NO_ANSWER_MESSAGE, result.error)
        assertEquals(EmptyResponseReason.ReasoningOnly, result.emptyReason)
        assertEquals(RequestTerminalStatus.Failed, result.status)
    }

    @Test
    fun testCompletelyBlankOutputYieldsNoVisibleAnswerFailure() {
        val result = ResponseFinalizer.finalize(
            requestId = "req-blank",
            attemptId = 1,
            rawAnswer = "   ",
            rawReasoning = "   "
        )

        assertTrue(result is FinalizedResponse.Failed)
        assertEquals("", result.answerText)
        assertEquals("", result.reasoningText)
        assertEquals(ResponseFinalizer.FAILED_NO_ANSWER_MESSAGE, result.error)
        assertEquals(EmptyResponseReason.NoVisibleAnswer, result.emptyReason)
        assertEquals(RequestTerminalStatus.Failed, result.status)
    }

    @Test
    fun testStopDuringReasoningDoesNotSubstituteReasoningForAnswer() {
        val result = ResponseFinalizer.finalize(
            requestId = "req-stop-reasoning",
            attemptId = 1,
            rawAnswer = "",
            rawReasoning = "Thinking about steps...",
            isStopped = true,
            acceptedAnswerFallback = ""
        )

        assertTrue(result is FinalizedResponse.Stopped)
        assertEquals("", result.answerText)
        assertEquals("Thinking about steps...", result.reasoningText)
        assertEquals(RequestTerminalStatus.Stopped, result.status)
    }

    @Test
    fun testErrorDuringReasoningDoesNotSubstituteReasoningForAnswer() {
        val result = ResponseFinalizer.finalize(
            requestId = "req-err-reasoning",
            attemptId = 1,
            rawAnswer = "",
            rawReasoning = "Thinking before failure...",
            sdkError = RuntimeException("Connection reset"),
            acceptedAnswerFallback = ""
        )

        assertTrue(result is FinalizedResponse.Failed)
        assertEquals("", result.answerText)
        assertEquals("Thinking before failure...", result.reasoningText)
        assertEquals(RequestTerminalStatus.Failed, result.status)
        assertEquals("Connection reset", result.error)
        assertEquals(EmptyResponseReason.ReasoningOnly, result.emptyReason)
    }

    @Test
    fun testValidAnswerFollowedByBlankCompletionPreservesAcceptedAnswer() {
        val result = ResponseFinalizer.finalize(
            requestId = "req-accepted-fallback",
            attemptId = 1,
            rawAnswer = "",
            rawReasoning = "Finished thoughts",
            isStopped = false,
            acceptedAnswerFallback = "42 is the final answer."
        )

        assertTrue(result is FinalizedResponse.Completed)
        assertEquals("42 is the final answer.", result.answerText)
        assertEquals("Finished thoughts", result.reasoningText)
        assertEquals(RequestTerminalStatus.Completed, result.status)
    }

    @Test
    fun testValidReasoningFollowedByBlankCompletionPreservesAcceptedReasoning() {
        val result = ResponseFinalizer.finalize(
            requestId = "req-accepted-reasoning-fallback",
            attemptId = 1,
            rawAnswer = "Final answer here.",
            rawReasoning = "",
            acceptedReasoningFallback = "Preserved reasoning from previous tokens."
        )

        assertTrue(result is FinalizedResponse.Completed)
        assertEquals("Final answer here.", result.answerText)
        assertEquals("Preserved reasoning from previous tokens.", result.reasoningText)
        assertEquals(RequestTerminalStatus.Completed, result.status)
    }
}
