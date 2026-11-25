package com.linux.recipes.data

import com.linux.recipes.platform.BillingProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [BillingRepository] over the store SDK ([provider]) and `POST /entitlement/refresh` (docs/api.md, "Subscription").
 *
 * The SDK is logged in with our user id whenever the session is signed in (after sign-in, and at app start
 * with a restored session) and logged out whenever it is signed out (sign-out, and any 401, which signs out).
 * [scope] runs that follower for the life of the app. Nothing reaches the store while signed out.
 */
class DefaultBillingRepository(
    private val api: Api,
    private val provider: BillingProvider,
    private val session: SessionRepository,
    scope: CoroutineScope,
    private val currentToken: () -> String? = { null },
    private val onUnauthorized: suspend () -> Unit,
) : BillingRepository {
    private val lock = Mutex()

    init {
        scope.launch { session.state.collect { sync(it) } }
    }

    override suspend fun plans(): List<Plan> = if (ensureLoggedIn()) provider.plans() else emptyList()

    override suspend fun purchase(planId: String): PurchaseResult = storeThenServer { provider.purchase(planId) }

    override suspend fun restore(): PurchaseResult = storeThenServer { provider.restore() }

    override fun manageUrl(): String? = provider.manageUrl()

    /** The store first; only when it accepted, our server decides whether the entitlement is active. */
    private suspend fun storeThenServer(store: suspend () -> PurchaseResult): PurchaseResult {
        if (session.state.value !is SessionState.SignedIn) return PurchaseResult.Failed
        if (!ensureLoggedIn()) return PurchaseResult.StoreUnavailable
        val result = store()
        if (result != PurchaseResult.Success) return result
        val token = currentToken()
        return when (val refresh = api.refreshEntitlement()) {
            is ApiResult.Ok -> if (refresh.value.entitlement.active) PurchaseResult.Success else PurchaseResult.Pending
            is ApiResult.Failure if refresh.status == 401 -> PurchaseResult.Failed.also {
                if (token != null && token == currentToken()) onUnauthorized()
            }
            // Offline, 429 or 503 billing_unavailable: the server learns of it from RevenueCat later.
            else -> PurchaseResult.Pending
        }
    }

    /** True when the SDK runs as the signed-in user; logs in first when the follower has not yet. False when signed out. */
    private suspend fun ensureLoggedIn(): Boolean {
        val userId = (session.state.value as? SessionState.SignedIn)?.userId ?: return false
        return lock.withLock { provider.logIn(userId) }
    }

    private suspend fun sync(state: SessionState) = lock.withLock {
        if (state is SessionState.SignedIn) provider.logIn(state.userId) else provider.logOut()
    }
}
