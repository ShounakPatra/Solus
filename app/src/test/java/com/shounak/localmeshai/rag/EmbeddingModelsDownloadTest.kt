package com.shounak.localmeshai.rag

import com.shounak.localmeshai.rag.embedding.EmbeddingModels
import kotlinx.coroutines.runBlocking
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
        assertEquals("https://github.com/ShounakPatra/Solus/releases/download/v6.0.5/bge-small-en-v1.5-int8.onnx", urls[1])
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
