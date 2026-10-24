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
        RepositoryFileDownloads.enqueue(context, repo, listOf(file))
        assertEquals(2, RepositoryFileDownloads.queue(context).listFiles()!!.count { it.extension == "json" })
    }
    @Test fun deniedStartKeepsManifestForNextForegroundOpen() {
        val context = QueueContext(folder.root, deny = true)
        val file = RepoFile("weights.bin", 100)
        val repo = ResolvedRepoInfo("owner/denied", "main", "abc", listOf(file))
        assertThrows(IllegalStateException::class.java) { RepositoryFileDownloads.enqueue(context, repo, listOf(file)) }
        assertEquals(1, RepositoryFileDownloads.queue(context).listFiles()!!.count { it.extension == "json" })
        assertEquals("Could not start download", RepositoryFileDownloads.states.value[RepositoryFileTransfer.key(repo.repoId, repo.commitSha, file.path)]!!.description)
    }
}
