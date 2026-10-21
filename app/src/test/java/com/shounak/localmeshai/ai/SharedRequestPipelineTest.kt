package com.shounak.localmeshai.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SharedRequestPipelineTest {

    @Test
    fun testRequestProgressStatesAreDistinct() {
        val states: List<RequestProgress> = listOf(
            RequestProgress.PreparingInput,
            RequestProgress.LoadingModel,
            RequestProgress.ValidatingBackend,
            RequestProgress.ProcessingPrompt,
            RequestProgress.Generating
        )
        assertEquals(5, states.distinct().size)
    }

    @Test
    fun testRequestTerminalStatusValues() {
        assertEquals(3, RequestTerminalStatus.values().size)
        assertTrue(RequestTerminalStatus.values().contains(RequestTerminalStatus.Completed))
        assertTrue(RequestTerminalStatus.values().contains(RequestTerminalStatus.Stopped))
        assertTrue(RequestTerminalStatus.values().contains(RequestTerminalStatus.Failed))
    }

    @Test
    fun testPreparedAttachmentLifecycleAndOwnership() {
        val dummyFile = File("dummy.pdf")
        val attachment = PreparedAttachment(
            name = "document.pdf",
            mimeType = "application/pdf",
            durableFile = dummyFile,
            extractedText = "Sample document content extracted once.",
            ownsResources = false
        )

        assertEquals("document.pdf", attachment.name)
        assertEquals("application/pdf", attachment.mimeType)
        assertEquals("Sample document content extracted once.", attachment.extractedText)
        assertFalse(attachment.ownsResources)

        // Closing without owned resources is a safe no-op
        attachment.close()
    }

    @Test
    fun testInferenceExecutionResultProperties() {
        val metrics = InferenceMetrics(
            requestId = "req-1",
            sessionId = "sess-1",
            attempt = 1,
            ackLatencyMs = 12L,
            decodeDurationMs = 500L,
            generatedTokens = 15,
            isTokenCountEstimated = false,
            decodeThroughputTokPerSec = 30.0
        )

        val result = InferenceExecutionResult(
            text = "Hello world",
            status = RequestTerminalStatus.Completed,
            error = null,
            metrics = metrics
        )

        assertEquals("Hello world", result.text)
        assertEquals(RequestTerminalStatus.Completed, result.status)
        assertNull(result.error)
        assertNotNull(result.metrics)
        assertEquals(30.0, result.metrics?.decodeThroughputTokPerSec!!, 0.001)
    }

}
