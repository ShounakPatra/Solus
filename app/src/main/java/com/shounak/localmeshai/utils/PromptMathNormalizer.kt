package com.shounak.localmeshai.utils

/**
 * Makes common Unicode mathematical scripts understandable to small local
 * models whose tokenizers often treat them as unrelated characters.  This is
 * used only for inference; the chat UI and saved message retain what the user
 * typed.
 */
object PromptMathNormalizer {
    private val superscripts = linkedMapOf(
        '⁰' to "^0", '¹' to "^1", '²' to "^2", '³' to "^3", '⁴' to "^4",
        '⁵' to "^5", '⁶' to "^6", '⁷' to "^7", '⁸' to "^8", '⁹' to "^9",
        '⁺' to "^+", '⁻' to "^-", '⁼' to "^=", '⁽' to "^(", '⁾' to "^)"
    )
    private val subscripts = linkedMapOf(
        '₀' to "_0", '₁' to "_1", '₂' to "_2", '₃' to "_3", '₄' to "_4",
        '₅' to "_5", '₆' to "_6", '₇' to "_7", '₈' to "_8", '₉' to "_9",
        '₊' to "_+", '₋' to "_-", '₌' to "_=", '₍' to "_(", '₎' to "_)"
    )
    // Unicode fraction glyphs are convenient to type, but are frequently a
    // single unknown token to small on-device models.  Give the model a plain
    // ASCII fraction while preserving the original glyph in the conversation.
    private val vulgarFractions = linkedMapOf(
        '¼' to "1/4", '½' to "1/2", '¾' to "3/4",
        '⅐' to "1/7", '⅑' to "1/9", '⅒' to "1/10",
        '⅓' to "1/3", '⅔' to "2/3",
        '⅕' to "1/5", '⅖' to "2/5", '⅗' to "3/5", '⅘' to "4/5",
        '⅙' to "1/6", '⅚' to "5/6",
        '⅛' to "1/8", '⅜' to "3/8", '⅝' to "5/8", '⅞' to "7/8",
        '⅟' to "1/", '↉' to "0/3"
    )
    // The keyboard symbols in the chat field should be meaningful to every
    // runtime, including small models that do not have good Unicode coverage.
    // ASCII operators/delimiters are intentionally left alone because they are
    // already part of every model's vocabulary.
    private val symbolWords = linkedMapOf(
        '√' to "sqrt ", '∛' to "cuberoot ", '∜' to "fourth root ",
        'π' to "pi", 'Π' to "Pi", 'Δ' to "Delta", 'δ' to "delta",
        '×' to " * ", '÷' to " / ", '~' to " approximately ",
        '∼' to " approximately ", '≈' to " approximately ",
        '≠' to " not equal to ", '≤' to " less than or equal to ",
        '≥' to " greater than or equal to ", '±' to " plus or minus ",
        '∓' to " minus or plus ", '•' to " * ",
        '€' to " EUR ", '¥' to " JPY ", '¢' to " cents ",
        '§' to " section ", '°' to " degrees ",
        '©' to " copyright ", '®' to " registered ", '™' to " trademark ",
        '✓' to " checked ", '✔' to " checked ", '✗' to " not checked ", '✘' to " not checked "
    )

    fun normalizeForInference(text: String): String {
        val result = StringBuilder(text.length)
        text.forEach { character ->
            result.append(
                superscripts[character]
                    ?: subscripts[character]
                    ?: vulgarFractions[character]
                    ?: symbolWords[character]
                    ?: character
            )
        }
        val normalized = collapseRepeatedSpaces(result.toString())
        // Word replacements may contribute a separator at the very end of a
        // prompt. Keep user-entered newlines intact; remove only that space.
        return if (normalized.endsWith(' ')) normalized.dropLast(1) else normalized
    }

    private fun collapseRepeatedSpaces(value: String): String {
        val result = StringBuilder(value.length)
        var previousWasSpace = false
        value.forEach { character ->
            if (character == ' ') {
                if (!previousWasSpace) result.append(character)
                previousWasSpace = true
            } else {
                result.append(character)
                previousWasSpace = false
            }
        }
        return result.toString()
    }
}
