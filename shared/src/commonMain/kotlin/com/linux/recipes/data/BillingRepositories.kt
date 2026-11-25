package com.linux.recipes.data

enum class PlanPeriod { Month, Year }

/** A store product of Recipes Pro. [price] is formatted by the store for the user's country, for example "0,49 €". */
data class Plan(val id: String, val period: PlanPeriod, val price: String)

sealed interface PurchaseResult {
    /** The store accepted it and our server's refresh shows the entitlement active. */
    data object Success : PurchaseResult
    data object Cancelled : PurchaseResult
    /** The store has not confirmed the payment yet, or it did and our server does not know yet; the next `quota()` settles it. */
    data object Pending : PurchaseResult
    data object StoreUnavailable : PurchaseResult
    data object Failed : PurchaseResult
}

interface BillingRepository {
    /** The plans of the current offering; empty when the store is unavailable. */
    suspend fun plans(): List<Plan>
    suspend fun purchase(planId: String): PurchaseResult
    suspend fun restore(): PurchaseResult
    /** The store's page to manage or cancel the subscription, or null when there is none. */
    fun manageUrl(): String?
}
