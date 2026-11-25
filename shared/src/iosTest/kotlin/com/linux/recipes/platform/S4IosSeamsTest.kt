package com.linux.recipes.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import platform.Security.errSecItemNotFound
import platform.Security.errSecMissingEntitlement
import platform.Security.errSecNotAvailable
import platform.Security.errSecSuccess

/**
 * Spike S4: the iOS seams work from Kotlin/Native without Swift. Runs only on the macOS CI job.
 * The WKWebView bridge is not tested here: a bare test binary has no web content process (docs/spikes/S4.md).
 */
class S4IosSeamsTest {
    /**
     * A bare test binary on the simulator gets errSecNotAvailable from the Keychain (first CI run, docs/spikes/S4.md);
     * the app gets a working Keychain. Either way nothing throws; only these two statuses count as "unavailable".
     */
    @Test
    fun keychainRoundTripOrSafeDegradation() {
        val store = KeychainSecureStore(service = "com.linux.recipes.s4-test")
        val status = store.delete("session_token")
        if (status == errSecNotAvailable || status == errSecMissingEntitlement) {
            assertFalse(store.remove("session_token"), "a failed delete is reported")
            assertFalse(store.put("session_token", "v1.user.secret"), "a failed write is reported")
            assertNull(store.get("session_token"), "an unreadable value is absent")
            return
        }
        assertTrue(status == errSecSuccess || status == errSecItemNotFound, "unexpected OSStatus $status")
        assertNull(store.get("session_token"))
        assertTrue(store.put("session_token", "v1.user.secret"))
        assertEquals("v1.user.secret", store.get("session_token"))
        assertTrue(store.put("session_token", "v1.user.replaced"))
        assertEquals("v1.user.replaced", store.get("session_token"))
        assertTrue(store.remove("session_token"))
        assertNull(store.get("session_token"))
        assertTrue(store.remove("session_token"), "removing an absent value succeeds")
    }

    @Test
    fun appleRequestHasTheHashedNonceAndNoScopes() {
        val request = AppleAuthProvider.appleIdRequest("n1.server")
        assertEquals(sha256Hex("n1.server"), request.nonce)
        assertTrue(request.requestedScopes.orEmpty().isEmpty(), "no name or email scope")
    }
}
