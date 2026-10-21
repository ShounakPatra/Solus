package com.shounak.localmeshai.features

import kotlin.math.ceil

data class ContextBudget(val estimatedTokens: Int, val limit: Int, val omittedMessages: Int) {
    val warning get() = estimatedTokens >= limit * 0.8
}

/** This is deliberately labelled an estimate: tokenizers differ between model families. */
object ContextBudgets {
    fun estimate(text: String): Int = ceil(text.codePointCount(0, text.length) / 3.0).toInt().coerceAtLeast(1)
    fun summarize(messages: List<String>, draft: String, system: String, limit: Int): ContextBudget {
        val retained = messages.takeLast(12)
        return ContextBudget(estimate((listOf(system) + messages + draft).joinToString("\n")),
            limit.coerceAtLeast(512), (messages.size - retained.size).coerceAtLeast(0))
    }
}
