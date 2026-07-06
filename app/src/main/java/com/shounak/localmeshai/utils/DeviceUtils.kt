package com.shounak.localmeshai.utils

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.shounak.localmeshai.models.ModelInfo
import java.util.Locale

enum class InferenceBackend {
    MEDIAPIPE,
    LITERT_GPU,
    LITERT_CPU
}

object DeviceUtils {
    private const val MIN_RAM_GIB_FOR_8_GB_DEVICE = 7.0
    private const val MIN_RAM_GIB_FOR_SMALL_LITERT_LM = 6.5
    private const val MIN_RAM_GIB_FOR_COMPACT_LITERT_LM = 10.5
    private const val MIN_RAM_GIB_FOR_LARGE_LITERT_LM = 14.5
    private const val MIN_RAM_GIB_FOR_HUGE_LITERT_LM = 22.0

    fun getTotalRamGB(context: Context): Double {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return memoryInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
    }

    fun getAvailableRamMb(context: Context): Long {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return memoryInfo.availMem / (1024L * 1024L)
    }

    fun selectBackend(modelInfo: ModelInfo, availableRamMb: Long): InferenceBackend {
        return selectBackend(modelInfo.fileName, availableRamMb)
    }

    fun selectBackend(fileName: String, availableRamMb: Long): InferenceBackend {
        val isLiteRTModel = fileName.endsWith(".litertlm", ignoreCase = true)
        val isHighRam = availableRamMb > 6_000

        return when {
            !isLiteRTModel -> InferenceBackend.MEDIAPIPE
            isHighRam && supportsLiteRtLmGpu() -> InferenceBackend.LITERT_GPU
            else -> InferenceBackend.LITERT_CPU
        }
    }

    fun selectBackendForModelFile(context: Context, fileName: String): InferenceBackend {
        return selectBackend(fileName, getAvailableRamMb(context))
    }

    fun isDeviceCompatible(context: Context): Pair<Boolean, String> {
        val ram = getTotalRamGB(context)
        val ramCompatible = ram >= MIN_RAM_GIB_FOR_8_GB_DEVICE
        val soc = deviceSoCText()
        val socCompatible = isSupportedChipset(soc)

        return if (ramCompatible && socCompatible) {
            true to "Device ready: ${String.format(Locale.US, "%.1f", ram)} GiB usable RAM, ${deviceChipLabel()}"
        } else {
            false to buildRequirementMessage(ram, ramCompatible, socCompatible)
        }
    }

