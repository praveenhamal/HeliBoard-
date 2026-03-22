// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

/**
 * Evaluates simple math expressions like "5+3*2", "10/4", "50%2".
 * Supports: +  -  *  /  %  with correct operator precedence (* / % before + -).
 * Supports decimal numbers (e.g. 3.5+1.5).
 * Returns null on invalid input or divide-by-zero.
 * Zero dependencies, zero cost when not called.
 */
object MathEvaluator {

    private val VALID_EXPR = Regex("""^\s*-?\d+(\.\d+)?(\s*[+\-*/]\s*-?\d+(\.\d+)?)*\s*$""")

    fun evaluate(expression: String): String? {
        // Pre-process % as percentage (divide by 100), e.g. "5%" -> "5/100"
        val expr = expression.trim().replace("%", "/100")
        if (!VALID_EXPR.matches(expr)) return null
        return try {
            val result = parseExpression(expr.replace(" ", ""))
            if (result == null || result.isNaN() || result.isInfinite()) return null
            // Show as integer if no fractional part
            if (result == kotlin.math.floor(result) && !result.isInfinite())
                result.toLong().toString()
            else
                "%.6f".format(result).trimEnd('0').trimEnd('.')
        } catch (e: Exception) {
            null
        }
    }

    // Recursive descent: handles + and - at top level
    private fun parseExpression(expr: String): Double? {
        val terms = splitOnAddSub(expr) ?: return null
        var result = parseTerm(terms[0]) ?: return null
        var i = 1
        while (i < terms.size - 1) {
            val op = terms[i]
            val value = parseTerm(terms[i + 1]) ?: return null
            result = when (op) {
                "+" -> result + value
                "-" -> result - value
                else -> return null
            }
            i += 2
        }
        return result
    }

    // Handles * / % 
    private fun parseTerm(expr: String): Double? {
        val factors = splitOnMulDiv(expr) ?: return null
        var result = factors[0].toDoubleOrNull() ?: return null
        var i = 1
        while (i < factors.size - 1) {
            val op = factors[i]
            val value = factors[i + 1].toDoubleOrNull() ?: return null
            result = when (op) {
                "*" -> result * value
                "/" -> { if (value == 0.0) return null; result / value }
                else -> return null
            }
            i += 2
        }
        return result
    }

    // Split "3+4-2" into ["3", "+", "4", "-", "2"]
    // Careful not to split on minus signs that are part of negative numbers
    private fun splitOnAddSub(expr: String): List<String>? {
        val parts = mutableListOf<String>()
        var current = StringBuilder()
        var i = 0
        while (i < expr.length) {
            val c = expr[i]
            if ((c == '+' || c == '-') && i > 0 && expr[i - 1].isDigit()) {
                parts.add(current.toString())
                parts.add(c.toString())
                current = StringBuilder()
            } else {
                current.append(c)
            }
            i++
        }
        if (current.isEmpty()) return null
        parts.add(current.toString())
        return parts
    }

    // Split "3*4/2" into ["3", "*", "4", "/", "2"]
    private fun splitOnMulDiv(expr: String): List<String>? {
        val parts = mutableListOf<String>()
        var current = StringBuilder()
        var i = 0
        while (i < expr.length) {
            val c = expr[i]
            if ((c == '*' || c == '/') && i > 0) {
                parts.add(current.toString())
                parts.add(c.toString())
                current = StringBuilder()
            } else {
                current.append(c)
            }
            i++
        }
        parts.add(current.toString())
        return parts
    }
}
