// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import android.text.InputType
import android.util.SparseArray
import android.view.KeyEvent
import android.view.inputmethod.InputMethodSubtype
import androidx.core.util.forEach
import helium314.keyboard.event.Event
import helium314.keyboard.event.HangulEventDecoder
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.event.HardwareEventDecoder
import helium314.keyboard.event.HardwareKeyboardEventDecoder
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.latin.EmojiAltPhysicalKeyDetector
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.RichInputMethodManager
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.common.InputPointers
import helium314.keyboard.latin.common.StringUtils
import helium314.keyboard.latin.common.combiningRange
import helium314.keyboard.latin.common.loopOverCodePoints
import helium314.keyboard.latin.common.loopOverCodePointsBackwards
import helium314.keyboard.latin.define.ProductionFlags
import helium314.keyboard.latin.inputlogic.InputLogic
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.SubtypeSettings
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class KeyboardActionListenerImpl(private val latinIME: LatinIME, private val inputLogic: InputLogic) : KeyboardActionListener {

    private val connection = inputLogic.mConnection
    private val emojiAltPhysicalKeyDetector by lazy { EmojiAltPhysicalKeyDetector(latinIME.resources) }

    // We expect to have only one decoder in almost all cases, hence the default capacity of 1.
    // If it turns out we need several, it will get grown seamlessly.
    private val hardwareEventDecoders: SparseArray<HardwareEventDecoder> = SparseArray(1)

    private val keyboardSwitcher = KeyboardSwitcher.getInstance()
    private val settings = Settings.getInstance()
    private val audioAndHapticFeedbackManager = AudioAndHapticFeedbackManager.getInstance()

    // language slide state
    private var initialSubtype: InputMethodSubtype? = null
    private var subtypeSwitchCount = 0

    // Shift selection mode: active when enabled in settings AND either Shift is physically held
    // or Caps Lock is currently active on the alphabet keyboard.
    private val isShiftSelectionMode: Boolean
        get() = Settings.getValues().mShiftSelectionMode && (isShiftHeld || keyboardSwitcher.keyboard?.mId?.isAlphabetShiftLocked == true)

    private var selectionAnchor = -1
    private var selectionCursor = -1

    // tracks whether the Shift key is physically held down right now
    private var isShiftHeld = false
    private var cursorMovedInSpaceSwipe = false

    override fun onPressKey(primaryCode: Int, repeatCount: Int, isSinglePointer: Boolean, hapticEvent: HapticEvent) {
        metaOnPressKey(primaryCode)
        // Track physical shift-key hold so we can activate selection on cursor movement
        if (primaryCode == KeyCode.SHIFT) {
            isShiftHeld = true
        }
        keyboardSwitcher.onPressKey(primaryCode, isSinglePointer, latinIME.currentAutoCapsState, latinIME.currentRecapitalizeState)
        // we need to use LatinIME for handling of key-down audio and haptics
        latinIME.hapticAndAudioFeedback(primaryCode, repeatCount, hapticEvent)
    }

    override fun onLongPressKey(primaryCode: Int) {
        metaOnLongPressKey(primaryCode)
        performHapticFeedback(HapticEvent.KEY_LONG_PRESS)
        if (primaryCode == KeyCode.SHIFT) {
            isShiftHeld = false
            selectionAnchor = -1
            selectionCursor = -1
        }
    }

    override fun onReleaseKey(primaryCode: Int, withSliding: Boolean) {
        metaOnReleaseKey(primaryCode)
        if (primaryCode == KeyCode.SHIFT) {
            isShiftHeld = false
            selectionAnchor = -1
            selectionCursor = -1
        }
        keyboardSwitcher.onReleaseKey(primaryCode, withSliding, latinIME.currentAutoCapsState, latinIME.currentRecapitalizeState)
    }

    override fun onKeyUp(keyCode: Int, keyEvent: KeyEvent): Boolean {
        emojiAltPhysicalKeyDetector.onKeyUp(keyEvent)
        if (!ProductionFlags.IS_HARDWARE_KEYBOARD_SUPPORTED)
            return false

        val keyIdentifier = keyEvent.deviceId.toLong() shl 32 + keyEvent.keyCode
        return inputLogic.mCurrentlyPressedHardwareKeys.remove(keyIdentifier)
    }

    override fun onKeyDown(keyCode: Int, keyEvent: KeyEvent): Boolean {
        emojiAltPhysicalKeyDetector.onKeyDown(keyEvent)
        if (!ProductionFlags.IS_HARDWARE_KEYBOARD_SUPPORTED)
            return false

        val event: Event
        if (settings.current.mLocale.language == "ko") { // todo: this does not appear to be the right place
            val subtype = keyboardSwitcher.keyboard?.mId?.mSubtype ?: RichInputMethodManager.getInstance().currentSubtype
            event = HangulEventDecoder.decodeHardwareKeyEvent(subtype, keyEvent) {
                getHardwareKeyEventDecoder(keyEvent.deviceId).decodeHardwareKey(keyEvent)
            }
        } else {
            event = getHardwareKeyEventDecoder(keyEvent.deviceId).decodeHardwareKey(keyEvent)
        }

        if (event.isHandled) {
            inputLogic.onCodeInput(
                settings.current, event,
                keyboardSwitcher.getKeyboardShiftMode(), // TODO: this is not necessarily correct for a hardware keyboard right now
                keyboardSwitcher.getCurrentKeyboardScript(),
                latinIME.mHandler
            )
            return true
        }
        return false
    }

    override fun onCodeInput(primaryCode: Int, x: Int, y: Int, isKeyRepeat: Boolean) {
        when (primaryCode) {
            KeyCode.TOGGLE_AUTOCORRECT -> return settings.toggleAutoCorrect()
            KeyCode.TOGGLE_INCOGNITO_MODE -> return settings.toggleAlwaysIncognitoMode()
        }
        val isCursorMoveCode = primaryCode == KeyCode.ARROW_LEFT || primaryCode == KeyCode.ARROW_RIGHT ||
                primaryCode == KeyCode.ARROW_UP || primaryCode == KeyCode.ARROW_DOWN ||
                primaryCode == KeyCode.PAGE_UP || primaryCode == KeyCode.PAGE_DOWN ||
                primaryCode == KeyCode.WORD_LEFT || primaryCode == KeyCode.WORD_RIGHT ||
                primaryCode == KeyCode.MOVE_START_OF_LINE || primaryCode == KeyCode.MOVE_END_OF_LINE ||
                primaryCode == KeyCode.MOVE_START_OF_PAGE || primaryCode == KeyCode.MOVE_END_OF_PAGE

        if (!isCursorMoveCode) {
            selectionAnchor = -1
            selectionCursor = -1
        }

        val currentMeta = if (isShiftSelectionMode && isCursorMoveCode) {
            metaState or KeyEvent.META_SHIFT_ON
        } else {
            metaState
        }
        val mkv = keyboardSwitcher.mainKeyboardView

        // checking if the character is a combining accent
        val event = if (primaryCode in combiningRange) { // todo: should this be done later, maybe in inputLogic?
            Event.createSoftwareDeadEvent(primaryCode, 0, currentMeta, mkv.getKeyX(x), mkv.getKeyY(y), null)
        } else {
            Event.createSoftwareKeypressEvent(primaryCode, currentMeta, mkv.getKeyX(x), mkv.getKeyY(y), isKeyRepeat)
        }
        latinIME.onEvent(event)
        metaAfterCodeInput(primaryCode)
    }

    override fun onTextInput(text: String?) = latinIME.onTextInput(text)

    override fun onStartBatchInput() = latinIME.onStartBatchInput()

    override fun onUpdateBatchInput(batchPointers: InputPointers?) = latinIME.onUpdateBatchInput(batchPointers)

    override fun onEndBatchInput(batchPointers: InputPointers?) = latinIME.onEndBatchInput(batchPointers)

    override fun onCancelBatchInput() = latinIME.onCancelBatchInput()

    // User released a finger outside any key
    override fun onCancelInput() {
        cursorMovedInSpaceSwipe = false
        selectionAnchor = -1
        selectionCursor = -1
    }

    override fun onFinishSlidingInput() =
        keyboardSwitcher.onFinishSlidingInput(latinIME.currentAutoCapsState, latinIME.currentRecapitalizeState)

    override fun onCustomRequest(requestCode: Int): Boolean {
        if (requestCode == Constants.CUSTOM_CODE_SHOW_INPUT_METHOD_PICKER) {
            return latinIME.showInputPickerDialog()
        }
        return false
    }

    override fun onHorizontalSpaceSwipe(steps: Int): Boolean = when (Settings.getValues().mSpaceSwipeHorizontal) {
        KeyboardActionListener.SWIPE_MOVE_CURSOR -> onMoveCursorHorizontally(steps)
        KeyboardActionListener.SWIPE_SWITCH_LANGUAGE -> onLanguageSlide(steps)
        KeyboardActionListener.SWIPE_TOGGLE_NUMPAD -> toggleNumpad(false, false)
        KeyboardActionListener.SWIPE_TOGGLE_CALC -> {
            keyboardSwitcher.setCalculatorKeyboard()
            true
        }
        else -> false
    }

    override fun onSpaceSwipeUp(steps: Int): Boolean = when (Settings.getValues().mSpaceSwipeUp) {
        KeyboardActionListener.SWIPE_MOVE_CURSOR -> onMoveCursorVertically(steps)
        KeyboardActionListener.SWIPE_SWITCH_LANGUAGE -> onLanguageSlide(steps)
        KeyboardActionListener.SWIPE_TOGGLE_NUMPAD -> toggleNumpad(false, false)
        KeyboardActionListener.SWIPE_HIDE_KEYBOARD -> {
            latinIME.requestHideSelf(0)
            true
        }
        KeyboardActionListener.SWIPE_TOGGLE_CALC -> {
            keyboardSwitcher.setCalculatorKeyboard()
            true
        }
        else -> false
    }

    override fun onSpaceSwipeDown(steps: Int): Boolean = when (Settings.getValues().mSpaceSwipeDown) {
        KeyboardActionListener.SWIPE_MOVE_CURSOR -> onMoveCursorVertically(steps)
        KeyboardActionListener.SWIPE_SWITCH_LANGUAGE -> onLanguageSlide(steps)
        KeyboardActionListener.SWIPE_TOGGLE_NUMPAD -> toggleNumpad(false, false)
        KeyboardActionListener.SWIPE_HIDE_KEYBOARD -> {
            latinIME.requestHideSelf(0)
            true
        }
        KeyboardActionListener.SWIPE_TOGGLE_CALC -> {
            keyboardSwitcher.setCalculatorKeyboard()
            true
        }
        else -> false
    }

    override fun onVerticalSpaceSwipe(steps: Int): Boolean = onMoveCursorVertically(steps)

    override fun onEndSpaceSwipe(){
        initialSubtype = null
        subtypeSwitchCount = 0
        if (cursorMovedInSpaceSwipe) {
            cursorMovedInSpaceSwipe = false
            inputLogic.restartSuggestionsOnWordTouchedByCursor(settings.current, keyboardSwitcher.currentKeyboardScript)
        }
        selectionAnchor = -1
        selectionCursor = -1
    }

    override fun toggleNumpad(withSliding: Boolean, forceReturnToAlpha: Boolean): Boolean {
        keyboardSwitcher.toggleNumpad(withSliding, latinIME.currentAutoCapsState, latinIME.currentRecapitalizeState, forceReturnToAlpha)
        return true
    }

    override fun onMoveDeletePointer(steps: Int) {
        inputLogic.finishInput()
        val end = connection.expectedSelectionEnd
        val actualSteps = actualSteps(steps)
        val start = connection.expectedSelectionStart + actualSteps
        if (start > end) return
        gestureMoveBackHaptics()
        connection.setSelection(start, end)
    }

    private fun actualSteps(steps: Int): Int {
        var actualSteps = 0
        // corrected steps to avoid splitting chars belonging to the same codepoint
        if (steps > 0) {
            val text = connection.getSelectedText(0) ?: return steps
            loopOverCodePoints(text) { cp, charCount ->
                actualSteps += charCount
                actualSteps >= steps
            }
        } else {
            val text = connection.getTextBeforeCursor(-steps * 4, 0) ?: return steps
            loopOverCodePointsBackwards(text) { cp, charCount ->
                actualSteps -= charCount
                actualSteps <= steps
            }
        }
        return actualSteps
    }

    override fun onUpWithDeletePointerActive() {
        if (!connection.hasSelection()) return
        inputLogic.finishInput()
        onCodeInput(KeyCode.DELETE, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
    }

    override fun resetMetaState() {
        metaState = 0
        isShiftHeld = false
        selectionAnchor = -1
        selectionCursor = -1
    }

    private fun onLanguageSlide(steps: Int): Boolean {
        if (abs(steps) < settings.current.mLanguageSwipeDistance) return false
        val subtypes = SubtypeSettings.getEnabledSubtypes(true)
        if (subtypes.size <= 1) { // only allow if we have more than one subtype
            return false
        }
        // decide next or previous dependent on up or down
        val current = RichInputMethodManager.getInstance().currentSubtype.rawSubtype
        var wantedIndex = subtypes.indexOf(current) + if (steps > 0) 1 else -1
        wantedIndex %= subtypes.size
        if (wantedIndex < 0) {
            wantedIndex += subtypes.size
        }
        val newSubtype = subtypes[wantedIndex]

        // do not switch if we would switch to the initial subtype after cycling all other subtypes
        if (initialSubtype == null) initialSubtype = current
        if (initialSubtype == newSubtype) {
            if ((subtypeSwitchCount > 0 && steps > 0) || (subtypeSwitchCount < 0 && steps < 0)) {
                return true
            }
        }
        if (steps > 0) subtypeSwitchCount++ else subtypeSwitchCount--

        keyboardSwitcher.switchToSubtype(newSubtype)
        return true
    }

    private fun isMultiLine(): Boolean {
        val inputAttributes = Settings.getValues().mInputAttributes ?: return false
        val inputType = inputAttributes.mInputType
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        if (inputClass != InputType.TYPE_CLASS_TEXT) return false
        return (inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
    }

    private fun onMoveCursorVertically(steps: Int): Boolean {
        if (steps == 0) return false
        if (!isMultiLine()) return true

        // Dispatch real DPAD_UP / DPAD_DOWN key events to the editor.
        // This delegates vertical movement entirely to the editor's own layout engine,
        // which correctly handles both hard newlines and soft-wrapped lines while
        // preserving the horizontal column position — identical to Samsung Keyboard / Gboard.
        val absSteps = kotlin.math.abs(steps)
        val keyCode = if (steps < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN

        val metaForEvent = if (isShiftSelectionMode) {
            KeyEvent.META_SHIFT_ON
        } else {
            0
        }

        inputLogic.finishInput()

        val downTime = android.os.SystemClock.uptimeMillis()
        repeat(absSteps) { i ->
            val eventTime = downTime + i
            val keyDown = KeyEvent(downTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0, metaForEvent)
            val keyUp   = KeyEvent(downTime, eventTime, KeyEvent.ACTION_UP,   keyCode, 0, metaForEvent)
            connection.sendKeyEvent(keyDown)
            connection.sendKeyEvent(keyUp)
        }

        cursorMovedInSpaceSwipe = true
        if (steps < 0) gestureMoveBackHaptics() else gestureMoveForwardHaptics()
        return true
    }

    private fun onMoveCursorHorizontally(rawSteps: Int): Boolean {
        if (rawSteps == 0) return false
        // for RTL languages we want to invert pointer movement
        val rtl = RichInputMethodManager.getInstance().currentSubtype.isRtlSubtype
        val steps = if (rtl) -rawSteps else rawSteps
        val moveSteps: Int
        if (steps < 0) {
            val text = connection.getTextBeforeCursor(-steps * 4, 0) ?: return false
            moveSteps = negativeMoveSteps(text, steps)
            if (moveSteps == 0) {
                // some apps don't return any text via input connection, and the cursor can't be moved
                // we fall back to virtually pressing the left/right key one or more times instead
                repeat(-steps) {
                    onCodeInput(if (rtl) KeyCode.ARROW_RIGHT else KeyCode.ARROW_LEFT, Constants.NOT_A_COORDINATE,
                        Constants.NOT_A_COORDINATE, false)
                }
                if (text.isNotEmpty()) {
                    gestureMoveBackHaptics()
                }
                return true
            }
            gestureMoveBackHaptics()
        } else {
            val text = connection.getTextAfterCursor(steps * 4, 0) ?: return false
            moveSteps = positiveMoveSteps(text, steps)
            if (moveSteps == 0) {
                // some apps don't return any text via input connection, and the cursor can't be moved
                // we fall back to virtually pressing the left/right key one or more times instead
                repeat(steps) {
                    onCodeInput(if (rtl) KeyCode.ARROW_LEFT else KeyCode.ARROW_RIGHT, Constants.NOT_A_COORDINATE,
                        Constants.NOT_A_COORDINATE, false)
                }
                if (text.isNotEmpty()) {
                    gestureMoveForwardHaptics(true)
                }
                return true
            }
            gestureMoveForwardHaptics(text.isNotEmpty())
        }

        if (isShiftSelectionMode) {
            if (!cursorMovedInSpaceSwipe) {
                inputLogic.finishInput()
            }
            cursorMovedInSpaceSwipe = true
            if (selectionAnchor < 0) {
                selectionAnchor = connection.expectedSelectionStart
                selectionCursor = selectionAnchor
            }
            selectionCursor += moveSteps
            val selStart = min(selectionAnchor, selectionCursor)
            val selEnd = max(selectionAnchor, selectionCursor)
            connection.setSelection(selStart, selEnd)
            return true
        }

        cursorMovedInSpaceSwipe = true
        // the shortcut below causes issues due to horrible handling of text fields by Firefox and forks
        // issues:
        //  * setSelection "will cause the editor to call onUpdateSelection", see: https://developer.android.com/reference/android/view/inputmethod/InputConnection#setSelection(int,%20int)
        //     but Firefox is simply not doing this within the same word... WTF?
        //     https://github.com/Helium314/HeliBoard/issues/1139#issuecomment-2588169384
        //  * inputType is NOT of variant InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT (variant appears to always be 0)
        //     -> this is "fixed" now using AppWorkarounds.adjustInputType
        val variation = InputType.TYPE_MASK_VARIATION and Settings.getValues().mInputAttributes.mInputType
        if (variation != InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT
                && inputLogic.moveCursorByAndReturnIfInsideComposingWord(moveSteps)) {
            // no need to finish input and restart suggestions if we're still in the word
            // this is a noticeable performance improvement when moving through long words
            val newPosition = connection.expectedSelectionStart + moveSteps
            connection.setSelection(newPosition, newPosition)
            return true
        }

        inputLogic.finishInput()
        val newPosition = connection.expectedSelectionStart + moveSteps
        connection.setSelection(newPosition, newPosition)
        return true
    }

    private fun positiveMoveSteps(text: CharSequence, steps: Int): Int {
        var actualSteps = 0
        // corrected steps to avoid splitting chars belonging to the same codepoint
        loopOverCodePoints(text) { cp, charCount ->
            // For emojis we (incorrectly) return 0 so the move is handled by virtual arrow key presses.
            // This is a simple workaround to avoid determining the correct character count, which can
            // be tricky because in some cases older Android versions show two emojis where newer ones show one.
            if (StringUtils.mightBeEmoji(cp)) return 0
            actualSteps += charCount
            actualSteps >= steps
        }
        return min(actualSteps, text.length)
    }

    private fun negativeMoveSteps(text: CharSequence, steps: Int): Int {
        var actualSteps = 0
        // corrected steps to avoid splitting chars belonging to the same codepoint
        loopOverCodePointsBackwards(text) { cp, charCount ->
            // For emojis we (incorrectly) return 0 so the move is handled by virtual arrow key presses.
            // This is a simple workaround to avoid determining the correct character count, which can
            // be tricky because in some cases older Android versions show two emojis where newer ones show one.
            if (StringUtils.mightBeEmoji(cp)) return 0
            actualSteps -= charCount
            actualSteps <= steps
        }
        return -min(-actualSteps, text.length)
    }

    private fun gestureMoveBackHaptics() {
        if (connection.canDeleteCharacters()) {
            performHapticFeedback(HapticEvent.GESTURE_MOVE)
        }
    }

    // hasTextAfterCursor is used because text before the cursor is cached, going through the InputConnection can be slow
    private fun gestureMoveForwardHaptics(hasTextAfterCursor: Boolean? = null) {
        if (hasTextAfterCursor ?: connection.hasTextAfterCursor()) {
            performHapticFeedback(HapticEvent.GESTURE_MOVE)
        }
    }

    private fun performHapticFeedback(hapticEvent: HapticEvent) {
        audioAndHapticFeedbackManager.performHapticFeedback(keyboardSwitcher.visibleKeyboardView, hapticEvent)
    }

    private fun getHardwareKeyEventDecoder(deviceId: Int): HardwareEventDecoder {
        hardwareEventDecoders.get(deviceId)?.let { return it }

        // TODO: create the decoder according to the specification
        val newDecoder = HardwareKeyboardEventDecoder(deviceId)
        hardwareEventDecoders.put(deviceId, newDecoder)
        return newDecoder
    }

    // -------------------------- meta state handling -----------------------------

    // current state
    // press enables meta
    // release keeps meta enabled, unless there was a onCodeInput for a different key in between
    // onCodeInput ends the meta if it was enabled
    // long press on meta key also ends meta so popups are handled properly
    // sliding from a meta key to some other words too, though this was not intended (and there are no sliding key input graphics)

    // todo: move meta state tracking to KeyboardState? seems more suitable, also for handling sliding input
    //  but the issue is that meta state is used in Event to determine whether it's a functional Event (does not add a character)
    //  (and also it's in the hardware keyEvents which are handled by onKeyUp/Down, but that should be manageable)

    /** actual Android metaState like in KeyEvent */
    private var metaState = 0

    /** keeps track of the state of meta keys by (HeliBoard) KeyCodes */
    private val metaPressStates = SparseArray<MetaPressState>(4)

    // todo: lock and non-lock versions interact badly: when any of them is released, the meta state is removed
    //  this is not wanted, especially because the state of the other key is not affected (still looks pressed)
    private fun metaOnPressKey(primaryCode: Int) {
        val metaCode = primaryCode.toMetaState() ?: return
        if (primaryCode.isMetaLock()) {
            // if unset -> lock, otherwise set to UNSET_ON_RELEASE so it's unset on release
            if (metaPressStates[primaryCode] != MetaPressState.LOCKED) {
                metaPressStates[primaryCode] = MetaPressState.LOCKED
                keyboardSwitcher.mainKeyboardView?.updateLockState(primaryCode, true)
                metaState = metaState or metaCode
            } else {
                metaPressStates[primaryCode] = MetaPressState.UNSET_ON_RELEASE
            }
            return
        }
        if (metaPressStates[primaryCode] == MetaPressState.RELEASED_BUT_ACTIVE) {
            // meta key is pressed again without other input -> should be disabled on release
            metaPressStates[primaryCode] = MetaPressState.UNSET_ON_RELEASE
        } else {
            // otherwise just press it normally
            metaPressStates[primaryCode] = MetaPressState.PRESSED
        }
        metaState = metaState or metaCode
        // pressed graphics are set anyway, no need to lock it
    }

    // looks like this is not called if there are no popups
    private fun metaOnLongPressKey(primaryCode: Int) {
        if (metaPressStates[primaryCode] != MetaPressState.PRESSED) return
        // we long-pressed a meta key that has popups -> disable so the meta state is not used for the popup
        metaPressStates[primaryCode] = MetaPressState.UNSET
        keyboardSwitcher.mainKeyboardView?.updateLockState(primaryCode, false)
        val metaCode = primaryCode.toMetaState() ?: return
        metaState = metaState and metaCode.inv()
    }

    private fun metaOnReleaseKey(primaryCode: Int) {
        val metaCode = primaryCode.toMetaState() ?: return
        val metaPressState = metaPressStates[primaryCode]
        if (metaPressState == MetaPressState.UNSET_ON_RELEASE) {
            metaPressStates[primaryCode] = MetaPressState.UNSET
            metaState = metaState and metaCode.inv()
            keyboardSwitcher.mainKeyboardView?.updateLockState(primaryCode, false)
        } else if (metaPressState == MetaPressState.PRESSED) {
            metaPressStates[primaryCode] = MetaPressState.RELEASED_BUT_ACTIVE
            keyboardSwitcher.mainKeyboardView?.updateLockState(primaryCode, true)
        }
    }

    private fun metaAfterCodeInput(primaryCode: Int) {
        val metaCode = primaryCode.toMetaState()
        if (metaCode != null) {
            // meta key might be a popup key, we just toggle between set and unset
            val metaPressState = metaPressStates[primaryCode] ?: MetaPressState.UNSET
            if (metaPressState == MetaPressState.UNSET) {
                metaPressStates[primaryCode] = MetaPressState.SET
                metaState = metaState or metaCode
                keyboardSwitcher.mainKeyboardView?.updateLockState(primaryCode, true)
            } else if (metaPressState == MetaPressState.SET) {
                metaPressStates[primaryCode] = MetaPressState.UNSET
                metaState = metaState and metaCode.inv()
                keyboardSwitcher.mainKeyboardView?.updateLockState(primaryCode, false)
            }
        } else if (metaState != 0) {
            // non-meta key -> unset all set / released_but_active, and mark pressed as UNSET_ON_RELEASE
            metaPressStates.forEach { key, value ->
                if (value == MetaPressState.RELEASED_BUT_ACTIVE || value == MetaPressState.SET) {
                    metaPressStates[key] = MetaPressState.UNSET
                    keyboardSwitcher.mainKeyboardView?.updateLockState(key, false)
                    val metaCode = key.toMetaState() ?: return@forEach
                    metaState = metaState and metaCode.inv()
                } else if (value == MetaPressState.PRESSED) {
                    metaPressStates[key] = MetaPressState.UNSET_ON_RELEASE
                }
            }
        }
    }

    companion object {
        private enum class MetaPressState {
            UNSET, // default state, not active
            SET, // enabled without onPressKey (e.g. in popup)
            PRESSED, // key is pressed
            UNSET_ON_RELEASE, // key is pressed, but state will be unset on release
            RELEASED_BUT_ACTIVE, // key was released without UNSET_ON_RELEASE state, meta state is still set
            LOCKED, // key is locked and will be released only by pressing the same key again
        }

        private fun Int.toMetaState() = when (this) {
            KeyCode.CTRL, KeyCode.CTRL_LOCK -> KeyEvent.META_CTRL_ON
            KeyCode.CTRL_LEFT               -> KeyEvent.META_CTRL_LEFT_ON
            KeyCode.CTRL_RIGHT              -> KeyEvent.META_CTRL_RIGHT_ON
            KeyCode.ALT, KeyCode.ALT_LOCK   -> KeyEvent.META_ALT_ON
            KeyCode.ALT_LEFT                -> KeyEvent.META_ALT_LEFT_ON
            KeyCode.ALT_RIGHT               -> KeyEvent.META_ALT_RIGHT_ON
            KeyCode.FN, KeyCode.FN_LOCK     -> KeyEvent.META_FUNCTION_ON
            KeyCode.META, KeyCode.META_LOCK -> KeyEvent.META_META_ON
            KeyCode.META_LEFT               -> KeyEvent.META_META_LEFT_ON
            KeyCode.META_RIGHT              -> KeyEvent.META_META_RIGHT_ON
            else -> null
        }

        private fun Int.isMetaLock() = this == KeyCode.CTRL_LOCK || this == KeyCode.ALT_LOCK || this == KeyCode.FN_LOCK || this == KeyCode.META_LOCK
    }
}
