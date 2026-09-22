package com.shounak.localmeshai.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AppUpdateManagerTest {

    @Test
    fun versionComparisonRecognizesNewerReleases() {
        assertTrue(AppUpdateManager.isVersionNewer("2.0.1", "2.0.0"))
        assertTrue(AppUpdateManager.isVersionNewer("v2.1.0", "2.0.0"))
        assertTrue(AppUpdateManager.isVersionNewer("3.0.0", "2.9.9"))
        assertTrue(AppUpdateManager.isVersionNewer("v2.0.0.1", "v2.0.0"))
        assertTrue(AppUpdateManager.isVersionNewer("2.1", "2.0.0"))
    }

    @Test
    fun versionComparisonRecognizesOlderOrEqualReleases() {
        assertFalse(AppUpdateManager.isVersionNewer("2.0.0", "2.0.0"))
        assertFalse(AppUpdateManager.isVersionNewer("v2.0.0", "2.0.0"))
        assertFalse(AppUpdateManager.isVersionNewer("1.9.9", "2.0.0"))
        assertFalse(AppUpdateManager.isVersionNewer("v1.5.0", "2.0.0"))
        assertFalse(AppUpdateManager.isVersionNewer("", "2.0.0"))
    }

    @Test
    fun deleteApksInDirectoryRemovesOnlyApkAndTmpFiles() {
        val tempDir = Files.createTempDirectory("solus_test_updates").toFile()
        try {
            val apkFile = File(tempDir, "Solus-v2.0.1.apk").apply { writeText("dummy apk content") }
            val tmpFile = File(tempDir, "Solus-v2.0.1.apk.tmp").apply { writeText("dummy tmp content") }
            val otherFile = File(tempDir, "release_notes.txt").apply { writeText("keep me") }

            assertTrue(apkFile.exists())
            assertTrue(tmpFile.exists())
            assertTrue(otherFile.exists())

            AppUpdateManager.deleteApksInDirectory(tempDir)

            assertFalse("APK file should be deleted", apkFile.exists())
            assertFalse("TMP file should be deleted", tmpFile.exists())
            assertTrue("Non-APK file should be preserved", otherFile.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }
}

