package com.shounak.localmeshai.utils

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CancellationException

class RepositoryFileTransferTest {
    @get:Rule val folder = TemporaryFolder()
    private fun sha(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test fun publishesVerifiedFilesAndReusesDuplicateDownloads() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("weights"))
            val transfer = RepositoryFileTransfer(folder.root, OkHttpClient())
            val file = RepoFile("nested/model.bin", 7, sha("weights"))
            val saved = transfer.download("owner/repo", "sha", file, server.url("/file").toString())
            assertEquals("weights", saved.readText())
            assertEquals(saved, transfer.download("owner/repo", "sha", file, server.url("/file").toString()))
            assertEquals(1, server.requestCount)
            assertTrue(File(folder.root, "staging").listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun checksumFailureNeverPublishesAndRetryCanSucceed() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("broken!"))
            server.enqueue(MockResponse().setBody("weights"))
            val transfer = RepositoryFileTransfer(folder.root, OkHttpClient())
            val file = RepoFile("model.bin", 7, sha("weights"))
            assertThrows(IOException::class.java) { transfer.download("owner/repo", "sha", file, server.url("/file").toString()) }
            assertFalse(transfer.target("owner/repo", "sha", file).exists())
            assertTrue(File(folder.root, "staging").listFiles().orEmpty().isEmpty())
            assertEquals("weights", transfer.download("owner/repo", "sha", file, server.url("/file").toString()).readText())
        }
    }

    @Test fun cancellationNeverPublishesPartialFiles() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("weights"))
            val transfer = RepositoryFileTransfer(folder.root, OkHttpClient())
            val file = RepoFile("model.bin", 7)
            assertThrows(CancellationException::class.java) {
                transfer.download("owner/repo", "sha", file, server.url("/file").toString(), checkActive = { throw CancellationException("Stopped") })
            }
            assertFalse(transfer.target("owner/repo", "sha", file).exists())
            assertTrue(File(folder.root, "staging").listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun sizeAndHttpFailuresDoNotLookLikeCompletedDownloads() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("too short"))
            server.enqueue(MockResponse().setResponseCode(403))
            val transfer = RepositoryFileTransfer(folder.root, OkHttpClient())
            val file = RepoFile("file.bin", 100)
            repeat(2) { assertThrows(IOException::class.java) { transfer.download("owner/repo", "sha", file, server.url("/file").toString()) } }
            assertFalse(transfer.target("owner/repo", "sha", file).exists())
        }
    }

    @Test fun repairsCorruptPreviousFilesAndKeepsSeparateDirectoryNames() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("weights"))
            val transfer = RepositoryFileTransfer(folder.root, OkHttpClient())
            val file = RepoFile("a/model.bin", 7, sha("weights"))
            val old = transfer.target("owner/repo", "sha", file)
            old.parentFile!!.mkdirs(); old.writeText("corrupt")
            assertEquals("weights", transfer.download("owner/repo", "sha", file, server.url("/file").toString()).readText())
            assertNotEquals(old, transfer.target("owner/repo", "sha", file.copy(path = "b/model.bin")))
            assertNotEquals(old, transfer.target("owner/repo", "other-sha", file))
            assertNotEquals(old, transfer.target("owner/other", "sha", file))
        }
    }

    @Test fun preservesLiteralUrlCharactersAndRefusesTraversal() {
        val url = RepositoryFileTransfer.url("owner/repo", "sha", "folder/a+b%20 c.bin")
        assertTrue(url.contains("a+b%2520%20c.bin"))
        val transfer = RepositoryFileTransfer(folder.root, OkHttpClient())
        for (path in listOf("../escape", "/absolute", "a/../../bad", "a\\bad", "a//bad", "a/./bad")) {
            assertThrows(IllegalArgumentException::class.java) { transfer.target("owner/repo", "sha", RepoFile(path)) }
        }
    }
}
