package com.shounak.localmeshai.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CacheCleanupCoordinatorTest {

    @Test
    fun cleanupReasonValuesAreComplete() {
        val reasons = CleanupReason.values()
        assertEquals(7, reasons.size)
        assertNotNull(CleanupReason.valueOf("BackgroundClose"))
        assertNotNull(CleanupReason.valueOf("Reopen"))
        assertNotNull(CleanupReason.valueOf("ModelSwitch"))
        assertNotNull(CleanupReason.valueOf("ModelReload"))
        assertNotNull(CleanupReason.valueOf("ModelUnload"))
        assertNotNull(CleanupReason.valueOf("DeferredReaderCompletion"))
        assertNotNull(CleanupReason.valueOf("Explicit"))
    }

    @Test
    fun cleanupResultDataModelProperties() {
        val res = CleanupResult(
            reason = CleanupReason.BackgroundClose,
            deletedFilesCount = 5,
            deletedBytes = 1024L,
            deferredResourcesCount = 1,
            failuresCount = 0
        )
        assertEquals(CleanupReason.BackgroundClose, res.reason)
        assertEquals(5, res.deletedFilesCount)
        assertEquals(1024L, res.deletedBytes)
        assertEquals(1, res.deferredResourcesCount)
        assertEquals(0, res.failuresCount)
    }
}
