package com.shounak.localmeshai.ai.tools

import java.math.BigDecimal
import java.math.MathContext

/**
 * Hand-written, sandboxed arithmetic evaluator used by the experimental calculator tool.
 *
 * Supported syntax: decimal numbers (`12`, `3.5`, `.5`, `1e3`), binary `+ - * / % ^`,
 * unary `+`/`-`, and parentheses. `^` is right-associative and binds tighter than unary
 * minus, so `-2^2 == -4` and `2^3^2 == 512`. `**`, `×`, `÷` and `−` are accepted as
 * aliases because models and users often emit them.
 *
 * There is no script engine, reflection, variable lookup, or function call support: the
 * input is tokenized and parsed by a small recursive-descent parser, and anything outside the
 * grammar is rejected with a readable error message.
 */
object CalculatorEvaluator {

    const val MAX_EXPRESSION_LENGTH = 512
    private const val MAX_NESTING_DEPTH = 64

    sealed class Result {
        data class Success(val value: Double) : Result() {
            /** Human/model friendly representation (no binary floating-point noise). */
            val formatted: String get() = formatNumber(value)
        }

        data class Error(val message: String) : Result()
    }

    fun evaluate(expression: String): Result {
        if (expression.isBlank()) return Result.Error("Error: expression is empty")
        if (expression.length > MAX_EXPRESSION_LENGTH) {
            return Result.Error("Error: expression is too long (max $MAX_EXPRESSION_LENGTH characters)")
        }
        return try {
            val tokens = tokenize(expression)
            val parser = Parser(tokens)
            val value = parser.parseExpression()
            if (!parser.isAtEnd()) {
                throw CalculatorException("unexpected '${parser.peek().text}' at position ${parser.peek().position + 1}")
            }
            if (value.isNaN() || value.isInfinite()) {
                Result.Error("Error: result is not a finite number")
            } else {
                Result.Success(if (value == 0.0) 0.0 else value) // normalise -0.0
            }
        } catch (e: CalculatorException) {
            Result.Error("Error: ${e.message}")
        }
    }

    fun formatNumber(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return value.toString()
        if (value == Math.rint(value) && kotlin.math.abs(value) < 1e15) {
            return value.toLong().toString()
        }
        return BigDecimal(value).round(MathContext(15)).stripTrailingZeros().let {
            if (kotlin.math.abs(value) >= 1e15 || kotlin.math.abs(value) < 1e-9) it.toString() else it.toPlainString()
        }
    }

    private class CalculatorException(message: String) : Exception(message)

    private enum class TokenType { NUMBER, PLUS, MINUS, STAR, SLASH, PERCENT, CARET, LPAREN, RPAREN, END }

    private data class Token(val type: TokenType, val text: String, val position: Int, val number: Double = 0.0)

