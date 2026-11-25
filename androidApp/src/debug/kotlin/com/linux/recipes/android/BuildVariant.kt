package com.linux.recipes.android

import android.app.Activity
import com.linux.recipes.platform.AuthProvider
import com.linux.recipes.platform.AuthResult

/**
 * Debug builds only (source set `debug`): talk to the local Worker (`wrangler dev --env local`) and sign in
 * with its test identity provider. The release source set has its own BuildVariant without any of this.
 */
object BuildVariant {
    /** The emulator reaches the development host at 10.0.2.2; override with `-PDEBUG_API_BASE_URL=...`. */
    val baseUrl: String = BuildConfig.DEBUG_API_BASE_URL

    @Suppress("UNUSED_PARAMETER")
    fun authProvider(activity: () -> Activity?): AuthProvider = TestAuthProvider()
}

/**
 * Provider "test" of the local entry (backend/src/testing/test-idp.ts): the id token is
 * `__TEST_IDP__|<sub>|<nonce>` with the server nonce unchanged. Every debug install signs in as the same user.
 */
class TestAuthProvider(private val sub: String = "debug-user") : AuthProvider {
    override val provider = "test"

    override suspend fun signIn(nonce: String): AuthResult = AuthResult.Success(idToken = "__TEST_IDP__|$sub|$nonce")
}
