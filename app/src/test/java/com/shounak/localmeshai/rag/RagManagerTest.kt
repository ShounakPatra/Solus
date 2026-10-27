package com.shounak.localmeshai.rag

import com.shounak.localmeshai.rag.embedding.UniversalSemanticEmbeddingEngine
import com.shounak.localmeshai.rag.store.InMemoryRagVectorStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RagManagerTest {
    @Test fun greetingsAndAssistantIdentityNeverAttachSourcesEvenWithAPreferredDocument() = runBlocking {
        val manager = RagManager(null, UniversalSemanticEmbeddingEngine(), InMemoryRagVectorStore(null))
        val document = manager.ingestText("cover_page.docx", "Name: Class: XII Stream: Subject: Session 2026-2027")
        for (query in listOf("hi", "Hello!", "who are you", "What is your name?", "Thanks!")) {
            assertFalse("New chat must not retrieve for $query", manager.retrieve(query, minScore = 0f).hasContext)
            assertFalse("An associated document must not force sources for $query",
                manager.retrieve(query, preferredDocumentId = document.documentId, minScore = 0f).hasContext)
        }
        assertTrue(manager.retrieve("Summarize this document", preferredDocumentId = document.documentId).hasContext)
    }

    @Test fun hashSimilarityAloneDoesNotGroundAnUnrelatedQuestion() = runBlocking {
        val manager = RagManager(null, UniversalSemanticEmbeddingEngine(), InMemoryRagVectorStore(null))
        val document = manager.ingestText("cover_page.docx", "Name: Class: XII Stream: Subject: Session 2026-2027")
        assertFalse(manager.retrieve("How do volcanoes erupt?", preferredDocumentId = document.documentId, minScore = -1f).hasContext)
        assertFalse(manager.retrieve("How do volcanoes erupt?", minScore = -1f).hasContext)
        assertFalse(manager.retrieve("Summarize the French revolution", minScore = 0f).hasContext)
        assertFalse(manager.retrieve("Summarize the French revolution", preferredDocumentId = document.documentId, minScore = 0f).hasContext)
    }

    @Test
    fun deletionWhileReindexingCannotRecreateTheDocument() = runBlocking {
        lateinit var manager: RagManager
        var deleteDuringEmbedding: String? = null
        val lexical = UniversalSemanticEmbeddingEngine()
        val engine = object : com.shounak.localmeshai.rag.embedding.SemanticEmbeddingEngine {
            override val dimension = 384
            override suspend fun embed(text: String) = lexical.embed(text)
            override suspend fun embedBatch(texts: List<String>): List<FloatArray> {
                deleteDuringEmbedding?.let(manager::removeDocument)
                return lexical.embedBatch(texts)
            }
        }
        manager = RagManager(null, engine, InMemoryRagVectorStore(null))
        val first = manager.ingestText("document.txt", "The original indexed document.")
        deleteDuringEmbedding = first.documentId
        val result = manager.ingestText("document.txt", "Updated document content.", first.documentId, replacesSameUpload = true)
        assertFalse(result.success)
        assertEquals(0, manager.totalIndexedChunks)
    }
    @Test
    fun cancelledEmbeddingIsPropagatedWithoutPublishingDocument() = runBlocking {
        val engine = object : com.shounak.localmeshai.rag.embedding.SemanticEmbeddingEngine {
            override val dimension = 384
            override suspend fun embed(text: String): FloatArray = throw kotlinx.coroutines.CancellationException("Stop")
            override suspend fun embedBatch(texts: List<String>): List<FloatArray> = throw kotlinx.coroutines.CancellationException("Stop")
        }
        val manager = RagManager(null, engine, InMemoryRagVectorStore(null))
        try {
            manager.ingestText("cancelled.txt", "The document must not be published after cancellation.")
            org.junit.Assert.fail("Cancellation was swallowed")
        } catch (_: kotlinx.coroutines.CancellationException) {
            assertEquals(0, manager.totalIndexedChunks)
        }
    }

    @Test
    fun testIngestTextAndRetrieve_EndToEnd() = runBlocking {
        val manager = RagManager(
            context = null,
            embeddingEngine = UniversalSemanticEmbeddingEngine(),
            vectorStore = InMemoryRagVectorStore(null)
        )

        val documentText = """
            Quarterly Financial Report Q3 2026.
            Solus Technologies announced strong operating profit of 25.4 million dollars in Q3.
            User adoption increased by 45 percent across Android on-device deployments.
            Capital expenditures remained focused on neural network acceleration and Vulkan graphics shaders.
        """.trimIndent()

        val ingestion = manager.ingestText("Solus_Q3_Report.txt", documentText)
        assertTrue(ingestion.success)
        assertTrue(ingestion.chunkCount >= 1)
        assertEquals("Solus_Q3_Report.txt", ingestion.documentName)

        val retrieval = manager.retrieve("What was the operating profit in Q3?", topK = 2, minScore = 0.25f)
        assertTrue("Expected matching context to be retrieved", retrieval.hasContext)
        assertEquals(1, retrieval.sourceNames.size)
        assertEquals("Solus_Q3_Report.txt", retrieval.sourceNames[0])
        assertTrue("Match score should be positive", retrieval.topMatchScore > 0.25f)

        val augmentedPrompt = manager.buildAugmentedPrompt("What was the operating profit in Q3?", retrieval)
        assertTrue(augmentedPrompt.contains("[Relevant Document Context]"))
        assertTrue(augmentedPrompt.contains("Solus_Q3_Report.txt"))
        assertTrue(augmentedPrompt.contains("What was the operating profit in Q3?"))
    }

    @Test
    fun testRetrieveWithNoIndexedDocuments_ReturnsEmpty() = runBlocking {
        val manager = RagManager(
            context = null,
            embeddingEngine = UniversalSemanticEmbeddingEngine(),
            vectorStore = InMemoryRagVectorStore(null)
        )

        val retrieval = manager.retrieve("Any query when store is empty")
        assertFalse(retrieval.hasContext)
        assertTrue(retrieval.matches.isEmpty())

        val prompt = manager.buildAugmentedPrompt("Direct query", retrieval)
        assertEquals("Direct query", prompt)
    }

    @Test
    fun testClearKnowledgeBase() = runBlocking {
        val manager = RagManager(
            context = null,
            embeddingEngine = UniversalSemanticEmbeddingEngine(),
            vectorStore = InMemoryRagVectorStore(null)
        )

        manager.ingestText("Doc1.txt", "Some relevant content here.")
        assertTrue(manager.totalIndexedChunks > 0)

        manager.clearKnowledgeBase()
        assertEquals(0, manager.totalIndexedChunks)
        assertTrue(manager.indexedDocuments.isEmpty())
    }

    @Test
    fun testRetrieveOverviewOrSummaryQuery_ReturnsPrimaryDocumentChunks() = runBlocking {
        val manager = RagManager(
            context = null,
            embeddingEngine = UniversalSemanticEmbeddingEngine(),
            vectorStore = InMemoryRagVectorStore(null)
        )

        val docText = "Detailed technical blueprint for Solus neural network graph execution."
        val ingestion = manager.ingestText("blueprint.txt", docText)
        assertTrue(ingestion.success)

        // Meta queries like "summarize" or "overview" should successfully return chunks
        val retrieval = manager.retrieve("Summarize this document and highlight its key points.", topK = 3)
        assertTrue(retrieval.hasContext)
        assertEquals("blueprint.txt", retrieval.sourceNames.first())
        assertTrue("Summary retrieval should have high match score", retrieval.topMatchScore >= 0.8f)
    }

    @Test
    fun testRetrieveWithPreferredDocumentId_PrioritizesTargetDoc() = runBlocking {
        val manager = RagManager(
            context = null,
            embeddingEngine = UniversalSemanticEmbeddingEngine(),
            vectorStore = InMemoryRagVectorStore(null)
        )

        val ing1 = manager.ingestText("DocA.txt", "Alpha system notes and configurations.")
        val ing2 = manager.ingestText("DocB.txt", "Beta system notes and configurations.")

        val retrieval = manager.retrieve(
            query = "Explain everything",
            topK = 2,
            preferredDocumentId = ing2.documentId
        )
        assertTrue(retrieval.hasContext)
        assertEquals("DocB.txt", retrieval.matches.first().chunk.documentName)
    }

    @Test
    fun testIngestDuplicateDocumentName_CoexistsUnlessExplicitReplacement() = runBlocking {
        val manager = RagManager(
            context = null,
            embeddingEngine = UniversalSemanticEmbeddingEngine(),
            vectorStore = InMemoryRagVectorStore(null)
        )

        val ing1 = manager.ingestText("file.txt", "Initial version of file.")
        val initialChunks = manager.totalIndexedChunks
        assertEquals(1, manager.indexedDocuments.size)

        // Same name, different content -> coexists with distinct documentId
        val ing2 = manager.ingestText("file.txt", "Updated second version of file with different words.")
        assertEquals(2, manager.indexedDocuments.size)
        assertTrue(ing1.documentId != ing2.documentId)

        // Explicit replacement of the same upload -> replaces existing document
        val ing3 = manager.ingestText("file.txt", "Third version replacing second.", targetDocumentId = ing2.documentId,
            replacesSameUpload = true)
        assertTrue(ing3.success)
        assertEquals(2, manager.indexedDocuments.size)
        assertEquals(ing2.documentId, ing3.documentId)
    }

    @Test
    fun testRetrieveUnrelatedQuery_DoesNotMatchUnrelatedDocument() = runBlocking {
        val manager = RagManager(
            context = null,
            embeddingEngine = UniversalSemanticEmbeddingEngine(),
            vectorStore = InMemoryRagVectorStore(null)
        )

        val docText = """
            ISC Class 12 Chemistry Test Solutions: Transition and Inner Transition Elements.
            Transition metals exhibit variable oxidation states due to the participation of (n-1)d and ns electrons.
            Chromium and copper show anomalous electronic configurations.
            Potassium permanganate acts as a powerful oxidizing agent in acidic medium.
        """.trimIndent()
        val ingestion = manager.ingestText("ISC_Chemistry.pdf", docText)
        assertTrue(ingestion.success)

        // Math query completely unrelated to chemistry
        val retrieval = manager.retrieve("formula of 2 sin A sin B", topK = 3, minScore = 0.35f)
        assertFalse("Unrelated math query should not match chemistry document", retrieval.hasContext)
        assertTrue("Matches should be empty for unrelated query", retrieval.matches.isEmpty())
    }

    @Test
    fun testRetrieveFollowUpQuestionWithoutReuploading_SemanticMatch() = runBlocking {
        val manager = RagManager(
            context = null,
            embeddingEngine = UniversalSemanticEmbeddingEngine(),
            vectorStore = InMemoryRagVectorStore(null)
        )

        val policyText = """
            Solus Hardware Warranty and Service Coverage:
            Battery replacement is completely free of charge for 24 months from the purchase date.
            Screen repairs require a fifty dollar copay. Liquid immersion and intentional damage are excluded.
        """.trimIndent()
        val ingestion = manager.ingestText("Hardware_Warranty.pdf", policyText)
        assertTrue(ingestion.success)

        // User asks a follow-up in another turn without re-uploading and without setting preferredDocumentId
        val retrieval = manager.retrieve(
            query = "Is battery replacement covered under warranty?",
            topK = 2,
            minScore = 0.30f,
            preferredDocumentId = null
        )
        assertTrue("Should retrieve context from previously uploaded file", retrieval.hasContext)
        assertEquals("Hardware_Warranty.pdf", retrieval.sourceNames.first())
        assertTrue("Top chunk should contain battery replacement info", retrieval.matches.first().chunk.text.contains("Battery replacement"))
    }

    @Test
    fun testFindMatchingDocumentByNameInQuery() = runBlocking {
        val manager = RagManager(
            context = null,
            embeddingEngine = UniversalSemanticEmbeddingEngine(),
            vectorStore = InMemoryRagVectorStore(null)
        )

        manager.ingestText("Employee_Handbook.pdf", "Vacation policy allows 20 days of paid time off per calendar year.")
        manager.ingestText("Quarterly_Budget.xlsx", "Total marketing expenditure reached 120 thousand dollars in Q2.")

        // Query references the handbook by name
        val matchedDoc = manager.findMatchingDocumentForQuery("What does the employee handbook say about vacation days?")
        assertEquals("Employee_Handbook.pdf", matchedDoc?.documentName)

        val retrieval = manager.retrieve(
            query = "What does the employee handbook say about vacation days?",
            topK = 2,
            minScore = 0.30f
        )
        assertTrue(retrieval.hasContext)
        assertEquals("Employee_Handbook.pdf", retrieval.sourceNames.first())
    }

    @Test
    fun testOverviewQueryWithoutPreferredDocId_MatchesIndexedDoc() = runBlocking {
        val manager = RagManager(
            context = null,
            embeddingEngine = UniversalSemanticEmbeddingEngine(),
            vectorStore = InMemoryRagVectorStore(null)
        )

        manager.ingestText("Project_Plan.pdf", "Project Plan: Milestone 1 delivers the offline on-device inference runtime.")

        val retrieval = manager.retrieve(
            query = "Give me an overview of what I uploaded",
            topK = 2,
            preferredDocumentId = null
        )
        assertTrue(retrieval.hasContext)
        assertEquals("Project_Plan.pdf", retrieval.sourceNames.first())
    }

    @Test
    fun testIsQueryRelatedToDocument() = runBlocking {
        val manager = RagManager(
            context = null,
            embeddingEngine = UniversalSemanticEmbeddingEngine(),
            vectorStore = InMemoryRagVectorStore(null)
        )

        assertFalse(manager.isQueryRelatedToDocument("What is the capital of France?"))

        manager.ingestText("Tax_Invoice_2025.pdf", "Invoice total amount: $540.00 USD due March 15.")

        assertTrue(manager.isQueryRelatedToDocument("What was the invoice total?"))
        assertTrue(manager.isQueryRelatedToDocument("Can you summarize the document?"))
        assertTrue(manager.isQueryRelatedToDocument("What did I upload?"))
        assertTrue(manager.isQueryRelatedToDocument("In the file, when is payment due?"))
    }
    @Test fun disabledGlobalRagNeverFallsBackFromMissingExplicitDocument() = runBlocking {
        val manager = RagManager(null, UniversalSemanticEmbeddingEngine(), InMemoryRagVectorStore(null))
        manager.ingestText("library.txt", "Quantum computers use quantum bits or qubits.")
        val result = manager.retrieve("Explain quantum bits", preferredDocumentId = "missing-document",
            minScore = 0.01f, allowGlobalSearch = false)
        assertFalse(result.hasContext)
        assertTrue(result.matches.isEmpty())
    }

    @Test fun disabledGlobalRagDoesNotInferLibraryDocumentForOverview() = runBlocking {
        val manager = RagManager(null, UniversalSemanticEmbeddingEngine(), InMemoryRagVectorStore(null))
        manager.ingestText("library.txt", "Quantum computers use quantum bits or qubits.")
        assertFalse(manager.retrieve("Summarize the document", allowGlobalSearch = false).hasContext)
    }

    @Test fun disabledGlobalRagKeepsExplicitDocumentContext() = runBlocking {
        val manager = RagManager(null, UniversalSemanticEmbeddingEngine(), InMemoryRagVectorStore(null))
        val attached = manager.ingestText("attached.txt", "Document content that must remain available.")
        manager.ingestText("library.txt", "Unrelated library content.")
        val result = manager.retrieve("Summarize the document", preferredDocumentId = attached.documentId,
            allowGlobalSearch = false)
        assertTrue(result.hasContext)
        assertEquals(listOf("attached.txt"), result.sourceNames)
    }
}
