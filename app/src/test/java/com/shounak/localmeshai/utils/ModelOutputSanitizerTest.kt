package com.shounak.localmeshai.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelOutputSanitizerTest {
    @Test
    fun cleanRemovesBrokenByteLevelEmojiClusterFromMiddleOfAnswer() {
        val raw = "Hi! How can I assist you today?\u0120\u00F0\u013F\u0130 What can I do for you?"

        assertEquals(
            "Hi! How can I assist you today? What can I do for you?",
            ModelOutputSanitizer.clean(raw)
        )
    }

    @Test
    fun cleanDecodesValidByteLevelEmoji() {
        val raw = "Hi\u0120\u00F0\u0141\u013A\u012C"

        assertEquals("Hi \uD83D\uDE0A", ModelOutputSanitizer.clean(raw))
    }

    @Test
    fun cleanDecodesCommonMojibakeText() {
        assertEquals("Cafe", ModelOutputSanitizer.clean("Cafe"))
        assertEquals("Caf\u00E9", ModelOutputSanitizer.clean("Caf\u00C3\u00A9"))
        assertEquals("Sparkles: \u2728", ModelOutputSanitizer.clean("Sparkles:\u0120\u00E2\u013E\u00A8"))
    }

    @Test
    fun cleanConvertsTokenizerSpacingWithoutRemovingRealAccents() {
        val multilingual = "M\u0101ori and \u0141\u00F3d\u017A stay readable."

        assertEquals("Hello world", ModelOutputSanitizer.clean("Hello\u0120world"))
        assertEquals(multilingual, ModelOutputSanitizer.clean(multilingual))
    }

    @Test
    fun normalizeFinalOutputAlsoSanitizesThinkingResponses() {
        val raw = "<think>\nchecking\u0120spacing\n</think>\n\nDone.\u0120\u00F0\u013F\u0130"

        assertEquals(
            "<think>\nchecking spacing\n</think>\n\nDone.",
            ThinkingTextUtils.normalizeFinalOutput(raw)
        )
    }
}
