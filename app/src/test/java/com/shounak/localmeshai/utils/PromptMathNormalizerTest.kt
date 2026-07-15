package com.shounak.localmeshai.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class PromptMathNormalizerTest {
    @Test
    fun normalizesUnicodeSuperscriptsAndSubscriptsForSmallModels() {
        assertEquals(
            "Expand (a+b)^2 and solve x_1 = y_2",
            PromptMathNormalizer.normalizeForInference("Expand (a+b)² and solve x₁ = y₂")
        )
    }

    @Test
    fun normalizesKeyboardFractionsAndMathSymbolsForInference() {
        assertEquals(
            "^3 3/4 2/3 ^4^5^6 7/8 ^7^8 sqrt pi / * Delta approximately * EUR JPY $ cents section degrees checked copyright registered trademark",
            PromptMathNormalizer.normalizeForInference(
                "³ ¾ ⅔ ⁴⁵⁶ ⅞ ⁷⁸ √π ÷ × Δ ~ • € ¥ $ ¢ § ° ✓ © ® ™"
            )
        )
    }
}
