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

        // 4 core count: (4 - 2) = 2
        val quadCore = RuntimeExecutionPolicy.resolve(
            availableProcessors = 4,
            totalRamGb = 8.0,
            isPowerSaveMode = false
        )
        assertEquals(2, quadCore.cpuThreads)

        // 3 core count: (3 - 2) = 1
        val triCore = RuntimeExecutionPolicy.resolve(
            availableProcessors = 3,
            totalRamGb = 8.0,
            isPowerSaveMode = false
        )
        assertEquals(1, triCore.cpuThreads)

        // Single or dual core fallback to minimum 1
        val dualCore = RuntimeExecutionPolicy.resolve(
            availableProcessors = 2,
            totalRamGb = 8.0,
            isPowerSaveMode = false
        )
        assertEquals(1, dualCore.cpuThreads)

        val singleCore = RuntimeExecutionPolicy.resolve(
            availableProcessors = 1,
            totalRamGb = 8.0,
            isPowerSaveMode = false
        )
        assertEquals(1, singleCore.cpuThreads)
    }

    @Test
    fun testCapsThreadsWhenRamIsLow() {
        // 8 cores but only 4GB RAM -> capped to 2
        val lowRamPolicy = RuntimeExecutionPolicy.resolve(
            availableProcessors = 8,
            totalRamGb = 4.0,
            isPowerSaveMode = false
        )
        assertEquals(2, lowRamPolicy.cpuThreads)

        // 8 cores with 5.9GB RAM -> capped to 2
        val edgeRamPolicy = RuntimeExecutionPolicy.resolve(
            availableProcessors = 8,
            totalRamGb = 5.9,
            isPowerSaveMode = false
        )
        assertEquals(2, edgeRamPolicy.cpuThreads)

        // 8 cores with 6.0GB RAM -> allows up to 4
        val sufficientRamPolicy = RuntimeExecutionPolicy.resolve(
            availableProcessors = 8,
            totalRamGb = 6.0,
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
            totalRamGb = 4.0,
            isPowerSaveMode = true
        )
        assertEquals(2, lowRamPolicy.cpuThreads)
        org.junit.Assert.assertTrue(lowRamPolicy.isLowRam)
        org.junit.Assert.assertTrue(lowRamPolicy.isPowerSaveMode)
    }
}
