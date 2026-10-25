package com.shounak.localmeshai.utils

import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RepositoryFileQueueTest {
    @get:Rule val folder = TemporaryFolder()
    private class QueueContext(private val root: File, val deny: Boolean = false) : ContextWrapper(null) {
        override fun getFilesDir() = root
        override fun getExternalFilesDir(type: String?) = File(root, "external")
        override fun startForegroundService(service: Intent): ComponentName? {
            check(!deny) { "Background foreground-service start denied" }
            return null
        }
        override fun startService(service: Intent) = startForegroundService(service)
    }
    @Test fun retryWhileOriginalBatchStillExistsGetsItsOwnDurableManifest() {
        val context = QueueContext(folder.root)
        val file = RepoFile("weights.bin", 100)
        val repo = ResolvedRepoInfo("owner/repo", "main", "abc", listOf(file))
        RepositoryFileDownloads.enqueue(context, repo, listOf(file))
        RepositoryFileDownloads.update(RepositoryFileTransfer.key(repo.repoId, repo.commitSha, file.path), RepositoryFileDownloadState("Failed"))
        RepositoryFileDownloads.enqueue(context, repo, listOf(file))
        assertEquals(2, RepositoryFileDownloads.queue(context).listFiles()!!.count { it.extension == "json" })
    }
    @Test fun doubleTapCoalescesPendingFilesButKeepsNewFiles() {
        val context = QueueContext(folder.root)
        val first = RepoFile("first.bin", 100)
        val second = RepoFile("second.bin", 200)
        val repo = ResolvedRepoInfo("owner/double", "main", "abc", listOf(first, second))
        RepositoryFileDownloads.enqueue(context, repo, listOf(first))
        RepositoryFileDownloads.enqueue(context, repo, listOf(first))
        assertEquals(1, RepositoryFileDownloads.queue(context).listFiles()!!.count { it.extension == "json" })
        RepositoryFileDownloads.enqueue(context, repo, listOf(first, second))
        val entries = RepositoryFileDownloads.queue(context).listFiles()!!.filter { it.extension == "json" }
            .map { org.json.JSONObject(it.readText()).getJSONArray("files").length() }
        assertEquals(listOf(1, 1), entries)
    }
    @Test fun deniedStartKeepsManifestForNextForegroundOpen() {
        val context = QueueContext(folder.root, deny = true)
        val file = RepoFile("weights.bin", 100)
        val repo = ResolvedRepoInfo("owner/denied", "main", "abc", listOf(file))
        assertThrows(IllegalStateException::class.java) { RepositoryFileDownloads.enqueue(context, repo, listOf(file)) }
        assertEquals(1, RepositoryFileDownloads.queue(context).listFiles()!!.count { it.extension == "json" })
        assertEquals("Could not start download", RepositoryFileDownloads.states.value[RepositoryFileTransfer.key(repo.repoId, repo.commitSha, file.path)]!!.description)
    }
    @Test fun changedArtifactIdentityIsNotCoalesced() {
        val context = QueueContext(folder.root)
        val file = RepoFile("weights.bin", 100, sha256 = "a".repeat(64))
        val repo = ResolvedRepoInfo("owner/identity", "main", "abc", listOf(file))
        RepositoryFileDownloads.enqueue(context, repo, listOf(file))
        RepositoryFileDownloads.enqueue(context, repo, listOf(file.copy(sha256 = "b".repeat(64))))
        RepositoryFileDownloads.enqueue(context, repo.copy(commitSha = "def"), listOf(file))
        assertEquals(3, RepositoryFileDownloads.queue(context).listFiles()!!.count { it.extension == "json" })
    }
}
