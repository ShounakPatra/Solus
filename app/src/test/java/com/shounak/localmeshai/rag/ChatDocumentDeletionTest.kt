package com.shounak.localmeshai.rag

import com.shounak.localmeshai.data.*
import com.shounak.localmeshai.rag.embedding.UniversalSemanticEmbeddingEngine
import com.shounak.localmeshai.rag.store.InMemoryRagVectorStore
import com.shounak.localmeshai.ui.viewmodels.ChatMessage
import com.shounak.localmeshai.ui.viewmodels.ChatSession
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatDocumentDeletionTest {
    @get:Rule val files = TemporaryFolder()

    private fun owner(repository: ChatSessionRepository, documentId: String) {
        val original = repository.attachmentStore.getAttachmentDir(ChatKind.Text, "owner", "upload", true)
            .resolve("notes.txt").apply { writeText("Private battery repair details.") }
        val message = ChatMessage("Attached notes", true, documentName = "notes.txt", documentPath = original.path, ragDocumentId = documentId)
        assertTrue(repository.saveChatSessions(listOf(ChatSession("owner", "Owner", listOf(message), 1L))))
    }

    @Test fun deletingAnOwnedUploadHidesItImmediatelyAndRejectsLateIndexingAfterRestart() = runBlocking {
        val repository = ChatSessionRepository(files.newFolder())
        owner(repository, "private-doc")
        val manager = RagManager(deletionJournal = repository.deletionJournal)
        assertTrue(manager.ingestText("notes.txt", "Private battery repair details.", "private-doc").success)
        assertEquals(DeletionOutcome.Completed, repository.deleteSessionAsync(ChatKind.Text, "owner"))
        assertTrue(manager.indexedDocuments.isEmpty())
        assertFalse(manager.retrieveForChat("Summarize notes.txt", allowedDocumentIds = setOf("private-doc")).hasContext)
        assertFalse(manager.ingestText("notes.txt", "Late indexing must be refused.", "private-doc").success)
        manager.loadAsync()
        assertEquals(0, manager.vectorStore.getTotalChunkCount())
        val restarted = ChatDeletionJournal(repository.chatFile.parentFile!!)
        assertTrue("Deletion identity must survive journal-entry cleanup", "private-doc" in restarted.deletedRagDocuments())
    }

    @Test fun explicitlyRetainedLibraryDocumentsSurviveDeletingTheirOriginalChat() = runBlocking {
        val repository = ChatSessionRepository(files.newFolder())
        owner(repository, "shared-doc")
        val manager = RagManager(deletionJournal = repository.deletionJournal)
        manager.ingestText("notes.txt", "Library document retained for another chat.", "shared-doc")
        assertTrue(manager.retainForLibrary("shared-doc"))
        repository.deleteSessionAsync(ChatKind.Text, "owner")
        assertEquals(listOf("shared-doc"), manager.indexedDocuments.map { it.documentId })
        assertFalse("shared-doc" in repository.deletionJournal.deletedRagDocuments())
    }

    @Test fun inFlightEmbeddingCannotRecreateADeletedChatUpload() = runBlocking {
        val repository = ChatSessionRepository(files.newFolder())
        owner(repository, "in-flight")
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val lexical = UniversalSemanticEmbeddingEngine()
        val engine = object : com.shounak.localmeshai.rag.embedding.SemanticEmbeddingEngine by lexical {
            override suspend fun embedBatch(texts: List<String>): List<FloatArray> {
                entered.complete(Unit)
                finish.await()
                return lexical.embedBatch(texts)
            }
        }
        val manager = RagManager(embeddingEngine = engine, deletionJournal = repository.deletionJournal)
        val indexing = async { manager.ingestText("notes.txt", "Private battery repair details.", "in-flight") }
        entered.await()
        try { repository.deleteSessionAsync(ChatKind.Text, "owner") } finally { finish.complete(Unit) }
        assertFalse(indexing.await().success)
        assertEquals(0, manager.vectorStore.getTotalChunkCount())
    }

    private fun breakJournal(root: java.io.File) {
        root.listFiles()?.filter { it.name.startsWith("chat_deletion_journal.json") }?.forEach { it.delete() }
        root.resolve("chat_deletion_journal.json").writeText("{broken")
    }

    @Test fun unreadableDeletionJournalIsReportedAndNeverThrowsFromDeletionOrIndexing() = runBlocking {
        val root = files.newFolder()
        val journal = ChatDeletionJournal(root)
        val manager = RagManager(deletionJournal = journal)
        assertTrue(manager.ingestText("notes.txt", "Battery replacement schedule.", "kept").success)
        breakJournal(root)
        assertFalse(journal.refreshForHistoryAccess())

        assertTrue(manager.indexedDocuments.isEmpty())
        assertTrue("An empty list must come with a recovery state", manager.indexNeedsRecovery.value)
        val cleared = manager.clearKnowledgeBase()
        assertFalse(cleared.success)
        assertEquals(RagManager.INDEX_RECOVERY_MESSAGE, cleared.message)
        assertFalse(manager.removeDocument("kept").success)
        assertEquals("Nothing may change without a durable deletion record", 1, manager.vectorStore.getDocuments().size)
        val late = manager.ingestText("other.txt", "Indexed while the journal is unreadable.", "other")
        assertFalse(late.success)
        assertTrue(late.message.startsWith("Ingestion failed"))
        val retrieval = manager.retrieveForChat("Summarize notes.txt", allowedDocumentIds = setOf("kept"))
        assertFalse(retrieval.hasContext)
        assertTrue(retrieval.unavailableReason!!.contains("needs recovery"))

        root.resolve("chat_deletion_journal.json").writeText(
            """{"version":3,"pending":[],"tombstones":[],"deletedRagDocuments":[],"retainedRagDocuments":[]}""")
        assertTrue(journal.refreshForHistoryAccess())
        assertEquals(listOf("kept"), manager.indexedDocuments.map { it.documentId })
        assertFalse(manager.indexNeedsRecovery.value)
        assertTrue(manager.removeDocument("kept").success)
        assertTrue(manager.indexedDocuments.isEmpty())
    }

    @Test fun clearingBeforeOrphanRecoveryRetiresTheOrphanedDocumentsToo() = runBlocking {
        val journal = ChatDeletionJournal(files.newFolder())
        val orphan = RagManager()
        orphan.ingestText("old-model.txt", "Indexed by an embedding model that was removed.", "orphan-doc")
        val manager = RagManager(vectorStore = InMemoryRagVectorStore(null), orphanedStore = orphan.vectorStore, deletionJournal = journal)
        manager.ingestText("current.txt", "Indexed by the active model.", "current-doc")
        assertTrue(manager.clearKnowledgeBase().success)
        assertEquals(setOf("orphan-doc", "current-doc"), journal.deletedRagDocuments())
        assertEquals(0, orphan.vectorStore.getTotalChunkCount())
        manager.loadAsync()
        assertTrue(manager.indexedDocuments.isEmpty())
        assertEquals(0, manager.vectorStore.getTotalChunkCount())
    }

    @Test fun orphanRecoveryKeepsNewFallbackDocumentsWithoutResurrectingRetiredOnes() = runBlocking {
        val repository = ChatSessionRepository(files.newFolder())
        owner(repository, "retired")
        val old = RagManager()
        old.ingestText("notes.txt", "Private battery repair details.", "retired")
        val fallback = RagManager()
        fallback.ingestText("fresh.txt", "A new document created in fallback mode.", "fresh")
        repository.deleteSessionAsync(ChatKind.Text, "owner")
        val recovered = RagManager(vectorStore = fallback.vectorStore, orphanedStore = old.vectorStore, deletionJournal = repository.deletionJournal)
        recovered.loadAsync()
        assertEquals(listOf("fresh"), recovered.indexedDocuments.map { it.documentId })
        assertEquals(0, old.vectorStore.getTotalChunkCount())
    }
}
