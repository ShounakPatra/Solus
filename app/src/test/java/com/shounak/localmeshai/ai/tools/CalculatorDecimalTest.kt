package com.shounak.localmeshai.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalculatorDecimalTest {
    @Test fun decimalArithmeticDoesNotExposeBinaryRoundingArtifacts() {
        assertEquals("0.3", CalculatorEvaluator.evaluate("0.1+0.2"))
        assertEquals("0.02", CalculatorEvaluator.evaluate("0.1*0.2"))
        assertEquals("0.1", CalculatorEvaluator.evaluate("0.3-0.2"))
        assertEquals("1.5", CalculatorEvaluator.evaluate("3/2"))
        assertEquals("9007199254740993", CalculatorEvaluator.evaluate("9007199254740992+1"))
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
