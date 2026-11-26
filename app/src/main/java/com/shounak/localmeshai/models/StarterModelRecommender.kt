package com.shounak.localmeshai.models

import android.content.Context
import com.shounak.localmeshai.utils.DeviceUtils
import java.util.Locale

data class StarterModelRecommendation(
    val model: ModelInfo,
    val reason: String,
    val ramSummary: String
)

/**
 * Selects the single best-fit, zero-token starter model for the device's RAM
 * so first-time users can download and chat locally within 60 seconds.
 */
object StarterModelRecommender {

    fun recommendForDevice(
        context: Context,
        catalog: List<ModelInfo> = ModelCatalog.defaultModels
    ): StarterModelRecommendation? {
        val ramInfo = DeviceUtils.getRamMemoryInfo(context)
        return recommend(
            totalRamGb = ramInfo.totalGb,
            availableRamMb = ramInfo.availableBytes / (1024L * 1024L),
            catalog = catalog
        )
    }

    fun recommend(
        totalRamGb: Double,
        availableRamMb: Long,
        catalog: List<ModelInfo> = ModelCatalog.defaultModels
    ): StarterModelRecommendation? {
        val candidates = catalog.filter { model ->
            !model.isFuturePlaceholder &&
                !model.requiresHuggingFaceToken &&
                !model.url.isNullOrBlank() &&
                model.type == ModelType.Text &&
                model.status != ModelStatus.Blocked &&
                model.status != ModelStatus.NeedsConversion &&
                model.status != ModelStatus.ComingSoon
        }
        if (candidates.isEmpty()) return null

        val preferredIds: List<String>
        val reason: String
        when {
            totalRamGb < 4.5 || availableRamMb < 1_400L -> {
                preferredIds = listOf("smollm2_135m_gguf", "smollm2_360m_gguf", "qwen25_05b_gguf")
                reason = "Ultra-lightweight (~101 MB) for smooth offline speed on 4 GB RAM devices."
            }
            totalRamGb < 6.5 || availableRamMb < 2_400L -> {
                preferredIds = listOf("smollm2_360m_gguf", "qwen25_05b_gguf", "smollm2_135m_gguf")
                reason = "Compact (~258 MB) with fast replies and generous headroom on 6 GB phones."
            }
            else -> {
                preferredIds = listOf("llama32_1b_gguf", "qwen25_05b_gguf", "smollm2_360m_gguf")
                reason = "Fast 1B starter model (SHA-256 verified) tuned for 8 GB+ phones."
            }
        }

        val chosen = preferredIds.firstNotNullOfOrNull { id ->
            candidates.firstOrNull { it.id == id }
        } ?: candidates.minByOrNull { it.expectedFileSizeBytes.takeIf { s -> s > 0L } ?: Long.MAX_VALUE }
            ?: return null

        val availGb = availableRamMb / 1024.0
        val ramSummary = String.format(Locale.US, "%.1f GiB RAM · %.1f GiB free", totalRamGb, availGb)
        return StarterModelRecommendation(
            model = chosen,
            reason = reason,
            ramSummary = ramSummary
        )
    }
}
