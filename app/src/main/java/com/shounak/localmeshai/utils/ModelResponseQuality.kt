package com.shounak.localmeshai.utils

import java.util.Locale

object ModelResponseQuality {
    fun isGenericNonAnswer(response: String, userText: String = ""): Boolean {
        val normalized = response.trim().lowercase(Locale.US)
        if (normalized.isBlank()) return false

        val request = userText.trim().lowercase(Locale.US)

        // Memory statements and user instructions are not questions seeking factual answers.
        // Confirming them with an acknowledgment is the correct and expected behavior.
        if (isMemoryOrInstruction(request)) {
            return false
        }

        // Any response confirming memory retention, recording, or noting is valid.
        if (normalized.contains("remember") ||
            normalized.contains("noted") ||
            normalized.contains("keep that in mind") ||
            normalized.contains("keep in mind") ||
            normalized.contains("got it")
        ) {
            return false
        }

        if (request in setOf("hi", "hello", "hey", "hii", "yo")) {
            return normalized.contains("okay, i understand") ||
                normalized.contains("i will do my best to answer your request")
        }

        if (normalized in GenericNonAnswerExact) {
            return true
        }

        return GenericNonAnswerMarkers.any { marker ->
            if (marker == "okay, i understand" || marker == "ok, i understand") {
                normalized.startsWith(marker) && (normalized.length < 35 || isOnlyBoilerplate(normalized))
            } else {
                normalized.contains(marker)
            }
        }
    }

    fun isMemoryOrInstruction(request: String): Boolean {
        if (request.isBlank()) return false
        val lower = request.lowercase(Locale.ROOT)
        val triggers = listOf(
            "remember",
            "keep in mind",
            "bear in mind",
            "take note",
            "note that",
            "note:",
            "save this",
            "store this",
            "don't forget",
            "dont forget",
            "my name is",
            "my dog",
            "my cat",
            "call me",
            "i live in",
            "i prefer",
            "always ",
            "never ",
            "from now on"
        )
        return triggers.any { lower.contains(it) }
    }

    private fun isOnlyBoilerplate(normalized: String): Boolean {
        val boilerplateMarkers = listOf(
            "i will do my best to answer",
            "i will wait for your request",
            "please feel free to ask",
            "how can i help",
            "how may i help",
            "what can i do for you",
            "provide the final answer",
            "final answer"
        )
        val stripped = normalized.replace("okay, i understand", "")
            .replace("ok, i understand", "")
            .trim('.', '!', '?', ',', ' ')
        return stripped.isBlank() || boilerplateMarkers.any { stripped.contains(it) }
    }

    fun shouldSuppressLivePartial(response: String): Boolean {
        val normalized = response.trim().lowercase(Locale.US)
        if (normalized.length < 4) return false
        return LiveSuppressionMarkers.any { marker ->
            marker.startsWith(normalized) || normalized.startsWith(marker)
        }
    }

    private val GenericNonAnswerExact = setOf(
        "okay, i understand.",
        "okay, i understand",
        "the answer is the final answer.",
        "the answer is the final answer"
    )

    private val GenericNonAnswerMarkers = listOf(
        "i could not generate a response",
        "this model returned a generic non-answer",
        "okay, i understand",
        "ok, i understand",
        "i will do my best to answer your request",
        "i will wait for your request",
        "i am unable to provide information on the topic",
        "i cannot provide information on the topic",
        "please feel free to ask anything",
        "please feel free to ask me anything",
        "the answer is the final answer",
        "how can i help you today",
        "how can i assist you today",
        "how may i help you",
        "what can i do for you"
    )

    private val LiveSuppressionMarkers = listOf(
        "okay, i understand",
        "ok, i understand",
        "i will wait for your request",
        "i am unable to provide information on the topic",
        "i cannot provide information on the topic",
        "the answer is the final answer"
    )
}
