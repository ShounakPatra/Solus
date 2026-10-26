package com.shounak.localmeshai.utils

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AbandonedModelFilesTest {
    @get:Rule val temp = TemporaryFolder()
    private val now = 2_000_000_000_000L
    private val old = now - 30L * 24 * 60 * 60 * 1000

    private fun ageTree(root: File) {
        root.walkBottomUp().forEach { assertTrue(it.setLastModified(old)) }
    }

    @Test fun onlyAbandonedUnpublishedDraftsFromPriorProcessesAreRemoved() {
        val models = temp.newFolder("models")
        val repository = temp.newFolder("repository")
        fun draft(id: String) = File(models, id).apply { File(this, ".staging/weights.bin").apply { requireNotNull(parentFile).mkdirs(); writeText("weights") } }
        val abandoned = draft("draft_1234abcd").also(::ageTree)
        val published = draft("draft_abcdef12").apply { File(this, "manifest.json").writeText("published") }.also(::ageTree)
        val active = draft("draft_01234567").also(::ageTree)
        val current = draft("draft_76543210").also(::ageTree).apply { setLastModified(now) }
        ModelArtifactStorage.leaseArtifact(File(active, ".staging/weights.bin")).use {
            assertEquals(1, AbandonedModelFiles.prune(models, repository, now, now))
            assertFalse(abandoned.exists())
            assertTrue(published.exists())
            assertTrue(active.exists())
            assertTrue(current.exists())
        }
        // A draft opened in this process stays protected even after weeks in the UI.
        assertTrue(current.setLastModified(now))
        AbandonedModelFiles.prune(models, repository, now + 30L * 24 * 60 * 60 * 1000, now)
        assertTrue(current.exists())
    }

    @Test fun metadataAndTransferLeasesProtectPausedAndActiveParts() {
        val models = temp.newFolder("models")
        val repository = temp.newFolder("repository")
        val staging = File(repository, "staging").apply { mkdirs() }
        fun part(key: Char) = File(staging, key.toString().repeat(64) + ".part").apply { writeText("partial"); setLastModified(old) }
        val orphan = part('a')
        val paused = part('b').also { File(it.path + ".json").writeText("resume receipt") }
        val active = part('c')
        val other = File(staging, "unrelated.part").apply { writeText("keep"); setLastModified(old) }
        ModelArtifactStorage.leaseArtifact(active).use {
            assertEquals(1, AbandonedModelFiles.prune(models, repository, now, now))
            assertFalse(orphan.exists())
            assertTrue(paused.exists())
            assertTrue(active.exists())
            assertTrue(other.exists())
        }
    }
}
