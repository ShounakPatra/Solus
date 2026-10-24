package com.shounak.localmeshai.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeExecutionPolicyTest {

    @Test
    fun testResolvesThreadCountForVariousCpuConfigurations() {
        // High core count, plenty of RAM, normal power
        val highCore = RuntimeExecutionPolicy.resolve(
            availableProcessors = 8,
            totalRamGb = 12.0,
            isPowerSaveMode = false
        )
        assertEquals(4, highCore.cpuThreads)

        // 6 core count: (6 - 2) = 4
        val sixCore = RuntimeExecutionPolicy.resolve(
            availableProcessors = 6,
            totalRamGb = 8.0,
            isPowerSaveMode = false
        )
        assertEquals(4, sixCore.cpuThreads)

        // Missing frequency information falls back to the available cores, up to four.
        val quadCore = RuntimeExecutionPolicy.resolve(
            availableProcessors = 4,
            totalRamGb = 8.0,
            isPowerSaveMode = false
        )
        assertEquals(4, quadCore.cpuThreads)

        // Do not subtract two cores on smaller CPUs.
        val triCore = RuntimeExecutionPolicy.resolve(
            availableProcessors = 3,
            totalRamGb = 8.0,
            isPowerSaveMode = false
        )
        assertEquals(3, triCore.cpuThreads)

        // Single and dual core devices stay within the available core count.
        val dualCore = RuntimeExecutionPolicy.resolve(
            availableProcessors = 2,
            totalRamGb = 8.0,
            isPowerSaveMode = false
        )
        assertEquals(2, dualCore.cpuThreads)

        val singleCore = RuntimeExecutionPolicy.resolve(
            availableProcessors = 1,
            totalRamGb = 8.0,
            isPowerSaveMode = false
        )
        assertEquals(1, singleCore.cpuThreads)
    }

    @Test
    fun testCapsThreadsWhenRamIsLow() {
        // Devices below 4 GiB retain a two-thread memory budget.
        val lowRamPolicy = RuntimeExecutionPolicy.resolve(
            availableProcessors = 8,
            totalRamGb = 3.0,
            isPowerSaveMode = false
        )
        assertEquals(2, lowRamPolicy.cpuThreads)

        // Just below the low-RAM boundary.
        val edgeRamPolicy = RuntimeExecutionPolicy.resolve(
            availableProcessors = 8,
            totalRamGb = 3.9,
            isPowerSaveMode = false
        )
        assertEquals(2, edgeRamPolicy.cpuThreads)

        // At the low-RAM boundary the regular budget applies.
        val sufficientRamPolicy = RuntimeExecutionPolicy.resolve(
            availableProcessors = 8,
            totalRamGb = 4.0,
            isPowerSaveMode = false
        )
        assertEquals(4, sufficientRamPolicy.cpuThreads)
    }

    @Test
    fun testCapsThreadsInPowerSaveMode() {
        // 8 cores with 16GB RAM in power save mode -> capped to 2
        val powerSavePolicy = RuntimeExecutionPolicy.resolve(
            availableProcessors = 8,
            totalRamGb = 16.0,
            isPowerSaveMode = true
        )
        assertEquals(2, powerSavePolicy.cpuThreads)
    }

    @Test
    fun testFlagsPopulatedCorrectly() {
        val lowRamPolicy = RuntimeExecutionPolicy.resolve(
            availableProcessors = 8,
            totalRamGb = 3.0,
            isPowerSaveMode = true
        )
        assertEquals(2, lowRamPolicy.cpuThreads)
        org.junit.Assert.assertTrue(lowRamPolicy.isLowRam)
        org.junit.Assert.assertTrue(lowRamPolicy.isPowerSaveMode)
    }

    @Test fun frequencyClustersAndMeasuredOverridesDriveThreadChoice() {
        val frequencies = listOf(1800000L, 1800000L, 1800000L, 1800000L, 2400000L, 2400000L, 2400000L, 3000000L)
        assertEquals(4, RuntimeExecutionPolicy.resolve(8, 8.0, false, frequencies).cpuThreads)
        assertEquals(6, RuntimeExecutionPolicy.resolve(8, 8.0, false, frequencies, 6).cpuThreads)
        assertEquals(2, RuntimeExecutionPolicy.resolve(8, 8.0, true, frequencies, 6).cpuThreads)
        assertEquals(2, RuntimeExecutionPolicy.resolve(8, 3.0, false, frequencies, 6).cpuThreads)
    }
}
