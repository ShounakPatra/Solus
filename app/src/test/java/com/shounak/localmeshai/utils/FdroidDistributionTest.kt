package com.shounak.localmeshai.utils

import com.shounak.localmeshai.BuildConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class FdroidDistributionTest {
    @Test fun fdroidBuildDoesNotOfferGithubUpdates() = runBlocking {
        assumeTrue(BuildConfig.FDROID_DISTRIBUTION)
        assertFalse(AppSettingsData().autoCheckUpdates)
        // A fabricated old version must still return locally, without querying GitHub.
        val result = AppUpdateManager.checkForUpdates("0.0.0")
        assertTrue(result is AppUpdateManager.UpdateCheckResult.Error)
        assertTrue((result as AppUpdateManager.UpdateCheckResult.Error).message.contains("F-Droid"))
    }
}
