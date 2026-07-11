package com.shounak.localmeshai.utils

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

object ModelOutputSanitizer {
    private const val BYTE_LEVEL_TAB = '\u0109'
    private const val BYTE_LEVEL_NEWLINE = '\u010A'
    private const val BYTE_LEVEL_CR = '\u010D'
    private const val BYTE_LEVEL_SPACE = '\u0120'
    private const val SENTENCEPIECE_SPACE = '\u2581'

    private val specialTokenRegex = Regex(
        pattern = """<\|(?:im_start|im_end|begin_of_text|end_of_text|endoftext)\|>|<(?:bos|eos|start_of_turn|end_of_turn)>|<\s*/?s\s*>""",
        option = RegexOption.IGNORE_CASE
    )
    private val assistantRoleLineRegex = Regex(
        pattern = """^(?:assistant|model)\s*:?\s*$""",
        option = RegexOption.IGNORE_CASE
    )
    private val userRolePrefixRegex = Regex(
        pattern = """^user\s*:\s*""",
        option = RegexOption.IGNORE_CASE
    )
    private val byteLevelClusterAfterPunctuationRegex =
        Regex("""([.!?…。！？\n\r]\s*)[\u0100-\u0143]{1,8}(?=\s|$)""")
    private val isolatedByteLevelClusterRegex =
        Regex("""(?<![\p{L}\p{N}])[\u0100-\u0143]{2,}(?![\p{L}\p{N}])""")

    fun clean(text: String): String {
        if (text.isEmpty()) return ""

        val withoutSpecialTokens = specialTokenRegex.replace(text, "")
        val decoded = decodeByteLevelArtifacts(withoutSpecialTokens)
        val withoutReplacementChars = decoded.replace("\uFFFD", "")
        return stripDanglingArtifacts(withoutReplacementChars).trimEnd()
    }

    /**
     * Removes chat-template material that a small completion model may echo before
     * its actual answer. It only removes the latest user text when that text is a
     * complete leading line and more generated content follows, so a legitimate
     * one-line greeting such as "Hi!" remains untouched.
     */
    fun cleanAssistantText(text: String, latestUserText: String): String {
        var lines = clean(text).lines().toMutableList()
        fun dropLeadingBlankLines() {
            while (lines.firstOrNull()?.isBlank() == true) lines.removeAt(0)
        }

        dropLeadingBlankLines()
        if (lines.size > 1 && latestUserText.isNotBlank()) {
            val leadingContent = userRolePrefixRegex.replace(lines.first().trim(), "")
            if (leadingContent.equals(latestUserText.trim(), ignoreCase = true)) {
                lines.removeAt(0)
                dropLeadingBlankLines()
            }
        }
        while (lines.size > 1 && assistantRoleLineRegex.matches(lines.first().trim())) {
            lines.removeAt(0)
            dropLeadingBlankLines()
        }
        return lines.joinToString("\n").trim()
    }

    private fun decodeByteLevelArtifacts(input: String): String {
        val output = StringBuilder(input.length)
        var index = 0
        while (index < input.length) {
            val decoded = decodeUtf8ArtifactAt(input, index)
            if (decoded != null) {
                if (decoded.text.isNotEmpty()) {
                    output.append(decoded.text)
                }
                index += decoded.consumed
                if (decoded.text.isEmpty() && output.lastOrNull() == ' ') {
                    while (index < input.length && input[index] == ' ') {
                        index++
                    }
                }
                continue
            }

            when (val char = input[index]) {
                BYTE_LEVEL_SPACE, SENTENCEPIECE_SPACE -> appendSingleSpace(output)
                BYTE_LEVEL_NEWLINE -> output.append('\n')
                BYTE_LEVEL_TAB -> output.append('\t')
                BYTE_LEVEL_CR -> output.append('\n')
                else -> output.append(char)
            }
            index++
        }
        return output.toString()
    }

    private fun decodeUtf8ArtifactAt(input: String, start: Int): DecodeResult? {
        val firstByte = byteLevelByteFor(input[start]) ?: return null
        val expectedLength = utf8SequenceLength(firstByte) ?: return null
        val bytes = ByteArray(expectedLength)
        bytes[0] = firstByte.toByte()

        var offset = 1
        var hasByteLevelOnlyChar = isByteLevelOnlyChar(input[start])
        while (offset < expectedLength && start + offset < input.length) {
            val char = input[start + offset]
            val byte = byteLevelByteFor(char) ?: break
            if (!isUtf8ContinuationByte(byte)) break
            bytes[offset] = byte.toByte()
            hasByteLevelOnlyChar = hasByteLevelOnlyChar || isByteLevelOnlyChar(char)
            offset++
        }

        if (offset < expectedLength) {
            return if (offset > 1 && hasByteLevelOnlyChar) {
                DecodeResult(text = "", consumed = offset)
            } else {
                null
            }
        }

        val decoded = decodeUtf8(bytes)
        val shouldDecode = hasByteLevelOnlyChar || isCommonMojibakeLead(input[start])
        return when {
            decoded != null && shouldDecode -> DecodeResult(text = decoded, consumed = expectedLength)
            decoded == null && hasByteLevelOnlyChar -> DecodeResult(text = "", consumed = expectedLength)
            else -> null
        }
    }

    private fun stripDanglingArtifacts(text: String): String {
        return text
            .replace(byteLevelClusterAfterPunctuationRegex, "$1")
            .replace(isolatedByteLevelClusterRegex, "")
            .trimEnd { char -> char.code in 0x0080..0x009F || char == '\uFFFD' }
    }

    private fun appendSingleSpace(output: StringBuilder) {
        if (output.isEmpty() || output.last() != ' ') {
            output.append(' ')
        }
    }

    private fun byteLevelByteFor(char: Char): Int? {
        val code = char.code
        return when {
            code in 33..126 -> code
            code in 0x00A1..0x00AC -> code
            code in 0x00AE..0x00FF -> code
            code in 0x0100..0x0120 -> code - 0x0100
            code in 0x0121..0x0142 -> 127 + (code - 0x0121)
            code == 0x0143 -> 173
            else -> null
        }
    }

    private fun isByteLevelOnlyChar(char: Char): Boolean =
        char.code in 0x0100..0x0143

    private fun utf8SequenceLength(firstByte: Int): Int? {
        return when (firstByte) {
            in 0xC2..0xDF -> 2
            in 0xE0..0xEF -> 3
            in 0xF0..0xF4 -> 4
            else -> null
        }
    }

    private fun isUtf8ContinuationByte(byte: Int): Boolean =
        byte in 0x80..0xBF

    private fun isCommonMojibakeLead(char: Char): Boolean =
        char.code in 0x00C2..0x00F4

    private fun decodeUtf8(bytes: ByteArray): String? {
        val decoder = StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)

        return runCatching {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrNull()
    }

    private data class DecodeResult(
        val text: String,
        val consumed: Int
    )
}
