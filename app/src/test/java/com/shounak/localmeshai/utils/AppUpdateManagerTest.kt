package com.shounak.localmeshai.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTest {

    @Test
    fun versionComparisonRecognizesNewerReleases() {
        assertTrue(AppUpdateManager.isVersionNewer("2.0.1", "2.0.0"))
        assertTrue(AppUpdateManager.isVersionNewer("v2.1.0", "2.0.0"))
        assertTrue(AppUpdateManager.isVersionNewer("3.0.0", "2.9.9"))
        assertTrue(AppUpdateManager.isVersionNewer("v2.0.0.1", "v2.0.0"))
    }

    @Test
    fun versionComparisonRecognizesOlderOrEqualReleases() {
        assertFalse(AppUpdateManager.isVersionNewer("2.0.0", "2.0.0"))
        assertFalse(AppUpdateManager.isVersionNewer("v2.0.0", "2.0.0"))
        assertFalse(AppUpdateManager.isVersionNewer("1.9.9", "2.0.0"))
        assertFalse(AppUpdateManager.isVersionNewer("v1.5.0", "2.0.0"))
        assertFalse(AppUpdateManager.isVersionNewer("", "2.0.0"))
    }
}
