package com.shounak.localmeshai.data

import android.content.Context
import android.content.ContextWrapper
import com.shounak.localmeshai.memory.MemoryEntry
import com.shounak.localmeshai.memory.PersistentMemoryStore
import com.shounak.localmeshai.rag.RagManager
import com.shounak.localmeshai.rag.store.InMemoryRagVectorStore
import com.shounak.localmeshai.rag.store.RagChunkRecord
import com.shounak.localmeshai.ui.viewmodels.ChatMessage
import com.shounak.localmeshai.ui.viewmodels.ChatSession
import com.shounak.localmeshai.ui.viewmodels.VisionChatSession
import com.shounak.localmeshai.utils.AtomicReadResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class StorageSafetyRegressionTest {
    @get:Rule val files = TemporaryFolder()

    private fun chat(id: String, documentId: String? = null) = ChatSession(
        id = id, title = id, messages = listOf(ChatMessage("hello", true)),
        updatedAt = 1L, documentId = documentId
    )

    @Test fun corruptMemoryRejectsMutationsUntilSuccessfulReload() {
        val root = files.newFolder()
        val file = root.resolve("memory.json").apply { writeText("{ unreadable memory") }
        val store = PersistentMemoryStore(storageDir = root, storageFileName = file.name)
        store.load()
        assertTrue(store.loadState.value is StoreLoadState.RecoveryRequired)
        assertFalse(store.add(MemoryEntry(content = "new memory")))
        store.clearAll()
        store.persist()
        assertEquals("{ unreadable memory", file.readText())
        file.writeText("[]")
        store.load()
        assertTrue(store.add(MemoryEntry(content = "recovered memory")))
        assertEquals(1, store.count())
    }

    @Test fun corruptRagRejectsMutationsUntilSuccessfulReload() {
        val root = files.newFolder()
        val file = root.resolve("rag.json").apply { writeText("{ unreadable knowledge") }
        val store = InMemoryRagVectorStore(storageDir = root, storageFileName = file.name, expectedDimension = 3)
        val chunk = RagChunkRecord("chunk", "doc", "doc.txt", 0, "content", floatArrayOf(1f, 0f, 0f))
        store.load()
        assertTrue(store.loadState.value is StoreLoadState.RecoveryRequired)
        assertFalse(store.addChunks(listOf(chunk)))
        assertFalse(store.replaceDocument("doc", listOf(chunk)))
        store.clearAll()
        store.persist()
        assertEquals("{ unreadable knowledge", file.readText())
        file.writeText(InMemoryRagVectorStore.serializeRecords(emptyList(), dimension = 3))
        store.load()
        assertTrue(store.addChunks(listOf(chunk)))
    }

    @Test fun corruptChatAndVisionCannotBeSavedOrDeleted() = runBlocking {
        val root = files.newFolder()
        val repo = ChatSessionRepository(root)
        repo.chatFile.writeText("{ unreadable chats")
        repo.visionFile.writeText("{ unreadable vision")
        val attachment = repo.attachmentStore.getAttachmentDir(ChatKind.Text, "chat", "input", true)
            .resolve("saved.txt").apply { writeText("keep") }
        assertTrue(repo.loadChatSessionsAsync() is AtomicReadResult.Corrupt)
        assertTrue(repo.loadVisionSessionsAsync() is AtomicReadResult.Corrupt)
        assertFalse(repo.saveChatSessions(emptyList()))
        assertFalse(repo.saveVisionSessions(emptyList()))
        assertTrue(repo.deleteSessionAsync(ChatKind.Text, "chat") is DeletionOutcome.FailedBeforeCommit)
        assertTrue(repo.deleteSessionAsync(ChatKind.Vision, "vision") is DeletionOutcome.FailedBeforeCommit)
        assertEquals("{ unreadable chats", repo.chatFile.readText())
        assertEquals("{ unreadable vision", repo.visionFile.readText())
        assertEquals("keep", attachment.readText())
        assertFalse(repo.isTombstoned(ChatKind.Text, "chat"))
    }

    @Test fun saveChecksDiskEvenWithoutPriorLoad() {
        val root = files.newFolder()
        val repo = ChatSessionRepository(root)
        repo.chatFile.writeText("{ corrupt after construction")
        assertFalse(repo.saveChatSessions(listOf(chat("new"))))
        assertEquals("{ corrupt after construction", repo.chatFile.readText())
    }

    @Test fun failedJournalWritePreservesHistoryAttachmentsAndInMemoryVisibility() = runBlocking {
        val root = files.newFolder()
        val repo = ChatSessionRepository(root)
        assertTrue(repo.saveChatSessions(listOf(chat("chat"))))
        val input = repo.attachmentStore.getAttachmentDir(ChatKind.Text, "chat", "input", true)
            .resolve("saved.txt").apply { writeText("keep") }
        // Block the atomic transaction's temporary file with a nonempty directory.
        root.resolve("chat_deletion_journal.json.tmp").apply { mkdirs(); resolve("block").writeText("block") }
        assertTrue(repo.deleteSessionAsync(ChatKind.Text, "chat") is DeletionOutcome.FailedBeforeCommit)
        assertFalse(repo.isTombstoned(ChatKind.Text, "chat"))
        assertEquals(listOf("chat"), repo.loadChatSessions().map { it.id })
        assertEquals("keep", input.readText())
    }

    @Test fun deletedChatsRejectLateAttachmentsAndStalePersistence() = runBlocking {
        val root = files.newFolder()
        val repo = ChatSessionRepository(root)
        val snapshot = listOf(chat("deleted"), chat("keep"))
        assertTrue(repo.saveChatSessions(snapshot))
        assertEquals(DeletionOutcome.Completed, repo.deleteSessionAsync(ChatKind.Text, "deleted"))
        assertTrue(repo.saveChatSessions(snapshot))
        assertEquals(listOf("keep"), repo.loadChatSessions().map { it.id })
        val source = files.newFile().apply { writeText("late input") }
        assertThrows(IllegalStateException::class.java) {
            repo.attachmentStore.storeAttachmentFromFile(ChatKind.Text, "deleted", sourceFile = source)
        }
        assertFalse(repo.attachmentStore.getSessionAttachmentsDir(ChatKind.Text, "deleted").exists())
    }

    @Test fun unreadableDeletionJournalHidesPendingDeletionsAndBlocksWritesUntilRecovery() = runBlocking {
        checkDamagedJournalBlocksHistory("{ broken deletion journal", "{ broken backup")
    }

    @Test fun blankDeletionJournalCannotResurrectPendingDeletions() = runBlocking {
        checkDamagedJournalBlocksHistory("", null)
        checkDamagedJournalBlocksHistory(" \r\n\t", null)
    }

    @Test fun invalidJournalSchemaCannotResurrectPendingDeletions() = runBlocking {
        for (damaged in listOf(
            "{}",
            """{"version":2,"pending":"broken","tombstones":{}}""",
            """{"version":2,"tombstones":[]}""",
            """{"version":2,"pending":[]}""",
            """{"version":2,"pending":[],"tombstones":null}""",
            """{"version":"2","pending":[],"tombstones":[]}""",
            """{"version":0,"pending":[],"tombstones":[]}"""
        )) {
            checkDamagedJournalBlocksHistory(damaged, null)
        }
    }

    private suspend fun checkDamagedJournalBlocksHistory(damaged: String, damagedBackup: String?) {
        for (kind in ChatKind.entries) {
            val root = files.newFolder()
            val repo = ChatSessionRepository(root)
            val text = listOf(chat("deleted"), chat("keep"))
            val vision = listOf(
                VisionChatSession("deleted", "deleted", "question", "answer", 1L),
                VisionChatSession("keep", "keep", "question", "answer", 1L)
            )
            assertTrue(repo.saveChatSessions(text))
            assertTrue(repo.saveVisionSessions(vision))
            val history = if (kind == ChatKind.Text) repo.chatFile else repo.visionFile
            val blockedWrite = root.resolve("${history.name}.tmp").apply { mkdirs() }
            val blocker = blockedWrite.resolve("block").apply { writeText("prevent history commit") }
            assertEquals(DeletionOutcome.PendingCleanup, repo.deleteSessionAsync(kind, "deleted"))
            val journal = root.resolve("chat_deletion_journal.json")
            val recoverableJournal = journal.readText()
            journal.writeText(damaged)
            val backup = root.resolve("${journal.name}.bak")
            if (damagedBackup == null) {
                assertTrue(!backup.exists() || backup.delete())
            } else {
                backup.writeText(damagedBackup)
            }
            assertTrue(blocker.delete())
            assertTrue(blockedWrite.delete())
            val originalHistory = history.readText()

            val restarted = ChatSessionRepository(root)
            assertTrue(restarted.loadChatSessionsAsync() is AtomicReadResult.Corrupt)
            assertTrue(restarted.loadVisionSessionsAsync() is AtomicReadResult.Corrupt)
            assertTrue(restarted.loadChatSessions().isEmpty())
            assertTrue(restarted.loadVisionSessions().isEmpty())
            assertFalse(restarted.isChatStoreHealthy())
            assertFalse(restarted.isVisionStoreHealthy())
            assertFalse(restarted.saveChatSessions(text))
            assertFalse(restarted.saveVisionSessions(vision))
            assertTrue(restarted.deleteSessionAsync(kind, "keep") is DeletionOutcome.FailedBeforeCommit)
            val source = files.newFile().apply { writeText("new attachment") }
            assertThrows(IllegalStateException::class.java) {
                restarted.attachmentStore.storeAttachmentFromFile(kind, "keep", sourceFile = source)
            }
            assertEquals(originalHistory, history.readText())
            assertEquals(damaged, journal.readText())

            journal.writeText(recoverableJournal)
            val visibleIds = if (kind == ChatKind.Text) restarted.loadChatSessions().map { it.id }
                else restarted.loadVisionSessions().map { it.id }
            assertEquals(listOf("keep"), visibleIds)
            assertTrue(restarted.isChatStoreHealthy())
            assertTrue(restarted.isVisionStoreHealthy())
            assertTrue(restarted.saveChatSessions(text))
            assertTrue(restarted.saveVisionSessions(vision))
            val savedIds = if (kind == ChatKind.Text) restarted.loadChatSessions().map { it.id }
                else restarted.loadVisionSessions().map { it.id }
            assertEquals(listOf("keep"), savedIds)
        }
    }

    @Test fun journalCorruptionIsDetectedOnSaveWithoutAnotherHistoryLoad() {
        val root = files.newFolder()
        val repo = ChatSessionRepository(root)
        assertTrue(repo.saveChatSessions(listOf(chat("keep"))))
        assertTrue(repo.saveVisionSessions(emptyList()))
        root.resolve("chat_deletion_journal.json").writeText("{ broken journal")
        assertFalse(repo.saveChatSessions(emptyList()))
        assertFalse(repo.saveVisionSessions(emptyList()))
        assertFalse(repo.isChatStoreHealthy())
        assertFalse(repo.isVisionStoreHealthy())
        // Missing files must not clear a recovery error already observed in this process.
        assertTrue(root.resolve("chat_deletion_journal.json").delete())
        assertFalse(repo.saveChatSessions(emptyList()))
        assertEquals(listOf("keep"), SessionCodec.decodeChatSessions(repo.chatFile.readText()).map { it.id })
    }

    @Test fun futureDeletionJournalCannotBeBypassedBySavingOrAttaching() = runBlocking {
        val root = files.newFolder()
        val journal = root.resolve("chat_deletion_journal.json").apply {
            writeText("""{"version":999,"pending":[],"tombstones":[]}""")
        }
        val original = journal.readText()
        val repo = ChatSessionRepository(root)
        assertFalse(repo.saveChatSessions(listOf(chat("new"))))
        assertFalse(repo.saveVisionSessions(emptyList()))
        assertTrue(repo.loadChatSessionsAsync() is AtomicReadResult.Corrupt)
        assertTrue(repo.loadVisionSessionsAsync() is AtomicReadResult.Corrupt)
        val source = files.newFile().apply { writeText("new attachment") }
        assertThrows(IllegalStateException::class.java) {
            repo.attachmentStore.storeAttachmentFromFile(ChatKind.Text, "new", sourceFile = source)
        }
        assertEquals(original, journal.readText())
        assertFalse(repo.chatFile.exists())
        assertFalse(repo.visionFile.exists())
    }

    @Test fun validJournalBackupRecoversDeletionFiltering() = runBlocking {
        val root = files.newFolder()
        val repo = ChatSessionRepository(root)
        val snapshot = listOf(chat("deleted"), chat("keep"))
        assertTrue(repo.saveChatSessions(snapshot))
        assertEquals(DeletionOutcome.Completed, repo.deleteSessionAsync(ChatKind.Text, "deleted"))
        val journal = root.resolve("chat_deletion_journal.json")
        root.resolve("${journal.name}.bak").writeText(journal.readText())
        journal.writeText("{ corrupt primary")
        // Reintroduce a stale history snapshot: filtering must still honor the backup tombstone.
        repo.chatFile.writeText(SessionCodec.encodeChatSessions(snapshot))
        val restarted = ChatSessionRepository(root)
        assertEquals(listOf("keep"), restarted.loadChatSessions().map { it.id })
        assertTrue(restarted.isChatStoreHealthy())
        assertTrue(restarted.saveChatSessions(snapshot))
        assertEquals(listOf("keep"), restarted.loadChatSessions().map { it.id })
    }

    @Test fun deletingOneVisionSessionOnlyRemovesItsOwnedAndLegacyFiles() = runBlocking {
        val root = files.newFolder()
        val cache = files.newFolder()
        val repo = ChatSessionRepository(root, legacyCacheDir = { cache })
        fun vision(id: String) = VisionChatSession(id, id, "question", "answer", 1L)
        assertTrue(repo.saveVisionSessions(listOf(vision("deleted"), vision("keep"))))
        val legacyFiles = listOf(root, cache).map { directory ->
            directory.resolve("vision_sessions/session_deleted.png").apply {
                parentFile!!.mkdirs(); writeText("legacy image")
            }
        }
        val keep = repo.attachmentStore.getAttachmentDir(ChatKind.Vision, "keep", "input", true)
            .resolve("keep.txt").apply { writeText("keep") }
        assertEquals(DeletionOutcome.Completed, repo.deleteSessionAsync(ChatKind.Vision, "deleted"))
        assertTrue(legacyFiles.none { it.exists() })
        assertEquals("keep", keep.readText())
        assertEquals(listOf("keep"), repo.loadVisionSessions().map { it.id })
    }

    @Test fun sharedKnowledgeSurvivesChatDeletion() = runBlocking {
        val manager = RagManager()
        val first = manager.ingestText("first.txt", "Shared content for both chat sessions.")
        val second = manager.ingestText("second.txt", "Shared content for both chat sessions.")
        assertTrue(first.success)
        assertEquals(first.documentId, second.documentId)
        val repo = ChatSessionRepository(files.newFolder())
        assertTrue(repo.saveChatSessions(listOf(chat("one", first.documentId), chat("two", second.documentId))))
        val visibleIds = mutableListOf("one", "two")
        HistoryDeletionCoordinator(repo).deleteSessions(ChatKind.Text, listOf("one"),
            onCommitted = { id, pending -> assertFalse(pending); visibleIds.remove(id) },
            onFailure = { fail(it) })
        assertEquals(listOf("two"), visibleIds)
        assertEquals(second.documentId, repo.loadChatSessions().single().documentId)
        assertEquals(first.documentId, manager.indexedDocuments.single().documentId)
    }

    @Test fun bulkDeletionOnlyUpdatesPresentationForCommittedSessions() = runBlocking {
        val root = files.newFolder()
        val repo = ChatSessionRepository(root)
        assertTrue(repo.saveChatSessions(listOf(chat("one"), chat("two"))))
        val visibleIds = mutableListOf("one", "two")
        val failures = mutableListOf<String>()
        HistoryDeletionCoordinator(repo).deleteSessions(ChatKind.Text, visibleIds.toList(),
            onCommitted = { id, _ ->
                visibleIds.remove(id)
                root.resolve("chat_deletion_journal.json.tmp").apply {
                    mkdirs(); resolve("block").writeText("block the next transaction")
                }
            },
            onFailure = failures::add)
        assertEquals(listOf("two"), visibleIds)
        assertEquals(listOf("two"), repo.loadChatSessions().map { it.id })
        assertEquals(1, failures.size)
        assertFalse(repo.isTombstoned(ChatKind.Text, "two"))
    }

    @Test fun futureRagSchemaCannotBeOverwritten() {
        val root = files.newFolder()
        val file = root.resolve("rag.json").apply { writeText("""{"version":999,"records":[]}""") }
        val store = InMemoryRagVectorStore(storageDir = root, storageFileName = file.name)
        store.load()
        assertTrue(store.loadState.value is StoreLoadState.RecoveryRequired)
        store.clearAll()
        assertEquals("""{"version":999,"records":[]}""", file.readText())
    }

    @Test fun deletionWaitsForAnInFlightAttachmentCopyBeforeCleaningItsDirectory() = runBlocking {
        val repo = ChatSessionRepository(files.newFolder())
        assertTrue(repo.saveChatSessions(listOf(chat("chat"))))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val source = object : ByteArrayInputStream("attachment bytes".toByteArray()) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return super.read(bytes, offset, length)
            }
        }
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
        }
        val copying = async(Dispatchers.IO) {
            repo.attachmentStore.storeAttachmentFromStream(context, ChatKind.Text, "chat",
                sourceUri = null, fallbackStream = source, targetFileName = "input.txt")
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val deleting = async(Dispatchers.IO) { repo.deleteSessionAsync(ChatKind.Text, "chat") }
            withTimeout(2_000) {
                while (!repo.isTombstoned(ChatKind.Text, "chat")) delay(1)
            }
            assertFalse(deleting.isCompleted)
            release.countDown()
            val copied = copying.await()
            assertEquals(DeletionOutcome.Completed, deleting.await())
            assertFalse(copied.exists())
        } finally {
            release.countDown()
        }
    }
}
