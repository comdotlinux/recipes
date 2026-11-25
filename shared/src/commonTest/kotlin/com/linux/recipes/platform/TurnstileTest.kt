package com.linux.recipes.platform

import com.linux.recipes.contract.contractFixtures
import kotlin.test.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class TurnstileTest {
    private val base = "https://recipe.munich-tech.de"

    @Test
    fun bridgeNamesMatchTheContractFixtureThePageIsCheckedAgainst() {
        val names = Json.parseToJsonElement(contractFixtures.getValue("turnstile_bridge.json")).jsonObject
        assertEquals(names.getValue("android").jsonObject.getValue("object").jsonPrimitive.content, TURNSTILE_ANDROID_BRIDGE)
        assertEquals(names.getValue("ios").jsonObject.getValue("messageHandler").jsonPrimitive.content, TURNSTILE_IOS_HANDLER)
        assertEquals("onToken", names.getValue("android").jsonObject.getValue("method").jsonPrimitive.content)
    }

    @Test
    fun pageUrl() {
        assertEquals("https://recipe.munich-tech.de/turnstile.html", TurnstilePolicy.pageUrl(base))
    }

    @Test
    fun onlyTheBaseHostAndTurnstileOverHttpsAreAllowed() {
        listOf(
            "https://recipe.munich-tech.de/turnstile.html",
            "https://recipe.munich-tech.de/other?x=1",
            "https://challenges.cloudflare.com/turnstile/v0/api.js",
            "about:blank",
            "about:srcdoc",
        ).forEach { assertTrue(TurnstilePolicy.isAllowed(it, base), it) }
        listOf(
            "http://recipe.munich-tech.de/turnstile.html",
            "http://challenges.cloudflare.com/x",
            "https://evil.example/turnstile.html",
            "https://recipe.munich-tech.de.evil.example/",
            "https://munich-tech.de/",
            "javascript:alert(1)",
            "file:///etc/passwd",
            "data:text/html,hi",
            "not a url",
        ).forEach { assertFalse(TurnstilePolicy.isAllowed(it, base), it) }
    }

    @Test
    fun aLocalDebugBaseUrlAllowsExactlyItsOwnOrigin() {
        val local = "http://10.0.2.2:8787"
        assertTrue(TurnstilePolicy.isAllowed("http://10.0.2.2:8787/turnstile.html", local))
        assertTrue(TurnstilePolicy.isAllowed("https://challenges.cloudflare.com/turnstile/v0/api.js", local))
        assertFalse(TurnstilePolicy.isAllowed("http://10.0.2.2:9999/", local), "other port")
        assertFalse(TurnstilePolicy.isAllowed("https://10.0.2.2:8787/", local), "other scheme")
        assertFalse(TurnstilePolicy.isAllowed("http://challenges.cloudflare.com/", local))
    }

    @Test
    fun scriptMessagesComeOnlyFromTheMainFrameOfTheExactOrigin() {
        data class Case(val main: Boolean, val protocol: String, val host: String, val port: Int, val base: String, val ok: Boolean)
        listOf(
            Case(true, "https", "recipe.munich-tech.de", 0, base, true),
            Case(true, "https", "recipe.munich-tech.de", 443, base, true),
            Case(false, "https", "recipe.munich-tech.de", 0, base, false),
            Case(true, "http", "recipe.munich-tech.de", 0, base, false),
            Case(true, "https", "recipe.munich-tech.de", 8443, base, false),
            Case(true, "https", "challenges.cloudflare.com", 0, base, false),
            Case(true, "https", "evil.example", 0, base, false),
            Case(true, "http", "10.0.2.2", 8787, "http://10.0.2.2:8787", true),
            Case(true, "http", "10.0.2.2", 9999, "http://10.0.2.2:8787", false),
            Case(true, "http", "localhost", 0, "http://localhost", true),
            Case(true, "file", "", 0, base, false),
        ).forEach { c ->
            assertEquals(c.ok, TurnstilePolicy.isTrustedMessageOrigin(c.main, c.protocol, c.host, c.port, c.base), c.toString())
        }
    }

    @Test
    fun gateHandsTheTokenToTheWaitingSignIn() = runTest {
        val gate = TurnstileGate()
        assertFalse(gate.pending.value)
        gate.deliver("ignored while nobody waits")

        val token = async { gate.awaitToken() }
        gate.pending.first { it }
        gate.deliver("tok")
        assertEquals("tok", token.await())
        assertFalse(gate.pending.value)

        val cancelled = async { gate.awaitToken() }
        gate.pending.first { it }
        gate.deliver(null)
        assertNull(cancelled.await())

        val failed = async { gate.awaitAnswer() }
        gate.pending.first { it }
        gate.fail()
        assertEquals(TurnstileAnswer.Failed, failed.await())
    }
}
