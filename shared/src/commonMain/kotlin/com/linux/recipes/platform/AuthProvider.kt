package com.linux.recipes.platform

/** Platform sign-in. Android: Credential Manager with Google ID. iOS: Sign in with Apple. */
interface AuthProvider {
    /** Value of `provider` in `POST /session`: "google" or "apple". */
    val provider: String

    /** Asks the user to sign in; the provider puts [nonce] (Google) or its SHA-256 (Apple) into the id token. */
    suspend fun signIn(nonce: String): AuthResult
}

sealed interface AuthResult {
    data class Success(val idToken: String, val appleAuthCode: String? = null) : AuthResult
    data object Cancelled : AuthResult
    data class Failed(val message: String) : AuthResult
}
