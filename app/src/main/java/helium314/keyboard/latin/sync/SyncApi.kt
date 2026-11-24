// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class SyncMetaResponse(
    val updatedAt: Long = 0,
    val size: Long = 0
)

object SyncApi {
    private val json = Json { ignoreUnknownKeys = true }

    fun getBlob(endpoint: String, idToken: String): Result<ByteArray> {
        return runCatching {
            val url = URL(normalizeEndpoint(endpoint) + "/sync")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $idToken")
                connectTimeout = 15_000
                readTimeout = 15_000
            }

            val code = conn.responseCode
            if (code == 404) {
                throw NoSuchElementException("Nothing synced yet.")
            }
            if (code !in 200..299) {
                val errorMsg = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
                throw IllegalStateException("Sync download failed: $errorMsg")
            }

            conn.inputStream.use { it.readBytes() }
        }
    }

    fun getMeta(endpoint: String, idToken: String): Result<SyncMetaResponse> {
        return runCatching {
            val url = URL(normalizeEndpoint(endpoint) + "/sync/meta")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $idToken")
                connectTimeout = 10_000
                readTimeout = 10_000
            }

            val code = conn.responseCode
            if (code == 404) {
                throw NoSuchElementException("No cloud backup found.")
            }
            if (code !in 200..299) {
                val errorMsg = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
                throw IllegalStateException("Metadata fetch failed: $errorMsg")
            }

            val text = conn.inputStream.bufferedReader().use { it.readText() }
            json.decodeFromString<SyncMetaResponse>(text)
        }
    }

    fun putBlob(endpoint: String, idToken: String, encryptedBlob: ByteArray): Result<Long> {
        return runCatching {
            val url = URL(normalizeEndpoint(endpoint) + "/sync")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                doOutput = true
                setRequestProperty("Authorization", "Bearer $idToken")
                setRequestProperty("Content-Type", "application/octet-stream")
                setRequestProperty("Content-Length", encryptedBlob.size.toString())
                connectTimeout = 20_000
                readTimeout = 20_000
            }

            conn.outputStream.use { it.write(encryptedBlob) }

            val code = conn.responseCode
            if (code !in 200..299) {
                val errorMsg = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
                throw IllegalStateException("Sync upload failed: $errorMsg")
            }

            val text = conn.inputStream.bufferedReader().use { it.readText() }
            // Returns json with { success: true, updatedAt: 12345 }
            val meta = json.decodeFromString<SyncMetaResponse>(text)
            meta.updatedAt
        }
    }

    fun deleteBlob(endpoint: String, idToken: String): Result<Unit> {
        return runCatching {
            val url = URL(normalizeEndpoint(endpoint) + "/sync")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "DELETE"
                setRequestProperty("Authorization", "Bearer $idToken")
                connectTimeout = 10_000
                readTimeout = 10_000
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                val errorMsg = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
                throw IllegalStateException("Delete failed: $errorMsg")
            }
        }
    }

    private fun normalizeEndpoint(endpoint: String): String {
        return endpoint.trim().removeSuffix("/")
    }
}
