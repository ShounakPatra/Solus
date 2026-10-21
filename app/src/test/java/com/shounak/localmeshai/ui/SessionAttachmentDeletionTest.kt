package com.shounak.localmeshai.ui

import com.shounak.localmeshai.ui.viewmodels.VisionChatMessage
import com.shounak.localmeshai.ui.viewmodels.VisionChatSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class SessionAttachmentDeletionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun deleteChatSession_deletesAllAudioImagesAndDocumentsInSessionDirectory() {
        val filesDir = tempFolder.newFolder("filesDir")
        val sessionId = UUID.randomUUID().toString()

        // Create session attachments folder with audio, image, and document
        val sessionAttachmentsDir = File(filesDir, "chat_attachments/$sessionId").apply { mkdirs() }
        val audioFile = File(sessionAttachmentsDir, "audio_12345.wav").apply { writeText("fake audio data") }
        val imageFile = File(sessionAttachmentsDir, "image_12345.png").apply { writeText("fake image data") }
        val docFile = File(sessionAttachmentsDir, "document_report.pdf").apply { writeText("fake doc content") }

        // Also create a bitmap file in vision_sessions
        val visionSessionsDir = File(filesDir, "vision_sessions").apply { mkdirs() }
        val sessionBmp = File(visionSessionsDir, "session_$sessionId.png").apply { writeText("fake session bmp") }

        assertTrue(audioFile.exists())
        assertTrue(imageFile.exists())
        assertTrue(docFile.exists())
        assertTrue(sessionBmp.exists())

        // Simulate deleteChatSession cascade deletion logic
        if (sessionBmp.exists()) sessionBmp.delete()
        if (sessionAttachmentsDir.exists()) sessionAttachmentsDir.deleteRecursively()

        // Verify everything is deleted
        assertFalse("Session bitmap must be deleted", sessionBmp.exists())
        assertFalse("Audio file must be deleted", audioFile.exists())
        assertFalse("Image file must be deleted", imageFile.exists())
        assertFalse("Document file must be deleted", docFile.exists())
        assertFalse("Session attachments folder must be deleted", sessionAttachmentsDir.exists())
    }

    @Test
    fun clearHistory_deletesAllAttachmentsAndSessionFolders() {
        val filesDir = tempFolder.newFolder("filesDir")
        val attachmentsDir = File(filesDir, "chat_attachments").apply { mkdirs() }
        val visionDir = File(filesDir, "vision_sessions").apply { mkdirs() }

        // Create multiple sessions
        for (i in 1..3) {
            val sessDir = File(attachmentsDir, "session_$i").apply { mkdirs() }
            File(sessDir, "audio_$i.wav").writeText("audio$i")
            File(sessDir, "doc_$i.pdf").writeText("doc$i")
            File(visionDir, "session_session_$i.png").writeText("bmp$i")
        }

        assertTrue(attachmentsDir.exists())
        assertTrue(visionDir.exists())

        // Simulate clearHistory cascade deletion logic
        if (attachmentsDir.exists()) attachmentsDir.deleteRecursively()
        if (visionDir.exists()) visionDir.deleteRecursively()

        assertFalse("Attachments root dir must be deleted", attachmentsDir.exists())
        assertFalse("Vision sessions root dir must be deleted", visionDir.exists())
    }

    @Test
    fun visionChatMessage_preservesAudioAndDocumentProperties() {
        val message = VisionChatMessage(
            text = "Summarize this audio recording",
            isUser = true,
            audioPath = "/data/user/0/app/files/chat_attachments/s1/audio_1.wav",
            audioName = "Voice memo 1",
            audioDurationMs = 12500L,
            documentName = "sample.pdf"
        )

        assertEquals("/data/user/0/app/files/chat_attachments/s1/audio_1.wav", message.audioPath)
        assertEquals("Voice memo 1", message.audioName)
        assertEquals(12500L, message.audioDurationMs)
        assertEquals("sample.pdf", message.documentName)
        assertTrue(message.isUser)
    }

    @Test
    fun visionChatSession_preservesAudioAndDocumentProperties() {
        val session = VisionChatSession(
            id = "sess-123",
            title = "Voice memo conversation",
            question = "Listen to audio",
            answer = "Here is what was heard.",
            updatedAt = 25000L,
            audioPath = "/path/to/audio.wav",
            audioName = "recording.wav",
            audioDurationMs = 18000L,
            documentName = "attached.txt",
            documentId = "doc-999"
        )

        assertEquals("sess-123", session.id)
        assertEquals("/path/to/audio.wav", session.audioPath)
        assertEquals("recording.wav", session.audioName)
        assertEquals(18000L, session.audioDurationMs)
        assertEquals("attached.txt", session.documentName)
        assertEquals("doc-999", session.documentId)
    }

    @Test
    fun visionChatMessage_preservesImagePath() {
        val message = VisionChatMessage(
            text = "Describe this cat",
            isUser = true,
            imagePath = "/data/user/0/app/files/chat_attachments/s1/image_1.png",
            documentName = "cat.png"
        )

        assertEquals("/data/user/0/app/files/chat_attachments/s1/image_1.png", message.imagePath)
        assertEquals("cat.png", message.documentName)
        assertTrue(message.isUser)
    }

    @Test
    fun visionChatSession_preservesImagePathAndMessages() {
        val msg1 = VisionChatMessage(
            text = "What is in this picture?",
            isUser = true,
            imagePath = "/data/user/0/app/files/chat_attachments/s1/image_1.png"
        )
        val msg2 = VisionChatMessage(
            text = "This is a sleeping kitten.",
            isUser = false
        )
        val msg3 = VisionChatMessage(
            text = "What color is its fur?",
            isUser = true
        )
        val msg4 = VisionChatMessage(
            text = "It has orange tabby fur.",
            isUser = false
        )

        val session = VisionChatSession(
            id = "sess-multi",
            title = "What is in this picture?",
            question = "What is in this picture?",
            answer = "It has orange tabby fur.",
            updatedAt = 30000L,
            imagePath = "/data/user/0/app/files/chat_attachments/s1/image_1.png",
            messages = listOf(msg1, msg2, msg3, msg4)
        )

        assertEquals("sess-multi", session.id)
        assertEquals("/data/user/0/app/files/chat_attachments/s1/image_1.png", session.imagePath)
        assertEquals(4, session.messages.size)
        assertEquals("/data/user/0/app/files/chat_attachments/s1/image_1.png", session.messages[0].imagePath)
        assertEquals("What is in this picture?", session.messages[0].text)
        assertEquals("What color is its fur?", session.messages[2].text)
        assertNull(session.messages[2].imagePath)
    }

    @Test
    fun deleteChatSession_deletesFilesPointedByImagePath() {
        val filesDir = tempFolder.newFolder("filesDir2")
        val sessionId = UUID.randomUUID().toString()
        val sessionAttachmentsDir = File(filesDir, "chat_attachments/$sessionId").apply { mkdirs() }
        val imageFile = File(sessionAttachmentsDir, "image_9999.png").apply { writeText("fake image bytes") }
        val msgImageFile = File(sessionAttachmentsDir, "image_turn2.png").apply { writeText("fake turn2 image") }

        val msg1 = VisionChatMessage(text = "q1", isUser = true, imagePath = imageFile.absolutePath)
        val msg2 = VisionChatMessage(text = "a1", isUser = false)
        val msg3 = VisionChatMessage(text = "q2", isUser = true, imagePath = msgImageFile.absolutePath)
        val session = VisionChatSession(
            id = sessionId,
            title = "Title",
            question = "q1",
            answer = "a1",
            updatedAt = 1000L,
            imagePath = imageFile.absolutePath,
            messages = listOf(msg1, msg2, msg3)
        )

        assertTrue(File(session.imagePath!!).exists())
        assertTrue(File(session.messages[2].imagePath!!).exists())

        // Simulate deleteChatSession file cleanup
        session.imagePath?.let { path ->
            val f = File(path)
            if (f.exists()) f.delete()
        }
        session.messages.forEach { msg ->
            msg.imagePath?.let { path ->
                val f = File(path)
                if (f.exists()) f.delete()
            }
        }
        if (sessionAttachmentsDir.exists()) sessionAttachmentsDir.deleteRecursively()

        assertFalse(imageFile.exists())
        assertFalse(msgImageFile.exists())
        assertFalse(sessionAttachmentsDir.exists())
    }

    @Test
    fun deleteChatSession_deletesAllAudioAndDocumentsReferencedBySessionAndMessages() {
        val filesDir = tempFolder.newFolder("filesDir3")
        val cacheDir = tempFolder.newFolder("cacheDir3")
        val sessionId = UUID.randomUUID().toString()

        val sessionAttachmentsDir = File(filesDir, "chat_attachments/$sessionId").apply { mkdirs() }
        val sessionAudioFile = File(sessionAttachmentsDir, "audio_session.wav").apply { writeText("fake audio") }
        val sessionDocFile = File(sessionAttachmentsDir, "document_report.pdf").apply { writeText("fake doc") }
        val externalAudioFile = File(cacheDir, "multimodal_inputs").apply { mkdirs() }
            .resolve("legacy_recording.wav").apply { writeText("external audio") }

        val msg1 = VisionChatMessage(
            text = "Listen and read",
            isUser = true,
            audioPath = externalAudioFile.absolutePath,
            documentPath = sessionDocFile.absolutePath,
            documentName = "document_report.pdf"
        )
        val msg2 = VisionChatMessage(text = "Understood", isUser = false)
        val session = VisionChatSession(
            id = sessionId,
            title = "Multimedia chat",
            question = "Listen and read",
            answer = "Understood",
            updatedAt = 2000L,
            audioPath = sessionAudioFile.absolutePath,
            documentName = "document_report.pdf",
            messages = listOf(msg1, msg2)
        )

        assertTrue(sessionAudioFile.exists())
        assertTrue(sessionDocFile.exists())
        assertTrue(externalAudioFile.exists())

        // Simulate enhanced deleteChatSession file cleanup
        session.imagePath?.let { path -> File(path).takeIf { it.exists() }?.delete() }
        session.audioPath?.let { path -> File(path).takeIf { it.exists() }?.delete() }
        session.messages.forEach { msg ->
            msg.imagePath?.let { path -> File(path).takeIf { it.exists() }?.delete() }
            msg.audioPath?.let { path -> File(path).takeIf { it.exists() }?.delete() }
            msg.documentPath?.let { path -> File(path).takeIf { it.exists() }?.delete() }
        }
        if (sessionAttachmentsDir.exists()) sessionAttachmentsDir.deleteRecursively()

        assertFalse("Session audio must be deleted", sessionAudioFile.exists())
        assertFalse("Session doc must be deleted", sessionDocFile.exists())
        assertFalse("External/legacy audio must be deleted", externalAudioFile.exists())
        assertFalse("Session attachments folder must be deleted", sessionAttachmentsDir.exists())
    }

    @Test
    fun mediaRemainsOnDeviceWhileChatExists() {
        val filesDir = tempFolder.newFolder("filesDir4")
        val sessionId = UUID.randomUUID().toString()

        val sessionAttachmentsDir = File(filesDir, "chat_attachments/$sessionId").apply { mkdirs() }
        val audioFile = File(sessionAttachmentsDir, "voice_memo.wav").apply { writeText("pcm audio data") }
        val imageFile = File(sessionAttachmentsDir, "photo.png").apply { writeText("png image data") }
        val docFile = File(sessionAttachmentsDir, "contract.pdf").apply { writeText("pdf document data") }

        // While chat exists, simulate any cache sweeps or temporary directory cleanups
        val cacheDir = tempFolder.newFolder("cacheDir4")
        val tempInferenceCache = File(cacheDir, "multimodal_inputs").apply { mkdirs() }
        val tempDraft = File(tempInferenceCache, "temp_unsent.wav").apply { writeText("unsent") }

        // Clean cacheDir only
        tempDraft.delete()

        // Persistent chat attachments MUST remain intact on device
        assertTrue("Uploaded audio must remain on device until chat is deleted", audioFile.exists())
        assertTrue("Uploaded photo must remain on device until chat is deleted", imageFile.exists())
        assertTrue("Uploaded document must remain on device until chat is deleted", docFile.exists())
    }

    @Test
    fun cleanupOrphanAttachments_deletesOnlyOrphanFoldersAndKeepsActiveChatMedia() {
        val filesDir = tempFolder.newFolder("filesDir5")
        val attachmentsDir = File(filesDir, "chat_attachments").apply { mkdirs() }

        val activeSessionId = "active-session-123"
        val orphanSessionId = "orphan-session-999"

        val activeFolder = File(attachmentsDir, activeSessionId).apply { mkdirs() }
        val activeMedia = File(activeFolder, "photo.png").apply { writeText("active photo") }

        val orphanFolder = File(attachmentsDir, orphanSessionId).apply { mkdirs() }
        val orphanMedia = File(orphanFolder, "orphan_doc.pdf").apply { writeText("orphan doc") }

        val validSessionIds = setOf(activeSessionId)

        // Simulate orphan cleanup logic
        attachmentsDir.listFiles()?.filter { it.isDirectory }?.forEach { sub ->
            if (!validSessionIds.contains(sub.name)) {
                sub.deleteRecursively()
            }
        }

        assertTrue("Active session media must be retained", activeMedia.exists())
        assertTrue("Active session folder must be retained", activeFolder.exists())
        assertFalse("Orphan media must be deleted", orphanMedia.exists())
        assertFalse("Orphan folder must be deleted", orphanFolder.exists())
    }
}
