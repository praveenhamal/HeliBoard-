// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.sync

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.protectedPrefs
import androidx.core.content.edit
import java.security.SecureRandom
import java.util.Base64

object GoogleAuthManager {
    private const val TAG = "GoogleAuthManager"
    private const val PREF_ACCOUNT_EMAIL = "sync_account_email"

    private fun generateNonce(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE)
    }

    suspend fun signIn(context: Context, serverClientId: String): Result<GoogleIdTokenCredential> {
        val credentialManager = CredentialManager.create(context)
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(serverClientId)
            .setAutoSelectEnabled(false)
            .setNonce(generateNonce())
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        return try {
            val response = credentialManager.getCredential(context = context, request = request)
            when (val credential = response.credential) {
                is CustomCredential -> {
                    if (credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                        val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                        // Save email for UI
                        context.protectedPrefs().edit {
                            putString(PREF_ACCOUNT_EMAIL, googleIdTokenCredential.id)
                        }
                        Result.success(googleIdTokenCredential)
                    } else {
                        Result.failure(Exception("Unsupported credential type: ${credential.type}"))
                    }
                }
                else -> Result.failure(Exception("Unexpected credential format"))
            }
        } catch (e: GetCredentialCancellationException) {
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Sign in failed", e)
            Result.failure(e)
        }
    }

    suspend fun getIdToken(context: Context, serverClientId: String): Result<String> {
        val credentialManager = CredentialManager.create(context)
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(true) // try silent / authorized first
            .setServerClientId(serverClientId)
            .setAutoSelectEnabled(true)
            .setNonce(generateNonce())
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        return try {
            val response = credentialManager.getCredential(context = context, request = request)
            val credential = response.credential
            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                Result.success(googleIdTokenCredential.idToken)
            } else {
                // Try interactive fallback
                val interactiveResult = signIn(context, serverClientId)
                interactiveResult.map { it.idToken }
            }
        } catch (_: Exception) {
            // Interactive fallback
            val interactiveResult = signIn(context, serverClientId)
            interactiveResult.map { it.idToken }
        }
    }

    fun getSavedAccountEmail(context: Context): String? {
        return context.protectedPrefs().getString(PREF_ACCOUNT_EMAIL, null)
    }

    suspend fun signOut(context: Context) {
        val credentialManager = CredentialManager.create(context)
        try {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        } catch (e: Exception) {
            Log.w(TAG, "Error clearing credentials", e)
        }
        context.protectedPrefs().edit {
            remove(PREF_ACCOUNT_EMAIL)
        }
    }
}
