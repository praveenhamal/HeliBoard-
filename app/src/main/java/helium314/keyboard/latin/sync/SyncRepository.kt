// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.sync

import android.content.Context
import androidx.core.content.edit
import helium314.keyboard.latin.utils.protectedPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.crypto.AEADBadTagException

sealed class SyncState {
    object SignedOut : SyncState()
    data class NeedCode(val email: String) : SyncState()
    data class Ready(val email: String, val code: String, val lastSyncedAt: Long) : SyncState()
    data class Loading(val message: String) : SyncState()
    data class Error(val message: String) : SyncState()
}

class SyncRepository(private val context: Context) {
    companion object {
        const val PREF_SYNC_SERVER_CLIENT_ID = "sync_server_client_id"
        const val PREF_SYNC_WORKER_ENDPOINT = "sync_worker_endpoint"
        const val PREF_SYNC_CLIPBOARD_ENABLED = "sync_clipboard_enabled"
        const val PREF_SYNC_LAST_SYNCED = "sync_last_synced"
    }

    private val prefs = context.protectedPrefs()

    fun getServerClientId(): String {
        return prefs.getString(PREF_SYNC_SERVER_CLIENT_ID, "") ?: ""
    }

    fun setServerClientId(clientId: String) {
        prefs.edit { putString(PREF_SYNC_SERVER_CLIENT_ID, clientId.trim()) }
    }

    fun getWorkerEndpoint(): String {
        return prefs.getString(PREF_SYNC_WORKER_ENDPOINT, "") ?: ""
    }

    fun setWorkerEndpoint(endpoint: String) {
        prefs.edit { putString(PREF_SYNC_WORKER_ENDPOINT, endpoint.trim()) }
    }

    fun isClipboardSyncEnabled(): Boolean {
        return prefs.getBoolean(PREF_SYNC_CLIPBOARD_ENABLED, true)
    }

    fun setClipboardSyncEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(PREF_SYNC_CLIPBOARD_ENABLED, enabled) }
    }

    fun getLastSyncedAt(): Long {
        return prefs.getLong(PREF_SYNC_LAST_SYNCED, 0L)
    }

    fun setLastSyncedAt(ts: Long) {
        prefs.edit { putLong(PREF_SYNC_LAST_SYNCED, ts) }
    }

    fun getCurrentState(): SyncState {
        val email = GoogleAuthManager.getSavedAccountEmail(context) ?: return SyncState.SignedOut
        val code = SyncCode.getCode(context)
        return if (code.isNullOrBlank()) {
            SyncState.NeedCode(email)
        } else {
            SyncState.Ready(email, code, getLastSyncedAt())
        }
    }

    suspend fun signIn(serverClientId: String): Result<String> = withContext(Dispatchers.IO) {
        setServerClientId(serverClientId)
        val result = GoogleAuthManager.signIn(context, serverClientId)
        result.map { it.id }
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        GoogleAuthManager.signOut(context)
        SyncCode.clearCode(context)
        setLastSyncedAt(0L)
    }

    fun generateAndSaveCode(): String {
        val newCode = SyncCode.generateCode()
        SyncCode.saveCode(context, newCode)
        return newCode
    }

    fun savePairingCode(code: String) {
        SyncCode.saveCode(context, code)
    }

    suspend fun syncTo(): Result<Long> = withContext(Dispatchers.IO) {
        val serverClientId = getServerClientId()
        val endpoint = getWorkerEndpoint()
        if (serverClientId.isBlank() || endpoint.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Server Client ID or Endpoint is not configured."))
        }

        val code = SyncCode.getCode(context)
            ?: return@withContext Result.failure(IllegalStateException("Sync code is not set."))

        val tokenResult = GoogleAuthManager.getIdToken(context, serverClientId)
        val token = tokenResult.getOrElse { return@withContext Result.failure(it) }

        // 1. Serialize local data
        val plainBytes = SyncSerializer.serialize(context, syncClipboard = isClipboardSyncEnabled())

        // 2. Encrypt with E2E sync code
        val encryptedBlob = SyncCrypto.encrypt(code, plainBytes)

        // 3. Upload to Cloudflare Worker
        val putResult = SyncApi.putBlob(endpoint, token, encryptedBlob)
        putResult.onSuccess { updatedAt ->
            setLastSyncedAt(updatedAt)
        }
        putResult
    }

    suspend fun syncFrom(): Result<SyncPayload> = withContext(Dispatchers.IO) {
        val serverClientId = getServerClientId()
        val endpoint = getWorkerEndpoint()
        if (serverClientId.isBlank() || endpoint.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Server Client ID or Endpoint is not configured."))
        }

        val code = SyncCode.getCode(context)
            ?: return@withContext Result.failure(IllegalStateException("Sync code is not set."))

        val tokenResult = GoogleAuthManager.getIdToken(context, serverClientId)
        val token = tokenResult.getOrElse { return@withContext Result.failure(it) }

        // 1. Download encrypted blob
        val blobResult = SyncApi.getBlob(endpoint, token)
        val blob = blobResult.getOrElse { return@withContext Result.failure(it) }

        // 2. Decrypt with E2E sync code
        val decryptedBytes = try {
            SyncCrypto.decrypt(code, blob)
        } catch (e: AEADBadTagException) {
            return@withContext Result.failure(IllegalStateException("Wrong sync code or corrupted cloud backup."))
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }

        // 3. Deserialize and apply
        try {
            val payload = SyncSerializer.deserialize(context, decryptedBytes, mergeClipboard = true)
            setLastSyncedAt(payload.createdAt)
            Result.success(payload)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun checkCloudMeta(): Result<SyncMetaResponse> = withContext(Dispatchers.IO) {
        val serverClientId = getServerClientId()
        val endpoint = getWorkerEndpoint()
        if (serverClientId.isBlank() || endpoint.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Not configured."))
        }
        val tokenResult = GoogleAuthManager.getIdToken(context, serverClientId)
        val token = tokenResult.getOrElse { return@withContext Result.failure(it) }
        SyncApi.getMeta(endpoint, token)
    }

    suspend fun deleteCloudBackup(): Result<Unit> = withContext(Dispatchers.IO) {
        val serverClientId = getServerClientId()
        val endpoint = getWorkerEndpoint()
        val tokenResult = GoogleAuthManager.getIdToken(context, serverClientId)
        val token = tokenResult.getOrElse { return@withContext Result.failure(it) }
        SyncApi.deleteBlob(endpoint, token)
    }
}
