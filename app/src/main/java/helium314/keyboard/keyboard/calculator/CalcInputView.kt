// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.calculator

import android.content.Context
import android.util.AttributeSet
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.brightenOrDarken
import helium314.keyboard.latin.utils.darken
import helium314.keyboard.latin.utils.isBrightColor

/**
 * Thin bar shown above the numpad when the calculator toolbar key is tapped.
 * Left side shows the expression being built; right side shows the live result.
 *
 * Swipe on the bar moves the cursor across the expression.
 * Committing the result is now done solely via the "=" key (or Enter).
 */
class CalcInputView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : LinearLayout(context, attrs, defStyle) {

    private var expressionView: EditText? = null
    private var resultView: TextView? = null
    private var displayContainer: View? = null

    val expression = StringBuilder()

    private var initialX = 0f
    private var lastMoveBy = 0

    override fun onFinishInflate() {
        super.onFinishInflate()
        expressionView   = findViewById(R.id.calc_expression)
        resultView       = findViewById(R.id.calc_result)
        displayContainer = findViewById(R.id.calc_display_container)

        // Cursor movement via swipe
        val threshold = 20f // pixels per character
        setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = event.x
                    lastMoveBy = 0
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.x - initialX
                    val moveBy = (deltaX / threshold).toInt()
                    if (moveBy != lastMoveBy) {
                        val view = expressionView ?: return@setOnTouchListener true
                        val currentPos = view.selectionStart
                        val newPos = (currentPos + (moveBy - lastMoveBy)).coerceIn(0, expression.length)
                        view.setSelection(newPos)
                        lastMoveBy = moveBy
                    }
                    true
                }
                else -> false
            }
        }
    }

    fun clear() {
        expression.clear()
        expressionView?.setText("")
        resultView?.text = ""
        applyColors()
    }

    private fun applyColors() {
        try {
            val colors = Settings.getValues()?.mColors ?: return
            val stripColor = colors.get(ColorType.STRIP_BACKGROUND)
            setBackgroundColor(stripColor)

            val textColor = colors.get(ColorType.KEY_TEXT)
            val displayBgBase = colors.get(ColorType.STRIP_BACKGROUND)
            val displayBg = if (isBrightColor(displayBgBase)) darken(displayBgBase) else brightenOrDarken(displayBgBase, true)

            val displayDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(displayBg)
                cornerRadius = 4f * resources.displayMetrics.density
                setStroke((1f * resources.displayMetrics.density).toInt(), textColor and 0x33FFFFFF or 0x22000000)
            }
            displayContainer?.background = displayDrawable

            expressionView?.setTextColor(textColor)
            expressionView?.setHintTextColor((textColor and 0x00FFFFFF) or 0x88000000.toInt())
            expressionView?.typeface = Typeface.MONOSPACE
            
            resultView?.setTextColor(textColor)
            resultView?.typeface = Typeface.MONOSPACE
        } catch (_: Exception) {
            // ignore color errors — not worth crashing for
        }
    }

    fun open() {
        expression.clear()
        expressionView?.setText("")
        resultView?.text = ""
        applyColors()
        visibility = View.VISIBLE
    }

    fun close() {
        visibility = View.GONE
        expression.clear()
        expressionView?.setText("")
        resultView?.text = ""
    }

    fun appendChar(char: String): String? {
        val view = expressionView ?: return null
        val cursorPos = view.selectionStart.coerceIn(0, expression.length)

        // Prevent consecutive identical operators
        if ("+-*/%".contains(char) && expression.isNotEmpty()) {
            val lastChar = if (cursorPos > 0) expression[cursorPos - 1].toString() else ""
            if (lastChar == char) return currentResult()
        }

        expression.insert(cursorPos, char)
        view.setText(expression.toString())
        view.setSelection((cursorPos + char.length).coerceAtMost(expression.length))

        val result = helium314.keyboard.latin.utils.MathEvaluator.evaluate(expression.toString())
        resultView?.text = if (result != null) "= $result" else ""
        return result
    }

    fun deleteChar() {
        val view = expressionView ?: return
        val cursorPos = view.selectionStart.coerceIn(0, expression.length)
        if (cursorPos > 0) {
            expression.deleteCharAt(cursorPos - 1)
            view.setText(expression.toString())
            view.setSelection((cursorPos - 1).coerceAtMost(expression.length))

            val result = helium314.keyboard.latin.utils.MathEvaluator.evaluate(expression.toString())
            resultView?.text = if (result != null) "= $result" else ""
        }
    }

    fun currentResult(): String? =
        helium314.keyboard.latin.utils.MathEvaluator.evaluate(expression.toString())
}
