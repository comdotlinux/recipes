package com.linux.recipes.platform

import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import platform.Foundation.NSDate
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSRunLoop
import platform.Foundation.NSThread
import platform.Foundation.NSURL
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runMode

/** Spike S4: the iOS seams work from Kotlin/Native without Swift. Runs only on the macOS CI job. */
class S4IosSeamsTest {
    @Test
    fun keychainRoundTrip() {
        val store = KeychainSecureStore(service = "com.linux.recipes.s4-test")
        store.remove("session_token")
        assertNull(store.get("session_token"))
        store.put("session_token", "v1.user.secret")
        assertEquals("v1.user.secret", store.get("session_token"))
        store.put("session_token", "v1.user.replaced")
        assertEquals("v1.user.replaced", store.get("session_token"))
        store.remove("session_token")
        assertNull(store.get("session_token"))
    }

    @Test
    fun appleRequestHasTheHashedNonceAndNoScopes() {
        val request = AppleAuthProvider.appleIdRequest("n1.server")
        assertEquals(sha256Hex("n1.server"), request.nonce)
        assertTrue(request.requestedScopes.orEmpty().isEmpty(), "no name or email scope")
    }

    @Test
    fun webViewBridgeDeliversTheTokenToKotlin() {
        assertTrue(NSThread.isMainThread, "WKWebView needs the main thread")
        var token: String? = null
        val turnstile = TurnstileWebView("https://recipe.munich-tech.de") { token = it }
        // The page script is what /turnstile.html runs when Turnstile succeeds.
        turnstile.webView.loadHTMLString(
            "<html><body><script>window.webkit.messageHandlers.turnstile.postMessage('tok-123');</script></body></html>",
            baseURL = NSURL.URLWithString("https://recipe.munich-tech.de/turnstile.html"),
        )
        val started = TimeSource.Monotonic.markNow()
        while (token == null && started.elapsedNow() < 30.seconds) {
            NSRunLoop.mainRunLoop.runMode(NSDefaultRunLoopMode, beforeDate = NSDate.dateWithTimeIntervalSinceNow(0.1))
        }
        turnstile.release()
        assertEquals("tok-123", token)
    }
}
