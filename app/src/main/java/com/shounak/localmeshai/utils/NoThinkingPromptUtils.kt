package com.shounak.localmeshai.utils

object NoThinkingPromptUtils {
    const val SWITCH = "/no_think"

    /**
     * MediaPipe does not expose chat-template context variables, so older Qwen 3
     * task bundles need the textual switch. Put it only once, at the end of the
     * latest user turn; repeating it in the system message and around the prompt
     * can produce an empty assistant turn.
     */
    fun wrap(prompt: String): String {
        val trimmedPrompt = prompt.trim()
        if (trimmedPrompt.isBlank()) return SWITCH
        if (trimmedPrompt.lineSequence().any { it.trim() == SWITCH }) return trimmedPrompt

        // ChatViewModel's stateless history prompt ends in `Assistant:`. The Qwen
        // soft switch belongs to the latest user turn, not the assistant prefill.
        return if (trimmedPrompt.endsWith(ASSISTANT_MARKER)) {
            val beforeAssistant = trimmedPrompt
                .removeSuffix(ASSISTANT_MARKER)
                .trimEnd()
            "$beforeAssistant\n$SWITCH\n$ASSISTANT_MARKER"
        } else {
            "$trimmedPrompt\n\n$SWITCH"
        }
    }

    private const val ASSISTANT_MARKER = "Assistant:"
}
