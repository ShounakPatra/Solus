package com.shounak.localmeshai.ai

import android.content.Context
import com.shounak.localmeshai.BuildConfig
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

data class GgufPerformanceReceipt(
    val modelSha256: String,
    val nativeBuildId: String = BuildConfig.NATIVE_BUILD_ID,
    val buildType: String = BuildConfig.BUILD_TYPE,
    val deviceIdentity: String = "",
    val backendPolicyVersion: Int = 2,
    val configurationKey: String = "",
    val timestampMs: Long = System.currentTimeMillis(),
    val coldSampleCount: Int = 0,
    val warmSampleCount: Int = 0,
    val cpuWarmTtftMedianMs: Double = 0.0,
    val vulkanWarmTtftMedianMs: Double = 0.0,
    val warmTtftImprovementFraction: Double = 0.0,
    val cpuColdLoadFirstTokenMs: Double = 0.0,
    val vulkanColdLoadFirstTokenMs: Double = 0.0,
    val coldRegressionFraction: Double = 0.0,
    val cpuSustainedDecodeTokensPerSec: Double = 0.0,
    val vulkanSustainedDecodeTokensPerSec: Double = 0.0,
    val decodeRegressionFraction: Double = 0.0,
    val cpuFrameP95Ms: Double = 0.0,
    val vulkanFrameP95Ms: Double = 0.0,
    val frameP95RegressionFraction: Double = 0.0,
    val maxInferenceRenderStallMs: Long = 0L,
    val hasNumericalAnomalyOrFailure: Boolean = false,
    val qualifiesVulkan: Boolean = false,
    val isComplete: Boolean = true
) {
    fun toJson(): String {
        val obj = JSONObject()
        obj.put("model_sha", modelSha256)
        obj.put("native_build_id", nativeBuildId)
        obj.put("build_type", buildType)
        obj.put("device_identity", deviceIdentity)
        obj.put("backend_policy_version", backendPolicyVersion)
        obj.put("config_key", configurationKey)
        obj.put("timestamp_ms", timestampMs)
        obj.put("cold_samples", coldSampleCount)
        obj.put("warm_samples", warmSampleCount)
        obj.put("cpu_warm_ttft_median_ms", cpuWarmTtftMedianMs)
        obj.put("vk_warm_ttft_median_ms", vulkanWarmTtftMedianMs)
        obj.put("warm_ttft_improvement_fraction", warmTtftImprovementFraction)
        obj.put("cpu_cold_load_ft_ms", cpuColdLoadFirstTokenMs)
        obj.put("vk_cold_load_ft_ms", vulkanColdLoadFirstTokenMs)
        obj.put("cold_regression_fraction", coldRegressionFraction)
        obj.put("cpu_decode_tok_s", cpuSustainedDecodeTokensPerSec)
        obj.put("vk_decode_tok_s", vulkanSustainedDecodeTokensPerSec)
        obj.put("decode_regression_fraction", decodeRegressionFraction)
        obj.put("cpu_frame_p95_ms", cpuFrameP95Ms)
        obj.put("vk_frame_p95_ms", vulkanFrameP95Ms)
        obj.put("frame_p95_regression_fraction", frameP95RegressionFraction)
        obj.put("max_render_stall_ms", maxInferenceRenderStallMs)
        obj.put("has_numerical_anomaly", hasNumericalAnomalyOrFailure)
        obj.put("qualifies_vulkan", qualifiesVulkan)
        obj.put("is_complete", isComplete)
        return obj.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): GgufPerformanceReceipt? {
            return runCatching {
                val obj = JSONObject(jsonStr)
                if (obj.optString("native_build_id") != BuildConfig.NATIVE_BUILD_ID) return null
                if (obj.optString("build_type") != BuildConfig.BUILD_TYPE) return null
                if (obj.optInt("backend_policy_version", 0) != 2) return null
                val modelSha = obj.getString("model_sha")
                if (modelSha.startsWith("local_gguf_") || modelSha.startsWith("content:") || modelSha.startsWith("file:")) return null

                GgufPerformanceReceipt(
                    modelSha256 = obj.getString("model_sha"),
                    nativeBuildId = obj.optString("native_build_id", BuildConfig.NATIVE_BUILD_ID),
                    buildType = obj.optString("build_type", BuildConfig.BUILD_TYPE),
                    deviceIdentity = obj.optString("device_identity", ""),
                    backendPolicyVersion = obj.optInt("backend_policy_version", 2),
                    configurationKey = obj.optString("config_key", ""),
                    timestampMs = obj.optLong("timestamp_ms", 0L),
                    coldSampleCount = obj.optInt("cold_samples", 0),
                    warmSampleCount = obj.optInt("warm_samples", 0),
                    cpuWarmTtftMedianMs = obj.optDouble("cpu_warm_ttft_median_ms", 0.0),
                    vulkanWarmTtftMedianMs = obj.optDouble("vk_warm_ttft_median_ms", 0.0),
                    warmTtftImprovementFraction = obj.optDouble("warm_ttft_improvement_fraction", 0.0),
                    cpuColdLoadFirstTokenMs = obj.optDouble("cpu_cold_load_ft_ms", 0.0),
                    vulkanColdLoadFirstTokenMs = obj.optDouble("vk_cold_load_ft_ms", 0.0),
                    coldRegressionFraction = obj.optDouble("cold_regression_fraction", 0.0),
                    cpuSustainedDecodeTokensPerSec = obj.optDouble("cpu_decode_tok_s", 0.0),
                    vulkanSustainedDecodeTokensPerSec = obj.optDouble("vk_decode_tok_s", 0.0),
                    decodeRegressionFraction = obj.optDouble("decode_regression_fraction", 0.0),
                    cpuFrameP95Ms = obj.optDouble("cpu_frame_p95_ms", 0.0),
                    vulkanFrameP95Ms = obj.optDouble("vk_frame_p95_ms", 0.0),
                    frameP95RegressionFraction = obj.optDouble("frame_p95_regression_fraction", 0.0),
                    maxInferenceRenderStallMs = obj.optLong("max_render_stall_ms", 0L),
                    hasNumericalAnomalyOrFailure = obj.optBoolean("has_numerical_anomaly", false),
                    qualifiesVulkan = obj.optBoolean("qualifies_vulkan", false),
                    isComplete = obj.optBoolean("is_complete", false)
                )
            }.getOrNull()
        }
    }
}

