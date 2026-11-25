package com.linux.recipes.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AI consent from `GET /me` (`consentVersion`), changed with `PUT` and `DELETE /me/consent`.
 * The state stays [ConsentState.Unknown] until `GET /me` reached the server: through [refresh], or as a side
 * effect of [DefaultImportRepository.quota] and [DefaultImportRepository.start]. A 401 ends the session as in
 * [DefaultRecipeRepository].
 */
class DefaultConsentRepository(
    private val api: Api,
    private val currentToken: () -> String? = { null },
    private val onUnauthorized: suspend () -> Unit,
) : ConsentRepository {
    private val _state = MutableStateFlow<ConsentState>(ConsentState.Unknown)
    override val state: StateFlow<ConsentState> = _state.asStateFlow()

    override val currentVersion: Int = AI_NOTICE_VERSION

    /** Runs with every `GET /me` answer; [DefaultImportRepository] uses it to adopt a job open on the server. */
    internal var onMe: (Me) -> Unit = {}

    /** True when the user accepted the notice this build shows. Import routes are only called then. */
    val isGiven: Boolean get() = _state.value == ConsentState.Given(currentVersion)

    override suspend fun refresh(): Boolean = loadMe() != null

    /** `GET /me`: updates [state] and returns the answer, or null when the server could not be reached. */
    suspend fun loadMe(): Me? {
        val token = currentToken()
        return when (val result = api.me()) {
            is ApiResult.Ok -> result.value.also {
                _state.value = stateOf(it.consentVersion)
                onMe(it)
            }
            else -> null.also { if (result.isUnauthorized()) unauthorized(token) }
        }
    }

    override suspend fun accept(): Boolean {
        val token = currentToken()
        return when (val result = api.putConsent(currentVersion)) {
            is ApiResult.Ok -> true.also { _state.value = stateOf(result.value.consentVersion) }
            else -> false.also { if (result.isUnauthorized()) unauthorized(token) }
        }
    }

    override suspend fun withdraw(): Boolean {
        val token = currentToken()
        return when (val result = api.deleteConsent()) {
            is ApiResult.Ok -> true.also { _state.value = ConsentState.NotGiven }
            else -> false.also { if (result.isUnauthorized()) unauthorized(token) }
        }
    }

    /** The server answered 403 consent_required (withdrawn on another device, or a newer notice). */
    fun markNotGiven() {
        _state.value = ConsentState.NotGiven
    }

    /** Forgets the state on sign-out. */
    fun clearLocal() {
        _state.value = ConsentState.Unknown
    }

    private suspend fun unauthorized(usedToken: String?) {
        if (usedToken != null && usedToken == currentToken()) onUnauthorized()
    }

    private fun ApiResult<*>.isUnauthorized() = this is ApiResult.Failure && status == 401

    companion object {
        /** Equal to `AI_NOTICE_VERSION` in backend/src/ai/gateway.ts; the server rejects any other version. */
        const val AI_NOTICE_VERSION = 1

        private fun stateOf(version: Int?): ConsentState = if (version == null) ConsentState.NotGiven else ConsentState.Given(version)
    }
}
