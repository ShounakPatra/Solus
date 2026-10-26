package com.shounak.localmeshai.ai

import android.util.Log

/**
 * Debug breakdown of first-token latency: engine creation, warm-up, conversation create/reset,
 * request preparation and send→first token. Contains no prompt or response text.
 *
 * On device: `adb logcat -s SolusTTFT`
 */
internal object TtftLog {
    const val TAG = "SolusTTFT"

    fun now(): Long = System.nanoTime()

    fun elapsedMs(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

    fun d(message: String) {
        Log.d(TAG, message)
    }
}

/**
 * Whether the live LiteRT-LM conversation can serve the next turn instead of being closed and
 * rebuilt. A conversation that has never been sent a message, whose thinking context and tool
 * settings already match the request, is the same conversation `resetConversation` would build
 * when there is no history to restore, so rebuilding it is pure overhead (and discards any preface
 * prefilled during load).
 */
internal object LiteRtConversationReuse {
    fun canSkipReset(
        conversationAlive: Boolean,
        conversationUsed: Boolean,
        restorationHistoryEmpty: Boolean,
        thinkingMatches: Boolean,
        toolSettingsCurrent: Boolean
    ): Boolean = conversationAlive && !conversationUsed && restorationHistoryEmpty && thinkingMatches && toolSettingsCurrent
}
