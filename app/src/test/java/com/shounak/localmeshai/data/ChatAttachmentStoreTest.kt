package com.shounak.localmeshai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ChatAttachmentStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun getSessionAttachmentsDir_createsPartitionedDirectorySafely() {
        val filesDir = tempFolder.newFolder("files")
        val store = ChatAttachmentStore(filesDir)

        val textSessionDir = store.getSessionAttachmentsDir(ChatKind.Text, "session_123", create = true)
        val visionSessionDir = store.getSessionAttachmentsDir(ChatKind.Vision, "session_456", create = true)

        assertTrue(textSessionDir.exists())
        assertTrue(visionSessionDir.exists())
        assertTrue(textSessionDir.path.contains("text"))
        assertTrue(visionSessionDir.path.contains("vision"))
    }

    @Test
    fun getSessionAttachmentsDir_rejectsPathTraversal() {
        val filesDir = tempFolder.newFolder("files")
        val store = ChatAttachmentStore(filesDir)

        try {
            store.getSessionAttachmentsDir(ChatKind.Text, "../evil_dir")
            fail("Expected IllegalArgumentException for path traversal in session ID")
        } catch (e: IllegalArgumentException) {
            // Expected
        }
    }

    @Test
    fun getAttachmentDir_createsAndRejectsTraversal() {
        val filesDir = tempFolder.newFolder("files")
        val store = ChatAttachmentStore(filesDir)

        val attachDir = store.getAttachmentDir(ChatKind.Text, "session_123", "attachment_abc", create = true)
        assertTrue(attachDir.exists())
        assertTrue(attachDir.path.contains("session_123"))
        assertTrue(attachDir.path.contains("attachment_abc"))

        try {
            store.getAttachmentDir(ChatKind.Text, "session_123", "../../attack")
            fail("Expected IllegalArgumentException for traversal in attachment ID")
        } catch (e: IllegalArgumentException) {
            // Expected
        }
    }

    @Test
    fun isOwnedAttachment_distinguishesOwnedFromUnownedFiles() {
        val filesDir = tempFolder.newFolder("files")
        val store = ChatAttachmentStore(filesDir)

        val sessionDir = store.getSessionAttachmentsDir(ChatKind.Text, "sess_1", create = true)
        val ownedFile = sessionDir.resolve("sample.pdf").apply { writeText("Hello PDF") }

        val outsideFile = filesDir.resolve("external.pdf").apply { writeText("Hello External") }
        val tempFile = tempFolder.newFolder("other").resolve("temp.txt").apply { writeText("Temp") }

        assertTrue(store.isOwnedAttachment(ownedFile))
        assertTrue(store.isOwnedAttachment(ownedFile, ChatKind.Text, "sess_1"))
        assertFalse(store.isOwnedAttachment(ownedFile, ChatKind.Text, "sess_2"))
        assertFalse(store.isOwnedAttachment(ownedFile, ChatKind.Vision, "sess_1"))

        assertFalse(store.isOwnedAttachment(outsideFile))
        assertFalse(store.isOwnedAttachment(tempFile))
    }

    @Test
    fun storeAttachmentFromFile_copiesDataAndVerifiesContent() {
        val filesDir = tempFolder.newFolder("files")
        val store = ChatAttachmentStore(filesDir)

        val srcFile = tempFolder.newFile("source_doc.txt").apply {
            writeText("Source document content for attachment test")
        }

        val storedFile = store.storeAttachmentFromFile(
            chatKind = ChatKind.Text,
            sessionId = "sess_100",
            attachmentId = "att_1",
            sourceFile = srcFile,
            targetFileName = "source_doc.txt"
        )

        assertTrue(storedFile.exists())
        assertEquals("Source document content for attachment test", storedFile.readText())
        assertTrue(store.isOwnedAttachment(storedFile, ChatKind.Text, "sess_100"))

        val owned = store.getOwnedFilesForSession(ChatKind.Text, "sess_100")
        assertEquals(1, owned.size)
        assertEquals(storedFile.canonicalPath, owned[0].canonicalPath)
    }

    @Test
    fun storeAttachmentFromFile_rejectsMissingOrEmptyFile() {
        val filesDir = tempFolder.newFolder("files")
        val store = ChatAttachmentStore(filesDir)

        val emptyFile = tempFolder.newFile("empty.txt")
        try {
            store.storeAttachmentFromFile(
                chatKind = ChatKind.Text,
                sessionId = "sess_200",
                sourceFile = emptyFile
            )
            fail("Expected IOException for empty file")
        } catch (e: IOException) {
            // Expected
        }

        val nonExistent = File(tempFolder.root, "non_existent.txt")
        try {
            store.storeAttachmentFromFile(
                chatKind = ChatKind.Text,
                sessionId = "sess_200",
                sourceFile = nonExistent
            )
            fail("Expected IOException for non-existent file")
        } catch (e: IOException) {
            // Expected
        }
    }

    @Test
    fun deleteSessionAttachments_removesOnlyTargetSession() {
        val filesDir = tempFolder.newFolder("files")
        val store = ChatAttachmentStore(filesDir)

        val src1 = tempFolder.newFile("doc1.txt").apply { writeText("doc1") }
        val src2 = tempFolder.newFile("doc2.txt").apply { writeText("doc2") }

        val f1 = store.storeAttachmentFromFile(ChatKind.Text, "sess_A", "att_1", src1)
        val f2 = store.storeAttachmentFromFile(ChatKind.Text, "sess_B", "att_2", src2)

        assertTrue(f1.exists())
        assertTrue(f2.exists())

        val deletedA = store.deleteSessionAttachments(ChatKind.Text, "sess_A")
        assertTrue(deletedA)
        assertFalse(f1.exists())
        assertTrue("Session B attachment must be preserved", f2.exists())

        // Deleting non-existent session is idempotent
        assertTrue(store.deleteSessionAttachments(ChatKind.Text, "non_existent"))
    }
}
