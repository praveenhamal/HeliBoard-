// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.edit
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.R
import helium314.keyboard.latin.ai.AiProvider
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.dialogs.TextInputDialog
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.SwitchPreference
import helium314.keyboard.settings.preferences.TextInputPreference

@Composable
fun AiGrammarScreen(onClickBack: () -> Unit) {
    val ctx = LocalContext.current
    val b = (ctx.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
    val enabled = ctx.prefs().getBoolean(Settings.PREF_AI_GRAMMAR_ENABLED, Defaults.PREF_AI_GRAMMAR_ENABLED)
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_ai_grammar),
        settings = listOf(
            Settings.PREF_AI_GRAMMAR_ENABLED,
            if (enabled) Settings.PREF_AI_API_KEY else null,
            if (enabled) Settings.PREF_AI_MODEL else null,
        )
    )
}

private fun maskKey(key: String) = if (key.length <= 8) "••••" else key.take(4) + "••••••••" + key.takeLast(4)

@Composable
private fun AiApiKeyDialog(setting: Setting, onDismiss: () -> Unit) {
    val prefs = LocalContext.current.prefs()
    TextInputDialog(
        onDismissRequest = onDismiss,
        onConfirmed = {
            prefs.edit { putString(Settings.PREF_AI_API_KEY, it.trim()) }
            KeyboardSwitcher.getInstance().setThemeNeedsReload()
        },
        initialText = prefs.getString(Settings.PREF_AI_API_KEY, "") ?: "",
        title = { Text(setting.title) },
        description = { Text(stringResource(R.string.ai_api_key_info)) },
        checkTextValid = { it.isBlank() || AiProvider.detect(it) != null },
    )
}

fun createAiGrammarSettings(context: Context) = listOf(
    Setting(context, Settings.PREF_AI_GRAMMAR_ENABLED, R.string.ai_grammar_title, R.string.ai_grammar_summary) { setting ->
        var askForKey by rememberSaveable { mutableStateOf(false) }
        val prefs = LocalContext.current.prefs()
        SwitchPreference(setting, Defaults.PREF_AI_GRAMMAR_ENABLED, onCheckedChange = { on ->
            // turning it on without a key: ask for the key right away
            if (on && prefs.getString(Settings.PREF_AI_API_KEY, "").isNullOrBlank()) askForKey = true
            KeyboardSwitcher.getInstance().setThemeNeedsReload()
        })
        if (askForKey) AiApiKeyDialog(setting) { askForKey = false }
    },
    Setting(context, Settings.PREF_AI_API_KEY, R.string.ai_api_key_title) { setting ->
        var showDialog by rememberSaveable { mutableStateOf(false) }
        val key = LocalContext.current.prefs().getString(Settings.PREF_AI_API_KEY, "") ?: ""
        val provider = AiProvider.detect(key)
        Preference(
            name = setting.title,
            onClick = { showDialog = true },
            description = if (key.isBlank()) stringResource(R.string.ai_api_key_missing)
                else maskKey(key) + (provider?.let { " (${it.name.lowercase()})" } ?: ""),
        )
        if (showDialog) AiApiKeyDialog(setting) { showDialog = false }
    },
    Setting(context, Settings.PREF_AI_MODEL, R.string.ai_model_title, R.string.ai_model_summary) {
        TextInputPreference(it, "")
    },
)
