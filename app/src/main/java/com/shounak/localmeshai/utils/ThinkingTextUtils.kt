package com.shounak.localmeshai.utils

data class ParsedThinkingContent(
    val thinkingText: String?,
    val finalResponseText: String,
    val isThinkingActive: Boolean
)

object ThinkingTextUtils {
    /** Hold a possible control-tag suffix until the next delta disambiguates it. */
    fun holdIncompleteTag(text: String): String {
        val lastAngle = text.lastIndexOf('<')
        val lastBracket = text.lastIndexOf('[')
        val start = maxOf(lastAngle, lastBracket)
        if (start < 0) return text
        val suffix = text.substring(start).lowercase()
        val tags = listOf(
            "<think>", "</think>", "<thought>", "</thought>", "<reasoning>", "</reasoning>",
            "[think]", "[/think]", "[thought]", "[/thought]", "[reasoning]", "[/reasoning]"
        )
        return if (tags.any { it.startsWith(suffix) && it != suffix }) text.substring(0, start) else text
    }

    private val openThinkTagRegex =
        Regex("""<\s*(?:think|thought|reasoning)\s*>|\[\s*(?:think|thought|reasoning)\s*\]""", RegexOption.IGNORE_CASE)
    private val closeThinkTagRegex =
        Regex("""<\s*/\s*(?:think|thought|reasoning)\s*>|\[\s*/\s*(?:think|thought|reasoning)\s*\]""", RegexOption.IGNORE_CASE)
    private val combinedTagRegex =
        Regex("""(<\s*(?:think|thought|reasoning)\s*>|\[\s*(?:think|thought|reasoning)\s*\])|(<\s*/\s*(?:think|thought|reasoning)\s*>|\[\s*/\s*(?:think|thought|reasoning)\s*\])""", RegexOption.IGNORE_CASE)

    fun parse(text: String, allowActiveThinking: Boolean): ParsedThinkingContent {
        if (text.isBlank()) return ParsedThinkingContent(null, "", false)

        val firstClose = closeThinkTagRegex.find(text)
        val firstOpen = openThinkTagRegex.find(text)
        val effectiveText = if (firstClose != null && (firstOpen == null || firstOpen.range.first > firstClose.range.first)) {
            "<think>" + text
        } else {
            text
        }

        val thinkingBuilder = StringBuilder()
        val answerBuilder = StringBuilder()
        var depth = 0
        var currentIndex = 0
        val length = effectiveText.length

        for (match in combinedTagRegex.findAll(effectiveText)) {
            val segment = effectiveText.substring(currentIndex, match.range.first)
            if (segment.isNotEmpty()) {
                if (depth > 0) {
                    thinkingBuilder.append(segment)
                } else {
                    answerBuilder.append(segment)
                }
            }
            val isOpen = match.groups[1] != null
            if (isOpen) {
                depth++
            } else {
                if (depth > 0) depth--
            }
            currentIndex = match.range.last + 1
        }

        if (currentIndex < length) {
            val remaining = effectiveText.substring(currentIndex)
            if (depth > 0) {
                thinkingBuilder.append(remaining)
            } else {
                answerBuilder.append(remaining)
            }
        }

        val rawThinking = thinkingBuilder.toString().trimLineBreaks()
        val rawAnswer = answerBuilder.toString().trimLineBreaks()
        val isThinkingActive = allowActiveThinking && (depth > 0)

        return ParsedThinkingContent(
            thinkingText = rawThinking.takeIf { it.isNotBlank() },
            finalResponseText = rawAnswer,
            isThinkingActive = isThinkingActive
        )
    }

    fun normalizeFinalOutput(text: String): String {
        val parsed = parse(ModelOutputSanitizer.clean(text), allowActiveThinking = false)
        val thinkingText = parsed.thinkingText?.trimLineBreaks()
        val finalText = parsed.finalResponseText.trimLineBreaks()

        return if (thinkingText.isNullOrBlank()) {
            finalText
        } else {
            buildString {
                append("<think>\n")
                append(thinkingText)
                append("\n</think>")
                if (finalText.isNotBlank()) {
                    append("\n\n")
                    append(finalText)
                }
            }.trimLineBreaks()
        }
    }

