package com.shounak.localmeshai.utils

import android.util.Log
import com.shounak.localmeshai.ai.LlamaCppEngine
import com.shounak.localmeshai.models.ArtifactRole
import com.shounak.localmeshai.models.ThinkingCapability
import com.shounak.localmeshai.models.ModelCapabilities
import java.io.File
import java.util.Locale

/**
 * Metadata record for an inspected GGUF file or file descriptor.
 */
data class GgufArtifactInspection(
    val location: String,
    val displayName: String,
    val sizeBytes: Long,
    val valid: Boolean,
    val isSupported: Boolean,
    val architecture: String = "",
    val name: String = "",
    val splitNo: Int = -1,
    val splitCount: Int = -1,
    val isProjector: Boolean = false,
    val hasVision: Boolean = false,
    val hasAudio: Boolean = false,
    val trainContextLength: Int = 0,
    val thinkingCapability: String = "Unknown",
    val error: String = ""
)

/**
 * Result of validating a complete GGUF bundle (single file, split shards, and optional projector).
 */
data class GgufBundleValidationResult(
    val isValid: Boolean,
    val orderedMainArtifacts: List<GgufArtifactInspection> = emptyList(),
    val projectorArtifact: GgufArtifactInspection? = null,
    val capabilities: ModelCapabilities = ModelCapabilities(supportsText = true),
    val primaryName: String = "",
    val contextLength: Int = capabilities.contextLength,
    val errorMessage: String? = null,
    val structuredError: ModelOperationError? = null
)

/**
 * Shared native GGUF inspector and bundle validator.
 * Validates GGUF headers, architecture compatibility, split shard sequence completeness,
 * and multimodal projector capabilities without loading tensor weights into memory.
 */
object GgufBundleInspector {
    private const val TAG = "GgufBundleInspector"

    /**
     * Inspects a single GGUF artifact by path or `/proc/self/fd/<fd>`.
     */
    fun inspectArtifact(
        location: String,
        displayName: String,
        sizeBytes: Long = 0L
    ): GgufArtifactInspection {
        val inspection = try {
            LlamaCppEngine.inspectModel(location)
        } catch (t: Throwable) {
            Log.w(TAG, "Native GGUF inspection failed for $displayName at $location: ${t.message}")
            return GgufArtifactInspection(
                location = location,
                displayName = displayName,
                sizeBytes = sizeBytes,
                valid = false,
                isSupported = false,
                error = "Inspection exception: ${t.message}"
            )
        }

        if (!inspection.valid || !inspection.isSupported || inspection.error.isNotBlank()) {
            val errorDetail = when {
                inspection.error.isNotBlank() -> inspection.error
                !inspection.valid -> "Invalid or corrupted GGUF format"
                !inspection.isSupported -> "Architecture '${inspection.architecture}' is not supported by runtime"
                else -> "Unsupported GGUF model"
            }
            return GgufArtifactInspection(
                location = location,
                displayName = displayName,
                sizeBytes = sizeBytes,
                valid = inspection.valid,
                isSupported = inspection.isSupported,
                architecture = inspection.architecture,
                name = inspection.name,
                splitNo = inspection.splitNo,
                splitCount = inspection.splitCount,
                error = errorDetail
            )
        }

        val lowerArch = inspection.architecture.lowercase(Locale.ROOT)
        val lowerFile = displayName.lowercase(Locale.ROOT)
        val isProj = inspection.isProjector || lowerArch == "clip" || lowerArch == "projector" ||
            lowerFile.contains("mmproj") || lowerFile.contains("projector")
        val hasVis = inspection.clipHasVisionEncoder

        return GgufArtifactInspection(
            location = location,
            displayName = displayName,
            sizeBytes = sizeBytes,
            valid = true,
            isSupported = true,
            architecture = inspection.architecture,
            name = inspection.name.ifBlank { displayName.substringBeforeLast('.') },
            splitNo = inspection.splitNo,
            splitCount = inspection.splitCount,
            isProjector = isProj,
            hasVision = hasVis,
            hasAudio = inspection.clipHasAudioEncoder,
            trainContextLength = inspection.trainContextLength,
            thinkingCapability = inspection.thinkingCapability,
            error = ""
        )
    }

    private val KNOWN_VISION_ARCHITECTURES = setOf(
        "mllama",
        "minicpmv",
        "minicpm_v",
        "qwen2vl",
        "qwen2_vl",
        "llava",
        "llava-hf",
        "llava_hf",
        "moondream",
        "moondream2",
        "granite-vision",
        "granite_vision",
        "paligemma",
        "paligemma2",
        "chameleon",
        "florence2"
    )

