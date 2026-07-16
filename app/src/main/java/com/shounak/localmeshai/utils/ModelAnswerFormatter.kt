package com.shounak.localmeshai.utils

/** Structured pieces of a model answer that need different native UI treatment. */
sealed interface ModelAnswerSegment {
    data class Text(val text: String) : ModelAnswerSegment
    data class Code(val code: String, val language: String) : ModelAnswerSegment
    data class DisplayMath(val latex: String) : ModelAnswerSegment
}

/**
 * Splits model answers into prose, code, and LaTeX without regular expressions.
 * Android's ICU regex implementation differs from the desktop JVM for several
 * valid Kotlin patterns, so deterministic string parsing prevents chat/history
 * rendering from ever failing during class initialization.
 */
object ModelAnswerFormatter {
    private val commandReplacements = linkedMapOf(
        "\\Longleftrightarrow" to "⟺", "\\Leftrightarrow" to "⇔",
        "\\longrightarrow" to "⟶", "\\longleftarrow" to "⟵",
        "\\rightarrow" to "→", "\\leftarrow" to "←", "\\implies" to "⇒",
        "\\because" to "∵", "\\therefore" to "∴", "\\approx" to "≈",
        "\\equiv" to "≡", "\\neq" to "≠", "\\ne" to "≠", "\\leq" to "≤",
        "\\geq" to "≥", "\\le" to "≤", "\\ge" to "≥", "\\times" to "×",
        "\\div" to "÷", "\\cdot" to "·", "\\pm" to "±", "\\mp" to "∓",
        "\\infty" to "∞", "\\sum" to "∑", "\\prod" to "∏", "\\int" to "∫",
        "\\partial" to "∂", "\\nabla" to "∇", "\\alpha" to "α", "\\beta" to "β",
        "\\gamma" to "γ", "\\delta" to "δ", "\\epsilon" to "ε", "\\theta" to "θ",
        "\\lambda" to "λ", "\\mu" to "μ", "\\pi" to "π", "\\rho" to "ρ",
        "\\sigma" to "σ", "\\phi" to "φ", "\\omega" to "ω", "\\Delta" to "Δ",
        "\\Theta" to "Θ", "\\Lambda" to "Λ", "\\Pi" to "Π", "\\Sigma" to "Σ",
        "\\Phi" to "Φ", "\\Omega" to "Ω"
    )
    private val structuralCommands = listOf(
        "\\begin", "\\frac", "\\dfrac", "\\tfrac", "\\sqrt", "\\binom", "\\sum",
        "\\prod", "\\int", "\\iint", "\\iiint", "\\oint", "\\lim", "\\cases",
        "\\matrix", "\\pmatrix", "\\bmatrix", "\\vmatrix", "\\aligned", "\\align",
        "\\gather", "\\overset", "\\underset", "\\overbrace", "\\underbrace", "\\cancel",
        "\\color", "\\textcolor", "\\ce", "\\chemfig", "\\boxed", "\\fbox", "\\left",
        "\\right", "\\substack", "\\stackrel", "\\operatorname"
    )
    private val superscriptCharacters = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴', '5' to '⁵',
        '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹', '+' to '⁺', '-' to '⁻',
        '=' to '⁼', '(' to '⁽', ')' to '⁾', 'n' to 'ⁿ', 'i' to 'ⁱ'
    )
    private val subscriptCharacters = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄', '5' to '₅',
        '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉', '+' to '₊', '-' to '₋',
        '=' to '₌', '(' to '₍', ')' to '₎'
    )

    fun parse(text: String): List<ModelAnswerSegment> {
        val result = mutableListOf<ModelAnswerSegment>()
        var cursor = 0
        while (cursor < text.length) {
            val fenceStart = text.indexOf("```", cursor)
            if (fenceStart < 0) {
                appendRichText(text.substring(cursor), result)
                break
            }
            appendRichText(text.substring(cursor, fenceStart), result)
            val headerEnd = text.indexOf('\n', fenceStart + 3)
            if (headerEnd < 0) {
                appendText(text.substring(fenceStart), result)
                break
            }
            val fenceEnd = text.indexOf("```", headerEnd + 1)
            if (fenceEnd < 0) {
                appendText(text.substring(fenceStart), result)
                break
            }
            result += ModelAnswerSegment.Code(
                code = text.substring(headerEnd + 1, fenceEnd).trimEnd(),
                language = text.substring(fenceStart + 3, headerEnd).trim()
            )
            cursor = fenceEnd + 3
        }
        return result.ifEmpty { listOf(ModelAnswerSegment.Text(formatInlineMath(text))) }
    }

    /** Never allows malformed model formatting to crash composition. */
    fun parseSafely(text: String): List<ModelAnswerSegment> =
        runCatching { parse(text) }.getOrElse { listOf(ModelAnswerSegment.Text(text)) }

    /** Decodes only unambiguous escaped line breaks and double-escaped LaTeX. */
    fun normalizeEscapedModelText(text: String): String {
        val output = StringBuilder()
        var cursor = 0
        while (cursor < text.length) {
            if (text.startsWith("\\r\\n", cursor)) {
                output.append('\n')
                cursor += 4
                continue
            }
            if (text.startsWith("\\n", cursor) && isEscapedLineBreak(text, cursor + 2)) {
                output.append('\n')
                cursor += 2
                continue
            }
            if (text.startsWith("\\\\", cursor) && isDoubleEscapedLatexStart(text, cursor + 2)) {
                output.append('\\')
                cursor += 2
                continue
            }
            output.append(text[cursor++])
        }
        return output.toString()
    }

    /** True for expressions that need the native renderer instead of selectable text. */
    fun requiresNativeMathRenderer(latex: String): Boolean =
        requiresNativeRenderer(normalizeEscapedModelText(latex))

    fun formatInlineMath(text: String): String {
        val output = StringBuilder()
        var cursor = 0
        while (cursor < text.length) {
            if (text.startsWith("\\(", cursor)) {
                val end = text.indexOf("\\)", cursor + 2)
                if (end >= 0) {
                    output.append(formatMath(text.substring(cursor + 2, end)))
                    cursor = end + 2
                    continue
                }
            }
            output.append(text[cursor++])
        }
        return formatCommands(output.toString(), decorateBoxes = true)
    }

    fun formatMath(latex: String): String =
        collapseWhitespace(
            formatCommands(
                normalizeEscapedModelText(latex).trim().removePrefix("\\(").removeSuffix("\\)")
                    .removePrefix("\\[").removeSuffix("\\]"),
                decorateBoxes = true
            ).replace("{", "").replace("}", "")
        )

    fun boxedContent(latex: String): String? {
        val value = latex.trim().removeSuffix(".").trim()
        if (!value.startsWith("\\boxed{")) return null
        val open = value.indexOf('{')
        val close = matchingBrace(value, open)
        if (close < 0 || close != value.lastIndex) return null
        return formatMath(value.substring(open + 1, close))
    }

    private fun appendRichText(source: String, result: MutableList<ModelAnswerSegment>) {
        val prose = StringBuilder()
        var cursor = 0
        fun flushProse() {
            if (prose.isNotEmpty()) {
                appendText(prose.toString(), result)
                prose.clear()
            }
        }
        while (cursor < source.length) {
            // A model may show the LaTeX source as an inline-code example, for
            // example `\\begin{bmatrix}...\\end{bmatrix}`.  That is documentation,
            // not a formula to render.  Keep it verbatim so it cannot be split
            // into empty/partial formula cards.
            if (source[cursor] == '`') {
                val end = source.indexOf('`', cursor + 1)
                if (end > cursor + 1) {
                    flushProse()
                    appendRawText(source.substring(cursor, end + 1), result)
                    cursor = end + 1
                    continue
                }
            }
            val math = readMathAt(source, cursor)
            if (math == null) {
                prose.append(source[cursor++])
                continue
            }
            // Streaming small models sometimes close a math delimiter before
            // they have produced the required arguments for \frac or \sqrt.
            // Rendering that fragment natively creates a misleading empty card
            // (or a lone radical).  Present a readable text fallback until the
            // model emits a complete expression instead.
            if (!isRenderableMath(math.latex)) {
                prose.append(readableMalformedMath(math.latex))
            } else if (math.isInline && !requiresNativeRenderer(math.latex)) {
                prose.append(formatMath(math.latex))
            } else {
                flushProse()
                result += ModelAnswerSegment.DisplayMath(math.latex.trim())
            }
            cursor = math.endExclusive
        }
        flushProse()
    }

    private fun readMathAt(source: String, start: Int): ParsedMath? {
        fun bounded(prefix: String, suffix: String, inline: Boolean): ParsedMath? {
            if (!source.startsWith(prefix, start)) return null
            val end = source.indexOf(suffix, start + prefix.length)
            return if (end >= 0) {
                ParsedMath(source.substring(start + prefix.length, end), end + suffix.length, inline)
            } else null
        }
        bounded("\\[", "\\]", inline = false)?.let { return it }
        bounded("$$", "$$", inline = false)?.let { return it }
        bounded("\\(", "\\)", inline = true)?.let { return it }
        if (source[start] == '$' && !source.startsWith("$$", start) &&
            (start == 0 || source[start - 1] != '$')
        ) {
            var end = start + 1
            while (end < source.length) {
                if (source[end] == '$' && (end + 1 == source.length || source[end + 1] != '$')) {
                    return ParsedMath(source.substring(start + 1, end), end + 1, isInline = true)
                }
                end++
            }
        }
        if (source.startsWith("\\begin{", start)) {
            val nameEnd = source.indexOf('}', start + 7)
            if (nameEnd >= 0) {
                val name = source.substring(start + 7, nameEnd)
                val closing = "\\end{$name}"
                val end = source.indexOf(closing, nameEnd + 1)
                if (end >= 0) return ParsedMath(source.substring(start, end + closing.length), end + closing.length, false)
            }
        }
        // Models commonly put a final boxed answer after a sentence rather than
        // on a line of its own. Keep it as a real math segment in both cases.
        if (source.startsWith("\\boxed{", start)) {
            val open = source.indexOf('{', start)
            val close = matchingBrace(source, open)
            if (close >= 0) return ParsedMath(source.substring(start, close + 1), close + 1, false)
        }
        return null
    }

    private fun appendText(source: String, result: MutableList<ModelAnswerSegment>) {
        if (source.isEmpty()) return
        val formatted = stripMarkdownEmphasis(formatInlineMath(source))
        if (formatted.isEmpty()) return
        val previous = result.lastOrNull()
        if (previous is ModelAnswerSegment.Text) {
            result[result.lastIndex] = ModelAnswerSegment.Text(previous.text + formatted)
        } else result += ModelAnswerSegment.Text(formatted)
    }

    /** Adds inline code without processing its backslashes as LaTeX commands. */
    private fun appendRawText(source: String, result: MutableList<ModelAnswerSegment>) {
        if (source.isEmpty()) return
        val previous = result.lastOrNull()
        if (previous is ModelAnswerSegment.Text) {
            result[result.lastIndex] = ModelAnswerSegment.Text(previous.text + source)
        } else {
            result += ModelAnswerSegment.Text(source)
        }
    }

    /** Removes Markdown bold/underline markers after code sections are isolated. */
    private fun stripMarkdownEmphasis(value: String): String =
        value.replace("**", "").replace("__", "")

    private fun requiresNativeRenderer(latex: String): Boolean =
        latex.contains("\\\\") || latex.count { it == '{' } > 2 ||
            structuralCommands.any { latex.contains(it) }

    /**
     * Only hand structurally complete TeX to the native renderer.  It is much
     * more useful to show "fraction" than a black card containing only "-".
     */
    private fun isRenderableMath(latex: String): Boolean {
        val value = normalizeEscapedModelText(latex).trim()
        if (value.isBlank() || value.all { it.isWhitespace() || it in "-–—:;,." }) return false

        if (!allCommandsHaveArguments(value, listOf("\\dfrac", "\\tfrac", "\\frac"), 2)) return false
        if (!allCommandsHaveArguments(value, listOf("\\sqrt"), 1)) return false
        if (!allCommandsHaveArguments(value, listOf("\\boxed"), 1)) return false

        var begin = value.indexOf("\\begin{")
        while (begin >= 0) {
            val nameEnd = value.indexOf('}', begin + 7)
            if (nameEnd < 0) return false
            val name = value.substring(begin + 7, nameEnd)
            if (name.isBlank() || value.indexOf("\\end{$name}", nameEnd + 1) < 0) return false
            begin = value.indexOf("\\begin{", nameEnd + 1)
        }
        return true
    }

    private fun allCommandsHaveArguments(value: String, commands: List<String>, argumentCount: Int): Boolean {
        var index = 0
        while (index < value.length) {
            val command = commands.firstOrNull { value.startsWith(it, index) }
            if (command == null) {
                index++
                continue
            }
            var argumentStart = index + command.length
            repeat(argumentCount) {
                if (argumentStart !in value.indices || value[argumentStart] != '{') return false
                val argumentEnd = matchingBrace(value, argumentStart)
                if (argumentEnd <= argumentStart + 1) return false
                argumentStart = argumentEnd + 1
            }
            index = argumentStart
        }
        return true
    }

    private fun readableMalformedMath(latex: String): String {
        val readable = latex
            .replace("\\dfrac", "fraction")
            .replace("\\tfrac", "fraction")
            .replace("\\frac", "fraction")
            .replace("\\sqrt", "square root")
            .replace("\\boxed", "boxed")
        return formatMath(readable)
    }

    private fun isEscapedLineBreak(value: String, nextIndex: Int): Boolean {
        if (nextIndex >= value.length) return true
        val next = value[nextIndex]
        return next == '\\' || next.isWhitespace() || next.isUpperCase() ||
            next in ".,;:!?)]}"
    }

    private fun isDoubleEscapedLatexStart(value: String, nextIndex: Int): Boolean {
        if (nextIndex >= value.length) return false
        if (value[nextIndex] in "[]()") return true
        var end = nextIndex
        while (end < value.length && value[end].isLetter()) end++
        if (end == nextIndex) return false
        val command = "\\" + value.substring(nextIndex, end)
        return command in commandReplacements || command in structuralCommands ||
            command in setOf("\\boxed", "\\sqrt", "\\text", "\\textbf", "\\mathrm", "\\mathbf", "\\mathit")
    }

    private fun formatCommands(source: String, decorateBoxes: Boolean): String {
        var value = source
        repeat(4) {
            value = replaceTwoArgumentCommands(value, listOf("\\dfrac", "\\tfrac", "\\frac")) { top, bottom ->
                "(${formatCommands(top, false)}⁄${formatCommands(bottom, false)})"
            }
            value = replaceOneArgumentCommands(value, listOf("\\sqrt")) { content ->
                "√(${formatCommands(content, false)})"
            }
            value = replaceOneArgumentCommands(value, listOf("\\text", "\\textbf", "\\mathrm", "\\mathbf", "\\mathit", "\\operatorname")) { it }
        }
        value = replaceOneArgumentCommands(value, listOf("\\boxed")) { content ->
            val readable = formatCommands(content, false)
            if (decorateBoxes) "⟦ $readable ⟧" else readable
        }
        commandReplacements.forEach { (command, replacement) -> value = value.replace(command, replacement) }
        value = value.replace("\\left", "").replace("\\right", "")
            .replace("\\qquad", "   ").replace("\\quad", "  ")
            .replace("\\,", " ").replace("\\;", " ").replace("\\:", " ")
            .replace("\\!", "").replace("\\%", "%").replace("\\ ", " ")
        return stripUnknownCommands(convertScripts(value))
    }

    private fun replaceOneArgumentCommands(
        input: String,
        commands: List<String>,
        transform: (String) -> String
    ): String {
        val output = StringBuilder()
        var cursor = 0
        while (cursor < input.length) {
            val command = commands.firstOrNull { input.startsWith("$it{", cursor) }
            if (command == null) {
                output.append(input[cursor++])
                continue
            }
            val open = cursor + command.length
            val close = matchingBrace(input, open)
            if (close < 0) {
                output.append(input[cursor++])
                continue
            }
            output.append(transform(input.substring(open + 1, close)))
            cursor = close + 1
        }
        return output.toString()
    }

    private fun replaceTwoArgumentCommands(
        input: String,
        commands: List<String>,
        transform: (String, String) -> String
    ): String {
        val output = StringBuilder()
        var cursor = 0
        while (cursor < input.length) {
            val command = commands.firstOrNull { input.startsWith("$it{", cursor) }
            if (command == null) {
                output.append(input[cursor++])
                continue
            }
            val firstOpen = cursor + command.length
            val firstClose = matchingBrace(input, firstOpen)
            val secondOpen = firstClose + 1
            if (firstClose < 0 || secondOpen !in input.indices || input[secondOpen] != '{') {
                output.append(input[cursor++])
                continue
            }
            val secondClose = matchingBrace(input, secondOpen)
            if (secondClose < 0) {
                output.append(input[cursor++])
                continue
            }
            output.append(transform(input.substring(firstOpen + 1, firstClose), input.substring(secondOpen + 1, secondClose)))
            cursor = secondClose + 1
        }
        return output.toString()
    }

    private fun convertScripts(value: String): String {
        val output = StringBuilder()
        var cursor = 0
        while (cursor < value.length) {
            val marker = value[cursor]
            if (marker != '^' && marker != '_') {
                output.append(marker)
                cursor++
                continue
            }
            val isSuper = marker == '^'
            val contentStart = cursor + 1
            if (contentStart >= value.length) {
                output.append(marker)
                cursor++
                continue
            }
            val (content, next) = if (value[contentStart] == '{') {
                val close = matchingBrace(value, contentStart)
                if (close < 0) {
                    output.append(marker)
                    cursor++
                    continue
                }
                value.substring(contentStart + 1, close) to close + 1
            } else {
                var end = contentStart
                while (end < value.length && value[end] in "+-=()0123456789ni") end++
                if (end == contentStart) {
                    output.append(marker)
                    cursor++
                    continue
                }
                value.substring(contentStart, end) to end
            }
            val mapping = if (isSuper) superscriptCharacters else subscriptCharacters
            val converted = content.map { mapping[it] }
            if (converted.all { it != null }) output.append(converted.joinToString(""))
            else output.append(if (isSuper) "^($content)" else "_($content)")
            cursor = next
        }
        return output.toString()
    }

    private fun stripUnknownCommands(value: String): String {
        val output = StringBuilder()
        var cursor = 0
        while (cursor < value.length) {
            if (value[cursor] != '\\' || cursor + 1 >= value.length) {
                output.append(value[cursor++])
                continue
            }
            if (value[cursor + 1] == '\\') {
                output.append('\n')
                cursor += 2
                continue
            }
            var end = cursor + 1
            while (end < value.length && value[end].isLetter()) end++
            if (end == cursor + 1) {
                output.append(value[cursor++])
            } else {
                output.append(value.substring(cursor + 1, end))
                cursor = end
            }
        }
        return output.toString()
    }

    private fun matchingBrace(value: String, open: Int): Int {
        if (open !in value.indices || value[open] != '{') return -1
        var depth = 0
        for (index in open until value.length) {
            when (value[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return index
            }
        }
        return -1
    }

    private fun collapseWhitespace(value: String): String = value.lineSequence().joinToString("\n") { line ->
        val output = StringBuilder()
        var pendingSpace = false
        line.trim().forEach { character ->
            if (character == ' ' || character == '\t') {
                pendingSpace = output.isNotEmpty()
            } else {
                if (pendingSpace) output.append(' ')
                output.append(character)
                pendingSpace = false
            }
        }
        output.toString()
    }.trim()

    private data class ParsedMath(val latex: String, val endExclusive: Int, val isInline: Boolean)
}
