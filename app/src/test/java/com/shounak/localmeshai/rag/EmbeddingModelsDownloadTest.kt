package com.shounak.localmeshai.rag

import com.shounak.localmeshai.rag.embedding.EmbeddingModels
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class EmbeddingModelsDownloadTest {
    @Test fun deletingInactiveSearchModelCleansPartialDownloadsWithoutRemovingDocuments() = runBlocking {
        val context = object : android.content.ContextWrapper(null) {
            override fun getFilesDir() = temp.root
        }
        val modelDir = EmbeddingModels.directory(context, EmbeddingModels.BGE_SMALL_EN_V15).apply { mkdirs() }
        java.io.File(modelDir, "model.onnx.part").writeText("partial")
        val manager = RagManager(
            context,
            vectorStore = com.shounak.localmeshai.rag.store.InMemoryRagVectorStore(null),
            deletionJournal = com.shounak.localmeshai.data.ChatDeletionJournal(temp.root)
        )
        val document = manager.ingestText("cars.txt", "The battery powers the vehicle.")
        manager.setEmbeddingModel(EmbeddingModels.LEXICAL_ID).getOrThrow()
        assertEquals(1, manager.embeddingFilesRevision.value)
        assertFalse(modelDir.exists())
        assertEquals(document.documentId, manager.indexedDocuments.single().documentId)
        assertTrue(manager.retrieveForChat("vehicle battery", minScore = 0f).hasContext)
    }
    @Test fun deletionRemovesModelAndCompanionsButKeepsDocumentStorage() {
        val context = object : android.content.ContextWrapper(null) {
            override fun getFilesDir() = temp.root
        }
        val spec = EmbeddingModels.BGE_SMALL_EN_V15
        val modelDir = EmbeddingModels.directory(context, spec).apply { mkdirs() }
        spec.files.forEach { java.io.File(modelDir, it.localName).writeText("model companion") }
        java.io.File(modelDir, "model.onnx.part").writeText("interrupted download")
        val document = java.io.File(temp.root, "rag_originals/document.pdf").apply { parentFile!!.mkdirs(); writeText("original document") }
        EmbeddingModels.delete(context, spec)
        assertFalse(modelDir.exists())
        assertEquals("original document", document.readText())
        EmbeddingModels.delete(context, spec) // Already absent is a successful deletion too.
    }
    @Test
    fun cancellingAStalledHttpRequestAbortsTransport() = runBlocking {
        val server = MockWebServer().apply {
            enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            start()
        }
        val dir = temp.newFolder("stalled")
        val s = spec(byteArrayOf(1), byteArrayOf(2))
        val job = launch(kotlinx.coroutines.Dispatchers.Default) {
            EmbeddingModels.download(dir, s, OkHttpClient(), { _, _ -> }) {
                listOf(server.url("/stalled").toString())
            }
        }
        try {
            assertTrue(server.takeRequest(3, java.util.concurrent.TimeUnit.SECONDS) != null)
            job.cancel()
            kotlinx.coroutines.withTimeout(2_000) { job.join() }
            assertTrue(dir.listFiles()!!.isEmpty())
        } finally {
            job.cancel()
            server.shutdown()
        }
    }

    @get:Rule val temp = TemporaryFolder()

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun spec(model: ByteArray, vocab: ByteArray, modelHash: String = sha(model)) =
        EmbeddingModels.BGE_SMALL_EN_V15.copy(
            model = EmbeddingModels.RemoteFile("m.onnx", "model.onnx", model.size.toLong(), modelHash),
            vocab = EmbeddingModels.RemoteFile("vocab.txt", "vocab.txt", vocab.size.toLong(), sha(vocab))
        )

    @Test
    fun downloadsVerifiesAndInstalls() = runBlocking {
        val model = ByteArray(200_000) { (it % 251).toByte() }
        val vocab = "[PAD]\n[UNK]\n[CLS]\n[SEP]\n".toByteArray()
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setBody(okio.Buffer().write(model)))
            server.enqueue(MockResponse().setBody(okio.Buffer().write(vocab)))
            val s = spec(model, vocab)
            val dir = temp.newFolder("bge")
            var last = 0L
            EmbeddingModels.download(dir, s, OkHttpClient(), { done, _ -> last = done }) { listOf(server.url("/" + it.remotePath).toString()) }
            assertTrue(EmbeddingModels.isInstalled(dir, s))
            assertEquals(s.downloadBytes, last)
            assertFalse(dir.listFiles()!!.any { it.name.endsWith(".part") })
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun checksumMismatchLeavesNothingInstalled() = runBlocking {
        val model = ByteArray(1_000) { 7 }
        val vocab = "[UNK]\n".toByteArray()
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setBody(okio.Buffer().write(model)))
            val s = spec(model, vocab, modelHash = "0".repeat(64))
            val dir = temp.newFolder("bad")
            val result = runCatching {
                EmbeddingModels.download(dir, s, OkHttpClient(), { _, _ -> }) { listOf(server.url("/" + it.remotePath).toString()) }
            }
            assertTrue(result.isFailure)
            assertFalse(EmbeddingModels.isInstalled(dir, s))
            assertTrue(dir.listFiles()!!.isEmpty())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun fallsBackToMirrorWhenPrimaryFails() = runBlocking {
        val model = ByteArray(5_000) { 3 }
        val vocab = "[UNK]\n".toByteArray()
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setBody(okio.Buffer().write(model)))
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setBody(okio.Buffer().write(vocab)))
            val s = spec(model, vocab)
            val dir = temp.newFolder("mirror")
            EmbeddingModels.download(dir, s, OkHttpClient(), { _, _ -> }) {
                listOf(server.url("/hf/" + it.remotePath).toString(), server.url("/gh/" + it.mirrorName).toString())
            }
            assertTrue(EmbeddingModels.isInstalled(dir, s))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun builtInSpecListsHuggingFaceThenReleaseMirror() {
        val spec = EmbeddingModels.BGE_SMALL_EN_V15
        val urls = spec.urls(spec.model)
        assertEquals(2, urls.size)
        assertTrue(urls[0].startsWith("https://huggingface.co/Xenova/bge-small-en-v1.5/resolve/"))
        assertEquals("https://github.com/ShounakPatra/Solus/releases/download/v6.0.6/bge-small-en-v1.5-int8.onnx", urls[1])
    }

    @Test
    fun corruptSameLengthCachedFileIsReplaced() = runBlocking {
        val model = ByteArray(1000) { 7 }
        val vocab = "[UNK]\n".toByteArray()
        val s = spec(model, vocab)
        val dir = temp.newFolder("cached")
        dir.resolve(s.model.localName).writeBytes(ByteArray(model.size) { 0 })
        dir.resolve(s.vocab.localName).writeBytes(vocab)
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setBody(okio.Buffer().write(model)))
            EmbeddingModels.download(dir, s, OkHttpClient(), { _, _ -> }) {
                listOf(server.url("/" + it.remotePath).toString())
            }
            assertEquals(sha(model), sha(dir.resolve(s.model.localName).readBytes()))
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test
    fun cancelledDownloadRemovesPartialFile() = runBlocking {
        val model = ByteArray(200_000) { 7 }
        val vocab = "[UNK]\n".toByteArray()
        val s = spec(model, vocab)
        val dir = temp.newFolder("cancel")
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setBody(okio.Buffer().write(model)))
            val result = runCatching {
                EmbeddingModels.download(dir, s, OkHttpClient(), { _, _ ->
                    throw kotlinx.coroutines.CancellationException("stop")
                }) { listOf(server.url("/" + it.remotePath).toString()) }
            }
            assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            assertTrue(dir.listFiles()!!.isEmpty())
        } finally { server.shutdown() }
    }
}
