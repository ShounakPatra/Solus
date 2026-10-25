package com.shounak.localmeshai.ai.tools

import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.pow

/** Bounded arithmetic parser. Power binds more tightly than unary signs. */
object CalculatorEvaluator {
    fun evaluate(expression: String): String = try {
        require(expression.length <= 512) { "Expression exceeds 512 characters" }
        val value = Parser(expression.replace("×", "*").replace("÷", "/")
            .replace("−", "-").replace("**", "^")).parse()
        value.stripTrailingZeros().toPlainString()
    } catch (e: ArithmeticException) {
        "Error: ${e.message}"
    } catch (e: IllegalArgumentException) {
        "Error: ${e.message}"
    }

    private class Parser(private val source: String) {
        private var position = 0
        private var depth = 0
        fun parse(): BigDecimal {
            val result = sum()
            whitespace()
            require(position == source.length) { "Unexpected character at position ${position + 1}" }
            return finite(result)
        }
        private fun whitespace() { while (source.getOrNull(position)?.isWhitespace() == true) position++ }
        private fun take(c: Char): Boolean {
            whitespace()
            return if (source.getOrNull(position) == c) { position++; true } else false
        }
        private fun finite(value: BigDecimal): BigDecimal {
            require(value.toDouble().isFinite()) { "Result is not finite" }
            require(value.scale().toLong() in -10_000L..10_000L && value.precision() <= 10_000) { "Result exceeds calculator limits" }
            return value
        }
        private fun sum(): BigDecimal {
            var value = product()
            while (true) value = when {
                take('+') -> finite(value + product())
                take('-') -> finite(value - product())
                else -> return value
            }
        }
        private fun product(): BigDecimal {
            var value = unary()
            while (true) value = when {
                take('*') -> finite(value * unary())
                take('/') -> { val rhs = unary(); require(rhs.signum() != 0) { "Division by zero" }; finite(value.divide(rhs, MathContext.DECIMAL128)) }
                take('%') -> { val rhs = unary(); require(rhs.signum() != 0) { "Modulo by zero" }; finite(value.remainder(rhs)) }
                else -> return value
            }
        }
        private inline fun nested(block: () -> BigDecimal): BigDecimal {
            require(++depth <= 64) { "Nesting exceeds 64 levels" }
            try { return block() } finally { depth-- }
        }
        private fun unary(): BigDecimal = when {
            take('+') -> nested { unary() }
            take('-') -> nested { -unary() }
            else -> power()
        }
        private fun power(): BigDecimal {
            val value = primary()
            if (!take('^')) return value
            val exponent = nested { unary() }
            val integer = runCatching { exponent.intValueExact() }.getOrNull()
            if (integer != null && integer in -4096..4096) {
                val powered = value.pow(kotlin.math.abs(integer), MathContext.DECIMAL128)
                return finite(if (integer < 0) BigDecimal.ONE.divide(powered, MathContext.DECIMAL128) else powered)
            }
            val approximate = value.toDouble().pow(exponent.toDouble())
            require(approximate.isFinite()) { "Result is not finite" }
            return finite(BigDecimal.valueOf(approximate))
        }
        private fun primary(): BigDecimal {
            if (take('(')) return nested {
                val value = sum()
                require(take(')')) { "Expected closing parenthesis" }
                value
            }
            whitespace()
            val start = position
            var digits = 0
            while (source.getOrNull(position)?.isDigit() == true) { digits++; position++ }
            if (source.getOrNull(position) == '.') {
                position++
                while (source.getOrNull(position)?.isDigit() == true) { digits++; position++ }
            }
            require(digits > 0) { "Expected number at position ${start + 1}" }
            if (source.getOrNull(position) in listOf('e', 'E')) {
                position++
                if (source.getOrNull(position) in listOf('+', '-')) position++
                val exponentStart = position
                while (source.getOrNull(position)?.isDigit() == true) position++
                require(position > exponentStart) { "Expected exponent" }
            }
            return finite(source.substring(start, position).toBigDecimalOrNull()
                ?: throw IllegalArgumentException("Invalid number"))
        }
    }
}
