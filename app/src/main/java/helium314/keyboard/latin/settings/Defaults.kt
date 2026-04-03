package helium314.keyboard.latin.settings

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.common.Constants.Separators
import helium314.keyboard.latin.common.Constants.Subtype.ExtraValue
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.latin.utils.POPUP_KEYS_LABEL_DEFAULT
import helium314.keyboard.latin.utils.POPUP_KEYS_ORDER_DEFAULT
import helium314.keyboard.latin.utils.defaultClipboardToolbarPref
import helium314.keyboard.latin.utils.defaultPinnedToolbarPref
import helium314.keyboard.latin.utils.defaultToolbarPref

object Defaults {
    fun initDynamicDefaults(context: Context) {
        PREF_GESTURE_DYNAMIC_PREVIEW_FOLLOW_SYSTEM = getTransitionAnimationScale(context) != 0.0f
        val dm = context.resources.displayMetrics
        val px600 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 600f, dm)
        PREF_POPUP_ON = dm.widthPixels >= px600 || dm.heightPixels >= px600
    }

    // must correspond to a file name
    val LayoutType.default get() = when (this) {
        LayoutType.MAIN -> "qwerty"
        LayoutType.SYMBOLS -> "symbols"
        LayoutType.MORE_SYMBOLS -> "symbols_shifted"
        LayoutType.FUNCTIONAL -> if (Settings.getInstance().isTablet) "functional_keys_tablet" else "functional_keys"
        LayoutType.NUMBER -> "number"
        LayoutType.NUMBER_ROW -> "number_row"
        LayoutType.NUMPAD -> "numpad"
        LayoutType.NUMPAD_LANDSCAPE -> "numpad_landscape"
        LayoutType.PHONE -> "phone"
        LayoutType.PHONE_SYMBOLS -> "phone_symbols"
        LayoutType.EMOJI_BOTTOM -> "emoji_bottom_row"
        LayoutType.CLIPBOARD_BOTTOM -> "clip_bottom_row"
        LayoutType.CALCULATOR -> "calculator"
    }

    private const val DEFAULT_SIZE_SCALE = 1.0f // 100%
    const val PREF_THEME_STYLE = KeyboardTheme.STYLE_MATERIAL
    const val PREF_ICON_STYLE = KeyboardTheme.STYLE_MATERIAL
    const val PREF_THEME_COLORS = KeyboardTheme.THEME_LIGHT
    const val PREF_THEME_COLORS_NIGHT = KeyboardTheme.THEME_DARK
    const val PREF_THEME_KEY_BORDERS = true
    @JvmField
    val PREF_THEME_DAY_NIGHT = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    const val PREF_CUSTOM_ICON_NAMES = "{\"calculator\":\"sym_keyboard_shift_holo\"}"
    const val PREF_TOOLBAR_CUSTOM_KEY_CODES = ""
    const val PREF_AUTO_CAP = false
    const val PREF_VIBRATE_ON = false
    const val PREF_VIBRATE_IN_DND_MODE = false
    const val PREF_SOUND_ON = false
    const val PREF_SUGGEST_EMOJIS = true
    const val PREF_INLINE_EMOJI_SEARCH = true
    const val PREF_SHOW_EMOJI_DESCRIPTIONS = true
    @JvmField
    var PREF_POPUP_ON = true
    const val PREF_AUTO_CORRECTION = false
    const val PREF_MORE_AUTO_CORRECTION = false
    const val PREF_AUTO_CORRECT_THRESHOLD = 0.185f
    const val PREF_AUTOCORRECT_SHORTCUTS = true
    const val PREF_BACKSPACE_REVERTS_AUTOCORRECT = true
    const val PREF_CENTER_SUGGESTION_TEXT_TO_ENTER = false
    const val PREF_SHOW_SUGGESTIONS = true
    const val PREF_ALWAYS_SHOW_SUGGESTIONS = true
    const val PREF_ALWAYS_SHOW_SUGGESTIONS_EXCEPT_WEB_TEXT = true
    const val PREF_KEY_USE_PERSONALIZED_DICTS = true
    const val PREF_KEY_USE_DOUBLE_SPACE_PERIOD = false
    const val PREF_BLOCK_POTENTIALLY_OFFENSIVE = false
    const val PREF_SHOW_LANGUAGE_SWITCH_KEY = false
    const val PREF_LANGUAGE_SWITCH_KEY = "internal"
    const val PREF_SHOW_EMOJI_KEY = true
    const val PREF_VARIABLE_TOOLBAR_DIRECTION = false
    const val PREF_ADDITIONAL_SUBTYPES = "de§KeyboardLayoutSet=MAIN:qwerty;en-US§KeyboardLayoutSet=CALCULATOR:calculator,SupportTouchPositionCorrection,TrySuppressingImeSwitcher;fr§KeyboardLayoutSet=MAIN:qwertz;hu§KeyboardLayoutSet=MAIN:qwerty"
    const val PREF_ENABLE_SPLIT_KEYBOARD = false
    const val PREF_ENABLE_SPLIT_KEYBOARD_LANDSCAPE = false
    @JvmField
    val PREF_SPLIT_SPACER_SCALE = Array(2) { DEFAULT_SIZE_SCALE }
    @JvmField
    val PREF_KEYBOARD_HEIGHT_SCALE = Array(2) { DEFAULT_SIZE_SCALE }
    @JvmField
    val PREF_BOTTOM_ROW_SCALE = Array(2) { DEFAULT_SIZE_SCALE }
    @JvmField
    val PREF_BOTTOM_PADDING_SCALE = arrayOf(DEFAULT_SIZE_SCALE, 0f)
    @JvmField
    val PREF_SIDE_PADDING_SCALE = arrayOf(0.323541f, 0f, 0f, 0f)
    const val PREF_FONT_SCALE = DEFAULT_SIZE_SCALE
    const val PREF_EMOJI_FONT_SCALE = DEFAULT_SIZE_SCALE
    const val PREF_EMOJI_KEY_FIT = true
    const val PREF_EMOJI_SKIN_TONE = ""
    const val PREF_SPACE_HORIZONTAL_SWIPE = "move_cursor"
    const val PREF_SPACE_VERTICAL_SWIPE = "calc"
    const val PREF_DELETE_SWIPE = true
    const val PREF_AUTOSPACE_AFTER_PUNCTUATION = false
    const val PREF_AUTOSPACE_AFTER_SUGGESTION = true
    const val PREF_AUTOSPACE_AFTER_GESTURE_TYPING = true
    const val PREF_AUTOSPACE_BEFORE_GESTURE_TYPING = true
    const val PREF_SHIFT_REMOVES_AUTOSPACE = false
    const val PREF_ALWAYS_INCOGNITO_MODE = false
    const val PREF_BIGRAM_PREDICTIONS = true
    const val PREF_SUGGEST_PUNCTUATION = false
    const val PREF_SUGGEST_CLIPBOARD_CONTENT = true
    const val PREF_GESTURE_INPUT = true
    const val PREF_VIBRATION_DURATION_SETTINGS = -1
    const val PREF_KEYPRESS_SOUND_VOLUME = -0.01f
    const val PREF_KEY_LONGPRESS_TIMEOUT = 300
    const val PREF_ENABLE_EMOJI_ALT_PHYSICAL_KEY = true
    const val PREF_GESTURE_PREVIEW_TRAIL = true
    const val PREF_GESTURE_FLOATING_PREVIEW_TEXT = true
    const val PREF_GESTURE_FLOATING_PREVIEW_DYNAMIC = true
    @JvmField
    var PREF_GESTURE_DYNAMIC_PREVIEW_FOLLOW_SYSTEM = true
    const val PREF_GESTURE_SPACE_AWARE = false
    const val PREF_GESTURE_FAST_TYPING_COOLDOWN = 500
    const val PREF_GESTURE_TRAIL_FADEOUT_DURATION = 800
    const val PREF_SHOW_SETUP_WIZARD_ICON = true
    const val PREF_USE_CONTACTS = true
    const val PREF_USE_APPS = false
    const val PREFS_LONG_PRESS_SYMBOLS_FOR_NUMPAD = false
    const val PREF_ONE_HANDED_MODE = false
    @SuppressLint("RtlHardcoded")
    const val PREF_ONE_HANDED_GRAVITY = Gravity.LEFT
    const val PREF_ONE_HANDED_SCALE = 1f
    const val PREF_SHOW_NUMBER_ROW = true
    const val PREF_SHOW_NUMBER_ROW_IN_SYMBOLS = true
    const val PREF_LOCALIZED_NUMBER_ROW = true
    const val PREF_SHOW_NUMBER_ROW_HINTS = true
    const val PREF_CUSTOM_CURRENCY_KEY = "$"
    const val PREF_SHOW_HINTS = true
    const val PREF_POPUP_KEYS_ORDER = POPUP_KEYS_ORDER_DEFAULT
    const val PREF_POPUP_KEYS_LABELS_ORDER = POPUP_KEYS_LABEL_DEFAULT
    const val PREF_SHOW_POPUP_HINTS = false
    const val PREF_SHOW_TLD_POPUP_KEYS = true
    const val PREF_MORE_POPUP_KEYS = "main"
    const val PREF_SPACE_TO_CHANGE_LANG = false
    const val PREF_LANGUAGE_SWIPE_DISTANCE = 5
    const val PREF_ENABLE_CLIPBOARD_HISTORY = true
    const val PREF_CLIPBOARD_HISTORY_RETENTION_TIME = 121 // minutes
    const val PREF_CLIPBOARD_HISTORY_PINNED_FIRST = false
    const val PREF_ADD_TO_PERSONAL_DICTIONARY = false
    @JvmField
    val PREF_NAVBAR_COLOR = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    const val PREF_NARROW_KEY_GAPS = true
    const val PREF_ENABLED_SUBTYPES = "en-US§KeyboardLayoutSet=CALCULATOR:calculator,SupportTouchPositionCorrection,TrySuppressingImeSwitcher"
    const val PREF_SELECTED_SUBTYPE = "en-US§KeyboardLayoutSet=CALCULATOR:calculator,SupportTouchPositionCorrection,TrySuppressingImeSwitcher"
    const val PREF_URL_DETECTION = false
    const val PREF_DONT_SHOW_MISSING_DICTIONARY_DIALOG = false
    const val PREF_TOOLBAR_MODE = "EXPANDABLE"
    const val PREF_TOOLBAR_HIDING_GLOBAL = true
    const val PREF_TOOLBAR_SWIPE_DOWN_TO_HIDE = true
    const val PREF_QUICK_PIN_TOOLBAR_KEYS = false
    val PREF_PINNED_TOOLBAR_KEYS = defaultPinnedToolbarPref
    val PREF_TOOLBAR_KEYS = defaultToolbarPref
    const val PREF_AUTO_SHOW_TOOLBAR = false
    const val PREF_AUTO_HIDE_TOOLBAR = true
    val PREF_CLIPBOARD_TOOLBAR_KEYS = defaultClipboardToolbarPref
    const val PREF_ABC_AFTER_EMOJI = false
    const val PREF_ABC_AFTER_CLIP = true
    const val PREF_ABC_AFTER_SYMBOL_SPACE = true
    const val PREF_ABC_AFTER_NUMPAD_SPACE = false
    const val PREF_REMOVE_REDUNDANT_POPUPS = false
    const val PREF_SPACE_BAR_TEXT = ""
    const val PREF_TIMESTAMP_FORMAT = "yyyy-MM-dd HH:mm:ss"
    const val PREF_EMOJI_RECENT_KEYS = ""
    const val PREF_LAST_SHOWN_EMOJI_CATEGORY_PAGE_ID = 0
    const val PREF_SHOW_DEBUG_SETTINGS = true
    val PREF_DEBUG_MODE = BuildConfig.DEBUG
    const val PREF_SHOW_SUGGESTION_INFOS = false
    const val PREF_FORCE_NON_DISTINCT_MULTITOUCH = false
    const val PREF_SLIDING_KEY_INPUT_PREVIEW = true
    const val PREF_USER_COLORS = "[]"
    const val PREF_USER_MORE_COLORS = 0
    const val PREF_USER_ALL_COLORS = ""
    const val PREF_SAVE_SUBTYPE_PER_APP = false
}
