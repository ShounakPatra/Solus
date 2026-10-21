package com.shounak.localmeshai.utils

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * Parses, sanitizes, and formats release notes from GitHub releases.
 *
 * Strips raw HTML wrappers (div, center, p, a, img badges, tables) and cleans
 * markdown tags so that release highlights and changelogs are rendered cleanly
 * in Jetpack Compose UI without raw markup.
 */
object UpdateNotesFormatter {

    data class ParsedReleaseNotes(
        val versionTitle: String?,
        val subtitle: String?,
        val highlights: List<String>,
        val cleanSummary: String,
        val fullChangelog: String
    )

    private val HTML_ENTITIES = mapOf(
        "&middot;" to "·",
        "&bull;" to "•",
        "&amp;" to "&",
        "&lt;" to "<",
        "&gt;" to ">",
        "&quot;" to "\"",
        "&#39;" to "'",
        "&apos;" to "'",
        "&nbsp;" to " ",
        "&copy;" to "©",
        "&reg;" to "®",
        "&trade;" to "™",
        "&mdash;" to "—",
        "&ndash;" to "–"
    )

    fun decodeHtmlEntities(input: String): String {
        var text = input
        HTML_ENTITIES.forEach { (entity, replacement) ->
            text = text.replace(entity, replacement, ignoreCase = true)
        }
        text = Regex("""&#(\d+);""").replace(text) { match ->
            val code = match.groupValues[1].toIntOrNull()
            if (code != null) code.toChar().toString() else match.value
        }
        text = Regex("""&#x([0-9a-fA-F]+);""").replace(text) { match ->
            val code = match.groupValues[1].toIntOrNull(16)
            if (code != null) code.toChar().toString() else match.value
        }
        return text
    }

    /**
     * Strips all remaining HTML tags `<...>` while keeping their inner text.
     */
    fun stripHtmlTags(input: String): String {
        return input.replace(Regex("""<[^>]+>"""), "").trim()
    }

    /**
     * Parses raw GitHub release notes (HTML and/or Markdown) into structured,
     * human-readable presentation models.
     */
    fun parse(rawNotes: String): ParsedReleaseNotes {
        if (rawNotes.isBlank()) {
            return ParsedReleaseNotes(
                versionTitle = null,
                subtitle = null,
                highlights = emptyList(),
                cleanSummary = "No release notes provided.",
                fullChangelog = "No release notes provided."
            )
        }

        val decoded = decodeHtmlEntities(rawNotes)

        // 1. Extract subtitle if present in <h3> tags or early heading
        val subtitle = extractSubtitle(decoded)

        // 2. Extract highlights from HTML tables (e.g., Solus Highlights table)
        val tableHighlights = extractTableHighlights(decoded)

        // 3. Extract highlights from list items (<ul><li> or markdown lists)
        val listHighlights = extractListHighlights(decoded)

        // Combine or select primary highlights
        val primaryHighlights = when {
            tableHighlights.isNotEmpty() -> tableHighlights
            listHighlights.isNotEmpty() -> listHighlights
            else -> extractParagraphHighlights(decoded)
        }

        // 4. Build a full clean changelog
        val fullChangelog = buildCleanChangelog(decoded, tableHighlights)

        // 5. Build compact summary text (up to 4 bullet points)
        val cleanSummary = if (primaryHighlights.isNotEmpty()) {
            primaryHighlights.take(4).joinToString("\n") { bullet ->
                val trimmed = bullet.trim()
                if (trimmed.startsWith("•") || trimmed.startsWith("-") || trimmed.startsWith("*")) {
                    trimmed
                } else {
                    "• $trimmed"
                }
            }
        } else {
            fullChangelog.take(300).trim()
        }

        return ParsedReleaseNotes(
            versionTitle = extractVersionTitle(decoded),
            subtitle = subtitle,
            highlights = primaryHighlights,
            cleanSummary = cleanSummary,
            fullChangelog = fullChangelog
        )
    }

    private fun extractVersionTitle(text: String): String? {
        val h1Match = Regex("""<h1[^>]*>([\s\S]*?)</h1>""", RegexOption.IGNORE_CASE).find(text)
        if (h1Match != null) {
            val title = stripHtmlTags(h1Match.groupValues[1]).trim()
            if (title.isNotBlank()) return title
        }
        val mdH1Match = Regex("""^#\s+(.+)$""", RegexOption.MULTILINE).find(text)
        if (mdH1Match != null) {
            val title = stripHtmlTags(mdH1Match.groupValues[1]).trim()
            if (title.isNotBlank()) return title
        }
        return null
    }

