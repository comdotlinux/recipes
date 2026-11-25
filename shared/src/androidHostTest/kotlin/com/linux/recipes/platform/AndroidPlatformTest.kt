package com.linux.recipes.platform

import android.net.Uri
import android.webkit.WebResourceRequest
import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric has no AndroidKeyStore provider, so the store is tested with a software AES key of the same
 * type; the Keystore key itself is only exercised on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidPlatformTest {
    private val dir: File = Files.createTempDirectory("secure").toFile()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val store = AndroidSecureStore(dir) { key }

    @Test
    fun secureStoreRoundTripIsEncryptedOnDisk() {
        assertNull(store.get("session_token"))
        store.put("session_token", "v1.user.secret.tag")
        assertEquals("v1.user.secret.tag", store.get("session_token"))
        val raw = File(dir, "session_token.bin").readBytes()
        assertFalse(raw.decodeToString(throwOnInvalidSequence = false).contains("secret"))
        assertEquals(listOf("session_token.bin"), dir.list()!!.toList())

        store.put("session_token", "v1.user.other.tag")
        assertEquals("v1.user.other.tag", store.get("session_token"))
        store.remove("session_token")
        assertNull(store.get("session_token"))
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test
    fun keyPresentButFileMissingReadsAsSignedOut() {
        // State after the app's files are cleared while its Keystore key survives.
        assertNull(store.get("session_token"))
        store.put("session_token", "v1.user.secret.tag")
        assertEquals("v1.user.secret.tag", store.get("session_token"))
    }

    @Test
    fun fileWithoutItsKeyIsDiscardedAndReadsAsAbsent() {
        // State after the Keystore key is gone (data clear, restore) but the encrypted file survived.
        store.put("session_token", "v1.user.secret.tag")
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertNull(AndroidSecureStore(dir) { otherKey }.get("session_token"))
        assertFalse("the broken entry is removed", File(dir, "session_token.bin").exists())

        store.put("session_token", "v1.user.secret.tag")
        File(dir, "session_token.bin").writeBytes(ByteArray(5))
        assertNull(store.get("session_token"))
    }

    @Test
    fun keystoreProviderFailureReadsAsAbsentInsteadOfCrashing() {
        store.put("session_token", "v1.user.secret.tag")
        val failing = AndroidSecureStore(dir) { throw java.security.ProviderException("keystore unavailable") }
        assertNull(failing.get("session_token"))
        assertFalse(File(dir, "session_token.bin").exists())
        assertFalse("a failed write is reported, not thrown", failing.put("session_token", "v1.user.secret.tag"))
        assertTrue("nothing is stored any more", failing.remove("session_token"))
    }

    @Test
    fun keyNamesCannotEscapeTheDirectory() {
        assertThrows(IllegalArgumentException::class.java) { store.put("../token", "x") }
    }

    @Test
    fun webViewClientBlocksForeignRequests() {
        val client = TurnstileWebViewClient("https://recipe.munich-tech.de")
        assertNull(client.shouldInterceptRequest(null, request("https://challenges.cloudflare.com/turnstile/v0/api.js")))
        assertNull(client.shouldInterceptRequest(null, request("https://recipe.munich-tech.de/turnstile.html")))
        val blocked = client.shouldInterceptRequest(null, request("https://tracker.example/pixel.gif"))!!
        assertEquals(403, blocked.statusCode)
        assertArrayEquals(ByteArray(0), blocked.data.readBytes())

        assertFalse(client.shouldOverrideUrlLoading(null, request("https://recipe.munich-tech.de/turnstile.html")))
        assertTrue(client.shouldOverrideUrlLoading(null, request("https://evil.example/")))
        assertTrue(client.shouldOverrideUrlLoading(null, request("http://recipe.munich-tech.de/")))
    }

    @Test
    fun webViewClientReportsAPageThatCannotBeShown() {
        var errors = 0
        val client = TurnstileWebViewClient("https://recipe.munich-tech.de") { errors++ }
        val notConfigured = android.webkit.WebResourceResponse("application/json", "utf-8", 503, "Service Unavailable", emptyMap(), null)
        client.onReceivedHttpError(null, request("https://recipe.munich-tech.de/turnstile.html"), notConfigured)
        assertEquals(1, errors)
        client.onReceivedHttpError(null, request("https://recipe.munich-tech.de/favicon.ico", mainFrame = false), notConfigured)
        assertEquals("subresource errors are ignored", 1, errors)
    }

    @Test
    fun bridgeExposesTheContractMethodToJavaScript() {
        // contract/fixtures/turnstile_bridge.json names the method "onToken"; the common TurnstileTest checks that.
        val method = TurnstileBridge::class.java.getMethod("onToken", String::class.java)
        assertTrue(method.isAnnotationPresent(android.webkit.JavascriptInterface::class.java))
    }

    @Test
    fun bridgePassesOnlyNonBlankTokens() {
        val received = mutableListOf<String>()
        val bridge = TurnstileBridge { received += it }
        bridge.onToken("")
        bridge.onToken("  ")
        bridge.onToken("tok")
        assertEquals(listOf("tok"), received)
    }

    @Test
    fun googleSignInNeedsAClientIdAndAnActivity() = runTest {
        assertEquals(AuthResult.Failed("GOOGLE_SERVER_CLIENT_ID is not set"), GoogleAuthProvider("") { null }.signIn("n"))
        assertEquals(AuthResult.Failed("no activity"), GoogleAuthProvider("client.apps.googleusercontent.com") { null }.signIn("n"))
        assertEquals("google", GoogleAuthProvider("x") { null }.provider)
    }

    @Test
    fun googleOptionCarriesTheServerNonceAndClientId() {
        val option = GoogleAuthProvider.googleIdOption("client.apps.googleusercontent.com", "n1.server")
        assertEquals("n1.server", option.nonce)
        assertEquals("client.apps.googleusercontent.com", option.serverClientId)
        assertFalse(option.filterByAuthorizedAccounts)
    }

    private fun request(url: String, mainFrame: Boolean = true) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame() = mainFrame
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }
}
