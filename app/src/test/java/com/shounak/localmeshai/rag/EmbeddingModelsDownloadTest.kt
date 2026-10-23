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
            EmbeddingModels.download(dir, s, OkHttpClient(), { done, _ -> last = done }) { server.url("/" + it.remotePath).toString() }
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
                EmbeddingModels.download(dir, s, OkHttpClient(), { _, _ -> }) { server.url("/" + it.remotePath).toString() }
            }
            assertTrue(result.isFailure)
            assertFalse(EmbeddingModels.isInstalled(dir, s))
            assertTrue(dir.listFiles()!!.isEmpty())
        } finally {
            server.shutdown()
        }
    }
}
