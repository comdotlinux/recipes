package com.linux.recipes.ui

import com.linux.recipes.data.BillingRepository
import com.linux.recipes.data.Plan
import com.linux.recipes.data.PlanPeriod
import com.linux.recipes.data.PurchaseResult
import com.linux.recipes.platform.BillingProvider

/** Prices as the store formats them; the app shows them unchanged. */
val testPlans = listOf(Plan("monthly", PlanPeriod.Month, "0,49 €"), Plan("yearly", PlanPeriod.Year, "4,99 €"))

const val MANAGE_URL = "https://play.google.com/store/account/subscriptions"

/** A store that answers with the scripted results and records every call. */
class FakeBillingProvider : BillingProvider {
    var plans = testPlans
    var loggedInResult = true
    var purchaseResult: PurchaseResult = PurchaseResult.Success
    var restoreResult: PurchaseResult = PurchaseResult.Success
    var manage: String? = MANAGE_URL
    val logIns = mutableListOf<String>()
    var logOuts = 0
    val purchases = mutableListOf<String>()
    var restores = 0

    override suspend fun logIn(userId: String): Boolean {
        logIns += userId
        return loggedInResult
    }

    override suspend fun logOut() {
        logOuts++
    }

    override suspend fun plans() = plans

    override suspend fun purchase(planId: String): PurchaseResult {
        purchases += planId
        return purchaseResult
    }

    override suspend fun restore(): PurchaseResult {
        restores++
        return restoreResult
    }

    override fun manageUrl() = manage
}

/** The store's answers taken as the repository's, for screens that need no server. */
class FakeBillingRepository(private val store: FakeBillingProvider = FakeBillingProvider()) : BillingRepository {
    override suspend fun plans() = store.plans()
    override suspend fun purchase(planId: String) = store.purchase(planId)
    override suspend fun restore() = store.restore()
    override fun manageUrl() = store.manageUrl()
}
