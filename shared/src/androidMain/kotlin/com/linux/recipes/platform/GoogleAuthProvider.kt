package com.linux.recipes.platform

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException

/**
 * Google sign-in through Credential Manager. [serverClientId] is the OAuth web client id the server checks
 * as audience; [activity] returns the activity that is currently shown, which Credential Manager needs.
 * Google always includes its basic profile claims in the id token; the server reads only `sub` and `nonce`.
 */
class GoogleAuthProvider(
    private val serverClientId: String,
    private val activity: () -> Activity?,
) : AuthProvider {
    override val provider = "google"

    override suspend fun signIn(nonce: String): AuthResult {
        if (serverClientId.isEmpty()) return AuthResult.Failed("GOOGLE_SERVER_CLIENT_ID is not set")
        val host = activity() ?: return AuthResult.Failed("no activity")
        val request = GetCredentialRequest.Builder().addCredentialOption(googleIdOption(serverClientId, nonce)).build()
        return try {
            val credential = CredentialManager.create(host).getCredential(host, request).credential
            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                AuthResult.Success(GoogleIdTokenCredential.createFrom(credential.data).idToken)
            } else {
                AuthResult.Failed("unexpected credential type ${credential.type}")
            }
        } catch (_: GetCredentialCancellationException) {
            AuthResult.Cancelled
        } catch (e: GetCredentialException) {
            AuthResult.Failed(e.type)
        } catch (_: GoogleIdTokenParsingException) {
            AuthResult.Failed("unreadable Google id token")
        }
    }

    companion object {
        /** Google gets the server nonce unchanged; the id token's nonce claim equals it (Apple gets the SHA-256). */
        fun googleIdOption(serverClientId: String, nonce: String): GetGoogleIdOption = GetGoogleIdOption.Builder()
            .setServerClientId(serverClientId)
            .setNonce(nonce)
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(false)
            .build()
    }
}
