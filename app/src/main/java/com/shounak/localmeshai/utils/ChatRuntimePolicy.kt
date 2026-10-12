package com.shounak.localmeshai.utils

internal object ChatRuntimePolicy {
    fun shouldRetrieveDocuments(enabled: Boolean, attachedDocumentId: String?, indexedChunks: Int): Boolean =
        indexedChunks > 0 && (enabled || attachedDocumentId != null)

    fun shouldUseLiteRtLmConversation(fileName: String, modelIdentity: String): Boolean {
        return fileName.endsWith(".litertlm", ignoreCase = true) ||
            (fileName.endsWith(".task", ignoreCase = true) &&
                modelIdentity.contains("gemma", ignoreCase = true))
    }

    fun isGgufModel(fileName: String): Boolean {
        return fileName.endsWith(".gguf", ignoreCase = true)
    }

    fun isSafetensorsModel(fileName: String): Boolean {
        return fileName.endsWith(".safetensors", ignoreCase = true)
    }

    fun supportsAttachments(modelPathOrName: String): Boolean {
        val lower = modelPathOrName.lowercase()
        return lower.endsWith(".task") || lower.contains("vision") || lower.contains("multimodal") || lower.contains("clip") || lower.contains("llava")
    }
}