    fun canInitializeLiteRtLm(
        context: Context,
        modelId: String,
        modelName: String = "",
        modelSize: String = "",
        isVision: Boolean = false,
        fileName: String = "",
        backendLabel: String = "",
        supportsAudioInput: Boolean = false
    ): Pair<Boolean, String> {
        val ram = getTotalRamGB(context)
        val soc = deviceSoCText()
        val chipsetSupported = isSupportedChipset(soc)
        val highEndForLiteRtLm = supportsLiteRtLmGpu()
        val paramsB = estimateModelParametersB(modelName.ifBlank { modelId }, modelSize)
        val effectiveFileName = fileName.ifBlank { "$modelId.litertlm" }
        val selectedBackend = selectBackendForModelFile(context, effectiveFileName)
        val isLiteRtModel = effectiveFileName.endsWith(".litertlm", ignoreCase = true)
        val isMultimodalLiteRt = isVision ||
            supportsAudioInput ||
            backendLabel.contains("vision", ignoreCase = true) ||
            backendLabel.contains("multimodal", ignoreCase = true)
        val requiredRam = requiredRamGiBForLiteRtLm(paramsB)
        val gemma4CpuFallbackAllowed = canUseGemma4LiteRtCpuFallback(
            context = context,
            modelId = modelId,
            modelName = modelName,
            modelSize = modelSize,
            fileName = effectiveFileName,
            isMultimodalLiteRt = isMultimodalLiteRt
        )

        return when {
            ram < requiredRam -> {
                false to "This LiteRT-LM model needs a ${nominalDeviceRamLabel(requiredRam)} Android device profile. This device has ${String.format(Locale.US, "%.1f", ram)} GiB usable RAM, so the model is blocked before native initialization to avoid crashes."
            }
            isLiteRtModel && isMultimodalLiteRt && !highEndForLiteRtLm -> {
                val chip = deviceChipLabel()
                false to "Multimodal LiteRT-LM is enabled only on verified high-end Android GPU profiles. $chip does not meet that safe profile, so this model is blocked before native initialization to avoid crashes. Use a MediaPipe .task vision model on this device instead."
            }
            gemma4CpuFallbackAllowed -> true to ""
            highEndForLiteRtLm && selectedBackend == InferenceBackend.LITERT_GPU -> true to ""
            chipsetSupported -> {
                // Known mid-range chipset: allow small models freely, compact text models with enough RAM.
                when {
                    paramsB == null -> true to ""
                    paramsB <= 2.0f -> true to ""
                    !isVision && paramsB <= 4.1f && ram >= MIN_RAM_GIB_FOR_COMPACT_LITERT_LM -> true to ""
                    else -> {
                        false to "This LiteRT-LM model is above this Android device's safe profile for ${deviceChipLabel()}. Try a <=2B LiteRT-LM model or a MediaPipe .task model on this device."
                    }
                }
            }
            else -> {
                // Unknown chipset: allow small (<=2B) models on CPU, block larger ones.
                when {
                    paramsB != null && paramsB <= 2.0f -> true to ""
                    else -> {
                        false to "LiteRT-LM support is unverified on ${deviceChipLabel()}. " +
                            "Small (<=2B) LiteRT-LM models can still run, but this model is too large. " +
                            "Use a MediaPipe .task model on this device instead."
                    }
                }
            }
        }
    }

    fun canUseGemma4LiteRtCpuFallback(
        context: Context,
        modelId: String,
        modelName: String = "",
        modelSize: String = "",
        fileName: String = "",
        isMultimodalLiteRt: Boolean = false
    ): Boolean {
        val effectiveFileName = fileName.ifBlank { "$modelId.litertlm" }
        if (isMultimodalLiteRt) return false
        if (!effectiveFileName.endsWith(".litertlm", ignoreCase = true)) return false
        if (!isGemma4E2bLiteRt(modelId, modelName, effectiveFileName)) return false
        if (selectBackendForModelFile(context, effectiveFileName) != InferenceBackend.LITERT_CPU) return false
        return getTotalRamGB(context) >= MIN_RAM_GIB_FOR_SMALL_LITERT_LM
    }

    fun supportsLiteRtLmGpu(): Boolean {
        val soc = deviceSoCText()
        return !isBlockedLiteRtLmGpuChipset(soc) && isHighEndLiteRtLmChipset(soc)
    }

    fun currentDeviceChipLabel(): String {
        return deviceChipLabel()
    }

    fun shouldPreferLiteRtLmGpu(context: Context): Boolean {
        return selectBackendForModelFile(context, "model.litertlm") == InferenceBackend.LITERT_GPU
    }

    private fun deviceSoCText(): String {
        return deviceSoCValues().joinToString(separator = " ").lowercase(Locale.US)
    }

    private fun deviceSoCValues(): List<String> {
        val values = mutableListOf(
            Build.HARDWARE,
            Build.BOARD,
            Build.DEVICE,
            Build.PRODUCT,
            Build.MODEL,
            Build.MANUFACTURER
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            values.add(Build.SOC_MANUFACTURER)
            values.add(Build.SOC_MODEL)
        }
        return values
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
            .distinctBy { it.lowercase(Locale.US) }
    }

