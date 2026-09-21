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
        return search.contains("qwen3")
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
