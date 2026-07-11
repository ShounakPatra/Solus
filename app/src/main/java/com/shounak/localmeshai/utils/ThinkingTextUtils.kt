package com.shounak.localmeshai.utils

data class ParsedThinkingContent(
    val thinkingText: String?,
    val finalResponseText: String,
    val isThinkingActive: Boolean
)

object ThinkingTextUtils {
    private val completeThinkBlockRegex =
        Regex("""<\s*think\s*>([\s\S]*?)</\s*think\s*>""", RegexOption.IGNORE_CASE)
    private val openThinkTagRegex =
        Regex("""<\s*think\s*>""", RegexOption.IGNORE_CASE)
    private val closeThinkTagRegex =
        Regex("""</\s*think\s*>""", RegexOption.IGNORE_CASE)

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

            val finalText = mergeVisibleText(before, tail)
            val thinkingText = completedThinking.joinThinkingSegments()
            return if (finalText.isBlank() && thinkingText.isNotBlank()) {
                ParsedThinkingContent(
                    thinkingText = null,
                    finalResponseText = thinkingText,
                    isThinkingActive = false
                )
            } else {
                ParsedThinkingContent(
                    thinkingText = thinkingText.takeIf { it.isNotBlank() },
                    finalResponseText = finalText,
                    isThinkingActive = false
                )
            }
        }

        val finalText = withoutCompleteBlocks
            .removeLooseThinkTags()
            .trimLineBreaks()
        val thinkingText = completedThinking.joinThinkingSegments()

        return if (finalText.isBlank() && thinkingText.isNotBlank()) {
            ParsedThinkingContent(
                thinkingText = null,
                finalResponseText = thinkingText,
                isThinkingActive = false
            )
        } else {
            ParsedThinkingContent(
                thinkingText = thinkingText.takeIf { it.isNotBlank() },
                finalResponseText = finalText,
                isThinkingActive = false
            )
        }
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

        return parse(cleaned, allowActiveThinking = false)
            .finalResponseText
            .trimLineBreaks()
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
