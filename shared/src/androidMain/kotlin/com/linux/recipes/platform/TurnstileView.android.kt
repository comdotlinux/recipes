package com.linux.recipes.platform

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream

@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun TurnstileView(baseUrl: String, onToken: (String) -> Unit, modifier: Modifier, onError: () -> Unit) {
    val latestOnToken by rememberUpdatedState(onToken)
    val latestOnError by rememberUpdatedState(onError)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webViewClient = TurnstileWebViewClient(baseUrl) { latestOnError() }
                addJavascriptInterface(TurnstileBridge { token -> post { latestOnToken(token) } }, TURNSTILE_ANDROID_BRIDGE)
                loadUrl(TurnstilePolicy.pageUrl(baseUrl))
            }
        },
        onRelease = { it.destroy() },
    )
}

/** `window.RecipesBridge.onToken(token)` in the page lands here (on a WebView background thread). */
class TurnstileBridge(private val onToken: (String) -> Unit) {
    @JavascriptInterface
    fun onToken(token: String) {
        if (token.isNotBlank()) onToken.invoke(token)
    }
}

/** Blocks navigation and every request outside [TurnstilePolicy.isAllowed]; reports a page that fails to load. */
class TurnstileWebViewClient(private val baseUrl: String, private val onError: () -> Unit = {}) : WebViewClient() {
    override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest, errorResponse: WebResourceResponse) {
        if (request.isForMainFrame && errorResponse.statusCode >= 400) {
            Log.w(TAG, "Turnstile page answered HTTP ${errorResponse.statusCode}")
            onError()
        }
    }

    override fun onReceivedError(view: WebView?, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame) {
            Log.w(TAG, "Turnstile page failed to load: ${error.errorCode} ${error.description}")
            onError()
        }
    }

    private companion object {
        const val TAG = "RecipesTurnstile"
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean =
        !TurnstilePolicy.isAllowed(request.url.toString(), baseUrl)

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? =
        if (TurnstilePolicy.isAllowed(request.url.toString(), baseUrl)) {
            null
        } else {
            WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
}
