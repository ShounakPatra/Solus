package com.shounak.localmeshai.utils

import com.shounak.localmeshai.models.ModelStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadRestorationPolicyTest {
    @Test
    fun activeDownloadRestoresAsPausedWithResume() {
        assertEquals(
            ModelStatus.Paused,
            DownloadRestorationPolicy.afterProcessRestart(ModelStatus.Downloading, 0L)
        )
    }

    @Test
    fun failedDownloadPreservesFailedStatus() {
        assertEquals(ModelStatus.Failed, DownloadRestorationPolicy.afterFailure(4_096L))
        assertEquals(
            ModelStatus.Failed,
            DownloadRestorationPolicy.afterProcessRestart(ModelStatus.Failed, 4_096L)
        )
    }

    @Test
    fun failureWithoutPartialDataStillUsesFailed() {
        assertEquals(ModelStatus.Failed, DownloadRestorationPolicy.afterFailure(0L))
    }
}
