package com.shounak.localmeshai.utils

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Guards against JVM/Android regex-engine differences in the chat formatter. */
@RunWith(AndroidJUnit4::class)
class ModelAnswerFormatterAndroidTest {
    @Test
    fun initializesAndParsesEverySupportedMathDelimiterOnAndroid() {
        val answer = """
            Inline: \(x^2\) and ${'$'}\frac{a}{b}${'$'}.
            \[\begin{aligned}a&=b+c\\d&=e-f\end{aligned}\]
            ${'$'}${'$'}\ce{2H2 + O2 -> 2H2O}${'$'}${'$'}
            \begin{bmatrix}1 & 0 \\ 0 & 1\end{bmatrix}
        """.trimIndent()

        val segments = ModelAnswerFormatter.parseSafely(answer)

        assertTrue(segments.any { it is ModelAnswerSegment.DisplayMath })
        assertTrue(segments.none { it is ModelAnswerSegment.Text && it.text == answer })
    }
}
