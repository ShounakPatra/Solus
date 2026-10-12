package com.shounak.localmeshai.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InferenceResponseControllerTest {

    @Test
    fun testUnclosedThinkTagClosedAtStreamCompletionWithoutLeakingToAnswer() {
        val controller = InferenceResponseController(thinkingMode = true)
        controller.appendDelta("<think>")
        controller.appendDelta("Processing math step 1...")
        controller.appendDelta("\nStep 2: evaluating integral")

        assertTrue(controller.isThinkingActive)
        assertEquals("Processing math step 1...\nStep 2: evaluating integral", controller.currentThinkingText())
        assertEquals("", controller.currentAnswerText())

        // Generation ends abruptly without closing </think>
        controller.finish()

        assertFalse(controller.isThinkingActive)
        assertEquals("Processing math step 1...\nStep 2: evaluating integral", controller.getReasoningText())
        assertEquals("", controller.getFinalAnswer())

        val finalized = controller.finalizeResponse(requestId = "req-1")
        assertTrue("Should fail as ReasoningOnly when answer is blank", finalized is FinalizedResponse.Failed)
        assertEquals(EmptyResponseReason.ReasoningOnly, (finalized as FinalizedResponse.Failed).emptyReason)
    }

    @Test
    fun testCompleteThinkBlockSeparatesThinkingAndAnswer() {
        val controller = InferenceResponseController(thinkingMode = true)
        controller.appendDelta("<think>Analyzing question</think>")
        controller.appendDelta("The capital is Paris.")

        controller.finish()
        assertEquals("Analyzing question", controller.getReasoningText())
        assertEquals("The capital is Paris.", controller.getFinalAnswer())

        val finalized = controller.finalizeResponse(requestId = "req-2")
        assertTrue(finalized is FinalizedResponse.Completed)
        assertEquals("The capital is Paris.", finalized.answerText)
        assertEquals("Analyzing question", finalized.reasoningText)
    }

    @Test
    fun testBracketedThoughtTagsSupported() {
        val controller = InferenceResponseController(thinkingMode = true)
        controller.appendDelta("[THOUGHT]Let's think carefully about this.[/THOUGHT]")
        controller.appendDelta("The answer is 123.")

        controller.finish()
        assertEquals("Let's think carefully about this.", controller.getReasoningText())
        assertEquals("The answer is 123.", controller.getFinalAnswer())
    }

    @Test
    fun testWhitespaceVariationsInTags() {
        val controller = InferenceResponseController(thinkingMode = true)
        controller.appendDelta("<  thought  >Evaluating edge cases<  /  thought  >\n\nResult is valid.")

        controller.finish()
        assertEquals("Evaluating edge cases", controller.getReasoningText())
        assertEquals("Result is valid.", controller.getFinalAnswer())
    }

    @Test
    fun testThinkingDisabledSuppressesThinkingAndStripsLeakedTags() {
        val controller = InferenceResponseController(thinkingMode = false)
        controller.appendDelta("<think>Internal monologue leaked here</think>")
        controller.appendDelta("Direct response to user.")

        controller.finish()
        assertEquals("", controller.getReasoningText())
        assertEquals("Direct response to user.", controller.getFinalAnswer())

        val finalized = controller.finalizeResponse(requestId = "req-3")
        assertTrue(finalized is FinalizedResponse.Completed)
        assertEquals("Direct response to user.", finalized.answerText)
        assertEquals("", finalized.reasoningText)
    }

    @Test
    fun testNestedTagsDoNotCrashOrLeak() {
        val controller = InferenceResponseController(thinkingMode = true)
        controller.appendDelta("<think>Outer reasoning <think>inner thought</think> continuation</think>Final output")

        controller.finish()
        assertTrue(controller.getReasoningText().contains("Outer reasoning"))
        assertEquals("Final output", controller.getFinalAnswer())
    }

    @Test
    fun testStreamingFormattedTextHidesThinkingWhenDisabled() {
        val controller = InferenceResponseController(thinkingMode = false)
        controller.appendDelta("<think>Streaming some thought")
        assertEquals("", controller.currentFormattedText())

        controller.appendDelta("</think>Hello world!")
        assertEquals("Hello world!", controller.currentFormattedText())
    }

    @Test
    fun testObsoleteRequestAndAttemptDeltasAreRejected() {
        val controller = InferenceResponseController(
            thinkingMode = true,
            requestId = "req-active",
            initialAttemptId = 1
        )
        // Valid delta
        controller.appendDelta("First ", reqId = "req-active", attemptId = 1)
        // Obsolete request ID
        controller.appendDelta("STALE-REQ ", reqId = "req-old", attemptId = 1)
        // Obsolete attempt ID
        controller.appendDelta("STALE-ATTEMPT ", reqId = "req-active", attemptId = 0)
        // Valid delta
        controller.appendDelta("second", reqId = "req-active", attemptId = 1)

        controller.finish()
        assertEquals("First second", controller.getFinalAnswer())
    }

    @Test
    fun testGpuPartialOutputFollowedByCpuRecoveryClearsAttemptState() {
        val controller = InferenceResponseController(
            thinkingMode = true,
            requestId = "req-gpu-cpu",
            initialAttemptId = 1
        )
        // GPU attempt 1 emits some partial tokens then crashes
        controller.appendDelta("<think>Partial GPU thought...", reqId = "req-gpu-cpu", attemptId = 1)
        assertEquals("Partial GPU thought...", controller.currentThinkingText())

        // CPU recovery begins
        controller.beginAttempt(2)
        assertEquals("", controller.currentThinkingText())
        assertEquals("", controller.currentAnswerText())
        assertEquals("", controller.acceptedAnswerText())

        // Late GPU attempt 1 callback arriving after recovery begins is rejected
        controller.appendDelta("late GPU junk", reqId = "req-gpu-cpu", attemptId = 1)
        assertEquals("", controller.currentThinkingText())

        // CPU attempt 2 succeeds
        controller.appendDelta("Clean CPU answer", reqId = "req-gpu-cpu", attemptId = 2)
        controller.finish()
        assertEquals("Clean CPU answer", controller.getFinalAnswer())
        val finalized = controller.finalizeResponse()
        assertTrue(finalized is FinalizedResponse.Completed)
        assertEquals("Clean CPU answer", finalized.answerText)
    }

    @Test
    fun testRepeatedIdenticalDeltasPreserved() {
        val controller = InferenceResponseController(thinkingMode = false)
        listOf("a", "a", "abc").forEach { controller.appendDelta(it) }
        controller.finish()
        assertEquals("aaabc", controller.getFinalAnswer())
    }

    @Test
    fun testReasoningOnlyOutputWithThinkingOffProducesFailed() {
        val controller = InferenceResponseController(thinkingMode = false)
        controller.appendDelta("<think>Only thinking monologue here</think>")
        controller.finish()
        val finalized = controller.finalizeResponse(requestId = "req-no-think")
        assertTrue(finalized is FinalizedResponse.Failed)
        assertEquals("", finalized.answerText)
    }
}
