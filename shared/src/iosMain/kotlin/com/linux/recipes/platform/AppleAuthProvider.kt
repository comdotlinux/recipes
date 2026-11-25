package com.linux.recipes.platform

import kotlin.coroutines.resume
import kotlinx.cinterop.BetaInteropApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AuthenticationServices.ASAuthorization
import platform.AuthenticationServices.ASAuthorizationAppleIDCredential
import platform.AuthenticationServices.ASAuthorizationAppleIDProvider
import platform.AuthenticationServices.ASAuthorizationAppleIDRequest
import platform.AuthenticationServices.ASAuthorizationController
import platform.AuthenticationServices.ASAuthorizationControllerDelegateProtocol
import platform.AuthenticationServices.ASAuthorizationControllerPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASAuthorizationErrorCanceled
import platform.AuthenticationServices.ASPresentationAnchor
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.darwin.NSObject

/** Sign in with Apple. No scopes are requested, so Apple returns neither name nor email. */
class AppleAuthProvider : AuthProvider {
    override val provider = "apple"

    // ASAuthorizationController holds its delegate weakly; keep it alive until Apple answers.
    private var pending: AppleSignInDelegate? = null

    override suspend fun signIn(nonce: String): AuthResult = suspendCancellableCoroutine { continuation ->
        val delegate = AppleSignInDelegate { result ->
            pending = null
            if (continuation.isActive) continuation.resume(result)
        }
        pending = delegate
        val controller = ASAuthorizationController(authorizationRequests = listOf(appleIdRequest(nonce)))
        controller.delegate = delegate
        controller.presentationContextProvider = delegate
        continuation.invokeOnCancellation { pending = null }
        controller.performRequests()
    }

    companion object {
        /**
         * The request Apple receives: no scopes, and `nonce = sha256Hex(serverNonce)`. Apple echoes that value into
         * the id token; `POST /session` gets the unhashed server nonce and the server hashes it to compare.
         */
        fun appleIdRequest(nonce: String): ASAuthorizationAppleIDRequest =
            ASAuthorizationAppleIDProvider().createRequest().apply {
                requestedScopes = emptyList<Any>()
                setNonce(sha256Hex(nonce))
            }
    }
}

private class AppleSignInDelegate(private val done: (AuthResult) -> Unit) :
    NSObject(),
    ASAuthorizationControllerDelegateProtocol,
    ASAuthorizationControllerPresentationContextProvidingProtocol {

    override fun authorizationController(controller: ASAuthorizationController, didCompleteWithAuthorization: ASAuthorization) {
        val credential = didCompleteWithAuthorization.credential as? ASAuthorizationAppleIDCredential
        val idToken = credential?.identityToken?.utf8()
        done(
            if (idToken == null) {
                AuthResult.Failed("no identity token")
            } else {
                AuthResult.Success(idToken = idToken, appleAuthCode = credential.authorizationCode?.utf8())
            },
        )
    }

    override fun authorizationController(controller: ASAuthorizationController, didCompleteWithError: NSError) {
        done(if (didCompleteWithError.code == ASAuthorizationErrorCanceled) AuthResult.Cancelled else AuthResult.Failed(didCompleteWithError.localizedDescription))
    }

    override fun presentationAnchorForAuthorizationController(controller: ASAuthorizationController): ASPresentationAnchor =
        UIApplication.sharedApplication.connectedScenes
            .filterIsInstance<UIWindowScene>()
            .flatMap { it.windows.filterIsInstance<UIWindow>() }
            .firstOrNull { it.isKeyWindow() } ?: UIWindow()

    @OptIn(BetaInteropApi::class)
    private fun NSData.utf8(): String? = NSString.create(data = this, encoding = NSUTF8StringEncoding)?.toString()
}
