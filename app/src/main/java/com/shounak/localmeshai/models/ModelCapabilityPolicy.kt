package com.shounak.localmeshai.models

import androidx.compose.runtime.Immutable

/**
 * Single source of truth for what a selected model can actually consume.
 *
 * Imported models (.task, .litertlm, .gguf) carry authoritative capabilities that were
 * derived by inspecting the container at import time. For those models the manifest is
 * the only input: a missing vision/audio encoder means media input must stay disabled,
 * even if the model's declared [ModelType] is [ModelType.Vision] (that field is a display
 * hint and is never allowed to widen real capabilities).
 *
 * Catalog models have no bundled manifest, so their declared flags are the best
 * available answer. Document attachments (RAG) remain available for every chat model
 * because they never touch the vision pipeline.
 */
object ModelCapabilityPolicy {

    @Immutable
    data class AttachmentSupport(
        val vision: Boolean,
        val audio: Boolean,
        val documents: Boolean = true
    ) {
        /** True when the model can consume camera/photos or audio-file input. */
        val anyMedia: Boolean get() = vision || audio

        /** True when the attach control should be reachable at all. */
        val any: Boolean get() = anyMedia || documents
    }

    fun attachments(model: ModelInfo?): AttachmentSupport {
        if (model == null) {
            return AttachmentSupport(vision = false, audio = false, documents = false)
        }
        val hasInspectedManifest = model.bundle != null || model.downloadSpec != null
        return if (hasInspectedManifest) {
            AttachmentSupport(
                vision = model.supportsVision,
                audio = model.supportsAudio,
                documents = true
            )
        } else {
            AttachmentSupport(
                vision = model.supportsVisionInput || model.type == ModelType.Vision,
                audio = model.supportsAudioInput,
                documents = true
            )
        }
    }

    /**
     * Accurately explains why image input is disabled.
     */
    fun visionDisabledReason(
        model: ModelInfo?,
        isChecking: Boolean = false
    ): String? {
        if (model == null) return "Select a model"
        if (isChecking) return "Checking capabilities"
        if (model.bundle?.capabilities?.visionProjectorMissing == true) {
            return "Vision projector missing"
        }
        val support = attachments(model)
        if (!support.vision) {
            return "Image input unsupported"
        }
        return null
    }

    /**
     * Canonicalizes imported-model capabilities without name-based heuristic promotions.
     */
    fun canonicalizeImportedCapabilities(
        capabilities: ModelCapabilities,
        modelName: String = "",
        modelPath: String = ""
    ): ModelCapabilities {
        return capabilities
    }
}
