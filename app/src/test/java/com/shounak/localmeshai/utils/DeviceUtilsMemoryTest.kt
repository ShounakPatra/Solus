package com.shounak.localmeshai.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceUtilsMemoryTest {
    @Test
    fun recommendedContextWindowAdaptsToDeviceRam() {
        // Low RAM tier (<4.5GB)
        assertEquals(1024, DeviceUtils.recommendedContextWindowForRam(3.5))
        assertEquals(1536, DeviceUtils.recommendedContextWindowForRam(3.5, modelParamsB = 1.5f))

        // Mid RAM tier (4.5GB - 7.0GB)
        assertEquals(2048, DeviceUtils.recommendedContextWindowForRam(6.0))

        // High RAM tier (7.0GB - 11.0GB)
        assertEquals(4096, DeviceUtils.recommendedContextWindowForRam(8.0))

        // Ultra RAM tier (>=11.0GB)
        assertEquals(8192, DeviceUtils.recommendedContextWindowForRam(12.0))
    }

    @Test
    fun recommendedBatchSizeAdaptsToDeviceRam() {
        // Low RAM tier
        val (lowBatch, lowUbatch) = DeviceUtils.recommendedBatchSizeForRam(3.5)
        assertEquals(128, lowBatch)
        assertEquals(64, lowUbatch)

        // Mid RAM tier
        val (midBatch, midUbatch) = DeviceUtils.recommendedBatchSizeForRam(6.0)
        assertEquals(256, midBatch)
        assertEquals(128, midUbatch)

        // High RAM tier
        val (highBatch, highUbatch) = DeviceUtils.recommendedBatchSizeForRam(8.0)
        assertEquals(512, highBatch)
        assertEquals(256, highUbatch)
    }

    @Test
    fun recommendedThreadCountAdaptsToCoresAndRam() {
        // Low RAM device limits threads
        assertEquals(2, DeviceUtils.recommendedThreadCountForCoresAndRam(cores = 8, totalRamGB = 3.5))

        // Normal RAM device with 8 cores uses 4 threads
        assertEquals(4, DeviceUtils.recommendedThreadCountForCoresAndRam(cores = 8, totalRamGB = 8.0))

        // 4 core device uses 3 threads
        assertEquals(3, DeviceUtils.recommendedThreadCountForCoresAndRam(cores = 4, totalRamGB = 8.0))
    }
}
