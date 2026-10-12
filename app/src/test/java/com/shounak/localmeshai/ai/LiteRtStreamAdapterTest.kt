package com.shounak.localmeshai.ai

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtStreamAdapterTest {

    @Test
    fun testAppendsTextChunksInOrderAndReportsDeltas() = runBlocking {
        val deltas = mutableListOf<String>()
        val snapshots = mutableListOf<StructuredResponseSnapshot>()

        val adapter = LiteRtStreamAdapter(
            requestId = "req-stream-1",
            attemptId = 1,
            onDelta = { deltas.add(it) },
            onSnapshot = { snapshots.add(it) }
        )

        val msg1 = Message.model("Hello, ")
        val msg2 = Message.model("world!")

        adapter.onMessage(msg1)
        adapter.onMessage(msg2)
        adapter.onDone()

        assertEquals(listOf("Hello, ", "world!"), deltas)
        assertEquals("Hello, world!", adapter.currentSnapshot().answerText)
        assertEquals(2, snapshots.size)

        val finalSnapshot = adapter.awaitDone()
        assertEquals("Hello, world!", finalSnapshot.answerText)
    }

    @Test
    fun testAccumulatesReasoningChannelSeparately() = runBlocking {
        val deltas = mutableListOf<String>()
        var firstTokenTriggered = false

        val adapter = LiteRtStreamAdapter(
            requestId = "req-thought-1",
            attemptId = 1,
            thinkingMode = true,
            onFirstToken = { firstTokenTriggered = true },
            onDelta = { deltas.add(it) }
        )

        val thoughtMsg = Message.model(
            channels = mapOf("thought" to "I need to inspect the image carefully.")
        )
        val answerMsg = Message.model(
            contents = Contents.of(Content.Text("This image contains a dog."))
        )

        adapter.onMessage(thoughtMsg)
        assertTrue(firstTokenTriggered)
        adapter.onMessage(answerMsg)
        adapter.onDone()

        // When thinkingMode is true, reasoning is wrapped with <think> tags for streaming presentation
        assertEquals(
            listOf("<think>\n", "I need to inspect the image carefully.", "\n</think>\n\n", "This image contains a dog."),
            deltas
        )

        val snapshot = adapter.currentSnapshot()
        assertEquals("This image contains a dog.", snapshot.answerText)
        assertEquals("I need to inspect the image carefully.", snapshot.reasoningText)
        assertEquals("I need to inspect the image carefully.", snapshot.channels["thought"])
    }

    @Test
    fun testThinkingModeOffSuppressesReasoningDeltasButTriggersFirstToken() = runBlocking {
        val deltas = mutableListOf<String>()
        var firstTokenTriggered = false

        val adapter = LiteRtStreamAdapter(
            requestId = "req-thought-off",
            attemptId = 1,
            thinkingMode = false,
            onFirstToken = { firstTokenTriggered = true },
            onDelta = { deltas.add(it) }
        )

        val thoughtMsg = Message.model(
            channels = mapOf("thought" to "Silent background calculation.")
        )
        val answerMsg = Message.model(
            contents = Contents.of(Content.Text("Direct answer."))
        )

        adapter.onMessage(thoughtMsg)
        assertTrue(firstTokenTriggered)
        adapter.onMessage(answerMsg)
        adapter.onDone()

        // When thinkingMode is false, reasoning is suppressed from deltas
        assertEquals(listOf("Direct answer."), deltas)
        assertEquals("Direct answer.", adapter.currentSnapshot().answerText)
        assertEquals("Silent background calculation.", adapter.currentSnapshot().reasoningText)
    }

    @Test
    fun testEmptyMessageNeverResetsAccumulatedAnswer() = runBlocking {
        val adapter = LiteRtStreamAdapter(
            requestId = "req-empty-test",
            attemptId = 1
        )

        val textMsg = Message.model("Important answer")
        val emptyMsg = Message.model()
        val emptyTextMsg = Message.model(Contents.of(""))

        adapter.onMessage(textMsg)
        assertEquals("Important answer", adapter.currentSnapshot().answerText)

        // Neither empty message should reset the accumulated text!
        adapter.onMessage(emptyMsg)
        assertEquals("Important answer", adapter.currentSnapshot().answerText)

        adapter.onMessage(emptyTextMsg)
        assertEquals("Important answer", adapter.currentSnapshot().answerText)

        adapter.onDone()
        assertEquals("Important answer", adapter.awaitDone().answerText)
    }

    @Test
    fun testCancellationPreventsSubsequentEvents() = runBlocking {
        val adapter = LiteRtStreamAdapter(
            requestId = "req-cancel",
            attemptId = 1
        )

        val textMsg1 = Message.model("Chunk 1")
        adapter.onMessage(textMsg1)

        adapter.cancel()

        val textMsg2 = Message.model("Chunk 2")
        // Should be ignored
        adapter.onMessage(textMsg2)

        assertEquals("Chunk 1", adapter.currentSnapshot().answerText)
    }
}
