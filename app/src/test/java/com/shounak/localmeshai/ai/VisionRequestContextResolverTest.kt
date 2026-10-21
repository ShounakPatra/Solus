package com.shounak.localmeshai.ai

import com.shounak.localmeshai.ui.viewmodels.VisionChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VisionRequestContextResolverTest {

    @Test
    fun testImageOnlyPromptDefaultsToDescribeThisImage() {
        val tempFile = File.createTempFile("test_img", ".jpg")
        try {
            tempFile.writeText("fake image data")

            val history = listOf(
                VisionChatMessage(
                    text = "Initial prompt",
                    isUser = true,
                    imagePath = tempFile.absolutePath
                )
            )

            // User sends blank question on follow-up
            val resolved = VisionRequestContextResolver.resolve(
                rawQuestion = "",
                newBitmap = null,
                newFileUri = null,
                newAudioUri = null,
                newAudioBytes = null,
                currentSessionMessages = history,
                currentSessionId = "session-1"
            )

            assertTrue(resolved.isImageOnlyPrompt)
            assertEquals(VisionRequestContextResolver.DEFAULT_IMAGE_PROMPT, resolved.effectivePrompt)
            assertTrue(resolved.media is ResolvedVisionMedia.ReusedImage)
            assertEquals(tempFile.absolutePath, (resolved.media as ResolvedVisionMedia.ReusedImage).imageFile.absolutePath)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testTextFollowUpReusesPreviousImageInSameSession() {
        val tempFile = File.createTempFile("test_img2", ".jpg")
        try {
            tempFile.writeText("fake image bytes")

            val history = listOf(
                VisionChatMessage(
                    text = "What is in this picture?",
                    isUser = true,
                    imagePath = tempFile.absolutePath
                ),
                VisionChatMessage(
                    text = "A fluffy cat on a sofa.",
                    isUser = false
                )
            )

            val resolved = VisionRequestContextResolver.resolve(
                rawQuestion = "What color is its fur?",
                newBitmap = null,
                newFileUri = null,
                newAudioUri = null,
                newAudioBytes = null,
                currentSessionMessages = history,
                currentSessionId = "session-1"
            )

            assertFalse(resolved.isImageOnlyPrompt)
            assertEquals("What color is its fur?", resolved.effectivePrompt)
            assertTrue(resolved.media is ResolvedVisionMedia.ReusedImage)
            assertEquals(tempFile.absolutePath, resolved.imageFileToUse?.absolutePath)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testDocumentOrAudioTakesPrecedenceOverEarlierImage() {
        val tempFile = File.createTempFile("test_img3", ".jpg")
        try {
            tempFile.writeText("fake image bytes")

            val history = listOf(
                VisionChatMessage(
                    text = "Previous image turn",
                    isUser = true,
                    imagePath = tempFile.absolutePath
                )
            )

            val resolved = VisionRequestContextResolver.resolve(
                rawQuestion = "Summarize this document",
                newBitmap = null,
                newFileUri = null,
                newAudioUri = null,
                newAudioBytes = byteArrayOf(1, 2, 3), // explicit audio
                currentSessionMessages = history,
                currentSessionId = "session-1"
            )

            assertEquals(ResolvedVisionMedia.DocumentOrAudioOnly, resolved.media)
            assertNull(resolved.imageFileToUse)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testMissingReferencedImageYieldsAttachmentError() {
        val nonExistentPath = File("non_existent_file_xyz_123.jpg").absolutePath

        val history = listOf(
            VisionChatMessage(
                text = "Old turn",
                isUser = true,
                imagePath = nonExistentPath
            )
        )

        val resolved = VisionRequestContextResolver.resolve(
            rawQuestion = "Tell me about this image",
            newBitmap = null,
            newFileUri = null,
            newAudioUri = null,
            newAudioBytes = null,
            currentSessionMessages = history,
            currentSessionId = "session-1"
        )

        assertTrue(resolved.media is ResolvedVisionMedia.AttachmentError)
    }

    @Test
    fun testValidatePreparedImageFileThrowsWhenMissing() {
        val nonExistent = File("does_not_exist_abc.jpg")
        var threw = false
        try {
            VisionRequestContextResolver.validatePreparedImageFile(nonExistent)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun testBuildRestorationHistoryExcludesPlaceholdersAndFailedMessages() {
        val history = listOf(
            VisionChatMessage(
                text = "Hello model",
                isUser = true,
                id = "turn-1"
            ),
            VisionChatMessage(
                text = "Hello! How can I help you?",
                isUser = false,
                id = "turn-2",
                terminalStatus = RequestTerminalStatus.Completed
            ),
            VisionChatMessage(
                text = "Reading input…", // placeholder
                isUser = false,
                id = "turn-3"
            ),
            VisionChatMessage(
                text = "Failed answer",
                isUser = false,
                id = "turn-4",
                terminalStatus = RequestTerminalStatus.Failed
            ),
            VisionChatMessage(
                text = "Current user message",
                isUser = true,
                id = "current-in-flight"
            )
        )

        val restored = VisionRequestContextResolver.buildRestorationHistory(
            sessionMessages = history,
            currentRequestId = "current-in-flight",
            placeholderTexts = setOf("Reading input…")
        )

        // Only turn-1 and turn-2 should be in the restored history
        assertEquals(2, restored.size)
    }

    @Test
    fun testBuildRestorationHistoryPreservesImageOnlyUserMessages() {
        val tempImg = File.createTempFile("restored_img", ".jpg")
        try {
            tempImg.writeText("fake image bytes")
            val history = listOf(
                VisionChatMessage(
                    text = "", // blank text on image-only send
                    isUser = true,
                    imagePath = tempImg.absolutePath,
                    id = "image-turn-1"
                ),
                VisionChatMessage(
                    text = "A scenic mountain view.",
                    isUser = false,
                    id = "model-turn-1",
                    terminalStatus = RequestTerminalStatus.Completed
                )
            )

            val restored = VisionRequestContextResolver.buildRestorationHistory(
                sessionMessages = history,
                currentRequestId = "next-turn"
            )

            assertEquals(2, restored.size)
        } finally {
            tempImg.delete()
        }
    }
}
