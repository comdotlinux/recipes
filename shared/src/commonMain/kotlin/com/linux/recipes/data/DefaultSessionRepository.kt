package com.linux.recipes.data

import com.linux.recipes.platform.AuthProvider
import com.linux.recipes.platform.AuthResult
import com.linux.recipes.platform.SecureStore
import com.linux.recipes.platform.TurnstileAnswer
import com.linux.recipes.platform.TurnstileGate
import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The session token and its user id, kept in [SecureStore] and mirrored in memory for request headers.
 * The token is opaque (docs/api.md, "Authentication"): the app never parses it, so the user id from
 * `SessionResponse` is stored next to it.
 */
class TokenStore(
    private val secure: SecureStore,
    /** Called when sign-out could not remove the stored values; the app then removes them at its next start. */
    private val onClearFailed: () -> Unit = {},
) {
    @Volatile
    var token: String? = secure.get(KEY)
        private set

    /**
     * The session lives in memory even when the store refuses it; the next start is then signed out.
     * A token without its user id is cleared at start ([DefaultSessionRepository]), so a half write is safe.
     */
    fun save(token: String, userId: String) {
        secure.put(USER_ID_KEY, userId)
        secure.put(KEY, token)
        this.token = token
    }

    /** Ends the session in memory whatever the store answers. */
    fun clear() {
        // `and`, not `&&`: both removals are attempted.
        val removed = secure.remove(KEY) and secure.remove(USER_ID_KEY)
        token = null
        if (!removed) onClearFailed()
    }

    /** The signed-in user, or null when either value is missing. */
    fun userId(): String? = if (token == null) null else secure.get(USER_ID_KEY)

    companion object {
        const val KEY = "session_token"
        const val USER_ID_KEY = "session_user_id"
    }
}

/**
 * Sign-in order: server nonce, platform sign-in with that nonce, Turnstile check, `POST /session`.
 * [clearLocal] wipes cached recipes and pictures on sign-out.
 */
class DefaultSessionRepository(
    private val api: Api,
    private val auth: AuthProvider,
    private val turnstile: TurnstileGate,
    private val tokens: TokenStore,
    /** Diagnostics for failed sign-ins; never receives tokens or nonces. Android prints to logcat (System.out). */
    private val log: (String) -> Unit = { println("RecipesSignIn: $it") },
    private val clearLocal: suspend () -> Unit,
) : SessionRepository {
    init {
        // A token without its user id (one value failed to decrypt) must not keep being sent while signed out.
        if (tokens.token != null && tokens.userId() == null) tokens.clear()
    }

    private val _state = MutableStateFlow<SessionState>(
        tokens.userId()?.let { SessionState.SignedIn(it) } ?: SessionState.SignedOut,
    )
    override val state: StateFlow<SessionState> = _state.asStateFlow()

    override suspend fun signIn(ageConfirmed: Boolean): SignInResult {
        if (!ageConfirmed) return SignInResult.Failed(SignInResult.Failed.Reason.AgeNotConfirmed)
        val nonce = when (val result = api.nonce()) {
            is ApiResult.Ok -> result.value.nonce
            else -> return failure(result).also { log("nonce failed: ${describe(result)}") }
        }
        val identity = when (val result = auth.signIn(nonce)) {
            is AuthResult.Success -> result
            AuthResult.Cancelled -> return SignInResult.Cancelled
            is AuthResult.Failed -> return SignInResult.Failed(SignInResult.Failed.Reason.Rejected)
                .also { log("${auth.provider} sign-in failed: ${result.message}") }
        }
        val turnstileToken = when (val answer = turnstile.awaitAnswer()) {
            is TurnstileAnswer.Token -> answer.value
            TurnstileAnswer.Cancelled -> return SignInResult.Cancelled
            TurnstileAnswer.Failed -> return SignInResult.Failed(SignInResult.Failed.Reason.Rejected)
                .also { log("Turnstile page could not be shown") }
        }
        val request = SessionRequest(
            provider = auth.provider,
            idToken = identity.idToken,
            nonce = nonce,
            turnstileToken = turnstileToken,
            ageConfirmed = true,
            appleAuthCode = identity.appleAuthCode,
        )
        return when (val result = api.createSession(request)) {
            is ApiResult.Ok -> {
                // Whatever an earlier user left on disk must not be synced from that user's cursor.
                clearLocal()
                tokens.save(result.value.token, result.value.userId)
                _state.value = SessionState.SignedIn(result.value.userId)
                SignInResult.Success
            }
            else -> failure(result).also { log("session failed: ${describe(result)}") }
        }
    }

    /** Wipes local data first and the token last, so a failing wipe never leaves data behind without a session. */
    override suspend fun signOut() {
        if (tokens.token != null) api.deleteSession()
        clearLocal()
        tokens.clear()
        _state.value = SessionState.SignedOut
    }

    private fun describe(result: ApiResult<*>) = if (result is ApiResult.Failure) "${result.status} ${result.wire}" else "offline"

    /** 5xx and no answer: try again later. 4xx refusals: Rejected. 429: RateLimited. */
    private fun failure(result: ApiResult<*>): SignInResult.Failed = SignInResult.Failed(
        when {
            result !is ApiResult.Failure -> SignInResult.Failed.Reason.Network
            result.status >= 500 -> SignInResult.Failed.Reason.Network
            result.status == 429 -> SignInResult.Failed.Reason.RateLimited
            result.code == ErrorCode.AgeConfirmationRequired -> SignInResult.Failed.Reason.AgeNotConfirmed
            result.code == ErrorCode.AccountDeleting -> SignInResult.Failed.Reason.AccountDeleting
            else -> SignInResult.Failed.Reason.Rejected
        },
    )
}
