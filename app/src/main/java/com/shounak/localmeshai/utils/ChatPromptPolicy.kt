package com.shounak.localmeshai.utils

/** Prompt selection for runtimes whose task bundle already owns the chat template. */
internal object ChatPromptPolicy {
    fun mediaPipeBasePrompt(
        fullHistoryPrompt: String,
        rawUserText: String,
        useNativeGemmaTaskTemplate: Boolean,
        memoryContext: String = ""
    ): String = if (useNativeGemmaTaskTemplate) {
        val memoryPrefix = if (memoryContext.isNotBlank()) "${memoryContext.trim()}\n\n" else ""
        "$memoryPrefix${rawUserText.trim()}"
    } else {
        fullHistoryPrompt
    }

    fun nativeGemmaRetryPrompt(rawUserText: String, memoryContext: String = ""): String = buildString {
        append("Answer the request directly and briefly. ")
        append("Do not repeat the request and do not print role names.\n\n")
        if (memoryContext.isNotBlank()) {
            append(memoryContext.trim())
            append("\n\n")
        }
        append(rawUserText.trim())
    }
}
