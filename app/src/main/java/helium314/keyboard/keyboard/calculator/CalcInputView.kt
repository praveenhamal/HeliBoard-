// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.calculator

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings

/**
 * Thin bar shown above the numpad when the calculator toolbar key is tapped.
 * Left side shows the expression being built; right side shows the live result.
 *
 * Tapping the result side calls [onResultClicked] (wired by KeyboardSwitcher)
 * so the computed result is committed at the current cursor position.
 */
class CalcInputView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : LinearLayout(context, attrs, defStyle) {

    private var expressionView: TextView? = null
    private var resultView: TextView? = null

    val expression = StringBuilder()

    /**
     * Called when the user taps any part of the bar.
     * Wired by KeyboardSwitcher to call commitCalcResult().
     */
    var onResultClicked: (() -> Unit)? = null

    override fun onFinishInflate() {
        super.onFinishInflate()
        expressionView = findViewById(R.id.calc_expression)
        resultView     = findViewById(R.id.calc_result)

        // Tap ANYWHERE on the bar → commit the result and close calculator.
        // We use post{} to defer the callback because calling onTextInput() or setKeyboard()
        // synchronously inside a touch event can crash the IME.
        isClickable = true
        isFocusable = false
        setOnClickListener { post { onResultClicked?.invoke() } }
    }

    fun clear() {
        expression.clear()
        expressionView?.text = ""
        resultView?.text = ""
        applyColors()
    }

    private fun applyColors() {
        try {
            val colors = Settings.getValues()?.mColors ?: return
            colors.setBackground(this, ColorType.STRIP_BACKGROUND)
            val textColor = colors.get(ColorType.KEY_TEXT)
            expressionView?.setTextColor(textColor)
            expressionView?.setHintTextColor((textColor and 0x00FFFFFF) or 0x88000000.toInt())
            resultView?.setTextColor(textColor)
        } catch (_: Exception) {
            // ignore color errors — not worth crashing for
        }
    }

    fun open() {
        expression.clear()
        expressionView?.text = ""
        resultView?.text = ""
        applyColors()
        visibility = View.VISIBLE
    }

    fun close() {
        visibility = View.GONE
        expression.clear()
        expressionView?.text = ""
        resultView?.text = ""
    }

    fun appendChar(char: String): String? {
        expression.append(char)
        expressionView?.text = expression.toString()
        val result = helium314.keyboard.latin.utils.MathEvaluator.evaluate(expression.toString())
        resultView?.text = if (result != null) "= $result" else ""
        return result
    }

    fun deleteChar() {
        if (expression.isNotEmpty()) {
            expression.deleteCharAt(expression.length - 1)
            expressionView?.text = expression.toString()
            val result = helium314.keyboard.latin.utils.MathEvaluator.evaluate(expression.toString())
            resultView?.text = if (result != null) "= $result" else ""
        }
    }

    fun currentResult(): String? =
        helium314.keyboard.latin.utils.MathEvaluator.evaluate(expression.toString())
}
