package com.shounak.localmeshai.rag

import com.shounak.localmeshai.rag.embedding.SemanticEmbeddingEngine
import com.shounak.localmeshai.rag.embedding.UniversalSemanticEmbeddingEngine
import com.shounak.localmeshai.rag.store.InMemoryRagVectorStore
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RagEmbeddingSwitchTest {
    @Test fun removingNeuralEngineKeepsDocumentsSearchableWithKeywordFallback() = runBlocking {
        val neural = FakeEngine()
        val manager = RagManager(null, neural, InMemoryRagVectorStore(null, expectedDimension = 8, algorithmId = neural.algorithmId))
        val document = manager.ingestText("cars.txt", "The battery powers the vehicle.")
        val chunks = manager.vectorStore.getChunksForDocument(document.documentId).map { it.id to it.text }
        val lexical = UniversalSemanticEmbeddingEngine()
        manager.switchEmbeddingEngine(lexical, InMemoryRagVectorStore(null, expectedDimension = lexical.dimension)).getOrThrow()
        assertTrue(neural.released)
        assertSame(lexical, manager.embeddingEngine)
        assertEquals(chunks, manager.vectorStore.getChunksForDocument(document.documentId).map { it.id to it.text })
        assertTrue(manager.retrieveForChat("vehicle battery", minScore = 0f).hasContext)
    }
    @Test fun timeoutReturnsWhileNonInterruptibleEmbeddingRetainsIndexOwnership() = runBlocking {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val lexical = UniversalSemanticEmbeddingEngine()
        val engine = object : SemanticEmbeddingEngine by lexical {
            override suspend fun embedQuery(text: String): FloatArray {
                entered.complete(Unit)
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                return lexical.embed(text)
            }
        }
        val manager = RagManager(null, engine, InMemoryRagVectorStore(null, expectedDimension = engine.dimension))
        manager.ingestText("cars.txt", "The battery powers the vehicle.")
        try {
            val result = async { manager.retrieveForChat("vehicle battery", timeoutMillis = 100) }
            kotlinx.coroutines.withTimeout(2_000) { entered.await() }
            val timedOut = kotlinx.coroutines.withTimeout(2_000) { result.await() }
            assertTrue(timedOut.unavailableReason!!.contains("timed out"))
            assertFalse(timedOut.hasContext)
            val stillOwned = manager.retrieveForChat("vehicle battery")
            assertTrue(stillOwned.unavailableReason!!.contains("indexed"))
        } finally { release.countDown() }
        kotlinx.coroutines.withTimeout(2_000) {
            while (manager.retrieveForChat("vehicle battery", minScore = 0f).unavailableReason != null) kotlinx.coroutines.delay(10)
        }
        assertTrue(manager.retrieveForChat("vehicle battery", minScore = 0f).hasContext)
    }

    /** Deterministic 8-dim engine with a calibration so threshold mapping is exercised. */
    private class FakeEngine(private val fail: Boolean = false) : SemanticEmbeddingEngine {
        override val algorithmId = "fake-8"
        override val dimension = 8
        var released = false
        override suspend fun embed(text: String) = vector(text)
        override suspend fun embedBatch(texts: List<String>): List<FloatArray> {
            if (fail) error("model crashed")
            return texts.map(::vector)
        }
        override fun calibrate(rawCosine: Float) = ((rawCosine - 0.5f) / 0.5f).coerceIn(0f, 1f)
        override fun rawThreshold(calibrated: Float) = 0.5f + 0.5f * calibrated
        override fun release() { released = true }
        private fun vector(text: String) = FloatArray(8).also { v ->
            val lower = text.lowercase()
            if ("battery" in lower || "vehicle" in lower) v[0] = 1f
            if ("plant" in lower || "photosynthesis" in lower) v[1] = 1f
            if (v.all { it == 0f }) v[7] = 1f
        }
    }

    private fun lexicalManager() = RagManager(
        context = null,
        embeddingEngine = UniversalSemanticEmbeddingEngine(),
        vectorStore = InMemoryRagVectorStore(null, expectedDimension = 384)
    )

    @Test
    fun switchKeepsDocumentsAndChunkIdentity() = runBlocking {
        val manager = lexicalManager()
        val a = manager.ingestText("cars.txt", "The car battery was dead so it would not start.")
        val b = manager.ingestText("plants.txt", "Photosynthesis lets a plant turn sunlight into sugar.")
        val oldIds = manager.vectorStore.getChunksForDocument(a.documentId).map { it.id }
        val oldStore = manager.vectorStore

        val engine = FakeEngine()
        val newStore = InMemoryRagVectorStore(null, expectedDimension = 8, algorithmId = engine.algorithmId)
        val migrated = manager.switchEmbeddingEngine(engine, newStore).getOrThrow()

        assertEquals(a.chunkCount + b.chunkCount, migrated)
        assertSame(engine, manager.embeddingEngine)
        assertSame(newStore, manager.vectorStore)
        assertEquals(setOf("cars.txt", "plants.txt"), manager.indexedDocuments.map { it.documentName }.toSet())
        assertEquals(oldIds, manager.vectorStore.getChunksForDocument(a.documentId).map { it.id })
        assertTrue(manager.vectorStore.getChunksForDocument(a.documentId).all { it.embedding.size == 8 })
        assertEquals(0, oldStore.getTotalChunkCount())

        val hit = manager.retrieve("my vehicle won't start", topK = 1, minScore = 0.35f)
        assertTrue(hit.hasContext)
        assertEquals("cars.txt", hit.sourceNames.single())
        assertTrue(hit.topMatchScore in 0f..1f)
    }

    @Test
    fun failedSwitchLeavesKnowledgeBaseUntouched() = runBlocking {
        val manager = lexicalManager()
        manager.ingestText("cars.txt", "The car battery was dead so it would not start.")
        val oldEngine = manager.embeddingEngine
        val oldStore = manager.vectorStore
        val before = oldStore.getTotalChunkCount()

        val broken = FakeEngine(fail = true)
        val result = manager.switchEmbeddingEngine(broken, InMemoryRagVectorStore(null, expectedDimension = 8, algorithmId = "fake-8"))

        assertTrue(result.isFailure)
        assertSame(oldEngine, manager.embeddingEngine)
        assertSame(oldStore, manager.vectorStore)
        assertEquals(before, manager.vectorStore.getTotalChunkCount())
    }

    @Test
    fun switchReleasesThePreviousEngine() = runBlocking {
        val first = FakeEngine()
        val manager = RagManager(null, first, InMemoryRagVectorStore(null, expectedDimension = 8, algorithmId = "fake-8"))
        manager.switchEmbeddingEngine(UniversalSemanticEmbeddingEngine(), InMemoryRagVectorStore(null, expectedDimension = 384)).getOrThrow()
        assertTrue(first.released)
    }

    @Test
    fun mismatchedStoreDimensionIsRejected() = runBlocking {
        val manager = lexicalManager()
        val result = manager.switchEmbeddingEngine(FakeEngine(), InMemoryRagVectorStore(null, expectedDimension = 384, algorithmId = "fake-8"))
        assertTrue(result.isFailure)
    }

    @Test
    fun failedActivationKeepsSourceDocumentsAndEngine() = runBlocking {
        val manager = lexicalManager()
        manager.ingestText("cars.txt", "The battery powers the car.")
        val source = manager.vectorStore
        val result = manager.switchEmbeddingEngine(FakeEngine(),
            InMemoryRagVectorStore(null, expectedDimension = 8, algorithmId = "fake-8"),
            beforeActivation = { error("disk full") })
        assertTrue(result.isFailure)
        assertSame(source, manager.vectorStore)
        assertEquals(1, source.getDocuments().size)
    }

    @Test
    fun deletionDuringMigrationCannotResurrectDocuments() = runBlocking {
        val manager = lexicalManager()
        val doc = manager.ingestText("cars.txt", "The battery powers the car.")
        val source = manager.vectorStore
        val result = manager.switchEmbeddingEngine(FakeEngine(),
            InMemoryRagVectorStore(null, expectedDimension = 8, algorithmId = "fake-8"),
            onProgress = { done, _ -> if (done > 0) manager.removeDocument(doc.documentId) })
        assertTrue(result.isFailure)
        assertSame(source, manager.vectorStore)
        assertEquals(0, manager.totalIndexedChunks)
    }

    @Test
    fun cancelledMigrationKeepsSourceAndDoesNotActivate() = runBlocking {
        val manager = lexicalManager()
        manager.ingestText("cars.txt", "The battery powers the car.")
        val source = manager.vectorStore
        var activated = false
        val result = manager.switchEmbeddingEngine(FakeEngine(),
            InMemoryRagVectorStore(null, expectedDimension = 8, algorithmId = "fake-8"),
            onProgress = { done, _ -> if (done > 0) throw kotlinx.coroutines.CancellationException("stop") },
            beforeActivation = { activated = true })
        assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertTrue(!activated)
        assertSame(source, manager.vectorStore)
        assertEquals(1, source.getDocuments().size)
    }

    @Test
    fun missingModelRecoveryKeepsDocumentsAcrossRepeatedLoads() = runBlocking {
        val sourceEngine = FakeEngine()
        val source = InMemoryRagVectorStore(null, expectedDimension = 8, algorithmId = "fake-8")
        RagManager(null, sourceEngine, source).ingestText("cars.txt", "The battery powers the car.")
        val fallback = lexicalManager()
        fallback.ingestText("stale.txt", "A document removed from the authoritative index.")
        val manager = RagManager(null, UniversalSemanticEmbeddingEngine(), fallback.vectorStore, orphanedStore = source)
        manager.loadAsync()
        manager.loadAsync()
        assertEquals(listOf("cars.txt"), manager.indexedDocuments.map { it.documentName })
        assertEquals(0, source.getTotalChunkCount())
    }

    @Test
    fun emptyOrphanIndexDoesNotResurrectDeletedDocuments() = runBlocking {
        val fallback = lexicalManager()
        fallback.ingestText("stale.txt", "A document removed from the authoritative index.")
        val source = InMemoryRagVectorStore(null, expectedDimension = 8, algorithmId = "fake-8")
        val manager = RagManager(null, UniversalSemanticEmbeddingEngine(), fallback.vectorStore, orphanedStore = source)
        manager.loadAsync()
        assertEquals(0, manager.totalIndexedChunks)
    }
    @Test
    fun interactiveSearchDoesNotWaitForIndexingAndRecoversAfterwards() = runBlocking {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val finish = kotlinx.coroutines.CompletableDeferred<Unit>()
        val lexical = UniversalSemanticEmbeddingEngine()
        val engine = object : SemanticEmbeddingEngine by lexical {
            override suspend fun embedBatch(texts: List<String>): List<FloatArray> {
                entered.complete(Unit)
                finish.await()
                return lexical.embedBatch(texts)
            }
        }
        val manager = RagManager(null, engine, InMemoryRagVectorStore(null, expectedDimension = engine.dimension))
        val indexing = kotlinx.coroutines.CoroutineScope(coroutineContext).async {
            manager.ingestText("cars.txt", "The battery powers the vehicle.")
        }
        entered.await()
        try {
            kotlinx.coroutines.withTimeout(1_000) {
                val result = manager.retrieveForChat("vehicle battery")
                assertFalse(result.hasContext)
                assertTrue(result.unavailableReason!!.contains("indexed"))
                assertTrue(manager.buildAugmentedPrompt("vehicle battery", result).contains("temporarily unavailable"))
            }
        } finally {
            finish.complete(Unit)
        }
        assertTrue(indexing.await().success)
        assertTrue(manager.retrieve("vehicle battery", minScore = 0f).hasContext)
    }

}