    fun isVisionArchitecture(arch: String): Boolean {
        if (arch.isBlank()) return false
        val norm = arch.lowercase(Locale.ROOT).trim().replace("-", "").replace("_", "")
        return KNOWN_VISION_ARCHITECTURES.any {
            val target = it.replace("-", "").replace("_", "")
            norm == target || norm.contains(target)
        }
    }

    /**
     * Validates an assembled collection of inspected GGUF artifacts as a coherent model bundle.
     * Enforces expected roles, shard completeness, architecture consistency, and derives capabilities.
     *
     * @param inspections List of individual artifact inspection results.
     * @param expectedRoles Optional map of displayName -> expected ArtifactRole to verify role consistency.
     * @param expectedSizes Optional map of displayName -> expected size in bytes (checked if > 0).
     */
    fun validateBundle(
        inspections: List<GgufArtifactInspection>,
        expectedRoles: Map<String, ArtifactRole>? = null,
        expectedSizes: Map<String, Long>? = null
    ): GgufBundleValidationResult {
        if (inspections.isEmpty()) {
            return GgufBundleValidationResult(
                isValid = false,
                errorMessage = "No artifacts were provided for bundle inspection.",
                structuredError = ModelOperationError(
                    reason = ModelErrorReason.Unknown,
                    stage = ModelOperationStage.Inspection,
                    diagnosticDetails = "No artifacts provided"
                )
            )
        }

        // 1. Verify all individual artifacts passed GGUF inspection and size checks
        for (item in inspections) {
            if (!item.valid || !item.isSupported || item.error.isNotBlank()) {
                val reason = when {
                    !item.isSupported -> ModelErrorReason.UnsupportedFormat
                    !item.valid -> ModelErrorReason.EmptyOrDamagedHeader
                    else -> ModelErrorReason.UnsupportedFormat
                }
                return GgufBundleValidationResult(
                    isValid = false,
                    errorMessage = "Artifact '${item.displayName}' is invalid: ${item.error}",
                    structuredError = ModelOperationError(
                        reason = reason,
                        stage = ModelOperationStage.Inspection,
                        affectedFilenames = listOf(item.displayName),
                        architectureOrFormat = item.architecture,
                        diagnosticDetails = item.error.ifBlank { "Invalid or unsupported GGUF artifact '${item.displayName}'" }
                    )
                )
            }
            val expectedSize = expectedSizes?.get(item.displayName) ?: 0L
            if (expectedSize > 0L && item.sizeBytes > 0L && item.sizeBytes != expectedSize) {
                return GgufBundleValidationResult(
                    isValid = false,
                    errorMessage = "Artifact '${item.displayName}' size mismatch: expected $expectedSize bytes, found ${item.sizeBytes} bytes.",
                    structuredError = ModelOperationError(
                        reason = ModelErrorReason.EmptyOrDamagedHeader,
                        stage = ModelOperationStage.Inspection,
                        affectedFilenames = listOf(item.displayName),
                        diagnosticDetails = "Size mismatch: expected $expectedSize bytes, found ${item.sizeBytes} bytes"
                    )
                )
            }
        }

        // 2. Separate into candidate projectors and candidate main/shard models
        val projectors = mutableListOf<GgufArtifactInspection>()
        val mainCandidates = mutableListOf<GgufArtifactInspection>()

        for (item in inspections) {
            val declaredRole = expectedRoles?.get(item.displayName)
            if (declaredRole != null) {
                when (declaredRole) {
                    ArtifactRole.Projector -> {
                        if (!item.isProjector) {
                            return GgufBundleValidationResult(
                                isValid = false,
                                errorMessage = "Artifact '${item.displayName}' was declared as a multimodal projector but is not a valid projector (arch=${item.architecture}).",
                                structuredError = ModelOperationError(
                                    reason = ModelErrorReason.UnsupportedFormat,
                                    stage = ModelOperationStage.Inspection,
                                    affectedFilenames = listOf(item.displayName),
                                    architectureOrFormat = item.architecture,
                                    diagnosticDetails = "Not a valid projector (arch=${item.architecture})"
                                )
                            )
                        }
                        projectors.add(item)
                    }
                    ArtifactRole.Main, ArtifactRole.Shard -> {
                        if (item.isProjector) {
                            return GgufBundleValidationResult(
                                isValid = false,
                                errorMessage = "Artifact '${item.displayName}' was declared as a main/shard model but contains projector architecture '${item.architecture}'.",
                                structuredError = ModelOperationError(
                                    reason = ModelErrorReason.UnsupportedFormat,
                                    stage = ModelOperationStage.Inspection,
                                    affectedFilenames = listOf(item.displayName),
                                    architectureOrFormat = item.architecture,
                                    diagnosticDetails = "Declared as main/shard but contains projector arch '${item.architecture}'"
                                )
                            )
                        }
                        mainCandidates.add(item)
                    }
                }
            } else {
                if (item.isProjector) {
                    projectors.add(item)
                } else {
                    mainCandidates.add(item)
                }
            }
        }

        if (mainCandidates.isEmpty()) {
            if (projectors.isNotEmpty()) {
                return GgufBundleValidationResult(
                    isValid = false,
                    errorMessage = "No main model files found. Please ensure at least one main GGUF model is present.",
                    structuredError = ModelOperationError(
                        reason = ModelErrorReason.ProjectorWithoutMain,
                        stage = ModelOperationStage.Inspection,
                        affectedFilenames = projectors.map { it.displayName },
                        diagnosticDetails = "Companion projector selected without main model"
                    )
                )
            }
            return GgufBundleValidationResult(
                isValid = false,
                errorMessage = "No main model files found. Please ensure at least one main GGUF model is present.",
                structuredError = ModelOperationError(
                    reason = ModelErrorReason.EmptyOrDamagedHeader,
                    stage = ModelOperationStage.Inspection,
                    diagnosticDetails = "No main model files found"
                )
            )
        }

        if (projectors.size > 1) {
            return GgufBundleValidationResult(
                isValid = false,
                errorMessage = "Multiple projector files detected (${projectors.map { it.displayName }.joinToString()}). At most one projector is supported per bundle.",
                structuredError = ModelOperationError(
                    reason = ModelErrorReason.UnrelatedModelsSelected,
                    stage = ModelOperationStage.Inspection,
                    affectedFilenames = projectors.map { it.displayName },
                    diagnosticDetails = "Multiple projector files detected"
                )
            )
        }

        // 3. Shard validation
        val orderedMain: List<GgufArtifactInspection>
        val isSplit = mainCandidates.any { it.splitCount > 1 || it.splitNo >= 0 }

        if (isSplit) {
            val splitCounts = mainCandidates.map { it.splitCount }.distinct()
            if (splitCounts.size != 1 || splitCounts.first() <= 0) {
                return GgufBundleValidationResult(
                    isValid = false,
                    errorMessage = "Split model shards have inconsistent or invalid split counts: $splitCounts.",
                    structuredError = ModelOperationError(
                        reason = ModelErrorReason.EmptyOrDamagedHeader,
                        stage = ModelOperationStage.Inspection,
                        affectedFilenames = mainCandidates.map { it.displayName },
                        diagnosticDetails = "Inconsistent split counts: $splitCounts"
                    )
                )
            }
            val expectedCount = splitCounts.first()
            if (mainCandidates.size != expectedCount) {
                val missingIndices = (0 until expectedCount).filter { idx -> mainCandidates.none { it.splitNo == idx } }
                return GgufBundleValidationResult(
                    isValid = false,
                    errorMessage = "Incomplete split model: found ${mainCandidates.size} parts, but metadata indicates $expectedCount shards.",
                    structuredError = ModelOperationError(
                        reason = ModelErrorReason.MissingShards,
                        stage = ModelOperationStage.Inspection,
                        affectedFilenames = mainCandidates.map { it.displayName },
                        foundCount = mainCandidates.size,
                        requiredCount = expectedCount,
                        missingParts = missingIndices,
                        diagnosticDetails = "Found ${mainCandidates.size} of $expectedCount shards"
                    )
                )
            }

            // Verify architecture consistency
            val distinctArchs = mainCandidates.map { it.architecture.lowercase(Locale.ROOT) }.filter { it.isNotBlank() }.distinct()
            if (distinctArchs.size > 1) {
                return GgufBundleValidationResult(
                    isValid = false,
                    errorMessage = "Split model shards have mismatched architectures: $distinctArchs.",
                    structuredError = ModelOperationError(
                        reason = ModelErrorReason.UnrelatedModelsSelected,
                        stage = ModelOperationStage.Inspection,
                        affectedFilenames = mainCandidates.map { it.displayName },
                        diagnosticDetails = "Split model shards have mismatched architectures: $distinctArchs"
                    )
                )
            }

            // Verify contiguous 0..N-1 indices
            val sorted = mainCandidates.sortedBy { it.splitNo }
            val missingIndices = (0 until expectedCount).filter { idx -> mainCandidates.none { it.splitNo == idx } }
            if (missingIndices.isNotEmpty()) {
                return GgufBundleValidationResult(
                    isValid = false,
                    errorMessage = "Split model shards must have contiguous indices from 0 to ${expectedCount - 1}. Missing shard index ${missingIndices.first()} (found ${mainCandidates.map { it.splitNo }}).",
                    structuredError = ModelOperationError(
                        reason = ModelErrorReason.MissingShards,
                        stage = ModelOperationStage.Inspection,
                        affectedFilenames = mainCandidates.map { it.displayName },
                        foundCount = mainCandidates.size,
                        requiredCount = expectedCount,
                        missingParts = missingIndices,
                        diagnosticDetails = "Missing shard index ${missingIndices.first()}"
                    )
                )
            }
            orderedMain = sorted
        } else {
            if (mainCandidates.size > 1) {
                return GgufBundleValidationResult(
                    isValid = false,
                    errorMessage = "Multiple distinct models provided without GGUF split metadata (${mainCandidates.map { it.displayName }.joinToString()}).",
                    structuredError = ModelOperationError(
                        reason = ModelErrorReason.UnrelatedModelsSelected,
                        stage = ModelOperationStage.Inspection,
                        affectedFilenames = mainCandidates.map { it.displayName },
                        diagnosticDetails = "Multiple distinct models provided without GGUF split metadata"
                    )
                )
            }
            orderedMain = mainCandidates
        }

        val projector = projectors.firstOrNull()

        // 4. Derive capabilities authoritatively from native inspection metadata.
        //    Media input is only usable when the bundle carries the companion encoder:
        //    a vision-capable architecture without an mmproj can still not consume images,
        //    so it must not advertise media support to the composer.
        val mainHasVision = orderedMain.any { it.hasVision }
        val mainHasAudio = orderedMain.any { it.hasAudio }
        val projectorHasVision = projector != null && projector.hasVision
        val projectorHasAudio = projector?.hasAudio == true
        val mainIsVisionArch = orderedMain.any { isVisionArchitecture(it.architecture) }

        val hasVision = when {
            // The projector itself reports a vision encoder.
            projectorHasVision -> true
            // Projector present but only audio-capable: it cannot feed images.
            projector != null -> false
            // No separate projector: vision is usable only if the main file embeds one.
            else -> mainHasVision
        }
        val isVisionProjectorMissing = !hasVision && mainIsVisionArch && projector == null
        val hasAudio = projectorHasAudio || mainHasAudio

        val rawThinkingCap = orderedMain.firstOrNull()?.thinkingCapability?.let { capStr ->
            com.shounak.localmeshai.models.ThinkingCapability.entries.firstOrNull { it.name.equals(capStr, ignoreCase = true) }
        } ?: com.shounak.localmeshai.models.ThinkingCapability.Unknown

        val mainThinkingCapability = rawThinkingCap

        val detectedContextLength = orderedMain.firstOrNull()?.trainContextLength ?: 0

        val capabilities = ModelCapabilities(
            supportsText = true,
            supportsVision = hasVision,
            supportsAudio = hasAudio,
            thinking = mainThinkingCapability,
            thinkingControl = if (mainThinkingCapability == ThinkingCapability.Toggleable) com.shounak.localmeshai.models.ThinkingControlStrategy.NativeTemplate else com.shounak.localmeshai.models.ThinkingControlStrategy.None,
            thinkingDetectorVersion = ThinkingModeConfig.DETECTOR_VERSION,
            thinkingArtifactIdentity = orderedMain.firstOrNull()?.location.orEmpty(),
            contextLength = detectedContextLength,
            visionProjectorMissing = isVisionProjectorMissing
        )

        val primaryName = orderedMain.first().name.ifBlank {
            orderedMain.first().displayName.substringBeforeLast('.').replace('-', ' ').replace('_', ' ')
        }

        return GgufBundleValidationResult(
            isValid = true,
            orderedMainArtifacts = orderedMain,
            projectorArtifact = projector,
            capabilities = capabilities,
            primaryName = primaryName,
            contextLength = detectedContextLength,
            errorMessage = null
        )
    }

    /**
     * Inspects and validates a list of local files on disk.
     */
    fun inspectAndValidateFiles(
        files: List<File>,
        expectedRoles: Map<String, ArtifactRole>? = null,
        expectedSizes: Map<String, Long>? = null,
        logicalNames: List<String>? = null
    ): GgufBundleValidationResult {
        val inspections = files.mapIndexed { index, file ->
            val logicalName = logicalNames?.getOrNull(index) ?: file.name.removeSuffix(".download")
            inspectArtifact(
                location = file.absolutePath,
                displayName = logicalName,
                sizeBytes = file.length()
            )
        }
        return validateBundle(inspections, expectedRoles, expectedSizes)
    }
}
