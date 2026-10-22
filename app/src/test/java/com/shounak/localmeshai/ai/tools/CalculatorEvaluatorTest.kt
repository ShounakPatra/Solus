package com.shounak.localmeshai.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CalculatorEvaluatorTest {

    private fun value(expression: String): Double =
        when (val result = CalculatorEvaluator.evaluate(expression)) {
            is CalculatorEvaluator.Result.Success -> result.value
            is CalculatorEvaluator.Result.Error -> { fail("Expected success for '$expression' but got ${result.message}"); 0.0 }
        }

    private fun formatted(expression: String): String =
        (CalculatorEvaluator.evaluate(expression) as CalculatorEvaluator.Result.Success).formatted

    private fun error(expression: String): String =
        when (val result = CalculatorEvaluator.evaluate(expression)) {
            is CalculatorEvaluator.Result.Error -> result.message
            is CalculatorEvaluator.Result.Success -> { fail("Expected error for '$expression' but got ${result.value}"); "" }
        }

    @Test fun basicArithmetic() {
        assertEquals(5.0, value("2+3"), 0.0)
        assertEquals(-1.0, value("2-3"), 0.0)
        assertEquals(402.5, value("17.5*23"), 1e-12)
        assertEquals(2.5, value("5/2"), 0.0)
        assertEquals(1.0, value("10 % 3"), 0.0)
    }

    @Test fun operatorPrecedenceAndAssociativity() {
        assertEquals(14.0, value("2+3*4"), 0.0)
        assertEquals(20.0, value("(2+3)*4"), 0.0)
        assertEquals(2.0, value("8/2/2"), 0.0)
        assertEquals(3.0, value("10-5-2"), 0.0)
        assertEquals(512.0, value("2^3^2"), 0.0)   // right-associative
        assertEquals(17.0, value("1+2^4"), 0.0)
        assertEquals(48.0, value("3*2^4"), 0.0)
    }

    @Test fun unaryMinusAndPlus() {
        assertEquals(-5.0, value("-5"), 0.0)
        assertEquals(5.0, value("--5"), 0.0)
        assertEquals(5.0, value("+5"), 0.0)
        assertEquals(-4.0, value("-2^2"), 0.0)      // ^ binds tighter than unary minus
        assertEquals(4.0, value("(-2)^2"), 0.0)
        assertEquals(0.5, value("2^-1"), 0.0)
        assertEquals(-6.0, value("3*-2"), 0.0)
        assertEquals(1.0, value("-(2-3)"), 0.0)
    }

    @Test fun decimalsAndScientificNotation() {
        assertEquals(0.75, value(".5+.25"), 1e-12)
        assertEquals(1500.0, value("1.5e3"), 0.0)
        assertEquals(0.00025, value("2.5E-4"), 1e-15)
    }

    @Test fun nestedParenthesesAndWhitespace() {
        assertEquals(21.0, value(" ( ( 1 + 2 ) * ( 3 + 4 ) ) "), 0.0)
        assertEquals(10.0, value("((((10))))"), 0.0)
    }

    @Test fun aliasesAreAccepted() {
        assertEquals(8.0, value("2**3"), 0.0)
        assertEquals(6.0, value("2×3"), 0.0)
        assertEquals(2.0, value("6÷3"), 0.0)
        assertEquals(1.0, value("3−2"), 0.0)
    }

    @Test fun formattingRemovesFloatingPointNoise() {
        assertEquals("0.3", formatted("0.1+0.2"))
        assertEquals("402.5", formatted("17.5*23"))
        assertEquals("4", formatted("2+2"))
        assertEquals("-4", formatted("-2^2"))
        assertEquals("0", formatted("-0"))
        assertEquals("0.333333333333333", formatted("1/3"))
    }

    @Test fun divisionAndModuloByZeroAreReported() {
        assertEquals("Error: division by zero", error("1/0"))
        assertEquals("Error: division by zero", error("5/(2-2)"))
        assertEquals("Error: modulo by zero", error("5%0"))
        assertEquals("Error: division by zero", error("0^-1"))
    }

    @Test fun malformedInputIsRejectedWithClearMessages() {
        assertEquals("Error: expression is empty", error(""))
        assertEquals("Error: expression is empty", error("   "))
        assertTrue(error("2+").contains("ended unexpectedly"))
        assertTrue(error("(2+3").contains("missing closing parenthesis"))
        assertTrue(error("2+3)").contains("unexpected ')'"))
        assertTrue(error("()").contains("empty parentheses"))
        assertTrue(error("1..2").contains("malformed number"))
        assertTrue(error("1e").contains("malformed exponent"))
        assertTrue(error("2 3").contains("unexpected '3'"))
        assertTrue(error("*2").contains("unexpected '*'"))
    }

    @Test fun nonArithmeticInputIsRejected() {
        assertTrue(error("sqrt(4)").contains("unsupported character 's'"))
        assertTrue(error("x+1").contains("unsupported character 'x'"))
        assertTrue(error("java.lang.System.exit(0)").contains("unsupported character"))
        assertTrue(error("1; 2").contains("unsupported character ';'"))
    }

    @Test fun nonFiniteAndNonRealResultsAreErrors() {
        assertEquals("Error: result is not a finite number", error("10^400"))
        assertTrue(error("(-8)^0.5").contains("not a real number"))
    }

    @Test fun oversizedOrDeeplyNestedInputIsBounded() {
        assertTrue(error("1+".repeat(300) + "1").contains("too long"))
        assertTrue(error("(".repeat(100) + "1" + ")".repeat(100)).contains("nested too deeply"))
        assertTrue(error("-".repeat(100) + "1").contains("nested too deeply"))
    }
}
