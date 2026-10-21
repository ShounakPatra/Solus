package com.shounak.localmeshai.ai

import android.content.Context
import android.os.PowerManager
import com.shounak.localmeshai.utils.DeviceUtils

/**
 * Centralized CPU thread and resource budget execution policy.
 * Applies uniformly across all runtimes (LiteRT-LM, MediaPipe, GGUF/llama.cpp)
 * to prevent main thread starvation and device thermal/power thrashing.
 */
data class RuntimeExecutionPolicy(
    val cpuThreads: Int,
    val isPowerSaveMode: Boolean = false,
    val isLowRam: Boolean = false
) {
    companion object {
        const val MIN_CPU_THREADS = 1
        const val MAX_CPU_THREADS = 4
        const val LOW_RAM_THRESHOLD_GB = 6.0

        /**
         * Pure function resolving thread budget given processors, RAM, and power-save status.
         */
        fun resolve(
            availableProcessors: Int,
            totalRamGb: Double,
            isPowerSaveMode: Boolean
        ): RuntimeExecutionPolicy {
            val baseThreads = (availableProcessors - 2).coerceIn(MIN_CPU_THREADS, MAX_CPU_THREADS)
            val isLowRam = totalRamGb < LOW_RAM_THRESHOLD_GB
            val effectiveThreads = if (isPowerSaveMode || isLowRam) {
                minOf(2, baseThreads)
            } else {
                baseThreads
            }
            return RuntimeExecutionPolicy(
                cpuThreads = effectiveThreads,
                isPowerSaveMode = isPowerSaveMode,
                isLowRam = isLowRam
            )
        }

        /**
         * Resolves execution policy from Android Context.
         */
        fun resolve(context: Context): RuntimeExecutionPolicy {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val isPowerSave = powerManager?.isPowerSaveMode == true
            val totalRamGb = DeviceUtils.getTotalRamGB(context)
            val cores = Runtime.getRuntime().availableProcessors()
            return resolve(
                availableProcessors = cores,
                totalRamGb = totalRamGb,
                isPowerSaveMode = isPowerSave
            )
        }
    }
}
