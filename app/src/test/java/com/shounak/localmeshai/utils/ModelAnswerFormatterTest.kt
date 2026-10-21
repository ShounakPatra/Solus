package com.shounak.localmeshai.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelAnswerFormatterTest {
    @Test
    fun formatsScreenshotStyleInlineLatexWithoutCorruptingTimes() {
        val source = "So, \\(12.123 \\times 12.123 = 146.967129\\)."

        assertEquals("So, 12.123 × 12.123 = 146.967129.", ModelAnswerFormatter.formatInlineMath(source))
    }

    @Test
    fun splitsDisplayMathAndFormatsSuperscripts() {
        val segments = ModelAnswerFormatter.parse("First:\n\\[\n(a + b)^2 = a^2 + 2ab + b^2\n\\]\nDone.")

        assertTrue(segments.any { it is ModelAnswerSegment.DisplayMath })
        val math = (segments.first { it is ModelAnswerSegment.DisplayMath } as ModelAnswerSegment.DisplayMath).latex
        assertEquals("(a + b)² = a² + 2ab + b²", ModelAnswerFormatter.formatMath(math))
    }

    @Test
    fun recognizesStandaloneBoxedAnswer() {
        val segments = ModelAnswerFormatter.parse("The answer is:\n\\boxed{STRAWBERRY}.\nThere are 2 R's.")
        val boxed = segments.filterIsInstance<ModelAnswerSegment.DisplayMath>().single()

        assertEquals("STRAWBERRY", ModelAnswerFormatter.boxedContent(boxed.latex))
    }

    @Test
    fun preservesLatexInsideCodeFence() {
        val segments = ModelAnswerFormatter.parse("```latex\n\\times \\frac{1}{2}\n```")
        val code = segments.single() as ModelAnswerSegment.Code

        assertEquals("\\times \\frac{1}{2}", code.code)
        assertEquals("latex", code.language)
    }

    @Test
    fun formatsFractionsRootsAndGreekSymbols() {
        assertEquals(
            "x = (-b ± √(b² - 4ac)⁄2a), θ ≈ π",
            ModelAnswerFormatter.formatMath("x = \\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}, \\theta \\approx \\pi")
        )
    }

    @Test
    fun routesComplexInlineFractionToNativeRenderer() {
        val segments = ModelAnswerFormatter.parse("Euler wrote \\(e^{i\\pi}+1=0\\), while \\(\\frac{a}{b}\\) is a fraction.")

        assertEquals(3, segments.size)
        assertTrue(segments[0] is ModelAnswerSegment.Text)
        assertEquals("\\frac{a}{b}", (segments[1] as ModelAnswerSegment.DisplayMath).latex)
        assertEquals(" is a fraction.", (segments[2] as ModelAnswerSegment.Text).text)
    }

    @Test
    fun supportsSingleDollarInlineMath() {
        val segments = ModelAnswerFormatter.parse("The ratio is $\\frac{x+1}{y-1}$ today.")

        assertEquals("The ratio is ", (segments[0] as ModelAnswerSegment.Text).text)
        assertEquals("\\frac{x+1}{y-1}", (segments[1] as ModelAnswerSegment.DisplayMath).latex)
        assertEquals(" today.", (segments[2] as ModelAnswerSegment.Text).text)
    }

    @Test
    fun preservesMatrixEnvironmentForNativeRenderer() {
        val source = "Matrix:\n\\begin{bmatrix}a & b \\\\ c & d\\end{bmatrix}\nDone."
        val math = ModelAnswerFormatter.parse(source)
            .filterIsInstance<ModelAnswerSegment.DisplayMath>()
            .single()

        assertEquals("\\begin{bmatrix}a & b \\\\ c & d\\end{bmatrix}", math.latex)
    }

    @Test
    fun preservesCasesChemistryAndAlignedDisplayMath() {
        val source = """
            \[\begin{cases}x^2 & x \ge 0 \\ -x & x < 0\end{cases}\]
            $$\ce{2H2 + O2 -> 2H2O}$$
            \[\begin{aligned}a&=b+c\\d&=e-f\end{aligned}\]
        """.trimIndent()
        val math = ModelAnswerFormatter.parse(source)
            .filterIsInstance<ModelAnswerSegment.DisplayMath>()

        assertEquals(3, math.size)
        assertTrue(math[0].latex.contains("\\begin{cases}"))
        assertEquals("\\ce{2H2 + O2 -> 2H2O}", math[1].latex)
        assertTrue(math[2].latex.contains("\\begin{aligned}"))
    }

    @Test
    fun decodesLiteralLineBreaksWithoutCorruptingLatexCommands() {
        val source = """\boxed{STRAWBERRY}.\n\nThere are 2 R's. \nabla is a command."""
        val normalized = ModelAnswerFormatter.normalizeEscapedModelText(source)

        assertEquals("\\boxed{STRAWBERRY}.\n\nThere are 2 R's. \\nabla is a command.", normalized)
        assertTrue(ModelAnswerFormatter.parse(normalized).any { it is ModelAnswerSegment.DisplayMath })
    }

    @Test
    fun normalizesDoubleEscapedLatexCommandsBeforeFormatting() {
        val source = """144 + 2.952 + 0.015129 = 146.967129 \\times 1"""

        assertEquals(
            "144 + 2.952 + 0.015129 = 146.967129 × 1",
            ModelAnswerFormatter.formatMath(source)
        )
    }

    @Test
    fun routesInlineBoxedAnswersToFormulaCard() {
        val segments = ModelAnswerFormatter.parse("The word is: \\boxed{STRAWBERRY}.")

        assertEquals("The word is: ", (segments[0] as ModelAnswerSegment.Text).text)
        assertEquals("\\boxed{STRAWBERRY}", (segments[1] as ModelAnswerSegment.DisplayMath).latex)
        assertEquals(".", (segments[2] as ModelAnswerSegment.Text).text)
    }

    @Test
    fun preservesInlineLatexSyntaxExamplesAsLiteralText() {
        val source = "Use `\\begin{bmatrix}a & b \\\\ c & d\\end{bmatrix}` for a matrix."
        val segments = ModelAnswerFormatter.parse(source)

        assertFalse(segments.any { it is ModelAnswerSegment.DisplayMath })
        assertEquals(source, (segments.single() as ModelAnswerSegment.Text).text)
    }

    @Test
    fun malformedFractionOrRootNeverCreatesAnEmptyFormulaCard() {
        val fraction = ModelAnswerFormatter.parse("- $\\frac$ : means division")
        val root = ModelAnswerFormatter.parse("$\\sqrt{}$ : means a square root")

        assertFalse(fraction.any { it is ModelAnswerSegment.DisplayMath })
        assertFalse(root.any { it is ModelAnswerSegment.DisplayMath })
        assertTrue((fraction.single() as ModelAnswerSegment.Text).text.contains("fraction"))
        assertTrue((root.single() as ModelAnswerSegment.Text).text.contains("square root"))
    }

    @Test
    fun recognizesMarkdownListsWithoutChangingMathematicalNegatives() {
        val source = """
            - \(\frac{b}{2a}\) is the vertex.
            - The discriminant is b^2 - 4ac.
            -4 is negative, and -b is a negative coefficient.
            - x sin(x) and - \cos(x) are negative terms.
        """.trimIndent()
        val text = ModelAnswerFormatter.parse(source)
            .filterIsInstance<ModelAnswerSegment.Text>()
            .joinToString("") { it.text }

        assertTrue(text.contains("• "))
        assertTrue(text.contains("-4"))
        assertTrue(text.contains("-b"))
        assertTrue(text.contains("- x sin(x)"))
        assertTrue(text.contains("- cos(x)"))
        assertFalse(ModelAnswerFormatter.parse(source).any { it is ModelAnswerSegment.DisplayMath })
        assertTrue(ModelAnswerFormatter.parse(source).any { it is ModelAnswerSegment.InlineMathListItem })
    }

    @Test
    fun keepsInlineMathAtTheStartOfAListItemInOneListSegment() {
        val source = "- \\(\\frac{\\phantom{0}}{\\phantom{0}}\\): This represents a fraction."
        val item = ModelAnswerFormatter.parse(source)
            .filterIsInstance<ModelAnswerSegment.InlineMathListItem>()
            .single()

        val math = item.parts.filterIsInstance<ModelAnswerListPart.Math>().single()
        assertEquals("\\frac{\\phantom{0}}{\\phantom{0}}", math.latex)
        assertEquals("\\frac{\\square}{\\square}", ModelAnswerFormatter.prepareLatexForRenderer(math.latex))
        assertEquals("(□⁄□)", ModelAnswerFormatter.formatMath(math.latex))
    }

    @Test
    fun recoversMalformedDelimiterBetweenTwoInlineListFormulas() {
        val source = "- \\(\\frac{1}{2}\\)\\(: \\(\\frac{\\phantom{0}}{\\phantom{0}}\\)\\: fraction symbol"
        val item = ModelAnswerFormatter.parse(source)
            .filterIsInstance<ModelAnswerSegment.InlineMathListItem>()
            .single()
        val math = item.parts.filterIsInstance<ModelAnswerListPart.Math>()
        val text = item.parts.filterIsInstance<ModelAnswerListPart.Text>().joinToString("") { it.text }

        assertEquals(listOf("\\frac{1}{2}", "\\frac{\\phantom{0}}{\\phantom{0}}"), math.map { it.latex })
        assertTrue(text.contains("fraction symbol"))
        assertFalse(text.contains("\\("))
        assertEquals("\\frac{\\square}{\\square}", ModelAnswerFormatter.prepareLatexForRenderer(math[1].latex))
    }

    @Test
    fun keepsInlineCodeAsDedicatedListContentWithoutVisibleBackticks() {
        val source = "- `a` and `b` are matrix elements."
        val item = ModelAnswerFormatter.parse(source)
            .filterIsInstance<ModelAnswerSegment.InlineMathListItem>()
            .single()

        assertEquals(listOf("a", "b"), item.parts.filterIsInstance<ModelAnswerListPart.Code>().map { it.code })
        assertFalse(item.parts.filterIsInstance<ModelAnswerListPart.Text>().any { it.text.contains('`') })
    }

    @Test
    fun removesMarkdownBoldMarkersAroundFormulaListItems() {
        val source = "6. **\\(\\sqrt{b^2 - 4ac}\\)**: **This is the discriminant root.**"
        val text = ModelAnswerFormatter.parse(source)
            .filterIsInstance<ModelAnswerSegment.Text>()
            .joinToString("") { it.text }

        assertTrue(ModelAnswerFormatter.parse(source).any { it is ModelAnswerSegment.DisplayMath })
        assertFalse(text.contains("**"))
        assertTrue(text.contains("6. "))
        assertTrue(text.contains("This is the discriminant root."))
    }
}
