package com.shounak.localmeshai.ai

/** Excludes prefill and the first generated token from sustained decode throughput. */
internal object PortableBenchmarkMetrics {
    fun sample(start: Long, first: Long, last: Long, tokens: Int): Pair<Double, Double> {
        require(tokens > 0 && first >= start && last >= first) { "No valid generated-token timing sample" }
        val rate = if (tokens > 1 && last > first) (tokens - 1) * 1e9 / (last - first) else 0.0
        return (first - start) / 1e6 to rate
    }
}
