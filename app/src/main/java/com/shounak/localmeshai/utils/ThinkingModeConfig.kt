package com.shounak.localmeshai.utils

/** Request-scoped chat-template controls for models with native thinking modes. */
object ThinkingModeConfig {
    const val ENABLE_THINKING_KEY = "enable_thinking"

    fun supportsTextNoThinkingSwitch(
        modelPath: String = "",
        effectiveId: String = "",
        modelName: String = ""
    ): Boolean {
        val search = "$modelPath|$effectiveId|$modelName".lowercase()
        return listOf("qwen3", "qwen 3", "qwen-3", "qwen_3").any { search.contains(it) }
    }

    fun isThinkingSupported(
        modelPath: String = "",
        effectiveId: String = "",
        modelName: String = "",
        supportsThinkingFlag: Boolean = false
    ): Boolean {
        if (supportsThinkingFlag) return true
        val search = "$modelPath|$effectiveId|$modelName".lowercase()
        return listOf(
            "qwen3", "qwen 3", "deepseek", "reasoning", "mimo", "exaone",
            "gemma4", "gemma 4", "gemma-4"
        ).any { search.contains(it) }
    }

    fun liteRtExtraContext(
        isDefaultThinkingModel: Boolean,
        thinkingMode: Boolean
    ): Map<String, Any> {
        return if (isDefaultThinkingModel) {
            mapOf(ENABLE_THINKING_KEY to thinkingMode)
        } else {
            emptyMap()
        }
    }
}
