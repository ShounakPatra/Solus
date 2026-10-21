package com.shounak.localmeshai.rag

import com.shounak.localmeshai.rag.embedding.VectorEmbedding
import com.shounak.localmeshai.rag.store.InMemoryRagVectorStore
import com.shounak.localmeshai.rag.store.RagChunkRecord
import com.shounak.localmeshai.utils.FailableAtomicFileTransaction
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RagVectorStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testAddAndSearch_ReturnsRankedResults() {
        val store = InMemoryRagVectorStore(null)

        val v1 = VectorEmbedding.l2Normalize(floatArrayOf(1f, 0f, 0f))
        val v2 = VectorEmbedding.l2Normalize(floatArrayOf(0.7f, 0.7f, 0f))
        val v3 = VectorEmbedding.l2Normalize(floatArrayOf(0f, 1f, 0f))

        val chunks = listOf(
            RagChunkRecord("1", "docA", "Report.pdf", 0, "First chunk", v1),
            RagChunkRecord("2", "docA", "Report.pdf", 1, "Second chunk", v2),
            RagChunkRecord("3", "docB", "Notes.txt", 0, "Third chunk", v3)
        )

        store.addChunks(chunks)
        assertEquals(3, store.getTotalChunkCount())

        val query = VectorEmbedding.l2Normalize(floatArrayOf(1f, 0f, 0f))
        val results = store.search(query, topK = 2, minSimilarity = 0.5f)

        assertEquals(2, results.size)
        // Chunk 1 has similarity 1.0 (highest)
        assertEquals("1", results[0].chunk.id)
        assertEquals(1.0f, results[0].similarity, 1e-4f)

        // Chunk 2 has similarity ~0.707
        assertEquals("2", results[1].chunk.id)
        assertTrue(results[1].similarity > 0.6f)
    }

    @Test
    fun testMinSimilarity_FiltersLowScoringChunks() {
        val store = InMemoryRagVectorStore(null)

        val v1 = VectorEmbedding.l2Normalize(floatArrayOf(1f, 0f, 0f))
        val v2 = VectorEmbedding.l2Normalize(floatArrayOf(0f, 1f, 0f)) // Orthogonal: similarity = 0.0

        val chunks = listOf(
            RagChunkRecord("1", "docA", "Report.pdf", 0, "Chunk 1", v1),
            RagChunkRecord("2", "docA", "Report.pdf", 1, "Chunk 2", v2)
        )
        store.addChunks(chunks)

        val query = VectorEmbedding.l2Normalize(floatArrayOf(1f, 0f, 0f))
        val results = store.search(query, topK = 5, minSimilarity = 0.5f)

        assertEquals(1, results.size)
        assertEquals("1", results[0].chunk.id)
    }

    @Test
    fun testRemoveDocument_DeletesOnlyTargetDoc() {
        val store = InMemoryRagVectorStore(null)
        val v = floatArrayOf(1f, 0f)

        store.addChunks(
            listOf(
                RagChunkRecord("1", "doc1", "Doc1.txt", 0, "C1", v),
                RagChunkRecord("2", "doc1", "Doc1.txt", 1, "C2", v),
                RagChunkRecord("3", "doc2", "Doc2.txt", 0, "C3", v)
            )
        )
        assertEquals(3, store.getTotalChunkCount())

        val removed = store.removeDocument("doc1")
        assertTrue(removed)
        assertEquals(1, store.getTotalChunkCount())
        assertEquals("doc2", store.getDocuments().first().documentId)
    }

    @Test
    fun testClearAll_EmptiesStore() {
        val store = InMemoryRagVectorStore(null)
        store.addChunks(
            listOf(
                RagChunkRecord("1", "doc1", "Doc1.txt", 0, "C1", floatArrayOf(1f))
            )
        )
        assertEquals(1, store.getTotalChunkCount())

        store.clearAll()
        assertEquals(0, store.getTotalChunkCount())
        assertTrue(store.getDocuments().isEmpty())
    }

    @Test
    fun testGetChunksForDocument_ReturnsCorrectChunksOrdered() {
        val store = InMemoryRagVectorStore(null)
        val v = floatArrayOf(1f, 0f)
        val chunks = listOf(
            RagChunkRecord("1", "docA", "Report.pdf", 0, "Chunk 0", v),
            RagChunkRecord("2", "docB", "Other.pdf", 0, "Other chunk", v),
            RagChunkRecord("3", "docA", "Report.pdf", 1, "Chunk 1", v),
            RagChunkRecord("4", "docA", "Report.pdf", 2, "Chunk 2", v)
        )
        store.addChunks(chunks)

        val docAChunks = store.getChunksForDocument("docA", limit = 2)
        assertEquals(2, docAChunks.size)
        assertEquals("1", docAChunks[0].id)
        assertEquals("3", docAChunks[1].id)
    }

    @Test
    fun testGetRecentChunks_ReturnsChunksUpToLimit() {
        val store = InMemoryRagVectorStore(null)
        val v = floatArrayOf(1f, 0f)
        val chunks = listOf(
            RagChunkRecord("1", "docA", "Report.pdf", 0, "Chunk 0", v, timestamp = 2000L),
            RagChunkRecord("2", "docA", "Report.pdf", 1, "Chunk 1", v, timestamp = 2000L),
            RagChunkRecord("3", "docB", "Other.pdf", 0, "Other chunk", v, timestamp = 1000L)
        )
        store.addChunks(chunks)

        val recent = store.getRecentChunks(limit = 2)
        assertEquals(2, recent.size)
    }

    @Test
    fun testFailedWrite_PreservesPreviousStateAndFile() {
        val testFile = File(tempFolder.root, "rag_failed_write.json")
        val failableTx = FailableAtomicFileTransaction(testFile)
        val store = InMemoryRagVectorStore(
            storageDir = tempFolder.root,
            storageFileName = testFile.name,
            transaction = failableTx
        )

        val chunk1 = RagChunkRecord("1", "doc1", "Doc1.txt", 0, "Chunk 1", floatArrayOf(1f, 0f))
        assertTrue(store.addChunks(listOf(chunk1)))
        assertEquals(1, store.getTotalChunkCount())

        // Invalidate startWrite to simulate write failure
        failableTx.failStartWrite = true
        val chunk2 = RagChunkRecord("2", "doc2", "Doc2.txt", 0, "Chunk 2", floatArrayOf(0f, 1f))
        assertFalse(store.addChunks(listOf(chunk2)))

        // In-memory state remains 1
        assertEquals(1, store.getTotalChunkCount())
        assertNotNull(store.lastError)

        // Reopening store reloads only chunk1
        val reopened = InMemoryRagVectorStore(
            storageDir = tempFolder.root,
            storageFileName = testFile.name,
            transaction = FailableAtomicFileTransaction(testFile)
        )
        assertEquals(1, reopened.getTotalChunkCount())
        assertEquals("1", reopened.getRecentChunks(1).first().id)
    }

    @Test
    fun testInterruptedCommit_PreservesPreviousStateAndFile() {
        val testFile = File(tempFolder.root, "rag_interrupted.json")
        val failableTx = FailableAtomicFileTransaction(testFile)
        val store = InMemoryRagVectorStore(
            storageDir = tempFolder.root,
            storageFileName = testFile.name,
            transaction = failableTx
        )

        val chunk1 = RagChunkRecord("1", "doc1", "Doc1.txt", 0, "Chunk 1", floatArrayOf(1f, 0f))
        assertTrue(store.addChunks(listOf(chunk1)))
        assertEquals(1, store.getTotalChunkCount())

        // Fail finishWrite
        failableTx.failFinishWrite = true
        val chunk2 = RagChunkRecord("2", "doc2", "Doc2.txt", 0, "Chunk 2", floatArrayOf(0f, 1f))
        assertFalse(store.addChunks(listOf(chunk2)))
        assertEquals(1, store.getTotalChunkCount())

        val reopened = InMemoryRagVectorStore(
            storageDir = tempFolder.root,
            storageFileName = testFile.name,
            transaction = FailableAtomicFileTransaction(testFile)
        )
        assertEquals(1, reopened.getTotalChunkCount())
        assertEquals("1", reopened.getRecentChunks(1).first().id)
    }

    @Test
    fun testCorruptRead_PreservesCorruptFile() {
        val testFile = File(tempFolder.root, "rag_corrupt.json")
        testFile.writeText("{ malformed json RAG content [[")

        val store = InMemoryRagVectorStore(
            storageDir = tempFolder.root,
            storageFileName = testFile.name
        )

        assertEquals(0, store.getTotalChunkCount())
        assertNotNull(store.lastError)
        assertTrue(testFile.exists())
        assertEquals("{ malformed json RAG content [[", testFile.readText())
    }

    @Test
    fun testConcurrentMutations_SerializedAndConsistent() {
        val testFile = File(tempFolder.root, "rag_concurrent.json")
        val store = InMemoryRagVectorStore(
            storageDir = tempFolder.root,
            storageFileName = testFile.name
        )

        val threadCount = 8
        val itemsPerThread = 4
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until itemsPerThread) {
                        store.addChunks(
                            listOf(
                                RagChunkRecord(
                                    id = "t${t}_$i",
                                    documentId = "doc_$t",
                                    documentName = "Doc$t.txt",
                                    chunkIndex = i,
                                    text = "Text $t $i",
                                    embedding = floatArrayOf(1f, 0f)
                                )
                            )
                        )
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()

        assertEquals(threadCount * itemsPerThread, store.getTotalChunkCount())

        val reopened = InMemoryRagVectorStore(
            storageDir = tempFolder.root,
            storageFileName = testFile.name
        )
        assertEquals(threadCount * itemsPerThread, reopened.getTotalChunkCount())
    }

    @Test
    fun testDimensionChange_InvalidatesSavedIndex() {
        val testFile = File(tempFolder.root, "rag_dim_change.json")
        val store768 = InMemoryRagVectorStore(
            storageDir = tempFolder.root,
            storageFileName = testFile.name,
            expectedDimension = 4
        )

        val chunk = RagChunkRecord(
            id = "c1",
            documentId = "d1",
            documentName = "Doc.txt",
            chunkIndex = 0,
            text = "Content",
            embedding = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f)
        )
        assertTrue(store768.addChunks(listOf(chunk)))
        assertEquals(1, store768.getTotalChunkCount())

        // Reopen with different expected dimension (e.g. 2 instead of 4)
        val storeMismatched = InMemoryRagVectorStore(
            storageDir = tempFolder.root,
            storageFileName = testFile.name,
            expectedDimension = 2
        )

        // Saved index must be invalidated and cleared
        assertEquals(0, storeMismatched.getTotalChunkCount())
        assertNotNull(storeMismatched.lastError)
        assertTrue(
            "Error must indicate invalidation/mismatch",
            storeMismatched.lastError!!.contains("invalidated", ignoreCase = true) ||
                storeMismatched.lastError!!.contains("mismatch", ignoreCase = true)
        )
    }
}
