package com.shounak.localmeshai.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppCacheCleanerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun clearDirectoryContentsDeletesNestedFilesAndFoldersPreservingRoot() {
        val cacheRoot = tempFolder.newFolder("test_cache")
        val file1 = File(cacheRoot, "temp1.txt").apply { writeText("data1") }
        val subDir = File(cacheRoot, "subdir").apply { mkdir() }
        val file2 = File(subDir, "temp2.bin").apply { writeText("data2") }
        val subSubDir = File(subDir, "nested").apply { mkdir() }
        val file3 = File(subSubDir, "temp3.tmp").apply { writeText("data3") }

        assertTrue(file1.exists())
        assertTrue(file2.exists())
        assertTrue(file3.exists())

        val deletedCount = AppCacheCleaner.clearDirectoryContents(cacheRoot)

        assertTrue("Root directory should still exist", cacheRoot.exists())
        assertTrue("Root directory should still be a directory", cacheRoot.isDirectory)
        assertEquals("Root directory should be empty", 0, cacheRoot.listFiles()?.size ?: 0)
        assertFalse("Subdirectory should be removed", subDir.exists())
        assertFalse("Nested files should be removed", file1.exists())
        assertFalse("Nested files should be removed", file2.exists())
        assertFalse("Nested files should be removed", file3.exists())
        assertTrue("Should have reported deleted items", deletedCount >= 2)
    }

    @Test
    fun clearDirectoryContentsHandlesNonExistentOrEmptyDirectoryGracefully() {
        val emptyDir = tempFolder.newFolder("empty_dir")
        val nonExistentDir = File(tempFolder.root, "does_not_exist")

        assertEquals(0, AppCacheCleaner.clearDirectoryContents(emptyDir))
        assertEquals(0, AppCacheCleaner.clearDirectoryContents(nonExistentDir))
        assertEquals(0, AppCacheCleaner.clearDirectoryContents(null))
    }

    @Test
    fun clearStorageCacheDirDeletesUnprotectedFilesAndEmptySubdirectories() {
        val cacheRoot = tempFolder.newFolder("test_storage_cache")
        val cameraDir = File(cacheRoot, "camera_inputs").apply { mkdir() }
        val pic1 = File(cameraDir, "pic1.jpg").apply { writeText("pic1") }
        val multimodalDir = File(cacheRoot, "multimodal_inputs").apply { mkdir() }
        val audio1 = File(multimodalDir, "audio1.wav").apply { writeText("audio1") }
        val looseFile = File(cacheRoot, "temp_data.bin").apply { writeText("loose") }

        assertTrue(pic1.exists())
        assertTrue(audio1.exists())
        assertTrue(looseFile.exists())

        val deletedCount = AppCacheCleaner.clearStorageCacheDir(cacheRoot)

        assertTrue(cacheRoot.exists())
        assertFalse("Camera temp file should be deleted", pic1.exists())
        assertFalse("Multimodal temp file should be deleted", audio1.exists())
        assertFalse("Loose temp file should be deleted", looseFile.exists())
        assertFalse("Empty camera directory should be removed", cameraDir.exists())
        assertFalse("Empty multimodal directory should be removed", multimodalDir.exists())
        assertTrue("Deleted count should reflect deleted items", deletedCount >= 3)
    }

    @Test
    fun clearStorageCacheDirPreservesProtectedPathsAndTheirParentDirectories() {
        val cacheRoot = tempFolder.newFolder("test_protected_cache")
        val multimodalDir = File(cacheRoot, "multimodal_inputs").apply { mkdir() }
        val protectedFile = File(multimodalDir, "saved_image.jpg").apply { writeText("protected_content") }
        val unreferencedFile = File(multimodalDir, "stale_temp.jpg").apply { writeText("stale_content") }

        val protectedPaths = setOf(protectedFile.canonicalPath)

        val deletedCount = AppCacheCleaner.clearStorageCacheDir(cacheRoot, protectedPaths)

        assertTrue("Protected file must remain intact", protectedFile.exists())
        assertTrue("Parent directory containing protected file must remain", multimodalDir.exists())
        assertFalse("Unreferenced file must be deleted", unreferencedFile.exists())
        assertTrue("Deleted count should be at least 1", deletedCount >= 1)
    }

    @Test
    fun clearStorageCacheDirPreservesActiveInputFilesLeases() {
        val cacheRoot = tempFolder.newFolder("test_lease_cache")
        val cameraDir = File(cacheRoot, "camera_inputs").apply { mkdir() }
        val leasedFile = File(cameraDir, "active_stream_input.bin").apply { writeText("leased_content") }
        val staleFile = File(cameraDir, "stale.bin").apply { writeText("stale_content") }

        assertTrue(ActiveInputFiles.isProtected(leasedFile) == false)
        val lease = ActiveInputFiles.protect(leasedFile)
        assertTrue(ActiveInputFiles.isProtected(leasedFile))

        try {
            AppCacheCleaner.clearStorageCacheDir(cacheRoot)

            assertTrue("Leased active file must NOT be deleted", leasedFile.exists())
            assertFalse("Stale unreferenced file must be deleted", staleFile.exists())
        } finally {
            lease.close()
        }

        assertFalse("File is no longer protected after lease close", ActiveInputFiles.isProtected(leasedFile))

        // Now clearing should delete the previously leased file as well
        AppCacheCleaner.clearStorageCacheDir(cacheRoot)
        assertFalse("Previously leased file should now be deleted", leasedFile.exists())
    }

    @Test
    fun clearStorageCacheDirHandlesNullOrMissingDirectory() {
        assertEquals(0, AppCacheCleaner.clearStorageCacheDir(null))
        val missing = File(tempFolder.root, "does_not_exist_dir")
        assertEquals(0, AppCacheCleaner.clearStorageCacheDir(missing))
    }

    @Test
    fun clearStorageCacheDirExcludesLiteRtRuntimeDirectory() {
        val cacheRoot = tempFolder.newFolder("test_litert_cache")
        val liteRtDir = File(cacheRoot, LiteRtRuntimeCache.ROOT_DIR_NAME).apply { mkdir() }
        val kernelFile = File(liteRtDir, "kernel.bin").apply { writeText("compiled kernel") }
        val normalDir = File(cacheRoot, "other_cache").apply { mkdir() }
        val normalFile = File(normalDir, "other.tmp").apply { writeText("temp data") }

        assertTrue(kernelFile.exists())
        assertTrue(normalFile.exists())

        val deletedCount = AppCacheCleaner.clearStorageCacheDir(cacheRoot)

        assertTrue("LiteRT directory must be completely excluded from generic cache cleanup", liteRtDir.exists())
        assertTrue("LiteRT kernel must be preserved", kernelFile.exists())
        assertFalse("Normal cache file should be deleted", normalFile.exists())
        assertFalse("Empty normal cache directory should be deleted", normalDir.exists())
        assertTrue(deletedCount >= 1)
    }

    @Test
    fun pruneExpiredInputsPreservesProtectedAndFreshUnreferencedInputs() {
        val cacheRoot = tempFolder.newFolder("test_fresh_inputs")
        val cameraDir = File(cacheRoot, "camera_inputs").apply { mkdir() }
        val protectedPic = File(cameraDir, "pic_fresh.jpg").apply {
            writeText("camera data")
        }
        val multimodalDir = File(cacheRoot, "multimodal_inputs").apply { mkdir() }
        val unrefAudio = File(multimodalDir, "audio_fresh.wav").apply {
            writeText("audio data")
        }
        val previewDir = File(cacheRoot, "image_previews").apply { mkdir() }
        val unrefPreview = File(previewDir, "preview_fresh.png").apply {
            writeText("preview data")
        }

        val lease = ActiveInputFiles.protect(protectedPic)
        try {
            val deleted = AppCacheCleaner.pruneExpiredInputs(cacheRoot, System.currentTimeMillis())
            assertEquals("Fresh inputs must survive the retention window", 0, deleted)
            assertTrue("Protected input must be preserved", protectedPic.exists())
            assertTrue("Fresh audio must be preserved", unrefAudio.exists())
            assertTrue("Fresh preview must be preserved", unrefPreview.exists())
        } finally {
            lease.close()
        }
    }

    @Test
    fun pruneExpiredInputsDeletesExpiredUnreferencedInputs() {
        val cacheRoot = tempFolder.newFolder("test_expired_inputs")
        val cameraDir = File(cacheRoot, "camera_inputs").apply { mkdir() }
        val expiredPic = File(cameraDir, "pic_expired.jpg").apply {
            writeText("old camera data")
            setLastModified(1_000L)
        }
        val now = 1_000_000_000L

        val deleted = AppCacheCleaner.pruneExpiredInputs(cacheRoot, now)
        assertEquals(1, deleted)
        assertFalse("Expired unreferenced file must be deleted", expiredPic.exists())
    }
}
