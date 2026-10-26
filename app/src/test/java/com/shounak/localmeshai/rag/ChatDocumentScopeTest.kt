package com.shounak.localmeshai.rag

import com.shounak.localmeshai.data.SessionCodec
import com.shounak.localmeshai.rag.embedding.SemanticEmbeddingEngine
import com.shounak.localmeshai.rag.store.InMemoryRagVectorStore
import com.shounak.localmeshai.ui.viewmodels.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ChatDocumentScopeTest {
    @Test fun commonFilenameStemsDoNotNarrowOrdinaryDocumentQuestions() {
        assertTrue(ChatDocumentScope.isDocumentQuestion("What does section 3 say?"))
        assertTrue(ChatDocumentScope.referencedNames("Give me a summary", listOf("summary.pdf", "notes.pdf")).isEmpty())
        assertEquals(setOf("summary.pdf"), ChatDocumentScope.referencedNames("Summarize summary.pdf", listOf("summary.pdf", "notes.pdf")))
    }

    @Test fun namedUploadOverridesTheLastUploadForSummariesAndSearch() = runBlocking {
        val rag = manager()
        rag.ingestText("batteries.pdf", "Battery replacement information.", "battery")
        rag.ingestText("plants.pdf", "Photosynthesis information.", "plants")
        for (query in listOf("Summarize batteries.pdf", "What does batteries.pdf say about replacement?")) {
            val result = rag.retrieveForChat(query, preferredDocumentId = "plants", allowedDocumentIds = setOf("battery", "plants"))
            assertTrue(result.hasContext)
            assertEquals(setOf("battery"), result.matches.map { it.chunk.documentId }.toSet())
        }
    }

    @Test fun genericFileQuestionsBlankNamesAndStemSubstringsDoNotImplyAttachmentIntent() = runBlocking {
        val rag = RagManager(null, vectorStore = InMemoryRagVectorStore(null))
        rag.ingestText("report.pdf", "Vehicle battery maintenance.", "current")
        for (query in listOf("How do I read a file in Python?", "What is a PDF?", "How do reporting tools work?")) {
            assertFalse(query, ChatDocumentScope.isDocumentQuestion(query, listOf("report.pdf", "")))
            assertFalse(query, rag.retrieveForChat(query, allowedDocumentIds = setOf("current")).hasContext)
        }
        assertTrue(ChatDocumentScope.isDocumentQuestion("Summarize the document"))
        assertTrue(ChatDocumentScope.isDocumentQuestion("Read report.pdf" , listOf("report.pdf")))
    }
    // Deliberately overconfident semantic engine: unrelated text must still be rejected.
    private class Engine : SemanticEmbeddingEngine {
        override val dimension = 8
        override val algorithmId = "scope-test"
        var queries = 0
        override suspend fun embed(text: String) = FloatArray(8) { if (it == 0) 1f else 0f }
        override suspend fun embedBatch(texts: List<String>) = texts.map { embed(it) }
        override suspend fun embedQuery(text: String): FloatArray { queries++; return embed(text) }
    }
    private fun manager(engine: Engine = Engine()) = RagManager(null, engine,
        InMemoryRagVectorStore(null, expectedDimension = engine.dimension, algorithmId = engine.algorithmId))

    @Test fun newChatCannotReadPreviousUploadsEvenWithExplicitDocumentQuestions() = runBlocking {
        val engine = Engine()
        val rag = manager(engine)
        val old = rag.ingestText("private.pdf", "Previous chat secret: violet mountain.")
        val result = rag.retrieveForChat("Summarize private.pdf", preferredDocumentId = old.documentId,
            allowGlobalSearch = true, allowedDocumentIds = emptySet())
        assertFalse(result.hasContext)
        assertTrue(result.sourceNames.isEmpty())
        assertEquals(0, engine.queries)
    }

    @Test fun identicalFilenamesRemainIsolatedAndScopedOverviewCannotExpand() = runBlocking {
        val rag = manager()
        val old = rag.ingestText("report.pdf", "Previous chat secret: violet mountain.", "old")
        val current = rag.ingestText("report.pdf", "Current upload discusses vehicle batteries.", "current")
        val result = rag.retrieveForChat("Summarize the uploaded document", preferredDocumentId = old.documentId,
            allowGlobalSearch = true, allowedDocumentIds = setOf(current.documentId))
        assertEquals(listOf(current.documentId), result.matches.map { it.chunk.documentId }.distinct())
        assertFalse(result.matches.any { it.chunk.text.contains("secret") })
    }

    @Test fun unrelatedQuestionsAndGreetingsDoNotGetLexicalSources() = runBlocking {
        val rag = RagManager(null, vectorStore = InMemoryRagVectorStore(null))
        val current = rag.ingestText("report.pdf", "Vehicle battery replacement requires a specialist.")
        for (query in listOf("hi", "who are you", "what is my name", "Explain ocean tides", "Summarize quantum physics")) {
            assertFalse(query, rag.retrieveForChat(query, allowedDocumentIds = setOf(current.documentId)).hasContext)
        }
        assertTrue(rag.retrieveForChat("How is battery replacement handled?", minScore = 0f, allowedDocumentIds = setOf(current.documentId)).hasContext)
    }

    @Test fun neuralParaphrasesDoNotRequireLiteralWordsFromTheDocument() = runBlocking {
        val rag = manager()
        val uploaded = rag.ingestText("notes.txt", "The battery needs replacement.")
        assertTrue(rag.retrieveForChat("Why won't my automobile start?", allowedDocumentIds = setOf(uploaded.documentId)).hasContext)
    }

    @Test fun multipleCurrentUploadsAreSearchedWithoutOtherChats() = runBlocking {
        val rag = manager()
        rag.ingestText("old.txt", "Private old chat upload.", "old")
        rag.ingestText("a.txt", "Battery replacement.", "a")
        rag.ingestText("b.txt", "Vehicle maintenance.", "b")
        val result = rag.retrieveForChat("Summarize", allowedDocumentIds = setOf("a", "b"))
        assertEquals(setOf("a", "b"), result.matches.map { it.chunk.documentId }.toSet())
    }

    @Test fun onlyUserUploadIdsEstablishScopeAndOrdinaryFollowupsDoNotReuseRawDocuments() {
        assertEquals(setOf("current"), ChatDocumentScope.ids(listOf(true to "current", false to "old", true to null)))
        assertFalse(ChatDocumentScope.shouldIncludeText("hello", "report.pdf", "hello name report"))
        assertFalse(ChatDocumentScope.shouldIncludeText("Explain ocean tides", "report.pdf", "Battery replacement"))
        assertTrue(ChatDocumentScope.shouldIncludeText("Summarize this document", "report.pdf", "Battery replacement"))
        assertTrue(ChatDocumentScope.shouldIncludeText("What is battery replacement?", "report.pdf", "Battery replacement"))
    }

    @Test fun textAndVisionUploadScopeSurvivesHistoryAndBackupCodecRoundTrips() {
        val text = ChatSession("text", "chat", listOf(ChatMessage("Upload", true, ragDocumentId = "owned-text")), 1L)
        assertEquals("owned-text", SessionCodec.decodeChatSessions(SessionCodec.encodeChatSessions(listOf(text))).single().messages.first().ragDocumentId)
        val vision = VisionChatSession("vision", "chat", "Upload", "answer", 1L,
            messages = listOf(VisionChatMessage("Upload", true, ragDocumentId = "owned-vision")))
        assertEquals("owned-vision", SessionCodec.decodeVisionSessions(SessionCodec.encodeVisionSessions(listOf(vision))).single().messages.first().ragDocumentId)
        val legacy = text.copy(messages = listOf(ChatMessage("Hi", true)))
        assertNull(SessionCodec.decodeChatSessions(SessionCodec.encodeChatSessions(listOf(legacy))).single().messages.first().ragDocumentId)
    }
}