    private fun extractSubtitle(text: String): String? {
        val h3Match = Regex("""<h3[^>]*>([\s\S]*?)</h3>""", RegexOption.IGNORE_CASE).find(text)
        if (h3Match != null) {
            val candidate = stripHtmlTags(h3Match.groupValues[1]).trim()
            // Avoid using section subheaders as main subtitle
            if (candidate.isNotBlank() && !candidate.startsWith("16 KB Native") && candidate.length < 200) {
                return candidate
            }
        }
        return null
    }

    private fun extractTableHighlights(text: String): List<String> {
        val highlights = mutableListOf<String>()
        val trRegex = Regex("""<tr[^>]*>([\s\S]*?)</tr>""", RegexOption.IGNORE_CASE)
        val tdRegex = Regex("""<td[^>]*>([\s\S]*?)</td>""", RegexOption.IGNORE_CASE)

        for (trMatch in trRegex.findAll(text)) {
            val trContent = trMatch.groupValues[1]
            // Skip header rows
            if (trContent.contains("<th", ignoreCase = true)) continue

            val tdMatches = tdRegex.findAll(trContent).toList()
            if (tdMatches.size >= 2) {
                val col1 = cleanInlineMarkdownAndHtml(tdMatches[0].groupValues[1]).trim()
                val col2 = cleanInlineMarkdownAndHtml(tdMatches[1].groupValues[1]).trim()
                if (col1.isNotBlank() && col2.isNotBlank()) {
                    highlights.add("$col1: $col2")
                }
            } else if (tdMatches.size == 1) {
                val col = cleanInlineMarkdownAndHtml(tdMatches[0].groupValues[1]).trim()
                if (col.isNotBlank()) {
                    highlights.add(col)
                }
            }
        }
        return highlights
    }

    private fun extractListHighlights(text: String): List<String> {
        val highlights = mutableListOf<String>()
        // HTML list items
        val liRegex = Regex("""<li[^>]*>([\s\S]*?)</li>""", RegexOption.IGNORE_CASE)
        for (liMatch in liRegex.findAll(text)) {
            val item = cleanInlineMarkdownAndHtml(liMatch.groupValues[1]).trim()
            if (item.isNotBlank() && !isBoilerplateLine(item)) {
                highlights.add(item)
            }
        }

        // If no HTML list items found, try Markdown list items
        if (highlights.isEmpty()) {
            val mdListRegex = Regex("""^[ \t]*[-*•][ \t]+(.+)$""", RegexOption.MULTILINE)
            for (match in mdListRegex.findAll(text)) {
                val item = cleanInlineMarkdownAndHtml(match.groupValues[1]).trim()
                if (item.isNotBlank() && !isBoilerplateLine(item)) {
                    highlights.add(item)
                }
            }
        }

        return highlights
    }

    private fun extractParagraphHighlights(text: String): List<String> {
        val cleaned = buildCleanChangelog(text, emptyList())
        return cleaned.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") && !isBoilerplateLine(it) }
            .take(5)
    }

