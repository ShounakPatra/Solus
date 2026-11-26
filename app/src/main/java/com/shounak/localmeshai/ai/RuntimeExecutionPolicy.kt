package com.shounak.localmeshai.ai

import android.content.Context
import android.os.PowerManager
import com.shounak.localmeshai.utils.DeviceUtils
import kotlinx.coroutines.CancellationException

enum class AcceleratedBackendPreference {
    AUTO,
    NPU,
    VULKAN_GPU,
    CPU
}

data class AcceleratedBackendSelection(
    val backend: AcceleratedBackendPreference,
    val displayLabel: String,
    val reason: String
)

/**
 * Centralized CPU thread and resource budget execution policy.
 * Applies uniformly across all runtimes (LiteRT-LM, MediaPipe, GGUF/llama.cpp)
 * to prevent main thread starvation and device thermal/power thrashing,
 * and computes deterministic step-down recovery identities on OOM/backend init failure.
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
        const val MAX_RECOVERY_ATTEMPTS = 3
        const val MIN_RECOVERY_CONTEXT_TOKENS = 512

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
         * Returns true when [failure] looks like memory pressure, OOM, or GPU/backend init failure
         * that can be retried with reduced GPU offload and smaller context window.
         */
        fun isRecoverableInitFailure(failure: Throwable): Boolean {
            if (failure is CancellationException) return false
            var cur: Throwable? = failure
            while (cur != null) {
                if (cur is OutOfMemoryError) return true
                val msg = cur.message?.lowercase().orEmpty()
                if (msg.contains("cancel") ||
                    msg.contains("superseded") ||
                    msg.contains("blocked") ||
                    msg.contains("not found") ||
                    msg.contains("sha") ||
                    msg.contains("checksum")
                ) {
                    return false
                }
                if (msg.contains("out of memory") ||
                    msg.contains("oom") ||
                    msg.contains("cannot allocate") ||
                    msg.contains("alloc") ||
                    msg.contains("memory") ||
                    msg.contains("kv cache") ||
                    msg.contains("kv_cache") ||
                    msg.contains("vulkan") ||
                    msg.contains("gpu") ||
                    msg.contains("context") ||
                    msg.contains("mmap") ||
                    msg.contains("failed to load") ||
                    msg.contains("initialization failed") ||
                    msg.contains("failed to initialize")
                ) {
                    return true
                }
                cur = cur.cause
            }
            return false
        }

        /**
         * Computes the next conservative step-down [ModelLoadIdentity] when [current] failed with [failure].
         * Step 1 disables GPU offload (Vulkan/GPU -> CPU, gpuLayers = 0).
         * Subsequent steps halve/step down contextWindow (4096 -> 2048 -> 1024 -> 512) and batch sizes.
         * Returns null when no safer configuration remains.
         */
        fun nextRecoveryIdentity(
            current: ModelLoadIdentity,
            failure: Throwable
        ): ModelLoadIdentity? {
            if (!isRecoverableInitFailure(failure)) return null

            val wantsNpu = current.backendPreference.equals("NPU", ignoreCase = true)
            if (wantsNpu) {
                return current.copy(
                    backendPreference = "VULKAN",
                    gpuLayers = maxOf(current.gpuLayers, 99)
                )
            }

            val wantsGpuOffload = current.gpuLayers > 0 ||
                current.backendPreference.equals("AUTO", ignoreCase = true) ||
                current.backendPreference.equals("VULKAN", ignoreCase = true) ||
                current.backendPreference.equals("GPU", ignoreCase = true)

            if (wantsGpuOffload) {
                val steppedThreads = minOf(current.threads, 4).coerceAtLeast(1)
                return current.copy(
                    backendPreference = "CPU",
                    gpuLayers = 0,
                    threads = steppedThreads,
                    prefillThreads = minOf(current.prefillThreads, steppedThreads).coerceAtLeast(1)
                )
            }

            val nextContext = when {
                current.contextWindow > 4096 -> 4096
                current.contextWindow > 2048 -> 2048
                current.contextWindow > 1024 -> 1024
                current.contextWindow > MIN_RECOVERY_CONTEXT_TOKENS -> MIN_RECOVERY_CONTEXT_TOKENS
                else -> return null
            }

            val steppedThreads = minOf(current.threads, 2).coerceAtLeast(1)
            val nextBatch = minOf(current.batchSize, nextContext).coerceAtMost(128).coerceAtLeast(32)
            val nextUbatch = minOf(current.ubatchSize, nextBatch).coerceAtMost(64).coerceAtLeast(16)
            return current.copy(
                backendPreference = "CPU",
                gpuLayers = 0,
                contextWindow = nextContext,
                batchSize = nextBatch,
                ubatchSize = nextUbatch,
                threads = steppedThreads,
                prefillThreads = steppedThreads
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

        /**
         * Resolves runtime accelerator priority cleanly across NPU -> Vulkan GPU -> ARM CPU.
         * Never selects NPU when unsupported or crash-blacklisted.
         */
        fun selectAcceleratedBackend(
            preferredBackend: String,
            npuCapable: Boolean,
            npuBlocked: Boolean,
            gpuQualified: Boolean,
            gpuBlocked: Boolean,
            vendor: NpuVendor = NpuVendor.NONE
        ): AcceleratedBackendSelection {
            val npuLabel = when (vendor) {
                NpuVendor.QUALCOMM -> "Qualcomm NPU"
                NpuVendor.MEDIATEK -> "MediaTek NPU"
                NpuVendor.NONE -> "NPU"
            }
            val normalized = preferredBackend.trim().uppercase(java.util.Locale.US)
            return when (normalized) {
                "CPU" -> AcceleratedBackendSelection(
                    backend = AcceleratedBackendPreference.CPU,
                    displayLabel = "CPU",
                    reason = "User selected CPU safe mode."
                )
                "NPU" -> when {
                    npuCapable && !npuBlocked -> AcceleratedBackendSelection(
                        backend = AcceleratedBackendPreference.NPU,
                        displayLabel = npuLabel,
                        reason = "User selected $npuLabel."
                    )
                    gpuQualified && !gpuBlocked -> AcceleratedBackendSelection(
                        backend = AcceleratedBackendPreference.VULKAN_GPU,
                        displayLabel = "Vulkan GPU",
                        reason = if (npuBlocked) "NPU blacklisted after native crash; falling back to Vulkan GPU." else "NPU unavailable; falling back to Vulkan GPU."
                    )
                    else -> AcceleratedBackendSelection(
                        backend = AcceleratedBackendPreference.CPU,
                        displayLabel = "CPU",
                        reason = if (npuBlocked) "NPU blacklisted after native crash; falling back to ARM CPU." else "NPU unavailable; falling back to ARM CPU."
                    )
                }
                "VULKAN", "GPU" -> if (!gpuBlocked) {
                    AcceleratedBackendSelection(
                        backend = AcceleratedBackendPreference.VULKAN_GPU,
                        displayLabel = "Vulkan GPU",
                        reason = "User selected GPU accelerator."
                    )
                } else {
                    AcceleratedBackendSelection(
                        backend = AcceleratedBackendPreference.CPU,
                        displayLabel = "CPU",
                        reason = "GPU blacklisted after failure; using ARM CPU."
                    )
                }
                else -> when {
                    npuCapable && !npuBlocked -> AcceleratedBackendSelection(
                        backend = AcceleratedBackendPreference.NPU,
                        displayLabel = npuLabel,
                        reason = "Auto priority selected $npuLabel."
                    )
                    gpuQualified && !gpuBlocked -> AcceleratedBackendSelection(
                        backend = AcceleratedBackendPreference.VULKAN_GPU,
                        displayLabel = "Vulkan GPU",
                        reason = "Auto priority selected validated Vulkan GPU."
                    )
                    else -> AcceleratedBackendSelection(
                        backend = AcceleratedBackendPreference.CPU,
                        displayLabel = "CPU",
                        reason = "Auto priority selected tuned ARM CPU."
                    )
                }
            }
        }
    }
}
