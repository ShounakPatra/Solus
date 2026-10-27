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
    @Test fun rawGemmaChannelsStreamWithoutLeakingReasoningIntoTheAnswer() {
        for (thinking in listOf(true, false)) {
            val deltas = mutableListOf<String>()
            val adapter = LiteRtStreamAdapter("gemma", thinkingMode = thinking, onDelta = { deltas += it })
            "<|channel>thought\nCareful reasoning<channel|>Hello".forEach {
                adapter.onMessage(Message.model(it.toString()))
                assertFalse(adapter.currentSnapshot().answerText.contains("Careful"))
            }
            adapter.onDone()
            assertEquals("Careful reasoning", adapter.currentSnapshot().reasoningText)
            assertEquals("Hello", adapter.currentSnapshot().answerText)
            assertEquals(if (thinking) "<think>\nCareful reasoning\n</think>\n\nHello" else "Hello", deltas.joinToString(""))
        }
    }


    @Test
    fun verifiedPrefillSeparatesReasoningFromTheFirstTokenWithThinkingOnOrOff() {
        for (thinking in listOf(true, false)) {
            val controller = InferenceResponseController(thinkingMode = thinking, requestId = "prefill")
            val deltas = mutableListOf<String>()
            val adapter = LiteRtStreamAdapter("prefill", thinkingMode = thinking, startsInReasoning = true,
                onDelta = { deltas += it }, onSnapshot = {
                    controller.appendStructured(it.answerText, it.reasoningText, it.isThinkingActive,
                        "prefill", 1, replaceSnapshot = true)
                })
            adapter.onMessage(Message.model("Careful reasoning"))
            assertEquals("", controller.snapshot().answerText)
            assertEquals(if (thinking) "Careful reasoning" else "", controller.snapshot().reasoningText)
            assertEquals(thinking, controller.snapshot().isThinkingActive)
            if (!thinking) assertTrue(deltas.isEmpty())
            adapter.onMessage(Message.model("</thi"))
            assertEquals("", controller.snapshot().answerText)
            adapter.onMessage(Message.model("nk>Hello"))
            adapter.onDone()
            controller.finish()
            assertEquals("Hello", controller.getFinalAnswer())
            assertEquals(if (thinking) "Careful reasoning" else "", controller.getReasoningText())
            assertFalse(controller.snapshot().isThinkingActive)
            if (!thinking) assertEquals("Hello", deltas.joinToString(""))
        }
    }

    @Test
    fun stoppingPrefilledReasoningPreservesOneBlockAndRejectsLateCallbacks() {
        val snapshots = mutableListOf<StructuredResponseSnapshot>()
        val adapter = LiteRtStreamAdapter("stop-prefill", thinkingMode = true,
            startsInReasoning = true, onSnapshot = { snapshots += it })
        adapter.onMessage(Message.model("Partial reasoning"))
        assertEquals("", snapshots.last().answerText)
        assertTrue(snapshots.last().isThinkingActive)
        adapter.cancel()
        val stopped = snapshots.last()
        assertEquals("Partial reasoning", stopped.reasoningText)
        assertEquals("", stopped.answerText)
        assertFalse(stopped.isThinkingActive)
        adapter.onMessage(Message.model("late"))
        adapter.onDone()
        adapter.onError(IllegalStateException("late error"))
        assertEquals(stopped, adapter.currentSnapshot())
        assertEquals(2, snapshots.size)
        assertEquals(null, adapter.terminalError)
    }

    @Test
    fun prefillDoesNotDuplicateGeneratedTagsOrCaptureNativeChannelAnswers() {
        val explicit = LiteRtStreamAdapter("explicit", thinkingMode = true, startsInReasoning = true)
        listOf("<thi", "nk>Reason", "</think>Answer").forEach { explicit.onMessage(Message.model(it)) }
        explicit.onDone()
        assertEquals("Reason", explicit.currentSnapshot().reasoningText)
        assertEquals("Answer", explicit.currentSnapshot().answerText)
        val native = LiteRtStreamAdapter("native", thinkingMode = true, startsInReasoning = true)
        native.onMessage(Message.model(channels = mapOf("thought" to "Reason")))
        native.onMessage(Message.model("Answer"))
        native.onDone()
        assertEquals("Reason", native.currentSnapshot().reasoningText)
        assertEquals("Answer", native.currentSnapshot().answerText)
    }

    @Test
    fun terminalSnapshotsReleaseHeldLiteralSuffixesOnCompletionStopAndError() = runBlocking {
        for (suffix in listOf("<", "[", "<thi", "</thi")) {
            for (terminal in listOf("done", "stop", "error")) {
                val controller = InferenceResponseController(thinkingMode = false, requestId = "literal")
                val deltas = mutableListOf<String>()
                val adapter = LiteRtStreamAdapter("literal", onDelta = { deltas += it }, onSnapshot = {
                    controller.appendStructured(it.answerText, it.reasoningText, it.isThinkingActive,
                        "literal", 1, replaceSnapshot = true)
                })
                val text = "Literal suffix: $suffix"
                adapter.onMessage(Message.model(text))
                assertEquals("Literal suffix: ", adapter.currentSnapshot().answerText)
                when (terminal) {
                    "done" -> adapter.onDone()
                    "stop" -> adapter.cancel()
                    else -> adapter.onError(IllegalStateException("failure"))
                }
                controller.finish()
                assertEquals(text, adapter.currentSnapshot().answerText)
                assertEquals(text, controller.getFinalAnswer())
                assertEquals(text, deltas.joinToString(""))
                assertFalse(controller.snapshot().isThinkingActive)
                if (terminal == "done") assertEquals(text, adapter.awaitDone().answerText)
            }
        }
    }

    @Test
    fun terminalSuffixInsideReasoningIsNeverPromotedToAnswer() {
        val controller = InferenceResponseController(thinkingMode = true, requestId = "reason-literal")
        val adapter = LiteRtStreamAdapter("reason-literal", thinkingMode = true, startsInReasoning = true,
            onSnapshot = { controller.appendStructured(it.answerText, it.reasoningText, it.isThinkingActive,
                "reason-literal", 1, replaceSnapshot = true) })
        adapter.onMessage(Message.model("Reason about <"))
        adapter.onDone()
        controller.finish()
        assertEquals("Reason about <", controller.getReasoningText())
        assertEquals("", controller.getFinalAnswer())
        assertFalse(controller.snapshot().isThinkingActive)
    }

    @Test
    fun splitRawTagsAreSeparatedBeforeStructuredPublication() {
        val controller = InferenceResponseController(thinkingMode = true, rawUserText = "Hi", requestId = "raw")
        val adapter = LiteRtStreamAdapter("raw", thinkingMode = true, onSnapshot = {
            controller.appendStructured(it.answerText, it.reasoningText, it.isThinkingActive, "raw", 1, replaceSnapshot = true)
        })
        listOf("<thi", "nk>Careful", " thought</thi", "nk>Answer").forEach {
            adapter.onMessage(Message.model(it))
            assertFalse(controller.snapshot().answerText.contains("<think>"))
        }
        adapter.onDone()
        controller.finish()
        assertEquals("Careful thought", controller.getReasoningText())
        assertEquals("Answer", controller.getFinalAnswer())
        assertEquals(adapter.currentSnapshot().answerText, controller.getFinalAnswer())
    }

    @Test
    fun rawThinkingOffDoesNotLeakIntoLiveOrFinalAnswer() {
        val controller = InferenceResponseController(thinkingMode = false, rawUserText = "Hi", requestId = "off")
        val deltas = mutableListOf<String>()
        val adapter = LiteRtStreamAdapter("off", thinkingMode = false, onDelta = { deltas += it }, onSnapshot = {
            controller.appendStructured(it.answerText, it.reasoningText, it.isThinkingActive, "off", 1, replaceSnapshot = true)
        })
        listOf("<think>", "secret", "</think>", "Hello").forEach { adapter.onMessage(Message.model(it)) }
        adapter.onDone()
        controller.finish()
        assertEquals("Hello", deltas.joinToString(""))
        assertEquals("Hello", controller.getFinalAnswer())
        assertEquals("", controller.getReasoningText())
        assertFalse(controller.snapshot().isThinkingActive)
    }

    @Test
    fun stopDuringRawThinkingPreservesOneReasoningBlockWithoutInventingAnswer() {
        val controller = InferenceResponseController(thinkingMode = true, rawUserText = "Hi", requestId = "stop")
        val adapter = LiteRtStreamAdapter("stop", thinkingMode = true, onSnapshot = {
            controller.appendStructured(it.answerText, it.reasoningText, it.isThinkingActive, "stop", 1, replaceSnapshot = true)
        })
        adapter.onMessage(Message.model("<think>Partial reasoning"))
        assertTrue(controller.snapshot().isThinkingActive)
        adapter.cancel()
        controller.finish()
        val terminal = ResponseFinalizer.finalize("stop", rawAnswer = controller.getFinalAnswer(),
            rawReasoning = controller.getReasoningText(), isStopped = true, thinkingMode = true)
        assertEquals("", terminal.answerText)
        assertEquals("Partial reasoning", terminal.reasoningText)
        assertFalse(adapter.currentSnapshot().isThinkingActive)
        adapter.onMessage(Message.model("late"))
        assertEquals("Partial reasoning", adapter.currentSnapshot().reasoningText)
    }

    @Test
    fun nativeReasoningChannelDoesNotDuplicateTaggedReasoning() {
        val adapter = LiteRtStreamAdapter("duplicate", thinkingMode = true)
        adapter.onMessage(Message.model(contents = Contents.of(Content.Text("<think>Reason</think>Answer")),
            channels = mapOf("thought" to "Reason")))
        adapter.onDone()
        assertEquals("Reason", adapter.currentSnapshot().reasoningText)
        assertEquals("Answer", adapter.currentSnapshot().answerText)
    }

    @Test
    fun customDeclaredReasoningChannelIsSeparatedForAnyModel() {
        val adapter = LiteRtStreamAdapter("custom", thinkingMode = true, reasoningChannelKeys = setOf("deliberation"))
        adapter.onMessage(Message.model(channels = mapOf("deliberation" to "Reasoning")))
        assertTrue(adapter.currentSnapshot().isThinkingActive)
        adapter.onMessage(Message.model("Answer"))
        adapter.onDone()
        assertEquals("Reasoning", adapter.currentSnapshot().reasoningText)
        assertEquals("Answer", adapter.currentSnapshot().answerText)
    }

    @Test
    fun closingTagReclassificationClearsAnEarlierAcceptedAnswer() {
        val controller = InferenceResponseController(thinkingMode = true, rawUserText = "Hi", requestId = "prefix")
        val adapter = LiteRtStreamAdapter("prefix", thinkingMode = true, onSnapshot = {
            controller.appendStructured(it.answerText, it.reasoningText, it.isThinkingActive, "prefix", 1, replaceSnapshot = true)
        })
        // An older export can prefill the opening tag, so only the closing tag is generated.
        adapter.onMessage(Message.model("Reasoning"))
        adapter.onMessage(Message.model("</think>"))
        adapter.onDone()
        controller.finish()
        assertEquals("", controller.getFinalAnswer())
        assertEquals("", controller.acceptedAnswerText())
        assertEquals("Reasoning", controller.getReasoningText())
    }

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
        assertEquals(3, snapshots.size) // Includes the authoritative terminal publication.

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

    @Test
    fun gemma4ThoughtChannelDeltasReachLiveAndFinalReasoning() {
        // LiteRT-LM 0.18 streams Gemma 4 reasoning as per-token deltas on channels["thought"]
        // (internal_callback_util.cc SendMessageToChannel), then the answer as content deltas.
        val controller = InferenceResponseController(thinkingMode = true, rawUserText = "what's up", requestId = "g4")
        val deltas = mutableListOf<String>()
        var firstTokens = 0
        val adapter = LiteRtStreamAdapter("g4", thinkingMode = true, reasoningChannelKeys = setOf("thought"),
            onFirstToken = { firstTokens++ }, onDelta = { deltas += it }, onSnapshot = {
                controller.appendStructured(it.answerText, it.reasoningText, it.isThinkingActive, "g4", 1, replaceSnapshot = true)
            })
        adapter.onMessage(Message.model(channels = mapOf("thought" to "The user")))
        assertEquals(1, firstTokens)
        assertEquals("The user", controller.snapshot().reasoningText)
        assertTrue(controller.snapshot().isThinkingActive)
        adapter.onMessage(Message.model(channels = mapOf("thought" to " is greeting me.")))
        adapter.onMessage(Message.model("Not much! "))
        adapter.onMessage(Message.model("How about you?"))
        adapter.onDone()
        controller.finish()
        assertEquals("The user is greeting me.", adapter.currentSnapshot().reasoningText)
        assertEquals("Not much! How about you?", adapter.currentSnapshot().answerText)
        assertEquals("The user is greeting me.", controller.getReasoningText())
        assertEquals("Not much! How about you?", controller.getFinalAnswer())
        assertTrue(deltas.joinToString("").startsWith("<think>\nThe user is greeting me."))
        val terminal = ResponseFinalizer.finalize("g4", rawAnswer = adapter.currentSnapshot().answerText,
            rawReasoning = adapter.currentSnapshot().reasoningText, thinkingMode = true).toExecutionResult()
        assertEquals("The user is greeting me.", terminal.reasoningText)
    }

    @Test
    fun thinkingModeFalseDiscardsAThoughtChannelSoCallersMustPassTheResolvedFlag() {
        // Documents the contract VisionInferenceManager now honours with displaysReasoning: a model
        // that thinks while the adapter and controller run with thinkingMode=false loses its reasoning.
        val controller = InferenceResponseController(thinkingMode = false, requestId = "hidden")
        val adapter = LiteRtStreamAdapter("hidden", thinkingMode = false, onSnapshot = {
            controller.appendStructured(it.answerText, it.reasoningText, it.isThinkingActive, "hidden", 1, replaceSnapshot = true)
        })
        adapter.onMessage(Message.model(channels = mapOf("thought" to "Hidden reasoning")))
        adapter.onMessage(Message.model("Answer"))
        adapter.onDone()
        controller.finish()
        assertEquals("Hidden reasoning", adapter.currentSnapshot().reasoningText)
        assertEquals("", controller.getReasoningText())
        assertEquals("Answer", controller.getFinalAnswer())
    }
}
