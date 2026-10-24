package com.shounak.localmeshai.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AppUpdateManagerTest {
    @Test fun updateAssetSelectionPrefersSignedReleaseRegardlessOfAssetOrder() {
        val assets = org.json.JSONArray()
        for (name in listOf("Solus-v6-debug-123.apk", "app-release-unsigned.apk", "Solus.apk", "Solus-v6-signed-release-123.apk")) {
            assets.put(org.json.JSONObject().put("name", name).put("browser_download_url", "https://example.com/$name"))
        }
        assertEquals("https://example.com/Solus-v6-signed-release-123.apk", AppUpdateManager.selectUpdateApk(assets))
        val bad = org.json.JSONArray()
            .put(org.json.JSONObject().put("name", "app-debug.apk").put("browser_download_url", "https://example.com/debug.apk"))
            .put(org.json.JSONObject().put("name", "app-release-unsigned.apk").put("browser_download_url", "https://example.com/unsigned.apk"))
        org.junit.Assert.assertNull(AppUpdateManager.selectUpdateApk(bad))
        assertEquals("https://example.com/Solus.apk", AppUpdateManager.selectUpdateApk(org.json.JSONArray()
            .put(org.json.JSONObject().put("name", "Solus.apk").put("browser_download_url", "https://example.com/Solus.apk"))))
    }

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

    @Test
    fun repositoryConstantsPointToSolus() {
        assertEquals("https://github.com/ShounakPatra/Solus", AppUpdateManager.GITHUB_REPO_URL)
        assertEquals("app_updates_high", AppUpdateManager.UPDATE_CHANNEL_ID)
        assertEquals(9001, AppUpdateManager.UPDATE_NOTIFICATION_ID)
        assertEquals("extra_open_settings_about", AppUpdateManager.EXTRA_OPEN_SETTINGS_ABOUT)
        assertEquals("extra_update_version", AppUpdateManager.EXTRA_UPDATE_VERSION)
        assertEquals("extra_update_notes", AppUpdateManager.EXTRA_UPDATE_NOTES)
        assertEquals("extra_update_html_url", AppUpdateManager.EXTRA_UPDATE_HTML_URL)
        assertEquals("extra_update_download_url", AppUpdateManager.EXTRA_UPDATE_DOWNLOAD_URL)
    }

    @Test
    fun backgroundUpdateJobConstantsAreValid() {
        assertEquals(9002, AppUpdateManager.BACKGROUND_UPDATE_JOB_ID)
        assertEquals(6 * 60 * 60 * 1000L, AppUpdateManager.UPDATE_CHECK_INTERVAL_MS)
        assertEquals(30 * 60 * 1000L, AppUpdateManager.UPDATE_CHECK_FLEX_MS)
    }
}

