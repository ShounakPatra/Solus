package com.shounak.localmeshai.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shounak.localmeshai.ai.EmptyResponseReason
import com.shounak.localmeshai.ai.FinalizedResponse
import com.shounak.localmeshai.ai.RequestTerminalStatus
import com.shounak.localmeshai.ai.ResolvedVisionMedia
import com.shounak.localmeshai.ai.ResponseFinalizer
import com.shounak.localmeshai.ai.VisionRequestContextResolver
import com.shounak.localmeshai.ui.viewmodels.VisionChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VisionAnswerCompletionTest {

    @Test
    fun messagePreservesPartialAnswerAlongsideErrorMessage() {
        // When inference fails halfway through generation, partial output is kept in text
        // and the terminal failure description is placed in errorMessage.
        val partialText = "I see a cat with orange fur sitting near"
        val errorReason = "The model finished without an answer. Retry this message."

        val message = VisionChatMessage(
            text = partialText,
            isUser = false,
            terminalStatus = RequestTerminalStatus.Failed,
            errorMessage = errorReason
        )

        assertEquals("I see a cat with orange fur sitting near", message.text)
        assertEquals(errorReason, message.errorMessage)
        assertEquals(RequestTerminalStatus.Failed, message.terminalStatus)
        assertFalse(message.isUser)
    }

    @Test
    fun responseFinalizerMapsTerminalFailureCorrectly() {
        val result = ResponseFinalizer.finalize(
            requestId = "req-101",
            attemptId = 1,
            rawAnswer = "Steps to solve the puzzle: 1.",
            rawReasoning = "Analyzing visual layout",
            sdkError = RuntimeException("Connection aborted")
        )

        assertTrue(result is FinalizedResponse.Failed)
        assertEquals("Steps to solve the puzzle: 1.", result.answerText)
        assertEquals("Connection aborted", result.error)
        assertEquals(RequestTerminalStatus.Failed, result.status)

        val execResult = result.toExecutionResult()
        assertEquals("Steps to solve the puzzle: 1.", execResult.text)
        assertEquals("Connection aborted", execResult.error)
        assertEquals(RequestTerminalStatus.Failed, execResult.status)
    }

    @Test
    fun emptyAnswerWithReasoningYieldsReasoningOnlyOnAndroid() {
        val result = ResponseFinalizer.finalize(
            requestId = "req-102",
            attemptId = 1,
            rawAnswer = "",
            rawReasoning = "Analyzing the pixels in the bounding box..."
        )

        assertTrue(result is FinalizedResponse.Failed)
        assertEquals("", result.answerText)
        assertEquals("Analyzing the pixels in the bounding box...", result.reasoningText)
        assertEquals(EmptyResponseReason.ReasoningOnly, result.emptyReason)
        assertEquals(RequestTerminalStatus.Failed, result.status)
        assertEquals(ResponseFinalizer.FAILED_NO_ANSWER_MESSAGE, result.error)
    }

    @Test
    fun imageOnlySendResolvesDefaultPromptOnAndroid() {
        val tempFile = File.createTempFile("android_test_img", ".jpg")
        try {
            tempFile.writeText("simulated image data")

            val history = listOf(
                VisionChatMessage(
                    text = "Previous conversation",
                    isUser = true,
                    imagePath = tempFile.absolutePath
                )
            )

            val resolved = VisionRequestContextResolver.resolve(
                rawQuestion = "",
                newBitmap = null,
                newFileUri = null,
                newAudioUri = null,
                newAudioBytes = null,
                currentSessionMessages = history,
                currentSessionId = "session-android-1"
            )

            assertTrue(resolved.isImageOnlyPrompt)
            assertEquals("Describe this image.", resolved.effectivePrompt)
            assertTrue(resolved.media is ResolvedVisionMedia.ReusedImage)
        } finally {
            tempFile.delete()
        }
    }
}