    private fun isSupportedChipset(soc: String): Boolean {
        val snapdragonCompatible = listOf(
            "snapdragon 6 gen",
            "snapdragon 6s gen",
            "snapdragon 7 gen",
            "snapdragon 7+ gen",
            "snapdragon 7s gen",
            "snapdragon 7",
            "snapdragon 8 gen",
            "snapdragon 8+ gen",
            "snapdragon 8s gen",
            "snapdragon 8",
            "sm645", "sm647",
            "sm6450", "sm6475",
            "sm7435", "sm7450", "sm7475", "sm7550", "sm7635", "sm7675",
            "sm8450", "sm8475", "sm8550", "sm8650", "sm8750", "sm8850",
            "sm7", "sm8",
            "qcs8550"
        ).any { soc.contains(it) }

        val dimensityCompatible = listOf(
            "dimensity 7000", "dimensity 7020", "dimensity 7030", "dimensity 7050",
            "dimensity 7200", "dimensity 7300", "dimensity 7350", "dimensity 7400",
            "dimensity 8000", "dimensity 8020", "dimensity 8050", "dimensity 8100",
            "dimensity 8200", "dimensity 8250", "dimensity 8300", "dimensity 8350",
            "dimensity 8400", "dimensity 9000", "dimensity 9200", "dimensity 9300",
            "dimensity 9400",
            "dimensity 7",
            "dimensity 8",
            "dimensity 9",
            "mt6877", "mt6878", "mt6886", "mt6893", "mt6895", "mt6896", "mt6897",
            "mt688", "mt689",
            "mt698", "mt699"
        ).any { soc.contains(it) }

        val exynosCompatible = listOf(
            "exynos 1380", "exynos 1480", "exynos 1580",
            "exynos 2200", "exynos 2400", "exynos 2500",
            "s5e8835", "s5e8845", "s5e8855", "s5e9925", "s5e9945", "s5e9955"
        ).any { soc.contains(it) }

        return snapdragonCompatible || dimensityCompatible || exynosCompatible
    }

    private fun isHighEndLiteRtLmChipset(soc: String): Boolean {
        return listOf(
            "snapdragon 8 gen 2",
            "snapdragon 8 gen 3",
            "snapdragon 8 elite",
            "sm8550",
            "sm8650",
            "sm8750",
            "sm8850",
            "kalama",
            "pineapple",
            "dimensity 9300",
            "dimensity 9400",
            "mt6989",
            "mt6991",
            "mt6993",
            "exynos 2400",
            "exynos 2500",
            "s5e9945",
            "s5e9955"
        ).any { soc.contains(it) }
    }

    private fun isBlockedLiteRtLmGpuChipset(soc: String): Boolean {
        return listOf(
            "dimensity 7300",
            "dimensity7300",
            "mt6878",
            "mt6886"
        ).any { soc.contains(it) }
    }

    private fun isGemma4E2bLiteRt(modelId: String, modelName: String, fileName: String): Boolean {
        val source = "$modelId $modelName $fileName".lowercase(Locale.US)
        return source.contains("gemma4_e2b") ||
            source.contains("gemma 4 e2b") ||
            source.contains("gemma-4-e2b")
    }

    private fun requiredRamGiBForLiteRtLm(paramsB: Float?): Double {
        return when {
            paramsB == null -> MIN_RAM_GIB_FOR_SMALL_LITERT_LM
            paramsB <= 2.0f -> MIN_RAM_GIB_FOR_SMALL_LITERT_LM
            paramsB <= 4.1f -> MIN_RAM_GIB_FOR_COMPACT_LITERT_LM
            paramsB <= 14.5f -> MIN_RAM_GIB_FOR_LARGE_LITERT_LM
            else -> MIN_RAM_GIB_FOR_HUGE_LITERT_LM
        }
    }

    private fun nominalDeviceRamLabel(requiredRamGiB: Double): String {
        return when {
            requiredRamGiB <= MIN_RAM_GIB_FOR_SMALL_LITERT_LM -> "8 GB"
            requiredRamGiB <= MIN_RAM_GIB_FOR_COMPACT_LITERT_LM -> "12 GB"
            requiredRamGiB <= MIN_RAM_GIB_FOR_LARGE_LITERT_LM -> "16 GB"
            else -> "24 GB"
        }
    }

