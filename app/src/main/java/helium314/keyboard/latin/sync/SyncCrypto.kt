// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object SyncCrypto {
    private const val VERSION: Byte = 1
    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 12
    private const val TAG_LENGTH_BITS = 128
    private const val PBKDF2_ITERATIONS = 600_000
    private const val KEY_LENGTH_BITS = 256

    private val secureRandom = SecureRandom()

    /**
     * Encrypts plaintext bytes using a key derived from syncCode.
     * Output format:
     * [1 byte version = 1]
     * [16 bytes salt]
     * [12 bytes IV]
     * [N bytes ciphertext + 16 bytes GCM tag]
     */
    fun encrypt(rawCode: String, plaintext: ByteArray): ByteArray {
        val code = SyncCode.normalizeCode(rawCode)
        val salt = ByteArray(SALT_LENGTH).apply { secureRandom.nextBytes(this) }
        val iv = ByteArray(IV_LENGTH).apply { secureRandom.nextBytes(this) }

        val key = deriveKey(code, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec)
        cipher.updateAAD(byteArrayOf(VERSION))

        val ciphertext = cipher.doFinal(plaintext)

        val output = ByteBuffer.allocate(1 + SALT_LENGTH + IV_LENGTH + ciphertext.size)
        output.put(VERSION)
        output.put(salt)
        output.put(iv)
        output.put(ciphertext)
        return output.array()
    }

    /**
     * Decrypts encrypted blob using syncCode.
     * Throws AEADBadTagException if the syncCode is wrong or data was tampered with.
     */
    @Throws(AEADBadTagException::class, IllegalArgumentException::class)
    fun decrypt(rawCode: String, blob: ByteArray): ByteArray {
        if (blob.size < 1 + SALT_LENGTH + IV_LENGTH + (TAG_LENGTH_BITS / 8)) {
            throw IllegalArgumentException("Encrypted blob is too short")
        }

        val buffer = ByteBuffer.wrap(blob)
        val version = buffer.get()
        if (version != VERSION) {
            throw IllegalArgumentException("Unsupported backup version: $version")
        }

        val salt = ByteArray(SALT_LENGTH)
        buffer.get(salt)

        val iv = ByteArray(IV_LENGTH)
        buffer.get(iv)

        val ciphertext = ByteArray(buffer.remaining())
        buffer.get(ciphertext)

        val code = SyncCode.normalizeCode(rawCode)
        val key = deriveKey(code, salt)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec)
        cipher.updateAAD(byteArrayOf(VERSION))

        return cipher.doFinal(ciphertext)
    }

    private fun deriveKey(code: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(code.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val secretBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(secretBytes, "AES")
    }
}
