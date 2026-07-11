package com.shounak.localmeshai.utils

internal object ChatRuntimePolicy {
    fun shouldUseLiteRtLmConversation(fileName: String, modelIdentity: String): Boolean {
        return fileName.endsWith(".litertlm", ignoreCase = true) ||
            (fileName.endsWith(".task", ignoreCase = true) &&
                modelIdentity.contains("gemma", ignoreCase = true))
    }
}
