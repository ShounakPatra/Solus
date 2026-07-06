package com.shounak.localmeshai.utils

object NoThinkingPromptUtils {
    const val SWITCH = "/no_think"

    val systemMessage: String =
        SWITCH

    fun wrap(prompt: String): String {
        val trimmedPrompt = prompt.trim()
        return buildString {
            append(SWITCH)
            if (trimmedPrompt.isNotBlank()) {
                append("\n\n")
                append(trimmedPrompt)
            }
            append("\n\n")
            append(SWITCH)
        }
    }
}
