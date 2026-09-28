package com.shounak.localmeshai.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportManagerTest {

    @Test
    fun crashReportDataClassFormatsCorrectly() {
        val now = 1727170000000L
        val report = CrashReport(
            timestamp = now,
            title = "NullPointerException: Attempt to invoke virtual method",
            details = "### Solus Crash Report\n- **Device:** Google Pixel 8\n```\njava.lang.NullPointerException\n```",
            isNativeCrash = false
        )

        assertEquals("NullPointerException: Attempt to invoke virtual method", report.title)
        assertFalse(report.isNativeCrash)
        assertNotNull(report.formattedDate)
        assertTrue(report.formattedDate.isNotBlank())
    }

    @Test
    fun nativeCrashReportFlagWorks() {
        val report = CrashReport(
            timestamp = System.currentTimeMillis(),
            title = "Native Engine Crash: qwen25",
            details = "Native initialization crash detected by InitCrashGuard.",
            isNativeCrash = true
        )

        assertTrue(report.isNativeCrash)
    }

    @Test
    fun gitHubIssuesUrlTargetOwner() {
        assertEquals("ShounakPatra", CrashReportManager.GITHUB_REPO_OWNER)
        assertEquals("Solus", CrashReportManager.GITHUB_REPO_NAME)
        assertEquals(
            "https://github.com/ShounakPatra/Solus/issues/new",
            CrashReportManager.GITHUB_ISSUES_URL
        )
    }
}
