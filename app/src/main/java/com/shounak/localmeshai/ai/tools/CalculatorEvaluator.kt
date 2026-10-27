package com.shounak.localmeshai.ai.tools

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import kotlin.math.pow

/** Bounded arithmetic parser. Power binds more tightly than unary signs. */
object CalculatorEvaluator {
    /** Longest terminating decimal printed exactly; longer ones are rounded and marked. */
    private const val MAX_EXACT_DIGITS = 100

    fun evaluate(expression: String): String = try {
        require(expression.length <= 512) { "Expression exceeds 512 characters" }
        val parser = Parser(expression.replace("×", "*").replace("÷", "/")
            .replace("−", "-").replace("**", "^"))
        format(parser.parse(), parser.approximate)
    } catch (e: ArithmeticException) {
        "Error: ${e.message}"
    } catch (e: IllegalArgumentException) {
        "Error: ${e.message}"
    }

    /**
     * Prints exact results with every digit; integers are already bounded by the parser's
     * limits. A value that cannot be shown exactly (a repeating decimal, a very long one, or a
     * non-integer power computed in floating point) is rounded to 34 significant digits and
     * marked as approximate, so it never looks exact when it is not.
     */
    private fun format(value: Rational, approximate: Boolean): String {
        if (!approximate) {
            if (value.denominator == BigInteger.ONE) return value.numerator.toString()
            value.exactDecimalOrNull(MAX_EXACT_DIGITS)?.let { return it }
        }
        val rounded = value.decimal(MathContext.DECIMAL128).stripTrailingZeros().toPlainString()
        val fraction = "${value.numerator}/${value.denominator}".takeIf { !approximate && it.length <= 80 }
        return if (fraction != null) "≈$rounded (rounded to 34 significant digits; exact value $fraction)"
            else "≈$rounded (approximate)"
    }

    /** Fractions keep division exact until the final displayed decimal is rounded. */
    private class Rational private constructor(val numerator: BigInteger, val denominator: BigInteger) {
        fun decimal(context: MathContext) = BigDecimal(numerator).divide(BigDecimal(denominator), context)

        /** The exact decimal when the fraction terminates within [maxDigits] significant digits. */
        fun exactDecimalOrNull(maxDigits: Int): String? {
            var rest = denominator
            val two = BigInteger.valueOf(2)
            val five = BigInteger.valueOf(5)
            while (rest.mod(two).signum() == 0) rest /= two
            while (rest.mod(five).signum() == 0) rest /= five
            if (rest != BigInteger.ONE) return null
            // Only 2s and 5s remain, so this division terminates and is exact.
            val exact = BigDecimal(numerator).divide(BigDecimal(denominator)).stripTrailingZeros()
            return if (exact.precision() <= maxDigits) exact.toPlainString() else null
        }
        fun signum() = numerator.signum()
        operator fun plus(other: Rational) = of(numerator * other.denominator + other.numerator * denominator, denominator * other.denominator)
        operator fun minus(other: Rational) = this + -other
        operator fun unaryMinus() = of(-numerator, denominator)
        operator fun times(other: Rational) = of(numerator * other.numerator, denominator * other.denominator)
        operator fun div(other: Rational) = of(numerator * other.denominator, denominator * other.numerator)
        fun remainder(other: Rational): Rational {
            val quotient = (numerator * other.denominator) / (denominator * other.numerator)
            return this - of(quotient, BigInteger.ONE) * other
        }
        fun integerOrNull(): Int? = if (denominator != BigInteger.ONE) null else runCatching { numerator.intValueExact() }.getOrNull()
        fun pow(exponent: Int): Rational {
            val count = kotlin.math.abs(exponent)
            require(maxOf(numerator.bitLength(), denominator.bitLength()).toLong() * count <= 32768L) { "Result exceeds calculator limits" }
            val n = numerator.pow(count)
            val d = denominator.pow(count)
            return if (exponent < 0) of(d, n) else of(n, d)
        }
        companion object {
            fun of(n: BigInteger, d: BigInteger): Rational {
                require(d.signum() != 0) { "Division by zero" }
                val divisor = n.gcd(d)
                val sign = BigInteger.valueOf(d.signum().toLong())
                val numerator = n / divisor * sign
                val denominator = d.abs() / divisor
                require(maxOf(numerator.bitLength(), denominator.bitLength()) <= 32768) { "Result exceeds calculator limits" }
                return Rational(numerator, denominator)
            }
            fun of(value: BigDecimal): Rational {
                val scale = value.scale()
                require(scale.toLong() in -10_000L..10_000L) { "Result exceeds calculator limits" }
                return if (scale >= 0) of(value.unscaledValue(), BigInteger.TEN.pow(scale))
                    else of(value.unscaledValue() * BigInteger.TEN.pow(-scale), BigInteger.ONE)
            }
        }
    }

    private class Parser(private val source: String) {
        private var position = 0
        private var depth = 0
        /** Set once a non-integer power was computed in floating point. */
        var approximate = false
            private set
        fun parse(): Rational {
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
        private fun finite(value: Rational): Rational {
            require(value.decimal(MathContext.DECIMAL64).toDouble().isFinite()) { "Result is not finite" }
            return value
        }
        private fun sum(): Rational {
            var value = product()
            while (true) value = when {
                take('+') -> finite(value + product())
                take('-') -> finite(value - product())
                else -> return value
            }
        }
        private fun product(): Rational {
            var value = unary()
            while (true) value = when {
                take('*') -> finite(value * unary())
                take('/') -> { val rhs = unary(); require(rhs.signum() != 0) { "Division by zero" }; finite(value / rhs) }
                take('%') -> { val rhs = unary(); require(rhs.signum() != 0) { "Modulo by zero" }; finite(value.remainder(rhs)) }
                else -> return value
            }
        }
        private inline fun nested(block: () -> Rational): Rational {
            require(++depth <= 64) { "Nesting exceeds 64 levels" }
            try { return block() } finally { depth-- }
        }
        private fun unary(): Rational = when {
            take('+') -> nested { unary() }
            take('-') -> nested { -unary() }
            else -> power()
        }
        private fun power(): Rational {
            val value = primary()
            if (!take('^')) return value
            val exponent = nested { unary() }
            val integer = exponent.integerOrNull()
            if (integer != null && integer in -4096..4096) {
                return finite(value.pow(integer))
            }
            val estimate = value.decimal(MathContext.DECIMAL64).toDouble().pow(exponent.decimal(MathContext.DECIMAL64).toDouble())
            require(estimate.isFinite()) { "Result is not finite" }
            // A floating-point integer such as 4^0.5 is exact; anything else is an estimate.
            if (estimate != kotlin.math.floor(estimate)) approximate = true
            return finite(Rational.of(BigDecimal.valueOf(estimate)))
        }
        private fun primary(): Rational {
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
            return finite(Rational.of(source.substring(start, position).toBigDecimalOrNull()
                ?: throw IllegalArgumentException("Invalid number")))
        }
    }
}
