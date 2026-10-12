package com.shounak.localmeshai.ai

import android.content.Context

data class BackendDecision(
    val selectedBackend: LlamaBackend,
    val reason: String,
    val performanceReceipt: GgufPerformanceReceipt? = null,
    val isAuto: Boolean = true
)

object GgufBackendPolicy {
    const val POLICY_VERSION = 1

    /**
     * Decides the backend to use for GGUF model execution based on:
     * 1. Explicit user preference (if specified).
     * 2. Verified numerical correctness receipt (must pass protocol 5 TV <= 0.05).
     * 3. Complete user-started performance check receipt meeting all qualification criteria.
     *
     * In Auto mode, ALWAYS defaults to true CPU immediately unless a verified numerical receipt
     * AND a qualifying performance receipt are both present.
     */
    fun selectBackend(
        context: Context?,
        modelSha256: String,
        threads: Int = 4,
        contextWindow: Int = 2048,
        nBatch: Int = 256,
        nUbatch: Int = 128,
        prefillThreads: Int = threads,
        requestedBackend: LlamaBackend? = null
    ): BackendDecision {
        val validationKey = GgufGpuValidator.buildCacheKey(
            modelSha256 = modelSha256,
            threads = threads,
            contextWindow = contextWindow,
            nBatch = nBatch,
            nUbatch = nUbatch,
            prefillThreads = prefillThreads
        )
        val validationReceipt = GgufGpuValidator.getReceipt(context, validationKey)

        // 1. Explicit user choice
        if (requestedBackend != null) {
            if (requestedBackend == LlamaBackend.CPU) {
                return BackendDecision(
                    selectedBackend = LlamaBackend.CPU,
                    reason = "User explicitly selected CPU backend",
                    isAuto = false
                )
            }
            if (requestedBackend == LlamaBackend.VULKAN) {
                if (validationReceipt != null && validationReceipt.status == "FAIL" && validationReceipt.permanent) {
                    return BackendDecision(
                        selectedBackend = LlamaBackend.CPU,
                        reason = "Explicit Vulkan requested, but model has known permanent numerical failure: ${validationReceipt.failReason ?: "numerical disagreement"}. Falling back to true CPU.",
                        isAuto = false
                    )
                }
                return BackendDecision(
                    selectedBackend = LlamaBackend.VULKAN,
                    reason = "User explicitly selected Vulkan GPU backend",
                    isAuto = false
                )
            }
        }

        // 2. Auto selection
        // Condition A: Numerical validation missing, failed, or stale
        if (validationReceipt == null || validationReceipt.status != "PASS") {
            val reason = if (validationReceipt == null) {
                "Auto mode: No valid numerical receipt found for current model and backend policy. Defaulting to true CPU."
            } else if (validationReceipt.status == "FAIL") {
                "Auto mode: Numerical validation failed (${validationReceipt.failReason ?: "disagreement"}). Defaulting to true CPU."
            } else {
                "Auto mode: Numerical validation is pending or incomplete. Defaulting to true CPU."
            }
            return BackendDecision(
                selectedBackend = LlamaBackend.CPU,
                reason = reason,
                isAuto = true
            )
        }

        // Condition B: Numerical validation passed -> check performance receipt
        val perfKey = GgufPerformanceStore.buildCacheKey(
            modelSha256 = modelSha256,
            threads = threads,
            contextWindow = contextWindow,
            nBatch = nBatch,
            nUbatch = nUbatch,
            prefillThreads = prefillThreads
        )
        val perfReceipt = GgufPerformanceStore.getReceipt(context, perfKey)

        if (perfReceipt == null || !perfReceipt.isComplete) {
            return BackendDecision(
                selectedBackend = LlamaBackend.CPU,
                reason = "Auto mode: Numerical validation passed, but no qualifying performance receipt exists. Run 'Check CPU/GPU performance' to benchmark. Defaulting to true CPU.",
                isAuto = true
            )
        }

        // Evaluate performance qualification criteria:
        // - Median warm TTFT improves by at least 10%
        // - Cold load-plus-first-token latency does not regress by > 5%
        // - Sustained decode throughput does not regress by > 5%
        // - Frame-time p95 does not regress by > 5%
        // - Inference-associated rendering stalls <= 100 ms
        // - No failures or numerical anomalies
        val qualifies = perfReceipt.qualifiesVulkan &&
            perfReceipt.warmTtftImprovementFraction >= 0.10 &&
            perfReceipt.coldRegressionFraction <= 0.05 &&
            perfReceipt.decodeRegressionFraction <= 0.05 &&
            perfReceipt.frameP95RegressionFraction <= 0.05 &&
            perfReceipt.maxInferenceRenderStallMs <= 100L &&
            !perfReceipt.hasNumericalAnomalyOrFailure

        if (qualifies) {
            return BackendDecision(
                selectedBackend = LlamaBackend.VULKAN,
                reason = "Auto mode: Numerical pass and qualifying performance receipt found (warm TTFT improved by ${(perfReceipt.warmTtftImprovementFraction * 100).toInt()}%). Selecting Vulkan GPU.",
                performanceReceipt = perfReceipt,
                isAuto = true
            )
        }

        return BackendDecision(
            selectedBackend = LlamaBackend.CPU,
            reason = "Auto mode: Complete performance comparison favors CPU or did not meet qualification thresholds. Retaining true CPU.",
            performanceReceipt = perfReceipt,
            isAuto = true
        )
    }
}
