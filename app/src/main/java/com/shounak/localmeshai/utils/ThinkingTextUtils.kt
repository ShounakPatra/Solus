package com.shounak.localmeshai.utils

data class ParsedThinkingContent(
    val thinkingText: String?,
    val finalResponseText: String,
    val isThinkingActive: Boolean
)

object ThinkingTextUtils {
    private val completeThinkBlockRegex =
        Regex("""<\s*(?:think|thought|reasoning)\s*>([\s\S]*?)</\s*(?:think|thought|reasoning)\s*>""", RegexOption.IGNORE_CASE)
    private val openThinkTagRegex =
        Regex("""<\s*(?:think|thought|reasoning)\s*>""", RegexOption.IGNORE_CASE)
    private val closeThinkTagRegex =
        Regex("""</\s*(?:think|thought|reasoning)\s*>""", RegexOption.IGNORE_CASE)
    private val unclosedThinkTagRegex =
        Regex("""<\s*(?:think|thought|reasoning)\s*>[\s\S]*$""", RegexOption.IGNORE_CASE)

    fun parse(text: String, allowActiveThinking: Boolean): ParsedThinkingContent {
        val completedThinking = completeThinkBlockRegex
            .findAll(text)
            .mapNotNull { match ->
                match.groups[1]?.value?.trimLineBreaks()?.takeIf { it.isNotBlank() }
            }
            .toMutableList()

        val withoutCompleteBlocks = completeThinkBlockRegex.replace(text, "\n")
        val openMatch = openThinkTagRegex.find(withoutCompleteBlocks)

        if (openMatch != null) {
            val before = withoutCompleteBlocks
                .substring(0, openMatch.range.first)
                .removeLooseThinkTags()
                .trimLineBreaks()
            val tail = withoutCompleteBlocks
                .substring(openMatch.range.last + 1)
                .removeLooseThinkTags()
                .trimLineBreaks()

            if (allowActiveThinking && tail.isNotBlank()) {
                val activeThinking = (completedThinking + tail).joinThinkingSegments()
                return ParsedThinkingContent(
                    thinkingText = activeThinking.takeIf { it.isNotBlank() },
                    finalResponseText = before,
                    isThinkingActive = activeThinking.isNotBlank()
                )
            }

            val thinkingText = (completedThinking + tail).joinThinkingSegments()
            return ParsedThinkingContent(
                thinkingText = thinkingText.takeIf { it.isNotBlank() },
                finalResponseText = before,
                isThinkingActive = false
            )
        }

        val finalText = withoutCompleteBlocks
            .removeLooseThinkTags()
            .trimLineBreaks()
        val thinkingText = completedThinking.joinThinkingSegments()

        return ParsedThinkingContent(
            thinkingText = thinkingText.takeIf { it.isNotBlank() },
            finalResponseText = finalText,
            isThinkingActive = false
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
     * Returns the final answer while hiding reasoning when possible. Some
     * templates prefill the opening <think> tag, so generated text can contain
     * only a closing tag. If generation stops before any final answer exists,
     * preserve the reasoning text as a last-resort visible response instead of
     * turning a non-empty generation into the app's empty-response fallback.
     */
    fun finalResponseOrReasoning(text: String): String {
        val cleaned = ModelOutputSanitizer.clean(text)
        val firstClose = closeThinkTagRegex.find(cleaned)
        val firstOpen = openThinkTagRegex.find(cleaned)

        if (firstClose != null && (firstOpen == null || firstOpen.range.first > firstClose.range.first)) {
            val beforeClose = cleaned
                .substring(0, firstClose.range.first)
                .removeLooseThinkTags()
                .trimLineBreaks()
            val afterClose = cleaned
                .substring(firstClose.range.last + 1)
                .removeLooseThinkTags()
                .trimLineBreaks()
            return afterClose.ifBlank { beforeClose }
        }

        val parsed = parse(cleaned, allowActiveThinking = false)
        return parsed.finalResponseText.ifBlank { parsed.thinkingText.orEmpty() }.trimLineBreaks()
    }

    /**
     * Extracts ONLY the final answer outside of thinking tags.
     * When thinking mode is disabled, reasoning tokens must NEVER be returned as the final answer.
     * Also strips untagged reasoning monologue or extracts the spoken answer from within it.
     * Returns an empty string if generation only produced thinking tokens without an answer,
     * allowing the inference retry loop to fire.
     */
    fun extractFinalAnswerOnly(text: String): String {
        val cleaned = ModelOutputSanitizer.clean(text)
        val firstClose = closeThinkTagRegex.find(cleaned)
        val firstOpen = openThinkTagRegex.find(cleaned)

        val answerAfterTags = if (firstClose != null && (firstOpen == null || firstOpen.range.first > firstClose.range.first)) {
            val after = cleaned.substring(firstClose.range.last + 1)
            val stripped = unclosedThinkTagRegex.replace(completeThinkBlockRegex.replace(after, ""), "")
            stripped.removeLooseThinkTags().trimLineBreaks()
        } else {
            val strippedComplete = completeThinkBlockRegex.replace(cleaned, "")
            val strippedUnclosed = unclosedThinkTagRegex.replace(strippedComplete, "")
            strippedUnclosed.removeLooseThinkTags().trimLineBreaks()
        }

        if (answerAfterTags.isBlank()) {
            return ""
        }

        if (ModelResponseQuality.isReasoningLeak(answerAfterTags)) {
            val extracted = extractSpokenAnswerFromMonologue(answerAfterTags)
            return if (extracted != null && extracted.isNotBlank() && !ModelResponseQuality.isReasoningLeak(extracted)) {
                extracted
            } else {
                ""
            }
        }

        return answerAfterTags
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

    private fun String.removeLooseThinkTags(): String =
        closeThinkTagRegex.replace(openThinkTagRegex.replace(this, ""), "")

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
