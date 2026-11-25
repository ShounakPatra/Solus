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

    fun mediaPipePrompt(
        fullHistoryPrompt: String,
        rawUserText: String,
        useNativeGemmaTaskTemplate: Boolean,
        memoryContext: String = "",
        thinkingMode: Boolean,
        hasNativeThinkingMode: Boolean
    ): String {
        val base = mediaPipeBasePrompt(
            fullHistoryPrompt = fullHistoryPrompt,
            rawUserText = rawUserText,
            useNativeGemmaTaskTemplate = useNativeGemmaTaskTemplate,
            memoryContext = memoryContext
        )
        return when {
            !thinkingMode && hasNativeThinkingMode ->
                "Skip analysis and reasoning. Do not emit <think> tags. " +
                    "Start the final answer immediately.\n\n$base"
            thinkingMode && !hasNativeThinkingMode ->
                "Reason carefully before giving the final answer.\n\n$base"
            else -> base
        }
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
