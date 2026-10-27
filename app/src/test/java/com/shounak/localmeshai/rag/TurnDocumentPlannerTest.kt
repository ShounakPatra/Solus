package com.shounak.localmeshai.rag

import com.shounak.localmeshai.rag.embedding.UniversalSemanticEmbeddingEngine
import com.shounak.localmeshai.rag.store.InMemoryRagVectorStore
import com.shounak.localmeshai.rag.TurnDocumentPlanner.Entry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TurnDocumentPlannerTest {
    private val upload = Entry("u1", true, "d1.txt", "/chat/d1.txt", "d1")
    private val answer = Entry("a1", false, null, null, null)

    private fun plan(history: List<Entry>, turn: String?, library: String? = null, own: Boolean = false, ownId: String? = null,
                     repaired: String? = "repaired") = TurnDocumentPlanner.plan(history, turn, own, ownId, library,
        isDocumentQuestion = { true }, repair = { repaired })

    @Test fun libraryPickNeverReadsRepairsOrIndexesAnEarlierUpload() = runBlocking {
        val rag = RagManager(context = null, embeddingEngine = UniversalSemanticEmbeddingEngine(), vectorStore = InMemoryRagVectorStore(null))
        val library = rag.ingestText("kestrel.txt", "Kestrel library document about falcons and their hunting habits.", "library")
        assertTrue(library.success)
        val before = rag.indexedDocuments.single { it.documentId == "library" }
        val turn = Entry("u2", true, "kestrel.txt", null, "library")
        val result = plan(listOf(upload, answer, turn), "u2", library = "library")

        assertNull(result.savedUpload)
        assertNull(result.indexTargetId)
        assertNull(result.indexOwnerMessageId)
        assertEquals("library", result.libraryDocumentId)
        assertEquals(setOf("library"), result.ragScope)
        // The turn indexes only into an id it owns, so the library entry keeps its own content.
        result.indexTargetId?.let { rag.ingestText("d1.txt", "Old upload text", targetDocumentId = it) }
        assertEquals(before, rag.indexedDocuments.single { it.documentId == "library" })
    }

    @Test fun aNewAttachmentWinsOverAPendingLibraryPick() {
        val result = plan(listOf(upload, answer), "u2", library = "library", own = true, ownId = "fresh")
        assertNull(result.savedUpload)
        assertNull(result.libraryDocumentId)
        assertEquals("fresh", result.indexTargetId)
        assertEquals("u2", result.indexOwnerMessageId)
        assertTrue("fresh" in result.ragScope)
    }

    @Test fun followUpRepairIsWrittenToTheOriginalUploadNotTheFollowUp() {
        val legacy = upload.copy(ragDocumentId = null)
        val stamped = Entry("u2", true, "d1.txt", "/chat/d1.txt", null)
        val turn = Entry("u3", true, null, null, null)
        val result = plan(listOf(legacy, answer, stamped, turn), "u3")
        assertEquals("u1", result.savedUpload?.id)
        assertEquals("repaired", result.indexTargetId)
        assertEquals("u1", result.indexOwnerMessageId)

        val linked = plan(listOf(upload, answer, turn), "u3", repaired = null)
        assertEquals("u1", linked.savedUpload?.id)
        assertNull(linked.indexTargetId)
        assertNull(linked.indexOwnerMessageId)
        assertEquals(setOf("d1"), linked.ragScope)
    }

    @Test fun onlyDocumentQuestionsReadTheSavedUpload() {
        val turn = Entry("u2", true, null, null, null)
        val result = TurnDocumentPlanner.plan(listOf(upload, answer, turn), "u2", false, null, null,
            isDocumentQuestion = { false }, repair = { throw AssertionError("must not repair") })
        assertNull(result.savedUpload)
        assertNull(result.indexTargetId)
    }

    @Test fun savedUploadIsTheLatestUploadBeforeTheTurn() {
        val second = Entry("u2", true, "d2.txt", "/chat/d2.txt", "d2")
        val turn = Entry("u3", true, null, null, null)
        val later = Entry("u4", true, "d3.txt", "/chat/d3.txt", "d3")
        assertEquals("u2", TurnDocumentPlanner.savedUpload(listOf(upload, second, turn, later), "u3")?.id)
        assertEquals("u4", TurnDocumentPlanner.savedUpload(listOf(upload, second, turn, later), null)?.id)
        assertNull(TurnDocumentPlanner.savedUpload(listOf(turn, later), "u3"))
    }

    @Test fun retriedTurnsRecogniseOriginalUploadsAndLibraryPicks() {
        val stamped = Entry("u2", true, "d1.txt", "/chat/d1.txt", "d1")
        val libraryTurn = Entry("u3", true, "kestrel.txt", null, "library")
        val corrupted = Entry("u4", true, "d1.txt", "/chat/d1.txt", "library")
        val followUp = Entry("u5", true, null, null, "d1")
        val history = listOf(upload, stamped, libraryTurn, corrupted, followUp)
        assertTrue(TurnDocumentPlanner.isOriginalUpload(history, "u1"))
        assertFalse(TurnDocumentPlanner.isOriginalUpload(history, "u2"))
        assertEquals("library", TurnDocumentPlanner.libraryDocumentId(history, "u3"))
        assertEquals("library", TurnDocumentPlanner.libraryDocumentId(history, "u4"))
        assertNull(TurnDocumentPlanner.libraryDocumentId(history, "u1"))
        assertNull(TurnDocumentPlanner.libraryDocumentId(history, "u5"))
        assertNull(TurnDocumentPlanner.libraryDocumentId(history, "a1"))
    }
}
