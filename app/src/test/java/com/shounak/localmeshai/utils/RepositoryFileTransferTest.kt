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
    @Test fun caseOnlyPathsCanBothBeStoredWithoutOverwriting() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("upper")); server.enqueue(MockResponse().setBody("lower"))
            val transfer = RepositoryFileTransfer(folder.root, OkHttpClient())
            val upper = transfer.download("owner/repo", "sha", RepoFile("README.md", 5), server.url("/upper").toString())
            val lower = transfer.download("owner/repo", "sha", RepoFile("readme.md", 5), server.url("/lower").toString())
            assertNotEquals(upper.canonicalFile, lower.canonicalFile)
            assertEquals("upper", upper.readText()); assertEquals("lower", lower.readText())
        }
    }
    @Test fun previousDownloadLayoutRemainsDiscoverableWithoutAliasingCaseOnlySiblings() {
        val file = RepoFile("nested/README.md", 7, sha("weights"))
        val legacy = File(folder.root, RepositoryFileTransfer.key("owner/repo", "sha", "") + "/files/nested/README.md")
        legacy.parentFile!!.mkdirs(); legacy.writeText("weights")
        val transfer = RepositoryFileTransfer(folder.root, OkHttpClient())
        assertEquals(legacy.canonicalFile, transfer.existingTarget("owner/repo", "sha", file))
        assertEquals(legacy.canonicalFile, transfer.download("owner/repo", "sha", file).canonicalFile)
        assertNotEquals(legacy.canonicalFile, transfer.existingTarget("owner/repo", "sha", file.copy(path = "nested/readme.md")))
        assertEquals("weights", legacy.readText())
    }
    @Test fun interruptedTransferResumesWithRangeAndVerifiesWholeFile() {
        exerciseResume(ignoreRange = false)
    }
    @Test fun serverIgnoringRangeRestartsCleanlyInsteadOfAppending() {
        exerciseResume(ignoreRange = true)
    }
    private fun exerciseResume(ignoreRange: Boolean) {
        MockWebServer().use { server ->
            val payload = "x".repeat(200_000)
            val ranges = java.util.concurrent.CopyOnWriteArrayList<String>()
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val header = request.getHeader("Range")
                    if (header != null) ranges.add(header)
                    val start = if (header == null || ignoreRange) 0 else header.removePrefix("bytes=").removeSuffix("-").toInt()
                    return MockResponse().setBody(payload.substring(start)).setHeader("ETag", "v1").apply {
                        if (start > 0) setResponseCode(206).setHeader("Content-Range", "bytes $start-${payload.lastIndex}/${payload.length}")
                    }
                }
            }
            val transfer = RepositoryFileTransfer(folder.root, OkHttpClient())
            val file = RepoFile("model.bin", payload.length.toLong(), sha(payload))
            var stopped = false
            assertThrows(CancellationException::class.java) {
                transfer.download("owner/repo", "sha", file, server.url("/file").toString(),
                    checkActive = { if (stopped) throw CancellationException("Stopped") }, onProgress = { _, _ -> stopped = true })
            }
            val part = File(folder.root, "staging").listFiles()!!.single { it.extension == "part" }
            val prefix = part.length()
            assertTrue(prefix in 1 until payload.length.toLong())
            assertFalse(transfer.target("owner/repo", "sha", file).exists())
            val saved = transfer.download("owner/repo", "sha", file, server.url("/file").toString())
            assertEquals(payload, saved.readText())
            assertEquals(listOf("bytes=$prefix-"), ranges.toList())
            assertTrue(File(folder.root, "staging").listFiles().orEmpty().isEmpty())
        }
    }

}
