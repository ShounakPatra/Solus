package com.shounak.localmeshai.data

import kotlinx.coroutines.flow.first

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
    fun invalidJournalEntriesAndVersionsRequireRecoveryWithoutReconciliation() {
        val damagedDocuments = listOf(
            """{"version":2.5,"pending":[],"tombstones":[]}""",
            """{"version":null,"pending":[],"tombstones":[]}""",
            """{"version":2,"pending":[{"id":"entry","chatKind":"Text","sessionId":"deleted","ownedResourcePaths":"wrong type"}],"tombstones":[]}""",
            """{"version":2,"pending":[{"id":"entry","chatKind":"Text","sessionId":"deleted"}],"tombstones":[]}""",
            """{"version":2,"pending":[{"id":"","chatKind":"Text","sessionId":"deleted","ownedResourcePaths":[]}],"tombstones":[]}""",
            """{"version":2,"pending":[{"id":"entry","chatKind":"Text","sessionId":"","ownedResourcePaths":[]}],"tombstones":[]}""",
            """{"version":2,"pending":[{"id":"entry","chatKind":"Text","sessionId":42,"ownedResourcePaths":[]}],"tombstones":[]}""",
            """{"version":2,"pending":[{"id":"entry","chatKind":"Text","sessionId":"deleted","ownedResourcePaths":[42]}],"tombstones":[]}""",
            """{"version":2,"pending":[],"tombstones":[{"chatKind":"Text","sessionId":""}]}"""
        )
        for (damaged in damagedDocuments) {
            val root = tempFolder.newFolder()
            val file = root.resolve("chat_deletion_journal.json").apply { writeText(damaged) }
            val journal = ChatDeletionJournal(root)
            assertFalse(journal.refreshForHistoryAccess())
            assertTrue(journal.hasRecoveryError())
            assertFalse(journal.recordPendingDeletion(DeletionJournalEntry(
                chatKind = ChatKind.Text, sessionId = "keep", ownedResourcePaths = emptyList())))
            assertFalse(journal.reconcilePendingDeletions { error("Unreadable journal must not delete files") })
            assertEquals(damaged, file.readText())
        }
    }

    @Test
    fun emptyAndInvalidPrimaryJournalsRecoverFromValidBackup() {
        for (damaged in listOf("", """{"version":2,"pending":"broken","tombstones":{}}""")) {
            val root = tempFolder.newFolder()
            val original = ChatDeletionJournal(root)
            assertTrue(original.recordPendingDeletion(DeletionJournalEntry(
                chatKind = ChatKind.Text, sessionId = "deleted", ownedResourcePaths = emptyList())))
            val file = root.resolve("chat_deletion_journal.json")
            val valid = file.readText()
            root.resolve("${file.name}.bak").writeText(valid)
            file.writeText(damaged)

            val restored = ChatDeletionJournal(root)
            assertTrue(restored.refreshForHistoryAccess())
            assertFalse(restored.hasRecoveryError())
            assertTrue(restored.isTombstoned(ChatKind.Text, "deleted"))
            assertEquals(listOf("deleted"), restored.getPendingEntries().map { it.sessionId })
            assertEquals(valid, file.readText())
        }
    }

    @Test
    fun validEmptyAndLegacyJournalsRemainReadable() {
        for (document in listOf("[]", """{"version":1,"pending":[]}""",
            """{"version":2,"pending":[],"tombstones":[]}""")) {
            val root = tempFolder.newFolder()
            root.resolve("chat_deletion_journal.json").writeText(document)
            val journal = ChatDeletionJournal(root)
            assertTrue(journal.refreshForHistoryAccess())
            assertFalse(journal.hasRecoveryError())
            assertTrue(journal.getPendingEntries().isEmpty())
            assertTrue(journal.recordPendingDeletion(DeletionJournalEntry(
                chatKind = ChatKind.Vision, sessionId = "new", ownedResourcePaths = emptyList())))
            assertTrue(ChatDeletionJournal(root).isTombstoned(ChatKind.Vision, "new"))
        }
    }

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
    fun documentRetirementSurvivesCleanupAndExplicitLibraryRetention() {
        val root = tempFolder.newFolder("document_retirement")
        val journal = ChatDeletionJournal(root)
        assertTrue(journal.retainRagDocument("shared"))
        val entry = DeletionJournalEntry(chatKind = ChatKind.Text, sessionId = "owner",
            ownedResourcePaths = emptyList(), ownedRagDocumentIds = setOf("private", "shared"))
        assertTrue(journal.recordPendingDeletion(entry))
        assertTrue(journal.removeEntry(entry.id))
        val restored = ChatDeletionJournal(root)
        assertEquals(setOf("private"), restored.deletedRagDocuments())
        assertFalse(restored.retainRagDocument("private"))
        assertTrue(restored.recordRagDeletions(setOf("shared")))
        assertEquals(setOf("private", "shared"), ChatDeletionJournal(root).deletedRagDocuments())
    }

    @Test
    fun documentCleanupSubscriberReceivesDeletionRecordedBeforeItStarted() = kotlinx.coroutines.runBlocking {
        val journal = ChatDeletionJournal(tempFolder.newFolder("late_document_cleanup"))
        assertTrue(journal.recordRagDeletions(setOf("deleted-before-subscription")))
        val retired = kotlinx.coroutines.withTimeout(1_000) {
            journal.documentChanges.first()
        }
        assertEquals(setOf("deleted-before-subscription"), retired)
    }

    @Test
    fun invalidDocumentIdsAreDroppedWithoutFailingTheWholeJournal() {
        val root = tempFolder.newFolder("invalid_document_retirement")
        val file = File(root, "chat_deletion_journal.json")
        file.writeText("""{"version":3,"pending":[{"id":"entry","chatKind":"Text","sessionId":"owner","ownedResourcePaths":[],"ownedRagDocumentIds":["owned",""]}],""" +
            """"tombstones":[],"deletedRagDocuments":[42,"","  ","retired",null],"retainedRagDocuments":[7,"kept"]}""")
        val journal = ChatDeletionJournal(root)
        assertTrue(journal.refreshForHistoryAccess())
        assertFalse(journal.hasRecoveryError())
        assertEquals(setOf("retired"), journal.deletedRagDocuments())
        assertEquals(setOf("owned"), journal.getPendingEntries().single().ownedRagDocumentIds)
        assertTrue(journal.isTombstoned(ChatKind.Text, "owner"))
        assertTrue(journal.recordRagDeletions(setOf("new")))
        assertEquals(setOf("retired", "new"), ChatDeletionJournal(root).deletedRagDocuments())
        assertFalse("Retired ids stay retired", journal.retainRagDocument("retired"))
    }

    @Test
    fun unreadableJournalReportsRecoveryWithoutThrowingOrOverwriting() {
        val root = tempFolder.newFolder("unreadable_document_retirement")
        val file = File(root, "chat_deletion_journal.json")
        val invalid = """{"version":3,"pending":[],"tombstones":[],"deletedRagDocuments":"broken","retainedRagDocuments":[]}"""
        file.writeText(invalid)
        val journal = ChatDeletionJournal(root)
        assertTrue(journal.deletedRagDocuments().isEmpty())
        assertTrue("Callers must treat the empty set as incomplete", journal.hasRecoveryError())
        assertFalse(journal.recordRagDeletions(setOf("new")))
        assertFalse(journal.retainRagDocument("new"))
        assertEquals(invalid, file.readText())
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
