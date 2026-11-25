package com.shounak.localmeshai.utils

/** Prompt formatting for stateless completion runtimes without forced thinking instructions. */
internal object ChatPromptPolicy {
    fun mediaPipeBasePrompt(
        fullHistoryPrompt: String,
        rawUserText: String,
        memoryContext: String = ""
    ): String {
        if (fullHistoryPrompt.isNotBlank()) return fullHistoryPrompt
        val memoryPrefix = if (memoryContext.isNotBlank()) "${memoryContext.trim()}\n\n" else ""
        return "$memoryPrefix${rawUserText.trim()}"
    }
}
