package com.shounak.localmeshai.ui

import com.shounak.localmeshai.ai.RequestTerminalStatus
import com.shounak.localmeshai.data.ChatSessionRepository
import com.shounak.localmeshai.ui.viewmodels.ChatMessage
import com.shounak.localmeshai.ui.viewmodels.ChatSession
import com.shounak.localmeshai.ui.viewmodels.VisionChatMessage
import com.shounak.localmeshai.ui.viewmodels.VisionChatSession
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class MessageRetryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testSessionIsolation_FailInA_SendInB_RetryInA() {
        val rootDir = tempFolder.newFolder("session_isolation")
        val repo = ChatSessionRepository(rootDir)

        val uAId = "user-msg-A"
        val aAId = "asst-msg-A"
        val sessionA = ChatSession(
            id = "session-A",
            title = "Chat A",
            messages = listOf(
                ChatMessage(id = uAId, text = "Prompt A in session A", isUser = true),
                ChatMessage(
                    id = aAId,
                    text = "",
                    isUser = false,
                    terminalStatus = RequestTerminalStatus.Failed,
                    errorMessage = "Network timeout",
                    userMessageId = uAId,
                    attemptId = 1
                )
            ),
            updatedAt = 1000L
        )

        val uBId = "user-msg-B"
        val aBId = "asst-msg-B"
        val sessionB = ChatSession(
            id = "session-B",
            title = "Chat B",
            messages = listOf(
                ChatMessage(id = uBId, text = "Prompt B in session B", isUser = true),
                ChatMessage(
                    id = aBId,
                    text = "Answer B",
                    isUser = false,
                    terminalStatus = RequestTerminalStatus.Completed,
                    userMessageId = uBId,
                    attemptId = 1
                )
            ),
            updatedAt = 2000L
        )

        repo.saveChatSessions(listOf(sessionA, sessionB))

        // When retrieving the request to retry in Session A
        val requestA = repo.findChatUserRequest("session-A", uAId)
        assertNotNull(requestA)
        assertEquals("session-A", requestA?.sessionId)
        assertEquals(uAId, requestA?.userMessageId)
        assertEquals("Prompt A in session A", requestA?.text)

        // Verifies cross-session isolation: cannot look up session B message in session A
        val crossLookup = repo.findChatUserRequest("session-A", uBId)
        assertNull("Cross-session lookup must return null", crossLookup)

        // Session B lookup returns session B context
        val requestB = repo.findChatUserRequest("session-B", uBId)
        assertNotNull(requestB)
        assertEquals("Prompt B in session B", requestB?.text)
    }

    @Test
    fun testRetryKeepsOriginalUserTurnAndIncrementsAttemptId() {
        val userMsgId = "user-original-id"
        val assistantTurn1Id = "asst-attempt-1"

        val userMessage = ChatMessage(
            id = userMsgId,
            text = "Tell me a story",
            isUser = true
        )
        val failedAssistantMessage = ChatMessage(
            id = assistantTurn1Id,
            text = "",
            isUser = false,
            terminalStatus = RequestTerminalStatus.Failed,
            errorMessage = "Inference interrupted",
            attemptId = 1,
            userMessageId = userMsgId
        )

        val messages = mutableListOf(userMessage, failedAssistantMessage)

        // Simulating retry of turn:
        val existingAssistantIndex = messages.indexOfLast { !it.isUser && it.userMessageId == userMsgId }
        val previousAttemptId = messages[existingAssistantIndex].attemptId
        val nextAttemptId = previousAttemptId + 1

        val newRequestId = UUID.randomUUID().toString()
        messages[existingAssistantIndex] = messages[existingAssistantIndex].copy(
            text = "Generating...",
            terminalStatus = null,
            errorMessage = null,
            requestId = newRequestId,
            attemptId = nextAttemptId
        )

        // Verification:
        // 1. Original user message was NOT modified or replaced
        assertEquals(userMsgId, messages[0].id)
        assertEquals("Tell me a story", messages[0].text)
        assertTrue(messages[0].isUser)

        // 2. Assistant turn received incremented attempt ID (2)
        assertEquals(2, messages[1].attemptId)
        assertEquals(newRequestId, messages[1].requestId)
        assertEquals(userMsgId, messages[1].userMessageId)
    }

    @Test
    fun testExcludingFailedAssistantTurnsFromHistory() {
        val messages = listOf(
            ChatMessage(id = "u1", text = "First question", isUser = true),
            ChatMessage(id = "a1", text = "First answer", isUser = false, terminalStatus = RequestTerminalStatus.Completed),
            ChatMessage(id = "u2", text = "Second question", isUser = true),
            ChatMessage(
                id = "a2",
                text = "Interrupted...",
                isUser = false,
                terminalStatus = RequestTerminalStatus.Failed,
                errorMessage = "Engine crashed"
            ),
            ChatMessage(id = "u3", text = "Third question", isUser = true)
        )

        // Emulate the filter used by ChatViewModel.buildStructuredHistory and buildPrompt
        val priorHistory = messages.dropLast(1)
        val validTurns = priorHistory.filter { message ->
            if (message.isUser) {
                true
            } else {
                message.terminalStatus != RequestTerminalStatus.Failed &&
                message.errorMessage == null &&
                message.text.isNotBlank()
            }
        }

        assertEquals(3, validTurns.size)
        assertEquals("u1", validTurns[0].id)
        assertEquals("a1", validTurns[1].id)
        assertEquals("u2", validTurns[2].id)
        // a2 must be excluded because it failed!
        assertFalse(validTurns.any { it.id == "a2" })
    }

    @Test
    fun testMissingAttachmentReportingOnRetry() {
        val rootDir = tempFolder.newFolder("missing_attachment_test")
        val missingFile = File(rootDir, "missing_doc.pdf")
        assertFalse(missingFile.exists())

        val userMessage = ChatMessage(
            id = "u-attach",
            text = "Summarize this doc",
            isUser = true,
            documentName = "missing_doc.pdf",
            documentPath = missingFile.absolutePath
        )

        // When retrying, validating existence of the durable file:
        val docFile = File(userMessage.documentPath!!)
        val fileMissing = !docFile.exists()
        assertTrue("Missing file must be detected", fileMissing)

        // If file exists:
        val existingFile = tempFolder.newFile("present_doc.pdf")
        existingFile.writeText("sample content")
        val validMessage = userMessage.copy(documentPath = existingFile.absolutePath)
        val validDocFile = File(validMessage.documentPath!!)
        assertTrue("Existing file must pass check", validDocFile.exists())
    }

    @Test
    fun testVisionRetryReconstructionFromDurableFile() {
        val rootDir = tempFolder.newFolder("vision_retry_test")
        val durableImage = File(rootDir, "cached_photo.png")
        durableImage.writeBytes(byteArrayOf(1, 2, 3, 4))

        val repo = ChatSessionRepository(rootDir)
        val userMsgId = "vision-user-1"
        val session = VisionChatSession(
            id = "vision-sess-1",
            title = "Analyze photo",
            question = "Describe the image",
            answer = "",
            updatedAt = 3000L,
            imagePath = durableImage.absolutePath,
            messages = listOf(
                VisionChatMessage(
                    id = userMsgId,
                    text = "Describe the image",
                    isUser = true,
                    imagePath = durableImage.absolutePath
                ),
                VisionChatMessage(
                    id = "vision-asst-1",
                    text = "",
                    isUser = false,
                    terminalStatus = RequestTerminalStatus.Failed,
                    errorMessage = "GPU Out of memory",
                    attemptId = 1,
                    userMessageId = userMsgId
                )
            )
        )

        repo.saveVisionSessions(listOf(session))

        val request = repo.findVisionUserRequest("vision-sess-1", userMsgId)
        assertNotNull(request)
        assertEquals("Describe the image", request?.question)
        assertEquals(durableImage.absolutePath, request?.imagePath)
        assertTrue(File(request!!.imagePath!!).exists())
    }
}
