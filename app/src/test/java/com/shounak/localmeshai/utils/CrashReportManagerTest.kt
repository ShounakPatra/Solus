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

    @Test
    fun logSanitizationRedactsSensitiveTokens() {
        val rawLog = "Loaded model with token: hf_1234567890abcdefghijklmnopqrstuvwxyz and api_key=AIzaSyA1234567890abcdefghijklmnopqrstuv and Authorization: Bearer secret_bearer_token_1234567890"
        val sanitized = CrashReportManager.sanitizeLog(rawLog)

        assertFalse(sanitized.contains("hf_1234567890abcdefghijklmnopqrstuvwxyz"))
        assertTrue(sanitized.contains("hf_***REDACTED***"))

        assertFalse(sanitized.contains("AIzaSyA1234567890abcdefghijklmnopqrstuv"))
        assertTrue(sanitized.contains("AIza***REDACTED***"))

        assertFalse(sanitized.contains("secret_bearer_token_1234567890"))
        assertTrue(sanitized.contains("Bearer ***REDACTED***"))
    }
}