object GgufPerformanceStore {
    private const val PREFS_NAME = "gguf_performance_receipts"
    internal val testMemoryCache = ConcurrentHashMap<String, GgufPerformanceReceipt>()

    fun buildCacheKey(
        modelSha256: String,
        threads: Int,
        contextWindow: Int,
        nBatch: Int,
        nUbatch: Int,
        prefillThreads: Int = threads,
        companionSummary: String = ""
    ): String {
        val deviceId = GgufGpuValidator.getDeviceIdentity()
        val companionPart = if (companionSummary.isNotBlank()) "_comp$companionSummary" else ""
        val configStr = "bp1_th${threads}_pth${prefillThreads}_ctx${contextWindow}_b${nBatch}_ub${nUbatch}$companionPart"
        return "$modelSha256:${BuildConfig.NATIVE_BUILD_ID}:${BuildConfig.BUILD_TYPE}:$deviceId:$configStr"
    }

    fun getReceipt(context: Context?, cacheKey: String): GgufPerformanceReceipt? {
        testMemoryCache[cacheKey]?.let { return it }
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return null
        val jsonStr = prefs.getString(cacheKey, null) ?: return null
        return GgufPerformanceReceipt.fromJson(jsonStr)
    }

    fun saveReceipt(context: Context?, cacheKey: String, receipt: GgufPerformanceReceipt) {
        testMemoryCache[cacheKey] = receipt
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()?.apply {
            putString(cacheKey, receipt.toJson())
            apply()
        }
    }

    fun clear(context: Context?) {
        testMemoryCache.clear()
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()?.clear()?.apply()
    }
}
