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
    fun partialFailureRemainsResumable() {
        assertEquals(ModelStatus.Paused, DownloadRestorationPolicy.afterFailure(4_096L))
        assertEquals(
            ModelStatus.Paused,
            DownloadRestorationPolicy.afterProcessRestart(ModelStatus.Failed, 4_096L)
        )
    }

    @Test
    fun failureWithoutPartialDataStillUsesRetry() {
        assertEquals(ModelStatus.Failed, DownloadRestorationPolicy.afterFailure(0L))
    }
}
