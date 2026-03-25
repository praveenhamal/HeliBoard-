// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.keyboard.clipboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.View.MeasureSpec
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.keyboard.KeyboardId
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.MainKeyboardView
import helium314.keyboard.keyboard.PointerTracker
import helium314.keyboard.keyboard.internal.KeyDrawParams
import helium314.keyboard.keyboard.internal.KeyVisualAttributes
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.latin.ClipboardHistoryManager
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.createToolbarKey
import helium314.keyboard.latin.utils.getCodeForToolbarKey
import helium314.keyboard.latin.utils.getCodeForToolbarKeyLongClick
import helium314.keyboard.latin.utils.getEnabledClipboardToolbarKeys
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.setToolbarButtonsActivatedStateOnPrefChange

@SuppressLint("CustomViewStyleable")
class ClipboardHistoryView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet?,
        defStyle: Int = R.attr.clipboardHistoryViewStyle
) : FrameLayout(context, attrs, defStyle), View.OnClickListener,
    ClipboardDao.Listener, OnKeyEventListener,
    View.OnLongClickListener, SharedPreferences.OnSharedPreferenceChangeListener {

    private val clipboardLayoutParams = ClipboardLayoutParams(context)
    private val pinIconId: Int
    private val keyBackgroundId: Int

    private lateinit var clipboardRecyclerView: ClipboardHistoryRecyclerView
    private lateinit var placeholderView: TextView
    private lateinit var contentContainer: FrameLayout
    private var confirmationView: View? = null
    private var isShowingClearConfirmation = false
    private val toolbarKeys = mutableListOf<ImageButton>()
    private lateinit var clipboardAdapter: ClipboardAdapter

    lateinit var keyboardActionListener: KeyboardActionListener
    private lateinit var clipboardHistoryManager: ClipboardHistoryManager

    init {
        val clipboardViewAttr = context.obtainStyledAttributes(attrs,
                R.styleable.ClipboardHistoryView, defStyle, R.style.ClipboardHistoryView)
        pinIconId = clipboardViewAttr.getResourceId(R.styleable.ClipboardHistoryView_iconPinnedClip, 0)
        clipboardViewAttr.recycle()
        @SuppressLint("UseKtx")
        val keyboardViewAttr = context.obtainStyledAttributes(attrs, R.styleable.KeyboardView, defStyle, R.style.KeyboardView)
        keyBackgroundId = keyboardViewAttr.getResourceId(R.styleable.KeyboardView_keyBackground, 0)
        keyboardViewAttr.recycle()
        if (Settings.getValues().mSecondaryStripVisible) {
            getEnabledClipboardToolbarKeys(context.prefs())
                .forEach { toolbarKeys.add(createToolbarKey(context, it)) }
        }
        fitsSystemWindows = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val sv = Settings.getValues()
        val res = context.resources
        val width = ResourceUtils.getKeyboardWidth(context, sv) + paddingLeft + paddingRight
        val height = ResourceUtils.getSecondaryKeyboardHeight(res, sv) + paddingTop + paddingBottom
        setMeasuredDimension(width, height)
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initialize() {
        if (this::clipboardAdapter.isInitialized) return
        val colors = Settings.getValues().mColors

        clipboardAdapter = ClipboardAdapter(clipboardLayoutParams, this).apply {
            itemBackgroundId = keyBackgroundId
            pinnedIconResId = pinIconId
            // Delegate clip editing to KeyboardSwitcher — edit panel lives above the full keyboard
            onEditRequested = { id, currentText, currentTrigger ->
                KeyboardSwitcher.getInstance().startClipEdit(id, currentText, currentTrigger, clipboardHistoryManager)
            }
        }
        placeholderView = findViewById(R.id.clipboard_empty_view)
        clipboardRecyclerView = findViewById<ClipboardHistoryRecyclerView>(R.id.clipboard_list).apply {
            val colCount = resources.getInteger(R.integer.config_clipboard_keyboard_col_count)
            layoutManager = StaggeredGridLayoutManager(colCount, StaggeredGridLayoutManager.VERTICAL)
            @Suppress("deprecation")
            persistentDrawingCache = PERSISTENT_NO_CACHE
            clipboardLayoutParams.setListProperties(this)
            placeholderView = this@ClipboardHistoryView.placeholderView
        }
        contentContainer = findViewById(R.id.clipboard_content_container)
        val clipboardStrip = KeyboardSwitcher.getInstance().clipboardStrip
        toolbarKeys.forEach {
            clipboardStrip.addView(it)
            it.setOnClickListener(this@ClipboardHistoryView)
            it.setOnLongClickListener(this@ClipboardHistoryView)
            colors.setColor(it, ColorType.TOOL_BAR_KEY)
            colors.setBackground(it, ColorType.STRIP_BACKGROUND)
        }
    }

    // ── Existing setup helpers ────────────────────────────────────────────────

    private fun setupClipKey(params: KeyDrawParams) {
        clipboardAdapter.apply {
            itemBackgroundId = keyBackgroundId
            itemTypeFace = params.mTypeface
            itemTextColor = params.mTextColor
            itemTextSize = params.mLabelSize.toFloat()
        }
    }

    private fun setupToolbarKeys() {
        val toolbarKeyLayoutParams = LinearLayout.LayoutParams(resources.getDimensionPixelSize(R.dimen.config_suggestions_strip_edge_key_width), LinearLayout.LayoutParams.MATCH_PARENT)
        toolbarKeys.forEach { it.layoutParams = toolbarKeyLayoutParams }
    }

    private fun setupBottomRowKeyboard(editorInfo: EditorInfo, listener: KeyboardActionListener) {
        val keyboardView = findViewById<MainKeyboardView>(R.id.bottom_row_keyboard)
        keyboardView.setKeyboardActionListener(listener)
        PointerTracker.switchTo(keyboardView)
        val kls = KeyboardLayoutSet.Builder.buildEmojiClipBottomRow(context, editorInfo)
        val keyboard = kls.getKeyboard(KeyboardId.ELEMENT_CLIPBOARD_BOTTOM_ROW)
        keyboardView.setKeyboard(keyboard)
    }

    fun setHardwareAcceleratedDrawingEnabled(enabled: Boolean) {
        if (!enabled) return
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun startClipboardHistory(
            historyManager: ClipboardHistoryManager,
            keyVisualAttr: KeyVisualAttributes?,
            editorInfo: EditorInfo,
            keyboardActionListener: KeyboardActionListener
    ) {
        clipboardHistoryManager = historyManager
        initialize()
        setupToolbarKeys()
        historyManager.prepareClipboardHistory()
        historyManager.setHistoryChangeListener(this)
        clipboardAdapter.clipboardHistoryManager = historyManager

        val params = KeyDrawParams()
        params.updateParams(clipboardLayoutParams.bottomRowKeyboardHeight, keyVisualAttr)
        val settings = Settings.getInstance()
        settings.getCustomTypeface()?.let { params.mTypeface = it }
        setupClipKey(params)
        setupBottomRowKeyboard(editorInfo, keyboardActionListener)
        
        if (historyManager.pendingShowClearConfirmation) {
            historyManager.pendingShowClearConfirmation = false
            post { showClearConfirmation() }
        }
        updatePlaceholderVisibility()

        placeholderView.apply {
            typeface = params.mTypeface
            setTextColor(params.mTextColor)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, params.mLabelSize.toFloat() * 2)
        }
        clipboardRecyclerView.apply {
            adapter = clipboardAdapter
            val keyboardWidth = ResourceUtils.getKeyboardWidth(context, settings.current)
            layoutParams.width = keyboardWidth

            val keyboardAttr = context.obtainStyledAttributes(
                null, R.styleable.Keyboard, R.attr.keyboardStyle, R.style.Keyboard)
            val leftPadding = (keyboardAttr.getFraction(R.styleable.Keyboard_keyboardLeftPadding,
                keyboardWidth, keyboardWidth, 0f)
                    * settings.current.mSidePaddingScale).toInt()
            val rightPadding = (keyboardAttr.getFraction(R.styleable.Keyboard_keyboardRightPadding,
                keyboardWidth, keyboardWidth, 0f)
                    * settings.current.mSidePaddingScale).toInt()
            keyboardAttr.recycle()
            
            // clear root padding and move to inner layout
            findViewById<View>(R.id.clipboard_history_main_layout)?.setPadding(leftPadding, paddingTop, rightPadding, paddingBottom)
            setPadding(0, 0, 0, 0)
        }

    }

    fun stopClipboardHistory() {
        hideClearConfirmation()
        if (!this::clipboardAdapter.isInitialized) return
        clipboardRecyclerView.adapter = null
        clipboardHistoryManager.setHistoryChangeListener(null)
        clipboardAdapter.clipboardHistoryManager = null
    }

    // ── Toolbar click handling ────────────────────────────────────────────────

    override fun onClick(view: View) {
        if (isShowingClearConfirmation) return
        val tag = view.tag
        if (tag is ToolbarKey) {
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, this, HapticEvent.KEY_PRESS)
            val code = getCodeForToolbarKey(tag)
            if (code == KeyCode.CLIPBOARD_CLEAR_HISTORY) {
                showClearConfirmation()
                return
            }
            if (code != KeyCode.UNSPECIFIED) {
                keyboardActionListener.onCodeInput(code, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
                return
            }
        }
    }

    override fun onLongClick(view: View): Boolean {
        if (isShowingClearConfirmation) return true
        val tag = view.tag
        if (tag is ToolbarKey) {
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, this, HapticEvent.KEY_LONG_PRESS)
            val longClickCode = getCodeForToolbarKeyLongClick(tag)
            if (longClickCode != KeyCode.UNSPECIFIED) {
                keyboardActionListener.onCodeInput(
                    longClickCode,
                    Constants.NOT_A_COORDINATE,
                    Constants.NOT_A_COORDINATE,
                    false
                )
            }
            return true
        }
        return false
    }

    private fun showClearConfirmation() {
        if (isShowingClearConfirmation) return
        isShowingClearConfirmation = true
        if (confirmationView == null) {
            val colors = Settings.getValues().mColors
            val fg = colors.get(ColorType.KEY_TEXT)
            
            // outer container to block touches and fill area
            confirmationView = FrameLayout(context).apply {
                isClickable = true
                isFocusable = true
                setBackgroundColor(0x99000000.toInt()) // Dim background
                layoutParams = FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                
                // inner themed prompt
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    val padding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics).toInt()
                    setPadding(padding, padding, padding, padding)
                    colors.setBackground(this, ColorType.CLIPBOARD_SUGGESTION_BACKGROUND)
                    layoutParams = FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                        gravity = Gravity.CENTER
                    }
                    
                    addView(TextView(context).apply {
                        text = "Clear clipboard history?"
                        setTextColor(fg)
                        gravity = Gravity.CENTER
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                        setPadding(0, 16, 0, 16)
                    })
                    
                    val checkBox = CheckBox(context).apply {
                        text = "Delete pinned clips"
                        setTextColor(fg)
                        buttonTintList = android.content.res.ColorStateList.valueOf(fg)
                        setPadding(16, 8, 16, 8)
                    }
                    addView(checkBox)
                    
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                        setPadding(0, 16, 0, 16)
                        
                        addView(Button(context).apply {
                            text = "Cancel"
                            setTextColor(fg)
                            colors.setBackground(this, ColorType.KEY_BACKGROUND)
                            setOnClickListener { hideClearConfirmation() }
                        })
                        
                        addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(32, 1) })
                        
                        addView(Button(context).apply {
                            text = "Clear"
                            setTextColor(fg)
                            colors.setBackground(this, ColorType.KEY_BACKGROUND)
                            setOnClickListener {
                                clipboardHistoryManager.clearHistory(checkBox.isChecked)
                                hideClearConfirmation()
                            }
                        })
                    })
                })
            }
        }
        
        if (confirmationView?.parent == null) {
            addView(confirmationView)
        }
        // Do NOT set visibility to INVISIBLE, just let the dimmed FrameLayout cover it
        findViewById<View>(R.id.bottom_row_keyboard)?.visibility = View.INVISIBLE
        toolbarKeys.forEach { it.isEnabled = false }
        KeyboardSwitcher.getInstance().clipboardStrip.isEnabled = false
    }
    
    private fun hideClearConfirmation() {
        if (!isShowingClearConfirmation) return
        removeView(confirmationView)
        findViewById<View>(R.id.bottom_row_keyboard)?.visibility = View.VISIBLE
        toolbarKeys.forEach { it.isEnabled = true }
        KeyboardSwitcher.getInstance().clipboardStrip.isEnabled = true
        isShowingClearConfirmation = false
    }

    private fun updatePlaceholderVisibility() {
        if (!::clipboardHistoryManager.isInitialized) return
        val empty = clipboardHistoryManager.getHistorySize() == 0
        placeholderView.isVisible = empty
        clipboardRecyclerView.isVisible = !empty
    }

    // ── Clip tap handling ─────────────────────────────────────────────────────

    override fun onKeyDown(clipId: Long) {
        keyboardActionListener.onPressKey(KeyCode.NOT_SPECIFIED, 0, true, HapticEvent.KEY_PRESS)
    }

    override fun onKeyUp(clipId: Long) {
        val clipContent = clipboardHistoryManager.getHistoryEntryContent(clipId)
        keyboardActionListener.onTextInput(clipContent?.text)
        keyboardActionListener.onReleaseKey(KeyCode.NOT_SPECIFIED, false)
        if (Settings.getValues().mAlphaAfterClipHistoryEntry)
            keyboardActionListener.onCodeInput(KeyCode.ALPHA, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
    }

    // ── ClipboardDao.Listener ─────────────────────────────────────────────────

    override fun onClipInserted(position: Int) {
        clipboardAdapter.notifyItemInserted(position)
        clipboardRecyclerView.smoothScrollToPosition(position)
    }

    override fun onClipsRemoved(position: Int, count: Int) {
        clipboardRecyclerView.post {
            clipboardAdapter.notifyDataSetChanged()
            updatePlaceholderVisibility()
        }
    }

    override fun onClipMoved(oldPosition: Int, newPosition: Int) {
        if (oldPosition != newPosition) {
            clipboardAdapter.notifyItemMoved(oldPosition, newPosition)
        }
        clipboardAdapter.notifyItemChanged(newPosition)
        if (newPosition < oldPosition) clipboardRecyclerView.smoothScrollToPosition(newPosition)
    }

    override fun onClipChanged(position: Int) {
        clipboardAdapter.notifyItemChanged(position)
    }

    // ── Prefs listener ────────────────────────────────────────────────────────

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        setToolbarButtonsActivatedStateOnPrefChange(KeyboardSwitcher.getInstance().clipboardStrip, key)

        if (::clipboardHistoryManager.isInitialized && key == Settings.PREF_CLIPBOARD_HISTORY_PINNED_FIRST) {
            Settings.getInstance().onSharedPreferenceChanged(prefs, key)
            clipboardHistoryManager.sortHistoryEntries()
            clipboardAdapter.notifyDataSetChanged()
        }
    }

}
