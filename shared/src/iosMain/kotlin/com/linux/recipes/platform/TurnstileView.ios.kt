package com.linux.recipes.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKNavigationResponse
import platform.WebKit.WKNavigationResponsePolicy
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject

@Composable
actual fun TurnstileView(baseUrl: String, onToken: (String) -> Unit, modifier: Modifier, onError: () -> Unit) {
    val latestOnToken by rememberUpdatedState(onToken)
    val latestOnError by rememberUpdatedState(onError)
    val turnstile = remember(baseUrl) { TurnstileWebView(baseUrl, onError = { latestOnError() }) { latestOnToken(it) } }
    UIKitView(
        factory = {
            turnstile.load()
            turnstile.webView
        },
        modifier = modifier,
        onRelease = { turnstile.release() },
    )
}

/**
 * A WKWebView limited to [TurnstilePolicy]. The page posts the token to the `turnstile` script message handler,
 * which accepts messages only from the main frame of the base URL's exact origin.
 * Holds strong references to its delegates, which WebKit keeps weakly.
 */
@OptIn(ExperimentalForeignApi::class)
class TurnstileWebView(private val baseUrl: String, onError: () -> Unit = {}, onToken: (String) -> Unit) {
    private val messageHandler = TurnstileMessageHandler(baseUrl, onToken)
    private val navigationDelegate = TurnstileNavigationDelegate(baseUrl, onError)

    val webView: WKWebView = WKWebView(
        frame = CGRectZero.readValue(),
        configuration = WKWebViewConfiguration().apply {
            userContentController.addScriptMessageHandler(messageHandler, name = TURNSTILE_IOS_HANDLER)
        },
    ).also { it.navigationDelegate = navigationDelegate }

    fun load() {
        val url = NSURL.URLWithString(TurnstilePolicy.pageUrl(baseUrl)) ?: return
        webView.loadRequest(NSURLRequest.requestWithURL(url))
    }

    /** Breaks the retain cycle between the content controller and the message handler. */
    fun release() {
        webView.configuration.userContentController.removeScriptMessageHandlerForName(TURNSTILE_IOS_HANDLER)
        webView.navigationDelegate = null
    }
}

private class TurnstileMessageHandler(private val baseUrl: String, private val onToken: (String) -> Unit) :
    NSObject(),
    WKScriptMessageHandlerProtocol {
    override fun userContentController(userContentController: WKUserContentController, didReceiveScriptMessage: WKScriptMessage) {
        val frame = didReceiveScriptMessage.frameInfo
        val origin = frame.securityOrigin
        TurnstilePolicy.messageToken(frame.isMainFrame(), origin.protocol, origin.host, origin.port.toInt(), baseUrl, didReceiveScriptMessage.body)
            ?.let(onToken)
    }
}

private class TurnstileNavigationDelegate(private val baseUrl: String, private val onError: () -> Unit) :
    NSObject(),
    WKNavigationDelegateProtocol {
    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (WKNavigationActionPolicy) -> Unit,
    ) {
        val url = decidePolicyForNavigationAction.request.URL?.absoluteString.orEmpty()
        decisionHandler(
            if (TurnstilePolicy.isAllowed(url, baseUrl)) {
                WKNavigationActionPolicy.WKNavigationActionPolicyAllow
            } else {
                WKNavigationActionPolicy.WKNavigationActionPolicyCancel
            },
        )
    }

    /** An HTTP error for the page itself (for example 503 `not_configured`) ends the check as failed. */
    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationResponse: WKNavigationResponse,
        decisionHandler: (WKNavigationResponsePolicy) -> Unit,
    ) {
        val status = (decidePolicyForNavigationResponse.response as? NSHTTPURLResponse)?.statusCode ?: 200
        if (decidePolicyForNavigationResponse.forMainFrame && status >= 400) {
            decisionHandler(WKNavigationResponsePolicy.WKNavigationResponsePolicyCancel)
            onError()
        } else {
            decisionHandler(WKNavigationResponsePolicy.WKNavigationResponsePolicyAllow)
        }
    }

    override fun webView(webView: WKWebView, didFailProvisionalNavigation: WKNavigation?, withError: NSError) {
        onError()
    }
}
