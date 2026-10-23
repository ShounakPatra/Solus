package com.shounak.localmeshai.ai.tools

import org.json.JSONObject

/** Streams only parsed content, never the raw token stream containing tool arguments. */
internal class GgufToolTurnStream(
    private val publish: (String, String, Boolean) -> Unit,
    private val onText: (String) -> Unit
) {
    private var last = Triple("", "", false)

    fun update(answer: String, reasoning: String, thinking: Boolean) {
        val snapshot = Triple(answer, reasoning, thinking)
        if (snapshot == last) return
        val answerDelta = answer.removePrefix(last.first)
        val reasoningDelta = reasoning.removePrefix(last.second)
        if (answerDelta.isNotEmpty()) onText(answerDelta)
        if (reasoningDelta.isNotEmpty()) onText(reasoningDelta)
        last = snapshot
        publish(answer, reasoning, thinking)
    }

    fun complete(response: JSONObject) {
        if ((response.optJSONArray("tool_calls")?.length() ?: 0) > 0) {
            // An assistant preamble belongs to this tool round, not the final answer.
            clear()
        } else {
            update(response.optString("content", ""), response.optString("reasoning_content", ""), false)
        }
    }

    fun clear() {
        last = Triple("", "", false)
        publish("", "", false)
    }
}
