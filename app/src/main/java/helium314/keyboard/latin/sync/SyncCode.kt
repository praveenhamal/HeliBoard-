// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import helium314.keyboard.latin.utils.protectedPrefs
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object SyncCode {
    // 31 unambiguous characters (excluding 0, O, 1, I, L)
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private const val CODE_LENGTH = 10
    private const val KEYSTORE_ALIAS = "sleektype_sync_code_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val PREF_ENCRYPTED_CODE = "sync_encrypted_code"
    private const val PREF_CODE_IV = "sync_code_iv"

    private val secureRandom = SecureRandom()

    fun generateCode(): String {
        val sb = StringBuilder(CODE_LENGTH)
        for (i in 0 until CODE_LENGTH) {
            val idx = secureRandom.nextInt(ALPHABET.length)
            sb.append(ALPHABET[idx])
        }
        return sb.toString()
    }

    fun formatForDisplay(code: String): String {
        val clean = normalizeCode(code)
        if (clean.length == CODE_LENGTH) {
            return "${clean.substring(0, 5)}-${clean.substring(5)}"
        }
        return clean
    }

    fun normalizeCode(input: String): String {
        return input.uppercase().replace(Regex("[^A-Z0-9]"), "")
    }

    fun saveCode(context: Context, rawCode: String) {
        val normalized = normalizeCode(rawCode)
        try {
            val key = getOrCreateSecretKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val encryptedBytes = cipher.doFinal(normalized.toByteArray(Charsets.UTF_8))

            val encB64 = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
            val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)

            context.protectedPrefs().edit {
                putString(PREF_ENCRYPTED_CODE, encB64)
                putString(PREF_CODE_IV, ivB64)
            }
        } catch (_: Exception) {
            // Fallback if Keystore fails on exotic devices: store in protectedPrefs
            context.protectedPrefs().edit {
                putString(PREF_ENCRYPTED_CODE, Base64.encodeToString(normalized.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
                remove(PREF_CODE_IV)
            }
        }
    }

    fun getCode(context: Context): String? {
        val prefs = context.protectedPrefs()
        val encB64 = prefs.getString(PREF_ENCRYPTED_CODE, null) ?: return null
        val ivB64 = prefs.getString(PREF_CODE_IV, null)

        return try {
            if (ivB64 == null) {
                // Fallback unencrypted or old format
                String(Base64.decode(encB64, Base64.NO_WRAP), Charsets.UTF_8)
            } else {
                val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                val key = keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey ?: return null
                val iv = Base64.decode(ivB64, Base64.NO_WRAP)
                val encrypted = Base64.decode(encB64, Base64.NO_WRAP)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                val decrypted = cipher.doFinal(encrypted)
                String(decrypted, Charsets.UTF_8)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun clearCode(context: Context) {
        context.protectedPrefs().edit {
            remove(PREF_ENCRYPTED_CODE)
            remove(PREF_CODE_IV)
        }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            val key = keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey
            if (key != null) return key
        }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }
}
