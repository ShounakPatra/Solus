package com.shounak.localmeshai.utils

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InputOwnershipTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun freshAndFutureDatedInputsSurviveAndExpireAtTheRetentionBoundary() {
        val root = directory.newFolder("fresh_cache")
        val staging = root.resolve("multimodal_inputs").apply { mkdirs() }
        val now = 200_000_000L
        val fresh = staging.resolve("fresh.wav").apply { writeText("draft"); setLastModified(now - 1) }
        val future = staging.resolve("future.wav").apply { writeText("draft"); setLastModified(now + 1) }
        val expired = staging.resolve("expired.wav").apply {
            writeText("expired"); setLastModified(now - AppCacheCleaner.INPUT_RETENTION_MS)
        }
        assertEquals(1, AppCacheCleaner.pruneExpiredInputs(root, now))
        assertTrue(fresh.exists())
        assertTrue(future.exists())
        assertFalse(expired.exists())
    }

    @Test fun activeExpiredInputsSurviveUntilLastLeaseCloses() {
        val root = directory.newFolder("cache")
        val staging = root.resolve("multimodal_inputs").apply { mkdirs() }
        val input = staging.resolve("active.pdf").apply { writeText("test"); setLastModified(1) }
        val a = ActiveInputFiles.protect(input)
        val b = ActiveInputFiles.protect(input)
        try {
            assertEquals(0, AppCacheCleaner.pruneExpiredInputs(root, 100_000_000))
            a.close(); a.close()
            assertFalse(ActiveInputFiles.deleteIfUnreferenced(input))
            b.close()
            assertEquals(1, AppCacheCleaner.pruneExpiredInputs(root, 100_000_000))
            assertFalse(input.exists())
        } finally { a.close(); b.close() }
    }

    @Test fun automaticPruningPreservesCompilationLegacyAndUnrelatedFiles() {
        val root = directory.newFolder("cache")
        val files = listOf("litert_runtime/kernel.bin", "vision_sessions/session_old.png", "other/data.bin").map {
            root.resolve(it).apply { parentFile!!.mkdirs(); writeText("retain"); setLastModified(1) }
        }
        assertEquals(0, AppCacheCleaner.pruneExpiredInputs(root, 100_000_000))
        assertTrue(files.all { it.exists() })
    }

    @Test fun legacyMigrationIsRepeatableAndKeepsSourceUntilReferencesCommit() {
        val root = directory.newFolder("cache")
        val legacy = root.resolve("vision_sessions/image.png").apply { parentFile!!.mkdirs(); writeText("image bytes") }
        val durable = directory.newFolder("durable")
        val copied = LegacyAttachmentMigration.copyReferenced(legacy, root, durable)
        assertNotNull(copied)
        assertEquals("image bytes", copied!!.readText())
        assertTrue(legacy.exists())
        assertEquals(copied, LegacyAttachmentMigration.copyReferenced(legacy, root, durable))
        assertEquals(1, durable.listFiles()!!.size)
        assertNull(LegacyAttachmentMigration.copyReferenced(copied, root, durable))
    }

    @Test fun failedMigrationDoesNotDeleteSource() {
        val root = directory.newFolder("cache")
        val legacy = root.resolve("image.png").apply { writeText("saved data") }
        val blocked = directory.newFile("blocked")
        assertNull(LegacyAttachmentMigration.copyReferenced(legacy, root, blocked))
        assertEquals("saved data", legacy.readText())
    }
    @Test fun savedLegacyReferenceProtectsExpiredStagingAttachment() {
        val root = directory.newFolder("cache")
        val input = root.resolve("camera_inputs/saved.png").apply {
            parentFile!!.mkdirs(); writeText("saved attachment"); setLastModified(1)
        }
        assertEquals(0, AppCacheCleaner.pruneExpiredInputs(root, 100_000_000,
            protectedPaths = setOf(input.canonicalPath)))
        assertTrue(input.exists())
    }

    @Test fun releaseMethodDecrementsReferenceAndUnprotects() {
        val file = directory.newFile("test_release.bin")
        assertFalse(ActiveInputFiles.isProtected(file))

        ActiveInputFiles.protect(file)
        ActiveInputFiles.protect(file)
        assertTrue(ActiveInputFiles.isProtected(file))

        ActiveInputFiles.release(file)
        assertTrue(ActiveInputFiles.isProtected(file))

        ActiveInputFiles.release(file)
        assertFalse(ActiveInputFiles.isProtected(file))
    }

    @Test fun concurrentPreparationAndCleanupPreservesActiveLeases() {
        val root = directory.newFolder("concurrent_cache")
        val cameraDir = root.resolve("camera_inputs").apply { mkdirs() }
        val files = (1..10).map { cameraDir.resolve("file_$it.jpg").apply { writeText("data $it"); setLastModified(1) } }

        // Protect even files
        val leases = files.filterIndexed { index, _ -> index % 2 == 0 }.map { ActiveInputFiles.protect(it) }

        try {
            val threads = (1..4).map {
                Thread {
                    AppCacheCleaner.pruneExpiredInputs(root, 100_000_000)
                }.apply { start() }
            }
            threads.forEach { it.join() }

            // Leased files must survive
            files.forEachIndexed { index, file ->
                if (index % 2 == 0) {
                    assertTrue("Leased file $file must survive concurrent cleanup", file.exists())
                } else {
                    assertFalse("Unleased expired file $file must be deleted", file.exists())
                }
            }
        } finally {
            leases.forEach { it.close() }
        }
    }
}
