package com.linux.recipes.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Shows the Turnstile page from `<baseUrl>/turnstile.html` in a web view (docs/api.md, "Turnstile page").
 * The page hands the token over once: Android `window.RecipesBridge.onToken(token)`, iOS
 * `window.webkit.messageHandlers.turnstile.postMessage(token)`. Navigation and requests are limited to the
 * base URL's origin and `https://challenges.cloudflare.com`. [onError] runs when the page itself cannot be shown
 * (an HTTP error such as 503 `not_configured`, or no connection), so the caller can end sign-in as a failure.
 */
@Composable
expect fun TurnstileView(baseUrl: String, onToken: (String) -> Unit, modifier: Modifier = Modifier, onError: () -> Unit = {})

/** Android: name of the JavaScript interface the Turnstile page calls. */
const val TURNSTILE_ANDROID_BRIDGE = "RecipesBridge"

/** iOS: name of the script message handler the Turnstile page posts to. */
const val TURNSTILE_IOS_HANDLER = "turnstile"

object TurnstilePolicy {
    const val CHALLENGE_HOST = "challenges.cloudflare.com"

    fun pageUrl(baseUrl: String) = "$baseUrl/turnstile.html"

    /**
     * True for the base URL's own origin (scheme, host and port, so a local `http://10.0.2.2:8787` in a debug
     * build works), for `https://challenges.cloudflare.com`, and for the empty documents iframes start with.
     */
    fun isAllowed(url: String, baseUrl: String): Boolean {
        if (url == "about:blank" || url == "about:srcdoc") return true
        val parsed = runCatching { Url(url) }.getOrNull() ?: return false
        val base = Url(baseUrl)
        val sameOrigin = parsed.protocol == base.protocol && parsed.host == base.host && parsed.port == base.port
        val challenge = parsed.protocol == URLProtocol.HTTPS && parsed.host == CHALLENGE_HOST
        return sameOrigin || challenge
    }

    /**
     * iOS script messages are accepted only from the main frame of the base URL's exact origin: same scheme, host
     * and port. WebKit reports port 0 when the URL uses the scheme's default port.
     */
    fun isTrustedMessageOrigin(isMainFrame: Boolean, protocol: String, host: String, port: Int, baseUrl: String): Boolean {
        if (!isMainFrame) return false
        val base = Url(baseUrl)
        val effectivePort = if (port == 0) URLProtocol.byName[protocol]?.defaultPort ?: return false else port
        return protocol == base.protocol.name && host == base.host && effectivePort == base.port
    }
}

/** What the Turnstile step produced. */
sealed interface TurnstileAnswer {
    data class Token(val value: String) : TurnstileAnswer
    data object Cancelled : TurnstileAnswer
    data object Failed : TurnstileAnswer
}

/**
 * Connects the sign-in flow to the UI: [awaitAnswer] suspends until the screen that shows [TurnstileView]
 * calls [deliver] (token, or null when the user closes the check) or [fail] (the page could not be shown).
 * While an answer is wanted, [pending] is true.
 */
class TurnstileGate {
    private val mutex = Mutex()
    private var waiting: CompletableDeferred<TurnstileAnswer>? = null
    private val _pending = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    /** The token, or null when the user closed the check or the page failed. */
    suspend fun awaitToken(): String? = (awaitAnswer() as? TurnstileAnswer.Token)?.value

    suspend fun awaitAnswer(): TurnstileAnswer = mutex.withLock {
        val deferred = CompletableDeferred<TurnstileAnswer>()
        waiting = deferred
        _pending.value = true
        try {
            deferred.await()
        } finally {
            waiting = null
            _pending.value = false
        }
    }

    /** Called by the UI with the token, or with null when the user cancels. */
    fun deliver(token: String?) {
        waiting?.complete(if (token == null) TurnstileAnswer.Cancelled else TurnstileAnswer.Token(token))
    }

    /** Called by the UI when [TurnstileView] reports that the page could not be shown. */
    fun fail() {
        waiting?.complete(TurnstileAnswer.Failed)
    }
}
