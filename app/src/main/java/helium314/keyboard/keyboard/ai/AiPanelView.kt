// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.ai

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import helium314.keyboard.latin.R
import helium314.keyboard.latin.ai.AiClient
import helium314.keyboard.latin.ai.AiMode
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs

/**
 * Panel shown in place of the keyboard when the AI toolbar key is tapped while text is selected.
 * Top: preview of the (rewritten) text. Below: Proofread / Formal / Casual / Human, then cancel and apply.
 * Every mode is applied to the originally selected text, not to the previous result.
 */
class AiPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : LinearLayout(context, attrs, defStyle) {

    /** called with the text that should replace the selection */
    var onApply: ((String) -> Unit)? = null
    var onCancel: (() -> Unit)? = null

    private lateinit var textBox: TextView
    private lateinit var applyButton: Button
    private lateinit var cancelButton: Button
    private val modeButtons = ArrayList<Pair<Button, AiMode>>()

    private var original = ""
    private var current = ""
    private var requestId = 0
    private var loading = false

    override fun onFinishInflate() {
        super.onFinishInflate()
        textBox = findViewById(R.id.ai_text)
        applyButton = findViewById(R.id.ai_apply)
        cancelButton = findViewById(R.id.ai_cancel)
        modeButtons += findViewById<Button>(R.id.ai_proofread) to AiMode.PROOFREAD
        modeButtons += findViewById<Button>(R.id.ai_formal) to AiMode.FORMAL
        modeButtons += findViewById<Button>(R.id.ai_casual) to AiMode.CASUAL
        modeButtons += findViewById<Button>(R.id.ai_human) to AiMode.HUMAN
        modeButtons.forEach { (button, mode) -> button.setOnClickListener { run(mode) } }
        cancelButton.setOnClickListener { onCancel?.invoke() }
        applyButton.setOnClickListener {
            if (loading) return@setOnClickListener
            if (current != original) onApply?.invoke(current) else onCancel?.invoke()
        }
    }

    fun open(selectedText: String) {
        requestId++ // drop any in-flight request
        original = selectedText
        current = selectedText
        loading = false
        textBox.text = selectedText
        updateButtons()
        applyColors()
        visibility = View.VISIBLE
    }

    fun close() {
        requestId++
        loading = false
        visibility = View.GONE
        original = ""
        current = ""
    }

    private fun run(mode: AiMode) {
        val id = ++requestId
        loading = true
        textBox.setText(R.string.ai_working)
        updateButtons()
        AiClient.rewrite(context.prefs(), original, mode) { result ->
            post {
                if (id != requestId) return@post // closed or superseded
                loading = false
                result.onSuccess {
                    current = it
                    textBox.text = it
                }.onFailure {
                    current = original
                    textBox.text = original
                    helium314.keyboard.keyboard.KeyboardSwitcher.getInstance()
                        .showToast(context.getString(R.string.ai_error, it.message ?: ""), false)
                }
                updateButtons()
            }
        }
    }

    private fun updateButtons() {
        modeButtons.forEach { it.first.isEnabled = !loading }
        applyButton.isEnabled = !loading
        applyButton.alpha = if (!loading && current != original) 1f else 0.5f
    }

    private fun applyColors() {
        try {
            val colors = Settings.getValues().mColors
            colors.setBackground(this, ColorType.STRIP_BACKGROUND)
            val textColor = colors.get(ColorType.KEY_TEXT)
            textBox.setTextColor(textColor)
            colors.setBackground(textBox, ColorType.KEY_BACKGROUND)
            (modeButtons.map { it.first } + applyButton + cancelButton).forEach {
                it.setTextColor(textColor)
                colors.setBackground(it, ColorType.KEY_BACKGROUND)
            }
        } catch (_: Exception) {
            // colors are cosmetic, never crash the keyboard for them
        }
    }
}
