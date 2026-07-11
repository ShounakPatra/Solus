package com.shounak.localmeshai.utils

/** Request-scoped chat-template controls for models with native thinking modes. */
object ThinkingModeConfig {
    const val ENABLE_THINKING_KEY = "enable_thinking"

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
