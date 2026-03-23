// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import java.util.Locale

/**
 * Evaluates simple math expressions like "(5+3)*2", "10/4", "50%".
 * Supports: +  -  *  /  %  ( ) with correct operator precedence.
 * Returns null on invalid input or divide-by-zero.
 */
object MathEvaluator {

    fun evaluate(expression: String): String? {
        val expr = expression.trim()
        if (expr.isEmpty()) return null
        return try {
            val result = Parser(expr).parse()
            if (result.isNaN() || result.isInfinite()) return null
            // Show as integer if no fractional part
            if (result == kotlin.math.floor(result) && result < Long.MAX_VALUE && result > Long.MIN_VALUE)
                result.toLong().toString()
            else
                "%.6f".format(Locale.US, result).trimEnd('0').trimEnd('.')
        } catch (e: Exception) {
            null
        }
    }

    private class Parser(val input: String) {
        private var pos = -1
        private var ch = -1

        private fun nextChar() {
            ch = if (++pos < input.length) input[pos].code else -1
        }

        private fun eat(charToEat: Int): Boolean {
            while (ch == ' '.code) nextChar()
            if (ch == charToEat) {
                nextChar()
                return true
            }
            return false
        }

        fun parse(): Double {
            nextChar()
            val x = parseExpression()
            if (pos < input.length) throw Exception("Unexpected character: " + ch.toChar())
            return x
        }

        // Grammar:
        // expression = term | expression `+` term | expression `-` term
        // term = factor | term `*` factor | term `/` factor
        // factor = `+` factor | `-` factor | `(` expression `)` | number | factor `%`

        private fun parseExpression(): Double {
            var x = parseTerm()
            while (true) {
                if (eat('+'.code)) x += parseTerm()
                else if (eat('-'.code)) x -= parseTerm()
                else return x
            }
        }

        private fun parseTerm(): Double {
            var x = parseFactor()
            while (true) {
                if (eat('*'.code)) x *= parseFactor()
                else if (eat('/'.code)) {
                    val div = parseFactor()
                    if (div == 0.0) throw Exception("Division by zero")
                    x /= div
                } else return x
            }
        }

        private fun parseFactor(): Double {
            if (eat('+'.code)) return parseFactor()
            if (eat('-'.code)) return -parseFactor()

            var x: Double
            val startPos = this.pos
            if (eat('('.code)) {
                x = parseExpression()
                if (!eat(')'.code)) throw Exception("Missing closing parenthesis")
            } else if ((ch >= '0'.code && ch <= '9'.code) || ch == '.'.code) {
                while ((ch >= '0'.code && ch <= '9'.code) || ch == '.'.code) nextChar()
                x = input.substring(startPos, this.pos).toDouble()
            } else {
                throw Exception("Unexpected character: " + ch.toChar())
            }

            // Handle percentage as a postfix operator
            while (eat('%'.code)) {
                x /= 100.0
            }

            return x
        }
    }
}
