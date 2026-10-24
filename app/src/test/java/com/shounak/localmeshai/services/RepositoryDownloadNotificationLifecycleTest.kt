package com.shounak.localmeshai.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepositoryDownloadNotificationLifecycleTest {
    @Test fun completedSummarySurvivesServiceDestruction() {
        val lifecycle = RepositoryDownloadNotificationLifecycle()
        lifecycle.foregroundStarted()
        lifecycle.completionPosted()

        assertFalse(lifecycle.shouldRemoveOnDestroy())
    }

    @Test fun incompleteDownloadNotificationIsRemovedOnServiceDestruction() {
        val lifecycle = RepositoryDownloadNotificationLifecycle()
        lifecycle.foregroundStarted()

        assertTrue(lifecycle.shouldRemoveOnDestroy())
    }

    @Test fun aNewForegroundRunDoesNotPreserveAnOlderSummary() {
        val lifecycle = RepositoryDownloadNotificationLifecycle()
        lifecycle.completionPosted()
        lifecycle.foregroundStarted()

        assertTrue(lifecycle.shouldRemoveOnDestroy())
    }
}
