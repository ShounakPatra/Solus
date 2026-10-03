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

    @Test
    fun cleanRemovesGemmaTurnTokens() {
        val raw = "<start_of_turn>model\nA qubit can represent a combination of states.<end_of_turn>"

        assertEquals(
            "A qubit can represent a combination of states.",
            ModelOutputSanitizer.cleanAssistantText(raw, "What is a qubit?")
        )
    }

    @Test
    fun cleanAssistantTextRemovesEchoedUserAndRoleLinesFromScreenshotPattern() {
        val raw = "Hi\nAssistant\n\nWhat is quantum computing?\n\nQuantum computing uses qubits."

        assertEquals(
            "What is quantum computing?\n\nQuantum computing uses qubits.",
            ModelOutputSanitizer.cleanAssistantText(raw, "hi")
        )
    }

    @Test
    fun cleanAssistantTextKeepsLegitimateGreeting() {
        assertEquals(
            "Hi! What would you like help with?",
            ModelOutputSanitizer.cleanAssistantText("Hi! What would you like help with?", "hi")
        )
    }

    @Test
    fun cleanPreservesModernEmojiSequences() {
        val emojiText = "🙂 🐦‍🔥 👩🏽‍💻 🇮🇳 🫩"

        assertEquals(emojiText, ModelOutputSanitizer.clean(emojiText))
        assertEquals(
            "Answer $emojiText",
            ModelOutputSanitizer.cleanAssistantText("Answer $emojiText", "Show emoji")
        )
    }

    @Test
    fun cleanAssistantTextStripsImYourHallucination() {
        val raw1 = "Hi! I'm Your. You like ice cream, and your dog's name is Lambda."
        assertEquals(
            "Hi! You like ice cream, and your dog's name is Lambda.",
            ModelOutputSanitizer.cleanAssistantText(raw1, "hi")
        )

        val raw2 = "Hi! I'm Your. You prefer Kotlin, and your dog's name is Lambda. Let me know if there's something I can help with! 🍦🐾"
        assertEquals(
            "Hi! You prefer Kotlin, and your dog's name is Lambda. Let me know if there's something I can help with! 🍦🐾",
            ModelOutputSanitizer.cleanAssistantText(raw2, "hi")
        )

        val raw3 = "Hi! I'm Your."
        assertEquals(
            "Hi!",
            ModelOutputSanitizer.cleanAssistantText(raw3, "hi")
        )

        val raw4 = "I'm Your. You prefer Kotlin."
        assertEquals(
            "You prefer Kotlin.",
            ModelOutputSanitizer.cleanAssistantText(raw4, "hi")
        )

        val rawLegitimate = "Hi! I'm your assistant."
        assertEquals(
            "Hi! I'm your assistant.",
            ModelOutputSanitizer.cleanAssistantText(rawLegitimate, "hi")
        )
    }

    @Test
    fun removeRepeatedOpeningPrefixStripsDuplicateOpeningAcrossTurns() {
        val prev = "Hi! I love pizza too! 🍕 Let me know if you'd like a pizza recipe or something!"
        val curr = "Hi! I love pizza too! 🍕 What other things do you like to eat?"
        val stripped = ModelOutputSanitizer.removeRepeatedOpeningPrefix(curr, prev)
        assertEquals("What other things do you like to eat?", stripped)
    }
}
