package com.shounak.localmeshai.ai.tools

import java.math.BigDecimal
import kotlin.math.pow

/** Bounded arithmetic parser. Power binds more tightly than unary signs. */
object CalculatorEvaluator {
    fun evaluate(expression: String): String = try {
        require(expression.length <= 512) { "Expression exceeds 512 characters" }
        val value = Parser(expression.replace("×", "*").replace("÷", "/")
            .replace("−", "-").replace("**", "^")).parse()
        BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    } catch (e: IllegalArgumentException) {
        "Error: ${e.message}"
    }

    private class Parser(private val source: String) {
        private var position = 0
        private var depth = 0
        fun parse(): Double {
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
        private fun finite(value: Double): Double {
            require(value.isFinite()) { "Result is not finite" }
            return value
        }
        private fun sum(): Double {
            var value = product()
            while (true) value = when {
                take('+') -> finite(value + product())
                take('-') -> finite(value - product())
                else -> return value
            }
        }
        private fun product(): Double {
            var value = unary()
            while (true) value = when {
                take('*') -> finite(value * unary())
                take('/') -> { val rhs = unary(); require(rhs != 0.0) { "Division by zero" }; finite(value / rhs) }
                take('%') -> { val rhs = unary(); require(rhs != 0.0) { "Modulo by zero" }; finite(value % rhs) }
                else -> return value
            }
        }
        private inline fun nested(block: () -> Double): Double {
            require(++depth <= 64) { "Nesting exceeds 64 levels" }
            try { return block() } finally { depth-- }
        }
        private fun unary(): Double = when {
            take('+') -> nested { unary() }
            take('-') -> nested { -unary() }
            else -> power()
        }
        private fun power(): Double {
            val value = primary()
            return if (take('^')) finite(value.pow(nested { unary() })) else value
        }
        private fun primary(): Double {
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
            return finite(source.substring(start, position).toDoubleOrNull()
                ?: throw IllegalArgumentException("Invalid number"))
        }
    }
}
