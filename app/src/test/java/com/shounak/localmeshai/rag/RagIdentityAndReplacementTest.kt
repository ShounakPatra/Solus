package com.shounak.localmeshai.rag

import com.shounak.localmeshai.rag.embedding.SemanticEmbeddingEngine
import com.shounak.localmeshai.rag.embedding.UniversalSemanticEmbeddingEngine
import com.shounak.localmeshai.rag.store.InMemoryRagVectorStore
import com.shounak.localmeshai.rag.store.RagChunkRecord
import com.shounak.localmeshai.utils.FailableAtomicFileTransaction
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RagIdentityAndReplacementTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testSameNameDocuments_CoexistWithDistinctIds() = runBlocking {
        val manager = RagManager(
            context = null,
            embeddingEngine = UniversalSemanticEmbeddingEngine(),
            vectorStore = InMemoryRagVectorStore(null)
        )

        val doc1 = manager.ingestText("notes.txt", "Financial balance sheet and accounting details.")
        val doc2 = manager.ingestText("notes.txt", "Software architecture and compiler backend configuration.")

        assertTrue(doc1.success)
        assertTrue(doc2.success)
        assertNotEquals(doc1.documentId, doc2.documentId)
        assertEquals(2, manager.indexedDocuments.size)
        assertEquals(doc1.chunkCount + doc2.chunkCount, manager.totalIndexedChunks)
    }

    @Test
    fun testIngestDeduplication_ReusesExistingIdAndChunks() = runBlocking {
        val manager = RagManager(
            context = null,
            embeddingEngine = UniversalSemanticEmbeddingEngine(),
            vectorStore = InMemoryRagVectorStore(null)
        )

        val content = "Identical document body for knowledge base indexing."
        val doc1 = manager.ingestText("manual.txt", content)
        assertTrue(doc1.success)

        val initialChunks = manager.totalIndexedChunks

        // Ingest identical content under a different filename
        val doc2 = manager.ingestText("manual_copy.txt", content)
        assertTrue(doc2.success)
        assertEquals(doc1.documentId, doc2.documentId)
        assertEquals(initialChunks, manager.totalIndexedChunks)
        assertEquals(1, manager.indexedDocuments.size)
    }

    @Test
    fun testFailedReplacement_PreservesOldData() = runBlocking {
        val testFile = File(tempFolder.root, "rag_replacement_test.json")
        val failableTx = FailableAtomicFileTransaction(testFile)
        val store = InMemoryRagVectorStore(
            storageDir = tempFolder.root,
            storageFileName = testFile.name,
            transaction = failableTx
        )
        val manager = RagManager(
            context = null,
            embeddingEngine = UniversalSemanticEmbeddingEngine(),
            vectorStore = store
        )

        val doc1 = manager.ingestText("original.txt", "Critical baseline specification version 1.0.")
        assertTrue(doc1.success)
        val originalChunks = store.getChunksForDocument(doc1.documentId)
        assertEquals(1, originalChunks.size)
        assertTrue(originalChunks[0].text.contains("Critical baseline specification"))

        // Simulate atomic write failure during replacement
        failableTx.failStartWrite = true
        val failedReplace = manager.ingestText(
            documentName = "original.txt",
            content = "Corrupted draft replacement that must not overwrite.",
            targetDocumentId = doc1.documentId
        )

        assertFalse(failedReplace.success)

        // Verify the original document is still completely intact
        val preservedChunks = store.getChunksForDocument(doc1.documentId)
        assertEquals(1, preservedChunks.size)
        assertTrue(preservedChunks[0].text.contains("Critical baseline specification"))
    }

    @Test
    fun testPreservingExplicitDocumentIdOnReplacement() = runBlocking {
        val manager = RagManager(
            context = null,
            embeddingEngine = UniversalSemanticEmbeddingEngine(),
            vectorStore = InMemoryRagVectorStore(null)
        )

        val doc1 = manager.ingestText("spec.txt", "Initial system draft specifications.")
        assertTrue(doc1.success)
        val targetId = doc1.documentId

        val replaced = manager.ingestText(
            documentName = "spec.txt",
            content = "Final revised production specifications and deployment keys.",
            targetDocumentId = targetId
        )

        assertTrue(replaced.success)
        assertEquals(targetId, replaced.documentId)
        assertEquals(1, manager.indexedDocuments.size)

        val chunks = manager.vectorStore.getChunksForDocument(targetId)
        assertTrue(chunks.any { it.text.contains("Final revised production specifications") })
        assertFalse(chunks.any { it.text.contains("Initial system draft") })
    }

    @Test
    fun testConcurrentIngestion_SerializedAndConsistent() = runBlocking {
        val manager = RagManager(
            context = null,
            embeddingEngine = UniversalSemanticEmbeddingEngine(),
            vectorStore = InMemoryRagVectorStore(null)
        )

        val concurrencyCount = 10
        val jobs = (1..concurrencyCount).map { i ->
            async {
                manager.ingestText("doc_$i.txt", "Distinct content payload for document index $i.")
            }
        }

        val results = jobs.awaitAll()
        assertTrue(results.all { it.success })
        assertEquals(concurrencyCount, manager.indexedDocuments.size)
        assertEquals(concurrencyCount, manager.totalIndexedChunks)
    }

    @Test
    fun testIncompatibleVectorDimensions_Rejection() = runBlocking {
        val store = InMemoryRagVectorStore(null, expectedDimension = 384)

        // Attempt direct insertion of mismatched dimension (e.g. 10 floats instead of 384)
        val invalidRecord = RagChunkRecord(
            id = "bad_chunk",
            documentId = "bad_doc",
            documentName = "bad.txt",
            chunkIndex = 0,
            text = "Bad vector text",
            embedding = FloatArray(10)
        )

        val directAdded = store.addChunks(listOf(invalidRecord))
        assertFalse(directAdded)
        assertNotNull(store.lastError)
        assertTrue(store.lastError!!.contains("Incompatible vector dimension"))

        // Attempt ingestion through RagManager with an engine providing mismatched dimension
        val mockBadEngine = object : SemanticEmbeddingEngine {
            override val algorithmId: String get() = "mock-128"
            override val dimension: Int get() = 128
            override suspend fun embed(text: String): FloatArray = FloatArray(128)
            override suspend fun embedBatch(texts: List<String>): List<FloatArray> = texts.map { FloatArray(128) }
        }

        val managerWithBadEngine = RagManager(
            context = null,
            embeddingEngine = mockBadEngine,
            vectorStore = store
        )

        val badIngest = managerWithBadEngine.ingestText("bad_engine.txt", "Some text requiring embeddings.")
        assertFalse(badIngest.success)
        assertTrue(badIngest.message.contains("Incompatible vector dimension"))
    }

    @Test
    fun testScopedRetrieval_FindsChunksBeyond100() = runBlocking {
        val store = InMemoryRagVectorStore(null, expectedDimension = 384)
        val engine = UniversalSemanticEmbeddingEngine()
        val manager = RagManager(
            context = null,
            embeddingEngine = engine,
            vectorStore = store
        )

        val docId = "large_doc_1"
        val totalChunks = 125
        val records = ArrayList<RagChunkRecord>(totalChunks)

        for (i in 0 until totalChunks) {
            val text = if (i == 115) {
                "Critical diagnostic code: DELTA_OMEGA_999 found in deep section."
            } else {
                "Standard operational telemetry data sequence filler $i."
            }
            records.add(
                RagChunkRecord(
                    id = "chunk_$i",
                    documentId = docId,
                    documentName = "LargeTelemetryLog.txt",
                    chunkIndex = i,
                    text = text,
                    embedding = engine.embed(text)
                )
            )
        }

        assertTrue(store.addChunks(records))
        assertEquals(totalChunks, store.getTotalChunkCount())

        // Retrieve specifically searching for the deep chunk beyond index 100
        val retrieval = manager.retrieve(
            query = "DELTA_OMEGA_999 diagnostic code",
            topK = 2,
            minScore = 0.25f,
            preferredDocumentId = docId
        )

        assertTrue("Should retrieve deep context beyond chunk 100", retrieval.hasContext)
        val topChunk = retrieval.matches.first().chunk
        assertEquals(115, topChunk.chunkIndex)
        assertTrue(topChunk.text.contains("DELTA_OMEGA_999"))
    }
}
