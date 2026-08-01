package com.shounak.localmeshai.utils

internal object ChatRuntimePolicy {
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
}