    /**
     * Returns the final answer outside of thinking tags.
     * When allowReasoningFallback is false (e.g. Think off), reasoning is never exposed as a substitute final answer.
     */
    fun finalResponseOrReasoning(text: String, allowReasoningFallback: Boolean = false): String {
        val cleaned = ModelOutputSanitizer.clean(text)
        val parsed = parse(cleaned, allowActiveThinking = false)
        return if (parsed.finalResponseText.isNotBlank()) {
            parsed.finalResponseText
        } else if (allowReasoningFallback) {
            parsed.thinkingText.orEmpty()
        } else {
            ""
        }
    }

    /**
     * Extracts ONLY the final answer outside of thinking tags.
     * When thinking mode is disabled, reasoning tokens must NEVER be returned as the final answer.
     * Strips explicit thinking tags and handles untagged reasoning monologues without deleting valid answers.
     */
    fun extractFinalAnswerOnly(text: String): String {
        val cleaned = ModelOutputSanitizer.clean(text)
        val parsed = parse(cleaned, allowActiveThinking = false)
        val answer = parsed.finalResponseText.trim()

        if (answer.isBlank()) {
            return ""
        }

        if (ModelResponseQuality.isReasoningLeak(answer)) {
            val extracted = extractSpokenAnswerFromMonologue(answer)
            return if (extracted != null && extracted.isNotBlank() && !ModelResponseQuality.isReasoningLeak(extracted)) {
                extracted
            } else {
                ""
            }
        }

        return answer
    }

    fun extractSpokenAnswerFromMonologue(text: String): String? {
        val quoteRegex = Regex(
            """(?:maybe say something like|maybe say|i could say|i should say|i will say|i'll say|say something like|respond with|say)[,:]?\s*["“]([^"”]+)["”]?""",
            RegexOption.IGNORE_CASE
        )
        val quoteMatch = quoteRegex.findAll(text).lastOrNull()
        if (quoteMatch != null) {
            val candidate = quoteMatch.groupValues[1].trim()
            val danglingRegex = Regex("""\b(and|or|with|the|a|an|to|of|in|that|because)\s*$""", RegexOption.IGNORE_CASE)
            if (candidate.isNotBlank() && !danglingRegex.containsMatchIn(candidate)) {
                return candidate
            }
        }

        val finalAnswerRegex = Regex(
            """(?:final answer|response):\s*([^\n]+)""",
            RegexOption.IGNORE_CASE
        )
        val finalMatch = finalAnswerRegex.findAll(text).lastOrNull()
        if (finalMatch != null) {
            val candidate = finalMatch.groupValues[1].trim().removeSurrounding("\"", "\"")
            if (candidate.isNotBlank()) {
                return candidate
            }
        }

        return null
    }

    /**
     * Conservatively removes a leading serialized thinking envelope ONLY when its inner contents
     * exactly match the separately stored reasoningText. Preserves unrelated literal tags,
     * code blocks, and user messages.
     */
    fun stripDuplicateLeadingThinkingEnvelope(text: String, reasoningText: String?): String {
        if (reasoningText.isNullOrBlank() || text.isBlank()) return text
        val trimmed = text.trimStart()
        val cleanReasoning = reasoningText.trim()

        val openMatch = openThinkTagRegex.find(trimmed)
        if (openMatch != null && openMatch.range.first == 0) {
            val closeMatch = closeThinkTagRegex.find(trimmed, openMatch.range.last + 1)
            if (closeMatch != null) {
                val inner = trimmed.substring(openMatch.range.last + 1, closeMatch.range.first).trim()
                if (inner == cleanReasoning) {
                    return trimmed.substring(closeMatch.range.last + 1).trimStart()
                }
            }
        }
        return text
    }

    fun removeLooseThinkTags(text: String): String =
        closeThinkTagRegex.replace(openThinkTagRegex.replace(text, ""), "")

    private fun String.trimLineBreaks(): String =
        trim('\n', '\r')

    private fun List<String>.joinThinkingSegments(): String =
        filter { it.isNotBlank() }
            .joinToString("\n\n") { it.trimLineBreaks() }
            .trimLineBreaks()

    private fun mergeVisibleText(first: String, second: String): String =
        listOf(first, second)
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .trimLineBreaks()
}
