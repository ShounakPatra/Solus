package com.shounak.localmeshai.ai

import android.content.Context
import com.shounak.localmeshai.utils.DeviceUtils
import java.io.File

/**
 * Immutable requested execution profile for GGUF model loading and inference.
 * Resolved once per load operation and shared consistently across identity,
 * initialization, validation, and diagnostics.
 */
data class GgufExecutionProfile(
    val decodeThreads: Int,
    val prefillThreads: Int,
    val contextTokens: Int,
    val batchTokens: Int,
    val microBatchTokens: Int,
    val requestedGpuLayers: Int
) {
    init {
        require(decodeThreads > 0) { "decodeThreads must be > 0" }
        require(prefillThreads > 0) { "prefillThreads must be > 0" }
        require(contextTokens > 0) { "contextTokens must be > 0" }
        require(batchTokens > 0) { "batchTokens must be > 0" }
        require(microBatchTokens > 0) { "microBatchTokens must be > 0" }
        require(microBatchTokens <= batchTokens) { "microBatchTokens ($microBatchTokens) must be <= batchTokens ($batchTokens)" }
    }

    /**
     * Converts to [ModelLoadIdentity].
     */
    fun toIdentity(
        modelFile: File,
        runtimeKind: String = "LlamaCpp",
        backendPreference: String = "AUTO"
    ): ModelLoadIdentity {
        val length = if (modelFile.exists()) modelFile.length() else 0L
        val lastMod = if (modelFile.exists()) modelFile.lastModified() else 0L
        return ModelLoadIdentity(
            modelPath = modelFile.absolutePath,
            fileLength = length,
            fileLastModified = lastMod,
            runtimeKind = runtimeKind,
            backendPreference = backendPreference,
            threads = decodeThreads,
            prefillThreads = prefillThreads,
            contextWindow = contextTokens,
            batchSize = batchTokens,
            ubatchSize = microBatchTokens,
            gpuLayers = requestedGpuLayers
        )
    }

    companion object {
        enum class BenchmarkPreset {
            Baseline,            // 4 decode, 4 prefill, 512/256
            FewerPrefillThreads, // 4 decode, 2 prefill, 512/256
            SmallerBatches,      // 4 decode, 4 prefill, 128/64
            Combined             // 4 decode, 2 prefill, 128/64
        }

        /**
         * Resolves the production execution profile for a model given the device hardware.
         */
        fun resolve(
            context: Context,
            modelNameOrId: String = "",
            modelSize: String = "",
            customContextTokens: Int? = null,
            backendPreference: String = "AUTO",
            modelId: String? = null
        ): GgufExecutionProfile {
            val paramsB = DeviceUtils.estimateModelParametersB(modelNameOrId, modelSize)
            val maxDeviceContext = DeviceUtils.recommendedContextWindow(context, paramsB)
            val contextWindow = if (customContextTokens != null && customContextTokens > 0) {
                DeviceUtils.effectiveContextTokens(com.shounak.localmeshai.models.ModelFormat.GGUF, DeviceUtils.getTotalRamGB(context), customContextTokens, modelParamsB = paramsB)
            } else {
                maxDeviceContext
            }
            val threads = RuntimeExecutionPolicy.resolve(context, modelId).cpuThreads
            val (batch, ubatch) = DeviceUtils.recommendedBatchSize(context)
            val isGpu = backendPreference.equals("VULKAN", ignoreCase = true) ||
                backendPreference.equals("GPU", ignoreCase = true) ||
                backendPreference.equals("AUTO", ignoreCase = true)

            return GgufExecutionProfile(
                decodeThreads = threads,
                prefillThreads = threads, // Production baseline: 4 threads
                contextTokens = contextWindow,
                batchTokens = minOf(batch, contextWindow),
                microBatchTokens = minOf(ubatch, batch),
                requestedGpuLayers = if (isGpu) 99 else 0
            )
        }

        fun createPreset(
            preset: BenchmarkPreset,
            contextTokens: Int = 4096,
            requestedGpuLayers: Int = 99
        ): GgufExecutionProfile {
            return when (preset) {
                BenchmarkPreset.Baseline -> GgufExecutionProfile(
                    decodeThreads = 4,
                    prefillThreads = 4,
                    contextTokens = contextTokens,
                    batchTokens = 512,
                    microBatchTokens = 256,
                    requestedGpuLayers = requestedGpuLayers
                )
                BenchmarkPreset.FewerPrefillThreads -> GgufExecutionProfile(
                    decodeThreads = 4,
                    prefillThreads = 2,
                    contextTokens = contextTokens,
                    batchTokens = 512,
                    microBatchTokens = 256,
                    requestedGpuLayers = requestedGpuLayers
                )
                BenchmarkPreset.SmallerBatches -> GgufExecutionProfile(
                    decodeThreads = 4,
                    prefillThreads = 4,
                    contextTokens = contextTokens,
                    batchTokens = 128,
                    microBatchTokens = 64,
                    requestedGpuLayers = requestedGpuLayers
                )
                BenchmarkPreset.Combined -> GgufExecutionProfile(
                    decodeThreads = 4,
                    prefillThreads = 2,
                    contextTokens = contextTokens,
                    batchTokens = 128,
                    microBatchTokens = 64,
                    requestedGpuLayers = requestedGpuLayers
                )
            }
        }
    }
}
