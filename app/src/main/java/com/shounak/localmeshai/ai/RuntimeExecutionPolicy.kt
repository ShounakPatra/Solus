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
        const val MAX_CPU_THREADS = 6
        const val LOW_RAM_THRESHOLD_GB = 4.0

        /**
         * Pure function resolving thread budget given processors, RAM, and power-save status.
         */
        fun resolve(
            availableProcessors: Int,
            totalRamGb: Double,
            isPowerSaveMode: Boolean,
            coreFrequenciesKhz: List<Long> = emptyList(),
            tunedThreads: Int? = null
        ): RuntimeExecutionPolicy {
            val available = availableProcessors.coerceAtLeast(1)
            val topologyThreads = performanceCoreCount(coreFrequenciesKhz)
            val baseThreads = (tunedThreads?.takeIf { it in 1..MAX_CPU_THREADS }
                ?: topologyThreads ?: minOf(available, 4)).coerceIn(1, minOf(available, MAX_CPU_THREADS))
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
        fun threadPreferenceKey(modelId: String): String = "threads_${android.os.Build.FINGERPRINT}_${modelId}"

        fun resolve(context: Context, modelId: String? = null): RuntimeExecutionPolicy {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val isPowerSave = powerManager?.isPowerSaveMode == true
            val totalRamGb = DeviceUtils.getTotalRamGB(context)
            val cores = Runtime.getRuntime().availableProcessors()
            val frequencies = java.io.File("/sys/devices/system/cpu").listFiles().orEmpty()
                .filter { it.name.matches(Regex("cpu[0-9]+")) }.mapNotNull { cpu ->
                    runCatching { java.io.File(cpu, "cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull()
                }
            val speedPrefs = context.getSharedPreferences("runtime_speed", Context.MODE_PRIVATE)
            val tuned = modelId?.takeIf { it.isNotBlank() }?.let { speedPrefs.getInt(threadPreferenceKey(it), 0).takeIf { count -> count > 0 } }
            return resolve(
                availableProcessors = cores,
                totalRamGb = totalRamGb,
                isPowerSaveMode = isPowerSave,
                coreFrequenciesKhz = frequencies.takeIf { it.size >= cores }.orEmpty(),
                tunedThreads = tuned
            )
        }

        internal fun performanceCoreCount(frequencies: List<Long>): Int? {
            if (frequencies.isEmpty() || frequencies.any { it <= 0 }) return null
            val levels = frequencies.distinct().sorted()
            if (levels.size == 1) return frequencies.size
            val split = levels.zipWithNext().maxByOrNull { (low, high) -> high.toDouble() / low } ?: return null
            if (split.second.toDouble() / split.first < 1.10) return frequencies.size
            return frequencies.count { it >= split.second }
        }
    }
}
