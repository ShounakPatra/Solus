package com.shounak.localmeshai.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class PromptDeadlineTest {
    @Test fun answerAfterToolResultDisarmsTheRearmedDeadline() = runBlocking {
        val deadline = PromptDeadline(50)
        val answer = deadline.run({ fail("Answer streaming after a tool is healthy") }) {
            deadline.onProgress(RequestProgress.Generating)
            deadline.onProgress(RequestProgress.UsingTools)
            delay(10)
            deadline.onProgress(RequestProgress.ProcessingPrompt)
            deadline.onProgress(RequestProgress.Generating)
            delay(100)
            "answer"
        }
        assertEquals("answer", answer)
    }

    @Test fun stalledToolRoundAfterAPreambleStillTimesOut() = runBlocking {
        val stopped = CompletableDeferred<Unit>()
        val deadline = PromptDeadline(30)
        try {
            withTimeout(2_000) {
                deadline.run({ stopped.complete(Unit) }) {
                    deadline.onProgress(RequestProgress.Generating)
                    delay(60) // A healthy visible preamble must not expire.
                    deadline.onProgress(RequestProgress.UsingTools)
                    stopped.await()
                    ""
                }
            }
            fail("Tool work must re-arm the deadline after a visible preamble")
        } catch (_: PromptTimeoutException) { }
    }

    @Test fun inputBudgetScalesAndIsBounded() {
        assertEquals(120_000L, PromptDeadline.budgetMillis(0))
        assertTrue(PromptDeadline.budgetMillis(8_000) > PromptDeadline.budgetMillis(1_000))
        assertEquals(1_800_000L, PromptDeadline.budgetMillis(Int.MAX_VALUE))
    }

    @Test fun healthyToolRoundsRenewTheIdleDeadline() = runBlocking {
        val deadline = PromptDeadline(200)
        val result = deadline.run({ fail("Healthy progress must renew the deadline") }) {
            repeat(4) {
                delay(75)
                deadline.onProgress(RequestProgress.UsingTools)
            }
            deadline.onProgress(RequestProgress.Generating)
            delay(250)
            "answer"
        }
        assertEquals("answer", result)
    }
    @Test fun stalledPrefillCancelsNativeAndReportsRetryableFailure() = runBlocking {
        val nativeStopped = CompletableDeferred<Unit>()
        var cancelCount = 0
        try {
            withTimeout(2_000) {
                PromptDeadline(20).run({ cancelCount++; nativeStopped.complete(Unit) }) {
                    nativeStopped.await()
                    throw CancellationException("Native cancelled")
                }
            }
            fail("Expected a prompt timeout")
        } catch (error: PromptTimeoutException) {
            assertTrue(error.message!!.contains("Retry"))
            assertEquals(1, cancelCount)
            // Coroutine stack recovery may copy the timeout and wrap its original cause.
            assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it is CancellationException })
        }
    }

    @Test fun firstTokenDisarmsPrefillDeadlineForSlowGeneration() = runBlocking {
        val deadline = PromptDeadline(20)
        var cancelled = false
        val answer = deadline.run({ cancelled = true }) {
            deadline.onProgress(RequestProgress.Generating)
            delay(60)
            "answer"
        }
        assertEquals("answer", answer)
        assertFalse(cancelled)
    }

    @Test fun progressLabelsDoNotKeepStalledPrefillAlive() = runBlocking {
        val stopped = CompletableDeferred<Unit>()
        val deadline = PromptDeadline(20)
        try {
            deadline.run({ stopped.complete(Unit) }) {
                deadline.onProgress(RequestProgress.ProcessingPrompt)
                deadline.onProgress(RequestProgress.RecoveringOnCpu)
                stopped.await()
                ""
            }
            fail("Expected timeout even after backend recovery")
        } catch (_: PromptTimeoutException) { }
    }

    @Test fun normalCompletionNeverCancelsNativeLater() = runBlocking {
        var cancelled = false
        assertEquals("answer", PromptDeadline(20).run({ cancelled = true }) { "answer" })
        delay(40)
        assertFalse(cancelled)
    }

    @Test fun ordinaryFailureIsPreserved() = runBlocking {
        val failure = IllegalArgumentException("unsupported export")
        try {
            PromptDeadline().run({ fail("Must not cancel") }) { throw failure }
            fail("Expected original failure")
        } catch (error: IllegalArgumentException) {
            assertEquals(failure.message, error.message)
        }
    }

    @Test fun timeoutKeepsOwnershipUntilNativeCallActuallyReturns() = runBlocking {
        val timeoutShown = CompletableDeferred<Unit>()
        val nativeReturn = CompletableDeferred<Unit>()
        val result = async {
            try {
                PromptDeadline(20).run({}, onTimeout = { timeoutShown.complete(Unit) }) {
                    nativeReturn.await()
                }
                false
            } catch (_: PromptTimeoutException) { true }
        }
        withTimeout(2_000) { timeoutShown.await() }
        assertTrue("Native execution must retain the caller's runtime lock", result.isActive)
        nativeReturn.complete(Unit)
        assertTrue(result.await())
    }
}
