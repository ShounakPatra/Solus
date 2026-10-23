package com.shounak.localmeshai.ai

import org.junit.Assert.*
import org.junit.Test

class PortableBenchmarkMetricsTest {
    @Test fun prefillIsExcludedFromDecodeRate() {
        val sample = PortableBenchmarkMetrics.sample(0, 500_000_000, 1_500_000_000, 11)
        assertEquals(500.0, sample.first, 0.001)
        assertEquals(10.0, sample.second, 0.001)
    }
    @Test fun singleTokenDoesNotInventDecodeThroughput() {
        assertEquals(0.0, PortableBenchmarkMetrics.sample(0, 10, 10, 1).second, 0.0)
    }
    @Test(expected = IllegalArgumentException::class) fun noTokensIsNotSuccessfulBenchmark() {
        PortableBenchmarkMetrics.sample(0, 0, 0, 0)
    }
}