    private fun tokenize(input: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < input.length) {
            val c = input[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() || c == '.' -> {
                    val start = i
                    var sawDigit = false
                    var sawDot = false
                    while (i < input.length && (input[i].isDigit() || input[i] == '.')) {
                        if (input[i] == '.') {
                            if (sawDot) throw CalculatorException("malformed number at position ${start + 1}")
                            sawDot = true
                        } else {
                            sawDigit = true
                        }
                        i++
                    }
                    if (!sawDigit) throw CalculatorException("malformed number at position ${start + 1}")
                    // Optional exponent: e.g. 1e3, 2.5E-4
                    if (i < input.length && (input[i] == 'e' || input[i] == 'E')) {
                        var j = i + 1
                        if (j < input.length && (input[j] == '+' || input[j] == '-')) j++
                        if (j < input.length && input[j].isDigit()) {
                            while (j < input.length && input[j].isDigit()) j++
                            i = j
                        } else {
                            throw CalculatorException("malformed exponent at position ${i + 1}")
                        }
                    }
                    val text = input.substring(start, i)
                    val value = text.toDoubleOrNull()
                        ?: throw CalculatorException("malformed number '$text'")
                    tokens += Token(TokenType.NUMBER, text, start, value)
                }
                c == '+' -> { tokens += Token(TokenType.PLUS, "+", i); i++ }
                c == '-' || c == '−' -> { tokens += Token(TokenType.MINUS, "-", i); i++ }
                c == '*' && i + 1 < input.length && input[i + 1] == '*' -> {
                    tokens += Token(TokenType.CARET, "^", i); i += 2
                }
                c == '*' || c == '×' -> { tokens += Token(TokenType.STAR, "*", i); i++ }
                c == '/' || c == '÷' -> { tokens += Token(TokenType.SLASH, "/", i); i++ }
                c == '%' -> { tokens += Token(TokenType.PERCENT, "%", i); i++ }
                c == '^' -> { tokens += Token(TokenType.CARET, "^", i); i++ }
                c == '(' -> { tokens += Token(TokenType.LPAREN, "(", i); i++ }
                c == ')' -> { tokens += Token(TokenType.RPAREN, ")", i); i++ }
                else -> throw CalculatorException("unsupported character '$c' at position ${i + 1}")
            }
        }
        tokens += Token(TokenType.END, "end of expression", input.length)
        return tokens
    }

    /**
     * Grammar:
     *   expression := term (('+' | '-') term)*
     *   term       := unary (('*' | '/' | '%') unary)*
     *   unary      := ('+' | '-') unary | power
     *   power      := primary ('^' unary)?
     *   primary    := NUMBER | '(' expression ')'
     */
    private class Parser(private val tokens: List<Token>) {
        private var index = 0
        private var depth = 0

        fun peek(): Token = tokens[index]
        fun isAtEnd(): Boolean = peek().type == TokenType.END
        private fun advance(): Token = tokens[index].also { if (it.type != TokenType.END) index++ }
        private fun match(type: TokenType): Boolean = if (peek().type == type) { advance(); true } else false

        private inline fun <T> nested(block: () -> T): T {
            depth++
            if (depth > MAX_NESTING_DEPTH) throw CalculatorException("expression is nested too deeply")
            try {
                return block()
            } finally {
                depth--
            }
        }

        fun parseExpression(): Double = nested {
            var left = parseTerm()
            var more = true
            while (more) {
                when {
                    match(TokenType.PLUS) -> left += parseTerm()
                    match(TokenType.MINUS) -> left -= parseTerm()
                    else -> more = false
                }
            }
            left
        }

        private fun parseTerm(): Double {
            var left = parseUnary()
            while (true) {
                when {
                    match(TokenType.STAR) -> left *= parseUnary()
                    match(TokenType.SLASH) -> {
                        val right = parseUnary()
                        if (right == 0.0) throw CalculatorException("division by zero")
                        left /= right
                    }
                    match(TokenType.PERCENT) -> {
                        val right = parseUnary()
                        if (right == 0.0) throw CalculatorException("modulo by zero")
                        left %= right
                    }
                    else -> return left
                }
            }
        }

        private fun parseUnary(): Double = nested {
            when {
                match(TokenType.MINUS) -> -parseUnary()
                match(TokenType.PLUS) -> parseUnary()
                else -> parsePower()
            }
        }

        private fun parsePower(): Double {
            val base = parsePrimary()
            if (match(TokenType.CARET)) {
                val exponent = parseUnary()
                if (base == 0.0 && exponent < 0) throw CalculatorException("division by zero")
                val result = Math.pow(base, exponent)
                if (result.isNaN()) throw CalculatorException("result is not a real number")
                return result
            }
            return base
        }

        private fun parsePrimary(): Double {
            val token = peek()
            return when (token.type) {
                TokenType.NUMBER -> { advance(); token.number }
                TokenType.LPAREN -> {
                    advance()
                    if (peek().type == TokenType.RPAREN) {
                        throw CalculatorException("empty parentheses at position ${token.position + 1}")
                    }
                    val value = parseExpression()
                    if (!match(TokenType.RPAREN)) throw CalculatorException("missing closing parenthesis")
                    value
                }
                TokenType.RPAREN -> throw CalculatorException("unexpected ')' at position ${token.position + 1}")
                TokenType.END -> throw CalculatorException("expression ended unexpectedly")
                else -> throw CalculatorException("unexpected '${token.text}' at position ${token.position + 1}")
            }
        }
    }
}
