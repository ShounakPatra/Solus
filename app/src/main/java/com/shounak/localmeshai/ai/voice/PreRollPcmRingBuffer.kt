package com.shounak.localmeshai.ai.voice

/**
 * Fixed-capacity circular buffer storing the most recent [capacitySamples] float PCM samples
 * (default: 250 ms @ 16 kHz = 4,000 samples) so speech onset never clips initial phonemes.
 */
class PreRollPcmRingBuffer(val capacitySamples: Int = 4_000) {
    init {
        require(capacitySamples > 0) { "capacitySamples must be positive" }
    }

    private val buffer = FloatArray(capacitySamples)
    private var writePos = 0
    private var filled = 0

    fun write(samples: FloatArray, offset: Int = 0, length: Int = samples.size - offset) {
        if (length <= 0) return
        val start = if (length > capacitySamples) offset + (length - capacitySamples) else offset
        val count = minOf(length, capacitySamples)
        for (i in 0 until count) {
            buffer[writePos] = samples[start + i]
            writePos = (writePos + 1) % capacitySamples
        }
        filled = (filled + count).coerceAtMost(capacitySamples)
    }

    fun drain(): FloatArray {
        if (filled == 0) return FloatArray(0)
        val out = FloatArray(filled)
        val start = if (filled == capacitySamples) writePos else 0
        for (i in 0 until filled) {
            out[i] = buffer[(start + i) % capacitySamples]
        }
        clear()
        return out
    }

    fun clear() {
        writePos = 0
        filled = 0
    }
}

sealed interface VadTurnEvent {
    data object SpeechStarted : VadTurnEvent
    data class SpeechCommitted(val samples: FloatArray) : VadTurnEvent
    data object BargeInDetected : VadTurnEvent
}

/**
 * Pure deterministic VAD turn-taking & barge-in state machine over 512-sample (32 ms @ 16 kHz) chunks.
 * Supports:
 * - 250 ms pre-roll retention before speech onset
 * - ~500 ms hangover tail (16 chunks * 32 ms = 512 ms) before committing a speech segment
 * - Max segment safety cap (28 seconds) for Whisper's 30 s window
 * - Sustained speech barge-in threshold when the assistant is speaking/thinking
 */
class VadTurnStateMachine(
    private val sampleRateHz: Int = 16_000,
    preRollMs: Int = 250,
    hangoverMs: Int = 512,
    frameSamples: Int = 512,
    private val speechStartThreshold: Float = 0.50f,
    private val speechContinueThreshold: Float = 0.35f,
    private val bargeInConsecutiveFrames: Int = 5,
    private val minSpeechFramesToCommit: Int = 4,
    private val maxSegmentSamples: Int = 16_000 * 28
) {
    private val preRoll = PreRollPcmRingBuffer((sampleRateHz * preRollMs) / 1000)
    private val hangoverFrames = ((hangoverMs + frameSamples * 1000 / sampleRateHz - 1) / (frameSamples * 1000 / sampleRateHz)).coerceAtLeast(1)

    private var inSpeech = false
    private var silenceFrames = 0
    private var speechFrames = 0
    private var bargeInSpeechFrames = 0
    private val activeSamples = ArrayList<Float>(16_000 * 4)

    fun reset() {
        inSpeech = false
        silenceFrames = 0
        speechFrames = 0
        bargeInSpeechFrames = 0
        activeSamples.clear()
        preRoll.clear()
    }

    fun resetBargeInCounter() {
        bargeInSpeechFrames = 0
    }

    /**
     * Feeds one 512-sample frame plus its neural [speechProbability].
     * When [assistantBusy] is true, emits [VadTurnEvent.BargeInDetected] only after
     * [bargeInConsecutiveFrames] sustained speech frames so brief clicks/echoes are ignored.
     */
    fun processFrame(
        frame: FloatArray,
        speechProbability: Float,
        assistantBusy: Boolean
    ): List<VadTurnEvent> {
        val events = ArrayList<VadTurnEvent>(2)
        if (assistantBusy) {
            if (speechProbability >= speechStartThreshold) {
                bargeInSpeechFrames++
                preRoll.write(frame)
                if (bargeInSpeechFrames >= bargeInConsecutiveFrames) {
                    bargeInSpeechFrames = 0
                    inSpeech = true
                    silenceFrames = 0
                    speechFrames = bargeInConsecutiveFrames
                    activeSamples.clear()
                    val rolled = preRoll.drain()
                    for (s in rolled) activeSamples.add(s)
                    events.add(VadTurnEvent.BargeInDetected)
                    events.add(VadTurnEvent.SpeechStarted)
                }
            } else {
                bargeInSpeechFrames = 0
                preRoll.write(frame)
            }
            return events
        }

        bargeInSpeechFrames = 0
        if (!inSpeech) {
            preRoll.write(frame)
            if (speechProbability >= speechStartThreshold) {
                inSpeech = true
                silenceFrames = 0
                speechFrames = 1
                activeSamples.clear()
                val rolled = preRoll.drain()
                for (s in rolled) activeSamples.add(s)
                events.add(VadTurnEvent.SpeechStarted)
            }
            return events
        }

        for (s in frame) activeSamples.add(s)
        if (speechProbability >= speechContinueThreshold) {
            speechFrames++
            silenceFrames = 0
        } else {
            silenceFrames++
        }

        if (silenceFrames >= hangoverFrames || activeSamples.size >= maxSegmentSamples) {
            val enoughSpeech = speechFrames >= minSpeechFramesToCommit
            val segment = if (enoughSpeech) activeSamples.toFloatArray() else null
            inSpeech = false
            silenceFrames = 0
            speechFrames = 0
            activeSamples.clear()
            preRoll.clear()
            if (segment != null && segment.isNotEmpty()) {
                events.add(VadTurnEvent.SpeechCommitted(segment))
            }
        }
        return events
    }
}
