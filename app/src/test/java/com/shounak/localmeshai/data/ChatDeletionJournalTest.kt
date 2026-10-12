package com.shounak.localmeshai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChatDeletionJournalTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun recordPendingDeletion_marksTombstoneAndPersistsToDisk() {
        val rootDir = tempFolder.newFolder("journal_root")
        val journal = ChatDeletionJournal(rootDir)

        assertFalse(journal.isTombstoned(ChatKind.Text, "session_1"))

        val entry = DeletionJournalEntry(
            chatKind = ChatKind.Text,
            sessionId = "session_1",
            ownedResourcePaths = listOf("/files/chat_attachments/text/session_1")
        )

        val recorded = journal.recordPendingDeletion(entry)
        assertTrue(recorded)
        assertTrue(journal.isTombstoned(ChatKind.Text, "session_1"))
        assertFalse(journal.isTombstoned(ChatKind.Vision, "session_1"))

        val pending = journal.getPendingEntries()
        assertEquals(1, pending.size)
        assertEquals("session_1", pending[0].sessionId)
        assertEquals(ChatKind.Text, pending[0].chatKind)
        assertEquals(1, pending[0].ownedResourcePaths.size)
    }

    @Test
    fun newJournalInstance_restoresTombstonesFromExistingJournalFile() {
        val rootDir = tempFolder.newFolder("journal_restore")
        val journal1 = ChatDeletionJournal(rootDir)

        val entry = DeletionJournalEntry(
            chatKind = ChatKind.Vision,
            sessionId = "vision_sess_42",
            ownedResourcePaths = listOf("/files/chat_attachments/vision/vision_sess_42")
        )
        journal1.recordPendingDeletion(entry)

        // Instantiate new journal pointing to same directory
        val journal2 = ChatDeletionJournal(rootDir)
        assertTrue(journal2.isTombstoned(ChatKind.Vision, "vision_sess_42"))
        val pending = journal2.getPendingEntries()
        assertEquals(1, pending.size)
        assertEquals("vision_sess_42", pending[0].sessionId)
    }

    @Test
    fun removeEntry_removesFromPendingWhilePreservingTombstone() {
        val rootDir = tempFolder.newFolder("journal_remove")
        val journal = ChatDeletionJournal(rootDir)

        val entry = DeletionJournalEntry(
            id = "entry_to_remove",
            chatKind = ChatKind.Text,
            sessionId = "session_to_remove",
            ownedResourcePaths = listOf("/some/path")
        )
        journal.recordPendingDeletion(entry)
        assertEquals(1, journal.getPendingEntries().size)

        val removed = journal.removeEntry("entry_to_remove")
        assertTrue(removed)
        assertTrue(journal.getPendingEntries().isEmpty())
        assertTrue("Tombstone must persist in memory", journal.isTombstoned(ChatKind.Text, "session_to_remove"))
    }

    @Test
    fun reconcilePendingDeletions_cleansCompletedAndRetainsFailing() {
        val rootDir = tempFolder.newFolder("journal_reconcile")
        val journal = ChatDeletionJournal(rootDir)

        val file1 = tempFolder.newFile("file1.tmp").apply { writeText("data1") }
        val file2 = tempFolder.newFile("file2.tmp").apply { writeText("data2") }

        val successEntry = DeletionJournalEntry(
            id = "entry_success",
            chatKind = ChatKind.Text,
            sessionId = "sess_ok",
            ownedResourcePaths = listOf(file1.absolutePath)
        )
        val failEntry = DeletionJournalEntry(
            id = "entry_fail",
            chatKind = ChatKind.Vision,
            sessionId = "sess_err",
            ownedResourcePaths = listOf(file2.absolutePath)
        )

        journal.recordPendingDeletion(successEntry)
        journal.recordPendingDeletion(failEntry)
        assertEquals(2, journal.getPendingEntries().size)

        val result = journal.reconcilePendingDeletions { path ->
            if (path == file1.absolutePath) {
                File(path).delete()
                true
            } else {
                false // Simulated failure
            }
        }

        assertFalse("Reconcile should report false if any deletion failed", result)
        assertFalse(file1.exists())
        assertTrue(file2.exists())

        val remaining = journal.getPendingEntries()
        assertEquals(1, remaining.size)
        assertEquals("entry_fail", remaining[0].id)
    }
}
