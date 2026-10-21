package com.shounak.localmeshai.utils

import org.junit.Assert.*
import org.junit.Test

class InferenceTimingTest {
    @Test fun separatesInputPreparationFromPrefillAndDoesNotCountEmptyChunks() {
        var now = 0L
        val timing = InferenceTiming { now }
        now = 200_000_000L
        timing.startInference()
        timing.recordChunk(" ")
        now = 700_000_000L
        timing.recordChunk("Hello")
        timing.recordFirstVisibleText()
        now = 800_000_000L
        timing.recordChunk("Hello world")
        assertEquals("prepareMs=200 firstTextMs=700 prefillMs=500 totalMs=800", timing.summary())
    }

    @Test fun cancelledBeforeGenerationDoesNotInventFirstTokenTiming() {
        val timing = InferenceTiming { 1_000L }
        assertEquals("prepareMs=-1 firstTextMs=-1 prefillMs=-1 totalMs=0", timing.summary())
    }

    @Test fun calculatesAccurateThroughputStrictlyOverDecodeInterval() {
        var now = 0L
        val timing = InferenceTiming { now }

        // Acknowledge after 5ms
        now = 5_000_000L
        timing.recordAcknowledged()

        // Preparation takes 50ms (from 10ms to 60ms)
        now = 10_000_000L
        timing.startPreparation()
        now = 60_000_000L
        timing.endPreparation()

        // Queue wait takes 20ms (from 60ms to 80ms)
        now = 60_000_000L
        timing.startQueue()
        now = 80_000_000L
        timing.endQueue()

        // Loading model takes 100ms (from 80ms to 180ms)
        now = 80_000_000L
        timing.startLoading()
        now = 180_000_000L
        timing.endLoading()

        // Start inference (prefill) at 180ms
        now = 180_000_000L
        timing.startInference()

        // First token emitted at 300ms (prefill = 120ms)
        now = 300_000_000L
        timing.recordFirstToken()
        timing.recordFirstVisibleText()

        // Generation finishes at 1300ms (decode interval = 1000ms = 1.0s)
        now = 1_300_000_000L
        timing.endGeneration()

        // Decode 25 tokens in 1000ms decode interval -> 25.0 tok/sec
        val metrics = timing.buildMetrics(
            requestId = "req-123",
            sessionId = "session-abc",
            attempt = 1,
            generatedTokens = 25,
            isTokenCountEstimated = false
        )

        assertEquals("req-123", metrics.requestId)
        assertEquals("session-abc", metrics.sessionId)
        assertEquals(5L, metrics.ackLatencyMs)
        assertEquals(50L, metrics.preparationTimeMs)
        assertEquals(20L, metrics.queueTimeMs)
        assertEquals(100L, metrics.loadingValidationTimeMs)
        assertEquals(300L, metrics.firstTokenLatencyMs)
        assertEquals(300L, metrics.firstVisibleTextLatencyMs)
        assertEquals(1000L, metrics.decodeDurationMs)
        assertEquals(25, metrics.generatedTokens)
        assertFalse(metrics.isTokenCountEstimated)
        assertNotNull(metrics.decodeThroughputTokPerSec)
        assertEquals(25.0, metrics.decodeThroughputTokPerSec!!, 0.01)

        val summary = timing.detailedSummary("req-123", "session-abc", generatedTokens = 25)
        assertTrue("Summary contains tok/s: $summary", summary.contains("tok/s=25.0"))
        assertTrue("Summary contains ackMs: $summary", summary.contains("ackMs=5"))
        assertTrue("Summary contains prepMs: $summary", summary.contains("prepMs=50"))
        assertTrue("Summary contains decodeMs: $summary", summary.contains("decodeMs=1000"))
    }

    @Test fun usesNativeDecodeDurationWhenProvided() {
        var now = 0L
        val timing = InferenceTiming { now }
        now = 100_000_000L
        timing.startInference()
        now = 200_000_000L
        timing.recordFirstToken()
        now = 800_000_000L
        timing.endGeneration()

        // Native bridge reported exact JNI decode duration of 500ms for 20 tokens -> 40.0 tok/s
        val metrics = timing.buildMetrics(
            requestId = "req-jni",
            sessionId = "session-1",
            generatedTokens = 20,
            nativeDecodeDurationMs = 500L
        )

        assertEquals(500L, metrics.decodeDurationMs)
        assertEquals(40.0, metrics.decodeThroughputTokPerSec!!, 0.01)
    }

    @Test fun handlesZeroDecodeDurationWithoutDivisionByZero() {
        var now = 0L
        val timing = InferenceTiming { now }
        val metrics = timing.buildMetrics(
            requestId = "req-zero",
            sessionId = "session-0",
            generatedTokens = 0,
            nativeDecodeDurationMs = 0L
        )
        assertNull(metrics.decodeThroughputTokPerSec)
    }
    @Test fun rawChunksNeverInventTokenOrVisibleTiming() {
        var now = 0L
        val timing = InferenceTiming { now }
        now = 30_000_000L
        timing.recordChunk("<think>hidden reasoning")
        val metrics = timing.buildMetrics("raw", "session")
        assertNull(metrics.firstTokenLatencyMs)
        assertNull(metrics.firstVisibleTextLatencyMs)
        assertNull(metrics.decodeThroughputTokPerSec)
        now = 60_000_000L
        timing.recordFirstVisibleText()
        assertEquals(60L, timing.buildMetrics("raw", "session").firstVisibleTextLatencyMs)
    }

    @Test fun recordsAndFormatsPromptTokenMetrics() {
        val timing = InferenceTiming { 1_000_000L }
        val metrics = timing.buildMetrics(
            requestId = "req-prompt",
            sessionId = "session-1",
            promptTokens = 42,
            reusedTokens = 10,
            evaluatedTokens = 32,
            prefillDurationMs = 250L,
            generatedTokens = 15,
            nativeDecodeDurationMs = 500L
        )
        assertEquals(42, metrics.promptTokens)
        assertEquals(10, metrics.reusedTokens)
        assertEquals(32, metrics.evaluatedTokens)
        assertEquals(250L, metrics.prefillDurationMs)

        val summary = timing.formatMetricsSummary(metrics)
        assertTrue(summary.contains("promptToks=42"))
        assertTrue(summary.contains("reusedToks=10"))
        assertTrue(summary.contains("evalToks=32"))
        assertTrue(summary.contains("prefillMs=250"))
    }
}
