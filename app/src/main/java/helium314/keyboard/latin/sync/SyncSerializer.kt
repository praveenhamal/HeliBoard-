// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.sync

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import helium314.keyboard.latin.ClipboardHistoryEntry
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.protectedPrefs
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

@Serializable
data class SyncPayload(
    val v: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val device: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
    val clipboard: List<SyncClipEntry> = emptyList(),
    val settings: Map<String, String> = emptyMap(),
    val protectedSettings: Map<String, String> = emptyMap()
)

@Serializable
data class SyncClipEntry(
    val timestamp: Long,
    val pinned: Boolean,
    val text: String,
    val triggerKey: String = ""
)

object SyncSerializer {
    private const val TAG = "SyncSerializer"
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // Settings keys that are device-specific or sensitive credentials that shouldn't be synced
    private val EXCLUDED_SETTING_KEYS = setOf(
        "sync_encrypted_code",
        "sync_code_iv",
        "sync_account_email",
        "sync_last_synced",
        "ai_api_key", // keep API key on-device unless user intentionally wants it
    )

    fun serialize(context: Context, syncClipboard: Boolean = true): ByteArray {
        val clips = if (syncClipboard) {
            val dao = ClipboardDao.getInstance(context)
            if (dao != null) {
                val list = mutableListOf<SyncClipEntry>()
                for (i in 0 until dao.count()) {
                    val entry = dao.getAt(i)
                    list.add(
                        SyncClipEntry(
                            timestamp = entry.timeStamp,
                            pinned = entry.isPinned,
                            text = entry.text,
                            triggerKey = entry.triggerKey
                        )
                    )
                }
                list
            } else {
                emptyList()
            }
        } else {
            emptyList()
        }

        val prefsMap = mutableMapOf<String, String>()
        context.prefs().all.forEach { (key, value) ->
            if (!EXCLUDED_SETTING_KEYS.contains(key) && value != null) {
                prefsMap[key] = value.toString()
            }
        }

        val protectedPrefsMap = mutableMapOf<String, String>()
        context.protectedPrefs().all.forEach { (key, value) ->
            if (!EXCLUDED_SETTING_KEYS.contains(key) && value != null) {
                protectedPrefsMap[key] = value.toString()
            }
        }

        val payload = SyncPayload(
            v = 1,
            createdAt = System.currentTimeMillis(),
            device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            clipboard = clips,
            settings = prefsMap,
            protectedSettings = protectedPrefsMap
        )

        val jsonStr = json.encodeToString(payload)
        return gzip(jsonStr.toByteArray(Charsets.UTF_8))
    }

    fun deserialize(context: Context, gzippedData: ByteArray, mergeClipboard: Boolean = true): SyncPayload {
        val decompressed = gunzip(gzippedData)
        val jsonStr = String(decompressed, Charsets.UTF_8)
        val payload = json.decodeFromString<SyncPayload>(jsonStr)

        // 1. Restore/Merge Clipboard
        if (payload.clipboard.isNotEmpty()) {
            val dao = ClipboardDao.getInstance(context)
            if (dao != null) {
                if (!mergeClipboard) {
                    dao.clear()
                }
                for (clip in payload.clipboard) {
                    dao.addClip(clip.timestamp, clip.pinned, clip.text, clip.triggerKey)
                }
            }
        }

        // 2. Restore SharedPreferences
        if (payload.settings.isNotEmpty()) {
            context.prefs().edit {
                payload.settings.forEach { (key, strVal) ->
                    if (!EXCLUDED_SETTING_KEYS.contains(key)) {
                        applySettingValue(this, key, strVal, context.prefs().all[key])
                    }
                }
            }
        }

        if (payload.protectedSettings.isNotEmpty()) {
            context.protectedPrefs().edit {
                payload.protectedSettings.forEach { (key, strVal) ->
                    if (!EXCLUDED_SETTING_KEYS.contains(key)) {
                        applySettingValue(this, key, strVal, context.protectedPrefs().all[key])
                    }
                }
            }
        }

        return payload
    }

    private fun applySettingValue(editor: android.content.SharedPreferences.Editor, key: String, strVal: String, existingVal: Any?) {
        when (existingVal) {
            is Boolean -> editor.putBoolean(key, strVal.toBoolean())
            is Int -> editor.putInt(key, strVal.toIntOrNull() ?: 0)
            is Long -> editor.putLong(key, strVal.toLongOrNull() ?: 0L)
            is Float -> editor.putFloat(key, strVal.toFloatOrNull() ?: 0f)
            else -> {
                // If not found in current prefs, try parsing boolean/int/string
                if (strVal == "true" || strVal == "false") {
                    editor.putBoolean(key, strVal.toBoolean())
                } else {
                    editor.putString(key, strVal)
                }
            }
        }
    }

    private fun gzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream(data.size)
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun gunzip(data: ByteArray): ByteArray {
        val bis = ByteArrayInputStream(data)
        val gzip = GZIPInputStream(bis)
        return gzip.readBytes()
    }
}
