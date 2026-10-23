package com.shounak.localmeshai.utils

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

class StreamingResponsePublisherTest {
    @Test fun authoritativeToolRoundResetClearsVisiblePreambleWithoutChangingAttempt() = runBlocking {
        val updates = Channel<String>(Channel.UNLIMITED)
        val controller = com.shounak.localmeshai.ai.InferenceResponseController(false)
        val publisher = StreamingResponsePublisher(this, { controller.snapshot().answerText },
            { updates.trySend(it) }, intervalNanos = 0, snapshotProvider = { controller.snapshot() })
        controller.appendStructured("Checking documents", "", false, replaceSnapshot = true)
        publisher.notifyStructuredUpdate(allowEmpty = true)
        assertEquals("Checking documents", withTimeout(5000) { updates.receive() })
        controller.appendStructured("", "", false, replaceSnapshot = true)
        publisher.notifyStructuredUpdate(allowEmpty = true)
        assertEquals("", withTimeout(5000) { updates.receive() })
        assertEquals(1, publisher.currentAttempt)
        controller.appendStructured("Found it", "", false, replaceSnapshot = true)
        publisher.notifyStructuredUpdate(allowEmpty = true)
        assertEquals("Found it", withTimeout(5000) { updates.receive() })
        assertEquals("Found it", publisher.finishExplicit("Found it"))
    }

    @Test fun firstChunkPublishesWithoutAnotherChunk() = runBlocking {
        val updates = Channel<String>(Channel.UNLIMITED)
        val publisher = StreamingResponsePublisher(this, { it }, { updates.trySend(it) }, intervalNanos = 60_000_000_000)
        publisher.appendDelta("first")
        assertEquals("first", withTimeout(5000) { updates.receive() })
        publisher.appendDelta(" tail")
        assertEquals("first tail", publisher.finish())
        assertEquals("first tail", withTimeout(5000) { updates.receive() })
        publisher.appendDelta("stale")
        assertEquals("first tail", publisher.rawText())
        assertTrue(updates.tryReceive().isFailure)
    }

    @Test fun repeatedDeltasAreNeverMistakenForSnapshots() = runBlocking {
        val publisher = StreamingResponsePublisher(this, { it }, {})
        listOf("a", "a", "abc").forEach(publisher::appendDelta)
        assertEquals("aaabc", publisher.finish())
    }

    @Test fun splitUnicodeWhitespaceAndCodeFencesSurvive() = runBlocking {
        val publisher = StreamingResponsePublisher(this, { it }, {})
        listOf("\uD83D", "\uDE80", "\n\n", "`", "``kotlin\n", "val a = 1\n", "``", "`").forEach(publisher::appendDelta)
        assertEquals("🚀\n\n```kotlin\nval a = 1\n```", publisher.finish())
    }

    @Test fun resetDiscardsFailedAttemptAndTerminalFlushWins() = runBlocking {
        val updates = mutableListOf<String>()
        val publisher = StreamingResponsePublisher(this, { it }, { synchronized(updates) { updates.add(it) } })
        publisher.appendDelta("failed")
        publisher.reset()
        publisher.appendDelta("recovered")
        assertEquals("recovered complete", publisher.finish("recovered complete"))
        delay(40)
        assertEquals("recovered complete", synchronized(updates) { updates.last() })
        assertEquals("recovered", publisher.rawText())
    }

    @Test fun cancellationStillFlushesTail() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val updates = Channel<String>(Channel.UNLIMITED)
        val publisher = StreamingResponsePublisher(scope, { it }, { updates.trySend(it) })
        publisher.appendDelta("unfinished tail")
        scope.cancel()
        assertEquals("unfinished tail", publisher.finish())
        assertEquals("unfinished tail", withTimeout(5000) { updates.receive() })
    }
    @Test fun splitThinkingTagStaysHiddenAndFinalLiteralIsPreserved() = runBlocking {
        val updates = Channel<String>(Channel.UNLIMITED)
        val publisher = StreamingResponsePublisher(this,
            { ThinkingTextUtils.parse(ThinkingTextUtils.holdIncompleteTag(it), false).finalResponseText },
            { updates.trySend(it) },
            finalTransform = { ThinkingTextUtils.parse(it, false).finalResponseText })
        publisher.appendDelta("<thi")
        delay(40)
        assertTrue(updates.tryReceive().isFailure)
        publisher.appendDelta("nk>private reasoning</thi")
        delay(40)
        assertTrue(updates.tryReceive().isFailure)
        publisher.appendDelta("nk>Visible answer <")
        assertEquals("Visible answer <", publisher.finish())
    }

    @Test fun emptyDeltaDoesNotClearAccumulator() = runBlocking {
        val publisher = StreamingResponsePublisher(this, { it }, {})
        publisher.appendDelta("Hello")
        publisher.appendDelta("")
        assertEquals("Hello", publisher.rawText())
        assertEquals("Hello", publisher.finish())
    }

    @Test fun beginAttemptClearsAccumulatorOnlyForHigherAttempt() = runBlocking {
        val publisher = StreamingResponsePublisher(this, { it }, {})
        publisher.appendDelta("Attempt 1 text")

        // Lower or equal attempt ID does NOT reset
        publisher.beginAttempt(1)
        assertEquals("Attempt 1 text", publisher.rawText())

        // Higher attempt ID DOES reset
        publisher.beginAttempt(2)
        assertEquals("", publisher.rawText())
        publisher.appendDelta("Attempt 2 text")
        assertEquals("Attempt 2 text", publisher.finish())
    }

    @Test fun blankFinishDoesNotOverwriteAccumulatedText() = runBlocking {
        val publisher = StreamingResponsePublisher(this, { it }, {})
        publisher.appendDelta("Valid generated text")
        // Passing blank terminal text does not overwrite previously accumulated content
        val result = publisher.finish("")
        assertEquals("Valid generated text", result)
    }
}
