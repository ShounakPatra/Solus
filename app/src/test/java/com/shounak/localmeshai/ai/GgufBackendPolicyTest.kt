package com.shounak.localmeshai.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GgufBackendPolicyTest {

    private val testModelSha = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890"

    @Before
    fun setup() {
        GgufGpuValidator.testReceiptCache.clear()
        GgufGpuValidator.testMemoryCache.clear()
        GgufPerformanceStore.clear(null)
    }

    private fun createValidationReceipt(
        status: String,
        permanent: Boolean = true,
        failReason: String? = null,
        isCpuIsolated: Boolean = true
    ): GgufGpuValidator.ValidationReceipt {
        return GgufGpuValidator.ValidationReceipt(
            protocolVersion = GgufGpuValidator.PROTOCOL_VERSION,
            backendPolicyVersion = GgufBackendPolicy.POLICY_VERSION,
            isCpuIsolated = isCpuIsolated,
            modelSha256 = testModelSha,
            deviceIdentity = GgufGpuValidator.getDeviceIdentity(),
            requestedThreads = 4,
            requestedPrefillThreads = 4,
            requestedContextWindow = 2048,
            requestedBatch = 256,
            requestedUbatch = 128,
            actualThreads = 4,
            actualPrefillThreads = 4,
            actualContextWindow = 2048,
            actualBatch = 256,
            actualUbatch = 128,
            status = status,
            failReason = failReason,
            permanent = permanent
        )
    }

    @Test
    fun testExplicitCpuAlwaysSelectsCpu() {
        val decision = GgufBackendPolicy.selectBackend(
            context = null,
            modelSha256 = testModelSha,
            requestedBackend = LlamaBackend.CPU
        )
        assertEquals(LlamaBackend.CPU, decision.selectedBackend)
        assertFalse(decision.isAuto)
        assertTrue(decision.reason.contains("explicitly selected CPU"))
    }

    @Test
    fun testExplicitVulkanSelectsVulkanWhenNoFailureReceipt() {
        val decision = GgufBackendPolicy.selectBackend(
            context = null,
            modelSha256 = testModelSha,
            requestedBackend = LlamaBackend.VULKAN
        )
        assertEquals(LlamaBackend.VULKAN, decision.selectedBackend)
        assertFalse(decision.isAuto)
        assertTrue(decision.reason.contains("explicitly selected Vulkan"))
    }

    @Test
    fun testExplicitVulkanFallsBackToCpuWhenPermanentNumericalFailureExists() {
        val valKey = GgufGpuValidator.buildCacheKey(testModelSha, 4, 2048, 256, 128)
        GgufGpuValidator.saveReceipt(
            null,
            valKey,
            createValidationReceipt(
                status = "FAIL",
                permanent = true,
                failReason = "TV distance exceeded threshold: 0.12 > 0.05",
                isCpuIsolated = true
            )
        )

        val decision = GgufBackendPolicy.selectBackend(
            context = null,
            modelSha256 = testModelSha,
            requestedBackend = LlamaBackend.VULKAN
        )
        assertEquals(LlamaBackend.CPU, decision.selectedBackend)
        assertFalse(decision.isAuto)
        assertTrue(decision.reason.contains("known permanent numerical failure"))
    }

    @Test
    fun testAutoDefaultsToTrueCpuWhenNoNumericalReceipt() {
        val decision = GgufBackendPolicy.selectBackend(
            context = null,
            modelSha256 = testModelSha,
            requestedBackend = null
        )
        assertEquals(LlamaBackend.CPU, decision.selectedBackend)
        assertTrue(decision.isAuto)
        assertTrue(decision.reason.contains("No valid numerical receipt"))
    }

    @Test
    fun testAutoDefaultsToTrueCpuWhenNumericalValidationFailed() {
        val valKey = GgufGpuValidator.buildCacheKey(testModelSha, 4, 2048, 256, 128)
        GgufGpuValidator.saveReceipt(
            null,
            valKey,
            createValidationReceipt(
                status = "FAIL",
                permanent = true,
                failReason = "TV distance 0.08 exceeds 0.05",
                isCpuIsolated = true
            )
        )

        val decision = GgufBackendPolicy.selectBackend(
            context = null,
            modelSha256 = testModelSha,
            requestedBackend = null
        )
        assertEquals(LlamaBackend.CPU, decision.selectedBackend)
        assertTrue(decision.isAuto)
        assertTrue(decision.reason.contains("Numerical validation failed"))
    }

    @Test
    fun testAutoDefaultsToTrueCpuWhenNumericalPassedButNoPerformanceReceipt() {
        val valKey = GgufGpuValidator.buildCacheKey(testModelSha, 4, 2048, 256, 128)
        GgufGpuValidator.saveReceipt(
            null,
            valKey,
            createValidationReceipt(
                status = "PASS",
                permanent = true,
                isCpuIsolated = true
            )
        )

        val decision = GgufBackendPolicy.selectBackend(
            context = null,
            modelSha256 = testModelSha,
            requestedBackend = null
        )
        assertEquals(LlamaBackend.CPU, decision.selectedBackend)
        assertTrue(decision.isAuto)
        assertTrue(decision.reason.contains("no qualifying performance receipt exists"))
    }

    @Test
    fun testAutoDefaultsToTrueCpuWhenPerformanceComparisonDidNotQualify() {
        val valKey = GgufGpuValidator.buildCacheKey(testModelSha, 4, 2048, 256, 128)
        GgufGpuValidator.saveReceipt(
            null,
            valKey,
            createValidationReceipt(
                status = "PASS",
                permanent = true,
                isCpuIsolated = true
            )
        )

        val perfKey = GgufPerformanceStore.buildCacheKey(testModelSha, 4, 2048, 256, 128)
        GgufPerformanceStore.saveReceipt(
            null,
            perfKey,
            GgufPerformanceReceipt(
                modelSha256 = testModelSha,
                isComplete = true,
                qualifiesVulkan = false,
                warmTtftImprovementFraction = 0.05, // Only 5% improvement (< 10% threshold)
                coldRegressionFraction = 0.02,
                decodeRegressionFraction = 0.01,
                frameP95RegressionFraction = 0.01,
                maxInferenceRenderStallMs = 20L,
                hasNumericalAnomalyOrFailure = false
            )
        )

        val decision = GgufBackendPolicy.selectBackend(
            context = null,
            modelSha256 = testModelSha,
            requestedBackend = null
        )
        assertEquals(LlamaBackend.CPU, decision.selectedBackend)
        assertTrue(decision.isAuto)
        assertTrue(decision.reason.contains("Complete performance comparison favors CPU"))
    }

    @Test
    fun testAutoSelectsVulkanWhenNumericalPassedAndPerformanceQualifies() {
        val valKey = GgufGpuValidator.buildCacheKey(testModelSha, 4, 2048, 256, 128)
        GgufGpuValidator.saveReceipt(
            null,
            valKey,
            createValidationReceipt(
                status = "PASS",
                permanent = true,
                isCpuIsolated = true
            )
        )

        val perfKey = GgufPerformanceStore.buildCacheKey(testModelSha, 4, 2048, 256, 128)
        GgufPerformanceStore.saveReceipt(
            null,
            perfKey,
            GgufPerformanceReceipt(
                modelSha256 = testModelSha,
                isComplete = true,
                qualifiesVulkan = true,
                warmTtftImprovementFraction = 0.25, // 25% improvement
                coldRegressionFraction = 0.01,
                decodeRegressionFraction = 0.00,
                frameP95RegressionFraction = 0.02,
                maxInferenceRenderStallMs = 45L,
                hasNumericalAnomalyOrFailure = false
            )
        )

        val decision = GgufBackendPolicy.selectBackend(
            context = null,
            modelSha256 = testModelSha,
            requestedBackend = null
        )
        assertEquals(LlamaBackend.VULKAN, decision.selectedBackend)
        assertTrue(decision.isAuto)
        assertTrue(decision.reason.contains("Selecting Vulkan GPU"))
    }
}
