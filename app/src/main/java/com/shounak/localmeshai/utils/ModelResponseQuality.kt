package com.shounak.localmeshai.utils

import java.util.Locale

object ModelResponseQuality {
    fun isGenericNonAnswer(response: String, userText: String = ""): Boolean {
        val normalized = response.trim().lowercase(Locale.US)
        if (normalized.isBlank()) return false

        val request = userText.trim().lowercase(Locale.US)
        if (request in setOf("hi", "hello", "hey", "hii", "yo")) {
            return normalized.contains("okay, i understand") ||
                normalized.contains("i will do my best to answer your request")
        }

        return GenericNonAnswerMarkers.any { marker -> normalized.contains(marker) } ||
            normalized in GenericNonAnswerExact
    }

    private val GenericNonAnswerExact = setOf(
        "okay, i understand.",
        "okay, i understand",
        "the answer is the final answer.",
        "the answer is the final answer"
    )

    private val GenericNonAnswerMarkers = listOf(
        "okay, i understand",
        "ok, i understand",
        "i will do my best to answer your request",
        "please feel free to ask anything",
        "please feel free to ask me anything",
        "the answer is the final answer",
        "how can i help you today",
        "how can i assist you today",
        "how may i help you",
        "what can i do for you"
    )
}
