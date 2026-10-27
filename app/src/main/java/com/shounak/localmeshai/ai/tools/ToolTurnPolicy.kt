package com.shounak.localmeshai.ai.tools

/** Only skip schemas when the entire latest user message is a greeting. */
internal object ToolTurnPolicy {
    private val greetings = setOf("hi", "hello", "hey", "hello there", "good morning", "good afternoon", "good evening")

    fun needsTools(messages: List<Pair<String, String>>): Boolean {
        val latest = messages.lastOrNull { it.first == "user" }?.second ?: return true
        return latest.trim().trimEnd('!', '.', '?').trim().lowercase(java.util.Locale.ROOT) !in greetings
    }
}
