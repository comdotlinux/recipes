package com.linux.recipes.data

import com.linux.recipes.platform.Recognition
import com.linux.recipes.platform.RevenueCatBillingProvider
import com.linux.recipes.platform.TextRecognizer
import com.linux.recipes.ui.FakeBillingProvider
import com.linux.recipes.ui.FakeSessionRepository
import com.linux.recipes.ui.USER_ID
import com.linux.recipes.ui.testPlans
import com.revenuecat.purchases.kmp.models.PurchasesErrorCode
import io.ktor.http.HttpMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class BillingRepositoryTest {
    private val server = FakeServer()
    private val provider = FakeBillingProvider()
    private var token: String? = "v1.user.secret.tag"
    private var unauthorizedCalls = 0
    private val inactive = fixture("entitlement_refresh.json").replace("\"active\": true", "\"active\": false")

    private fun TestScope.repo(session: FakeSessionRepository = FakeSessionRepository(signedIn = true), scope: CoroutineScope = backgroundScope) =
        DefaultBillingRepository(server.api { token }, provider, session, scope, currentToken = { token }) { unauthorizedCalls++ }
            .also { runCurrent() }

    @Test
    fun theStoreIsLoggedInWithARestoredSessionAndOutOnSignOut() = runTest {
        val session = FakeSessionRepository(signedIn = true)
        repo(session)
        assertEquals(listOf(USER_ID), provider.logIns, "app start with a restored session logs in")
        session.signOut()
        runCurrent()
        assertEquals(1, provider.logOuts, "sign-out (and a 401, which signs out) logs out")
    }

    @Test
    fun signingInLogsInBeforeAnyPurchaseUi() = runTest {
        val session = FakeSessionRepository(signedIn = false)
        repo(session)
        assertTrue(provider.logIns.isEmpty())
        session.signIn(ageConfirmed = true)
        runCurrent()
        assertEquals(listOf(USER_ID), provider.logIns)
    }

    @Test
    fun nothingReachesTheStoreWhileSignedOut() = runTest {
        val repo = repo(FakeSessionRepository(signedIn = false))
        assertEquals(emptyList(), repo.plans())
        assertEquals(PurchaseResult.Failed, repo.purchase("monthly"))
        assertEquals(PurchaseResult.Failed, repo.restore())
        assertTrue(provider.purchases.isEmpty() && provider.restores == 0 && server.requests.isEmpty())
    }

    @Test
    fun aStoreThatCannotLogInIsUnavailable() = runTest {
        provider.loggedInResult = false
        val repo = repo()
        assertEquals(emptyList(), repo.plans())
        assertEquals(PurchaseResult.StoreUnavailable, repo.purchase("monthly"))
        assertTrue(provider.purchases.isEmpty())
    }

    @Test
    fun plansComeFromTheStore() = runTest {
        assertEquals(testPlans, repo().plans())
    }

    @Test
    fun aPurchaseIsSuccessOnlyWhenOurServerShowsTheEntitlement() = runTest {
        val repo = repo()
        server.handler = { json(fixture("entitlement_refresh.json")) }
        assertEquals(PurchaseResult.Success, repo.purchase("yearly"))
        assertEquals(listOf("yearly"), provider.purchases)
        val refresh = server.requests.single()
        assertEquals(HttpMethod.Post, refresh.method)
        assertEquals("/v1/entitlement/refresh", refresh.url.encodedPath)
        assertEquals("Bearer v1.user.secret.tag", refresh.headers["Authorization"])

        server.handler = { json(inactive) }
        assertEquals(PurchaseResult.Pending, repo.purchase("monthly"), "the store said yes, our server not yet")
        server.handler = { error(503, "billing_unavailable") }
        assertEquals(PurchaseResult.Pending, repo.purchase("monthly"))
        server.handler = { error(429, "rate_limited") }
        assertEquals(PurchaseResult.Pending, repo.purchase("monthly"))
        server.offline = true
        assertEquals(PurchaseResult.Pending, repo.purchase("monthly"))
        assertEquals(0, unauthorizedCalls)
    }

    @Test
    fun aRefreshAnswering401EndsTheSession() = runTest {
        val repo = repo()
        server.handler = { error(401, "unauthenticated") }
        assertEquals(PurchaseResult.Failed, repo.purchase("monthly"))
        assertEquals(1, unauthorizedCalls)
    }

    @Test
    fun whatTheStoreRefusesIsNotRefreshed() = runTest {
        val repo = repo()
        for (result in listOf(PurchaseResult.Cancelled, PurchaseResult.Pending, PurchaseResult.StoreUnavailable, PurchaseResult.Failed)) {
            provider.purchaseResult = result
            provider.restoreResult = result
            assertEquals(result, repo.purchase("monthly"))
            assertEquals(result, repo.restore())
        }
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun restoreIsFollowedByARefresh() = runTest {
        val repo = repo()
        server.handler = { json(fixture("entitlement_refresh.json")) }
        assertEquals(PurchaseResult.Success, repo.restore())
        assertEquals(1, provider.restores)
        assertEquals("/v1/entitlement/refresh", server.requests.single().url.encodedPath)
        assertEquals(provider.manage, repo.manageUrl())
    }

    @Test
    fun withoutAKeyRevenueCatIsUnavailableAndNothingCrashes() = runTest {
        val store = RevenueCatBillingProvider(apiKey = "", manageFallback = "https://example.test")
        assertEquals(false, store.logIn(USER_ID))
        store.logOut()
        assertEquals(emptyList(), store.plans())
        assertEquals(PurchaseResult.StoreUnavailable, store.purchase("monthly"))
        assertEquals(PurchaseResult.StoreUnavailable, store.restore())
        assertNull(store.manageUrl())
    }

    @Test
    fun storeErrorsMapToResults() {
        val map = RevenueCatBillingProvider::resultOf
        assertEquals(PurchaseResult.Cancelled, map(PurchasesErrorCode.PurchaseCancelledError, false))
        assertEquals(PurchaseResult.Cancelled, map(PurchasesErrorCode.UnknownError, true))
        assertEquals(PurchaseResult.Pending, map(PurchasesErrorCode.PaymentPendingError, false))
        assertEquals(PurchaseResult.StoreUnavailable, map(PurchasesErrorCode.StoreProblemError, false))
        assertEquals(PurchaseResult.StoreUnavailable, map(PurchasesErrorCode.PurchaseNotAllowedError, false))
        assertEquals(PurchaseResult.Failed, map(PurchasesErrorCode.NetworkError, false))
    }

    @Test
    fun quotaCarriesTheEndOfTheSubscription() = runTest {
        val me = fixture("me.json").replace("\"active\": false, \"expiresAt\": null", "\"active\": true, \"expiresAt\": \"2026-12-25T10:00:00Z\"")
        server.handler = { json(me) }
        val consent = DefaultConsentRepository(server.api()) {}
        val unused = object : TextRecognizer {
            override suspend fun recognize(jpeg: ByteArray): Recognition = Recognition.Unreadable
        }
        val quota = DefaultImportRepository(server.api(), RecipeCache(tempDir()), consent, unused) {}.quota()
        assertEquals(Quota(false, 0, 30, entitled = true, entitledUntil = "2026-12-25T10:00:00Z"), quota)
    }
}
