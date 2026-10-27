package com.shounak.localmeshai.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalculatorDecimalTest {
    @Test fun decimalArithmeticDoesNotExposeBinaryRoundingArtifacts() {
        assertEquals("1", CalculatorEvaluator.evaluate("1/3*3"))
        assertEquals("1", CalculatorEvaluator.evaluate("(1/7)*7"))
        assertEquals("1", CalculatorEvaluator.evaluate("(1/3)^2*9"))
        assertEquals("0.5", CalculatorEvaluator.evaluate("1/6+1/3"))
        assertEquals("0", CalculatorEvaluator.evaluate("(2/3)%(1/3)"))
        assertEquals("0.3", CalculatorEvaluator.evaluate("0.1+0.2"))
        assertEquals("0.02", CalculatorEvaluator.evaluate("0.1*0.2"))
        assertEquals("0.1", CalculatorEvaluator.evaluate("0.3-0.2"))
        assertEquals("1.5", CalculatorEvaluator.evaluate("3/2"))
        assertEquals("9007199254740993", CalculatorEvaluator.evaluate("9007199254740992+1"))
    }

    @Test fun exactResultsKeepEveryDigitAndRoundedOnesAreMarked() {
        assertEquals("340282366920938463463374607431768211456",
            CalculatorEvaluator.evaluate("18446744073709551616*18446744073709551616"))
        assertEquals("1" + "0".repeat(39) + "1", CalculatorEvaluator.evaluate("10^40+1"))
        assertEquals("-12345678901234567890123456789012345678901",
            CalculatorEvaluator.evaluate("-12345678901234567890123456789012345678901"))
        assertEquals(java.math.BigDecimal.ONE.divide(java.math.BigDecimal(2).pow(100)).toPlainString(),
            CalculatorEvaluator.evaluate("2^-100"))
        assertEquals("≈0.3333333333333333333333333333333333 (rounded to 34 significant digits; exact value 1/3)",
            CalculatorEvaluator.evaluate("1/3"))
        assertEquals("≈-0.6666666666666666666666666666666667 (rounded to 34 significant digits; exact value -2/3)",
            CalculatorEvaluator.evaluate("-2/3"))
        assertTrue(CalculatorEvaluator.evaluate("2^0.5").startsWith("≈1.414213562373095"))
        assertTrue(CalculatorEvaluator.evaluate("2^0.5").endsWith("(approximate)"))
        assertTrue(CalculatorEvaluator.evaluate("2^0.5*2^0.5").startsWith("≈"))
        assertTrue(CalculatorEvaluator.evaluate("2^-200").startsWith("≈"))
    }

    @Test fun powersAndLimitsRemainBounded() {
        assertEquals("512", CalculatorEvaluator.evaluate("2^3^2"))
        assertEquals("-4", CalculatorEvaluator.evaluate("-2^2"))
        assertEquals("0.25", CalculatorEvaluator.evaluate("2^-2"))
        assertEquals("2", CalculatorEvaluator.evaluate("4^0.5"))
        assertTrue(CalculatorEvaluator.evaluate("1/0").startsWith("Error:"))
        assertTrue(CalculatorEvaluator.evaluate("1e-999999999").startsWith("Error:"))
    }
}