    private fun estimateModelParametersB(modelName: String, modelSize: String): Float? {
        val source = "$modelName $modelSize"
        Regex("""(?i)(\d+(?:\.\d+)?)\s*B""")
            .find(source)
            ?.groupValues
            ?.getOrNull(1)
            ?.toFloatOrNull()
            ?.let { return it }

        Regex("""(?i)(\d+(?:\.\d+)?)\s*M""")
            .find(source)
            ?.groupValues
            ?.getOrNull(1)
            ?.toFloatOrNull()
            ?.let { return it / 1000f }

        return null
    }

    private fun buildRequirementMessage(
        ram: Double,
        ramCompatible: Boolean,
        socCompatible: Boolean
    ): String {
        val ramText = String.format(Locale.US, "%.1f", ram)
        return when {
            !ramCompatible && !socCompatible -> {
                "This phone has $ramText GiB usable RAM and ${deviceChipLabel()} was not recognized as supported. Solus requires 8 GB nominal RAM plus MediaTek Dimensity 7000 series or newer, Qualcomm Snapdragon 6 Gen series or newer, or Samsung Exynos 1380 or newer."
            }
            !ramCompatible -> {
                "This phone has $ramText GiB usable RAM. Local 2B models require an 8 GB nominal phone."
            }
            else -> {
                "${deviceChipLabel()} was not recognized as MediaTek Dimensity 7000 series or newer, Qualcomm Snapdragon 6 Gen series or newer, or Samsung Exynos 1380 or newer."
            }
        }
    }

    private fun deviceChipLabel(): String {
        val values = deviceSoCValues()
        val bestChip = values
            .flatMap(::chipCandidatesFrom)
            .maxWithOrNull(
                compareBy<ChipCandidate> { it.score }
                    .thenBy { it.numericValue }
                    .thenBy { it.label.length }
            )
        if (bestChip != null) return bestChip.label

        return values.firstOrNull() ?: "this chipset"
    }

    private data class ChipCandidate(
        val label: String,
        val score: Int,
        val numericValue: Int
    )

    private fun chipCandidatesFrom(rawValue: String): List<ChipCandidate> {
        val candidates = mutableListOf<ChipCandidate>()
        val lower = rawValue.lowercase(Locale.US)

        Regex("""(?i)\b(mt\d{4,5}|sm\d{4,5}|s5e\d{4,5})\b""")
            .findAll(rawValue)
            .forEach { match ->
                val label = match.value.uppercase(Locale.US)
                candidates.add(
                    ChipCandidate(
                        label = label,
                        score = chipCandidateScore(label),
                        numericValue = label.filter(Char::isDigit).toIntOrNull() ?: 0
                    )
                )
            }

        Regex("""(?i)\b(dimensity\s*\d{3,4}|snapdragon\s*\d(?:\s*[+s]?\s*gen\s*\d|\s*elite)?|exynos\s*\d{4})\b""")
            .findAll(rawValue)
            .forEach { match ->
                val label = match.value
                    .replace(Regex("""\s+"""), " ")
                    .trim()
                    .replaceFirstChar { it.titlecase(Locale.US) }
                candidates.add(
                    ChipCandidate(
                        label = label,
                        score = chipCandidateScore(label),
                        numericValue = label.filter(Char::isDigit).toIntOrNull() ?: 0
                    )
                )
            }

        val manufacturer = when {
            lower.contains("mediatek") -> "MediaTek"
            lower.contains("qualcomm") -> "Qualcomm"
            lower.contains("samsung") -> "Samsung"
            else -> null
        }
        if (manufacturer != null && rawValue.length > manufacturer.length) {
            candidates.add(
                ChipCandidate(
                    label = rawValue,
                    score = chipCandidateScore(rawValue),
                    numericValue = rawValue.filter(Char::isDigit).toIntOrNull() ?: 0
                )
            )
        }

        return candidates
    }

    private fun chipCandidateScore(label: String): Int {
        val normalized = label.lowercase(Locale.US)
        return when {
            isHighEndLiteRtLmChipset(normalized) -> 4
            isBlockedLiteRtLmGpuChipset(normalized) -> 3
            isSupportedChipset(normalized) -> 2
            Regex("""\b(mt|sm|s5e)\d{4,5}\b""").containsMatchIn(normalized) -> 1
            else -> 0
        }
    }
}
