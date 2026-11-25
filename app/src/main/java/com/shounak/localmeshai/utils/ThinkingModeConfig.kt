package com.shounak.localmeshai.utils

/**
 * Passive thinking configuration shared across backends and the composer UI.
 * All legacy per-model and per-format thinking-detection heuristics have been removed:
 * the user's thinking preference is passed passively to every backend, and template
 * visibility is gated only by a single template-marker check when a `chat_template`
 * string is available.
 */
object ThinkingModeConfig {
    const val ENABLE_THINKING_KEY = "enable_thinking"
    const val CONFIG_VERSION = 10

    private val templateThinkingMarkers = listOf(
        "enable_thinking",
        "<think>",
        "<|channel>thought",
        "<|thought|>"
    )

    fun liteRtExtraContext(thinkingEnabled: Boolean): Map<String, Any> =
        mapOf(ENABLE_THINKING_KEY to thinkingEnabled)

    /**
     * Returns true when the composer thinking toggle should be actively shown.
     * When a [chatTemplate] string is available and contains none of the standard
     * thinking markers, returns false so the button can be dimmed/hidden. When no
     * template string is exposed, leaves the toggle available as a passive no-op.
     */
    fun isToggleAvailableForTemplate(chatTemplate: String?): Boolean =
        chatTemplate.isNullOrBlank() || templateThinkingMarkers.any { chatTemplate.contains(it, ignoreCase = true) }
}