    private fun buildCleanChangelog(rawText: String, tableHighlights: List<String>): String {
        var text = rawText

        // Remove download buttons, badges, images, scripts
        text = text.replace(Regex("""<script[\s\S]*?</script>""", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("""<style[\s\S]*?</style>""", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("""<img[^>]*>""", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("""!\[.*?\]\(.*?\)"""), "")
        text = text.replace(Regex("""\[!\[.*?\]\(.*?\)\]\(.*?\)"""), "")
        // Remove shields badges and apk links
        text = text.replace(Regex("""<a[^>]*href="[^"]*(?:shields\.io|\.apk|download)[^"]*"[^>]*>[\s\S]*?</a>""", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("""\[.*?\]\([^)]*(?:\.apk|download)[^)]*\)"""), "")

        // Remove verification code blocks or raw metadata tables
        text = text.replace(Regex("""```(?:text)?\s*versionName[\s\S]*?```""", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("""<p><strong>versionCode[\s\S]*?</p>""", RegexOption.IGNORE_CASE), "")

        // Replace tables with clean bullet lists
        if (tableHighlights.isNotEmpty()) {
            text = text.replace(Regex("""<table[\s\S]*?</table>""", RegexOption.IGNORE_CASE), "\n" + tableHighlights.joinToString("\n") { "• $it" } + "\n")
        } else {
            text = text.replace(Regex("""<table[\s\S]*?</table>""", RegexOption.IGNORE_CASE), "")
        }

        // Convert HTML headers to Markdown headings
        text = text.replace(Regex("""<h1[^>]*>([\s\S]*?)</h1>""", RegexOption.IGNORE_CASE)) { "\n# " + stripHtmlTags(it.groupValues[1]).trim() + "\n" }
        text = text.replace(Regex("""<h2[^>]*>([\s\S]*?)</h2>""", RegexOption.IGNORE_CASE)) { "\n## " + stripHtmlTags(it.groupValues[1]).trim() + "\n" }
        text = text.replace(Regex("""<h3[^>]*>([\s\S]*?)</h3>""", RegexOption.IGNORE_CASE)) { "\n### " + stripHtmlTags(it.groupValues[1]).trim() + "\n" }
        text = text.replace(Regex("""<h4[^>]*>([\s\S]*?)</h4>""", RegexOption.IGNORE_CASE)) { "\n#### " + stripHtmlTags(it.groupValues[1]).trim() + "\n" }

        // Convert list elements
        text = text.replace(Regex("""<li[^>]*>([\s\S]*?)</li>""", RegexOption.IGNORE_CASE)) { "\n• " + stripHtmlTags(it.groupValues[1]).trim() }
        text = text.replace(Regex("""<hr\s*/?>""", RegexOption.IGNORE_CASE), "\n---\n")
        text = text.replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")

        // Convert remaining inline tags
        text = text.replace(Regex("""<(?:strong|b)[^>]*>([\s\S]*?)</(?:strong|b)>""", RegexOption.IGNORE_CASE)) { "**" + stripHtmlTags(it.groupValues[1]).trim() + "**" }
        text = text.replace(Regex("""<code[^>]*>([\s\S]*?)</code>""", RegexOption.IGNORE_CASE)) { "`" + stripHtmlTags(it.groupValues[1]).trim() + "`" }
        text = text.replace(Regex("""<a[^>]*href="([^"]*)"[^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)) { stripHtmlTags(it.groupValues[2]).trim() }

        // Strip any remaining HTML tags
        text = stripHtmlTags(text)

        // Normalize lines and whitespace
        val cleanLines = text.lines()
            .map { it.trim() }
            .filter { line ->
                !isBoilerplateLine(line)
            }

        // Collapse multiple blank lines
        val result = StringBuilder()
        var lastWasBlank = false
        for (line in cleanLines) {
            if (line.isBlank()) {
                if (!lastWasBlank) {
                    result.append("\n")
                    lastWasBlank = true
                }
            } else {
                result.append(line).append("\n")
                lastWasBlank = false
            }
        }

        return result.toString().trim()
    }

    private fun cleanInlineMarkdownAndHtml(input: String): String {
        var str = stripHtmlTags(input)
        str = decodeHtmlEntities(str)
        // Clean markdown link syntax: [Text](URL) -> Text
        str = str.replace(Regex("""\[(.*?)\]\(.*?\)""")) { it.groupValues[1] }
        return str.replace(Regex("""\s+"""), " ").trim()
    }

    private fun isBoilerplateLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return true
        if (trimmed == "---" || trimmed == "***" || trimmed == "___") return true
        if (trimmed.startsWith("Get the newest APK", ignoreCase = true)) return true
        if (trimmed.startsWith("Download Solus APK", ignoreCase = true)) return true
        if (trimmed.startsWith("Download app-release.apk", ignoreCase = true)) return true
        if (trimmed.startsWith("Install on Android", ignoreCase = true)) return true
        if (trimmed.startsWith("Open Models → download", ignoreCase = true)) return true
        if (trimmed.startsWith("Author:", ignoreCase = true)) return true
        if (trimmed.startsWith("Repository · All releases", ignoreCase = true)) return true
        if (trimmed.startsWith("Private AI. On your device.", ignoreCase = true)) return true
        if (trimmed.startsWith("versionCode", ignoreCase = true)) return true
        if (trimmed.startsWith("Target SDK", ignoreCase = true)) return true
        if (trimmed.startsWith("APK Size", ignoreCase = true)) return true
        if (trimmed.startsWith("Kotlin", ignoreCase = true) && trimmed.contains("=")) return true
        return false
    }

    /**
     * Formats clean text for notifications (plain text, single bullet per line).
     */
    fun formatForNotification(rawNotes: String): String {
        val parsed = parse(rawNotes)
        if (parsed.highlights.isNotEmpty()) {
            return parsed.highlights.take(3).joinToString("\n") { bullet ->
                val plain = stripMarkdownFormatting(bullet)
                if (plain.startsWith("•")) plain else "• $plain"
            }
        }
        return stripMarkdownFormatting(parsed.cleanSummary).take(250)
    }

    private fun stripMarkdownFormatting(text: String): String {
        return text
            .replace("**", "")
            .replace("__", "")
            .replace("`", "")
            .replace(Regex("""#+\s*"""), "")
            .trim()
    }

    /**
     * Converts formatted release notes into an [AnnotatedString] with styled bold,
     * code snippets, and colored bullet points for Jetpack Compose UI.
     */
    fun formatToAnnotatedString(
        content: String,
        primaryColor: Color,
        textColor: Color
    ): AnnotatedString = buildAnnotatedString {
        val lines = content.lines()
        for (i in lines.indices) {
            val line = lines[i]

            when {
                line.startsWith("# ") -> {
                    val text = line.removePrefix("# ").trim()
                    appendLineWithBold(text, primaryColor, FontWeight.Bold)
                }
                line.startsWith("## ") -> {
                    val text = line.removePrefix("## ").trim()
                    appendLineWithBold(text, primaryColor, FontWeight.Bold)
                }
                line.startsWith("### ") -> {
                    val text = line.removePrefix("### ").trim()
                    appendLineWithBold(text, textColor, FontWeight.SemiBold)
                }
                line.startsWith("• ") || line.startsWith("- ") || line.startsWith("* ") -> {
                    val bulletContent = line.replaceFirst(Regex("""^[•\-*]\s*"""), "")
                    // Render bullet symbol in primary color
                    withStyle(SpanStyle(color = primaryColor, fontWeight = FontWeight.Bold)) {
                        append("• ")
                    }
                    appendFormattedInlineText(bulletContent, primaryColor, textColor)
                }
                line.startsWith("---") -> {
                    // Skip or render divider
                }
                else -> {
                    appendFormattedInlineText(line, primaryColor, textColor)
                }
            }

            if (i < lines.size - 1) {
                append("\n")
            }
        }
    }

    private fun AnnotatedString.Builder.appendLineWithBold(
        text: String,
        color: Color,
        weight: FontWeight
    ) {
        withStyle(SpanStyle(color = color, fontWeight = weight)) {
            append(text)
        }
    }

    private fun AnnotatedString.Builder.appendFormattedInlineText(
        text: String,
        primaryColor: Color,
        textColor: Color
    ) {
        var index = 0
        while (index < text.length) {
            val boldIndex = text.indexOf("**", startIndex = index)
            val codeIndex = text.indexOf('`', startIndex = index)

            val markerIndex = when {
                boldIndex < 0 -> codeIndex
                codeIndex < 0 -> boldIndex
                else -> minOf(boldIndex, codeIndex)
            }

            if (markerIndex == -1) {
                withStyle(SpanStyle(color = textColor)) {
                    append(text.substring(index))
                }
                break
            }

            // Append preceding text
            if (markerIndex > index) {
                withStyle(SpanStyle(color = textColor)) {
                    append(text.substring(index, markerIndex))
                }
            }

            if (markerIndex == codeIndex) {
                val end = text.indexOf('`', startIndex = codeIndex + 1)
                if (end == -1) {
                    withStyle(SpanStyle(color = textColor)) {
                        append(text.substring(codeIndex))
                    }
                    break
                }
                val code = text.substring(codeIndex + 1, end)
                withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        color = primaryColor,
                        background = primaryColor.copy(alpha = 0.12f)
                    )
                ) {
                    append(" $code ")
                }
                index = end + 1
            } else {
                // Bold marker **
                val end = text.indexOf("**", startIndex = boldIndex + 2)
                if (end == -1) {
                    withStyle(SpanStyle(color = textColor)) {
                        append(text.substring(boldIndex))
                    }
                    break
                }
                val boldText = text.substring(boldIndex + 2, end)
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = textColor)) {
                    append(boldText)
                }
                index = end + 2
            }
        }
    }
}
