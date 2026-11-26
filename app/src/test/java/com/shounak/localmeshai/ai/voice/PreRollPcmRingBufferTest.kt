package com.shounak.localmeshai.ai.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreRollPcmRingBufferTest {

    @Test
    fun `ring buffer retains latest samples across wrap-around`() {
        val ring = PreRollPcmRingBuffer(capacitySamples = 4)
        ring.write(floatArrayOf(1f, 2f, 3f))
        ring.write(floatArrayOf(4f, 5f, 6f))
        assertArrayEquals(floatArrayOf(3f, 4f, 5f, 6f), ring.drain(), 1e-6f)
        assertEquals(0, ring.drain().size)
    }

    @Test
    fun `vad commits segment with pre-roll after hangover window`() {
        val machine = VadTurnStateMachine(
            sampleRateHz = 16_000,
            preRollMs = 64, // 1024 samples = 2 frames of 512
            hangoverMs = 96, // 3 frames
            frameSamples = 512,
            minSpeechFramesToCommit = 2
        )
        val silenceFrame = FloatArray(512) { 0.01f }
        val speechFrame = FloatArray(512) { 0.5f }

        // 2 silence pre-roll frames
        assertTrue(machine.processFrame(silenceFrame, 0.05f, assistantBusy = false).isEmpty())
        assertTrue(machine.processFrame(silenceFrame, 0.05f, assistantBusy = false).isEmpty())

        // Onset + 2 speech frames
        val onsetEvents = machine.processFrame(speechFrame, 0.90f, assistantBusy = false)
        assertEquals(listOf(VadTurnEvent.SpeechStarted), onsetEvents)
        assertTrue(machine.processFrame(speechFrame, 0.85f, assistantBusy = false).isEmpty())
        assertTrue(machine.processFrame(speechFrame, 0.80f, assistantBusy = false).isEmpty())

        // Hangover tail (3 silence frames -> commit on 3rd)
        assertTrue(machine.processFrame(silenceFrame, 0.10f, assistantBusy = false).isEmpty())
        assertTrue(machine.processFrame(silenceFrame, 0.10f, assistantBusy = false).isEmpty())
        val commitEvents = machine.processFrame(silenceFrame, 0.10f, assistantBusy = false)
        assertEquals(1, commitEvents.size)
        val committed = commitEvents.first() as VadTurnEvent.SpeechCommitted
        // 2 pre-roll frames (drained at onset) + 2 speech + 3 hangover = 7 frames = 3584 samples
        assertEquals(7 * 512, committed.samples.size)
    }

    @Test
    fun `sustained speech triggers barge-in while assistant busy`() {
        val machine = VadTurnStateMachine(bargeInConsecutiveFrames = 3)
        val frame = FloatArray(512) { 0.25f }

        // 2 frames -> brief click/echo ignored
        assertTrue(machine.processFrame(frame, 0.9f, assistantBusy = true).isEmpty())
        assertTrue(machine.processFrame(frame, 0.9f, assistantBusy = true).isEmpty())

        // Silence resets counter
        assertTrue(machine.processFrame(frame, 0.1f, assistantBusy = true).isEmpty())

        // 3 consecutive speech frames -> triggers BargeInDetected + SpeechStarted
        assertTrue(machine.processFrame(frame, 0.9f, assistantBusy = true).isEmpty())
        assertTrue(machine.processFrame(frame, 0.9f, assistantBusy = true).isEmpty())
        val bargeEvents = machine.processFrame(frame, 0.9f, assistantBusy = true)
        assertEquals(listOf(VadTurnEvent.BargeInDetected, VadTurnEvent.SpeechStarted), bargeEvents)
    }
}
