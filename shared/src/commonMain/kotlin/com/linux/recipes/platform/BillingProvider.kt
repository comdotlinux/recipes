package com.linux.recipes.platform

import com.linux.recipes.data.Plan
import com.linux.recipes.data.PlanPeriod
import com.linux.recipes.data.PurchaseResult
import com.revenuecat.purchases.kmp.Purchases
import com.revenuecat.purchases.kmp.configure
import com.revenuecat.purchases.kmp.ktx.awaitLogIn
import com.revenuecat.purchases.kmp.ktx.awaitLogOut
import com.revenuecat.purchases.kmp.ktx.awaitOfferings
import com.revenuecat.purchases.kmp.ktx.awaitPurchase
import com.revenuecat.purchases.kmp.ktx.awaitRestore
import com.revenuecat.purchases.kmp.models.CustomerInfo
import com.revenuecat.purchases.kmp.models.Package
import com.revenuecat.purchases.kmp.models.PurchasesErrorCode
import com.revenuecat.purchases.kmp.models.PurchasesException
import com.revenuecat.purchases.kmp.models.PurchasesTransactionException
import kotlin.coroutines.cancellation.CancellationException

/**
 * The thin seam over the store SDK. [PurchaseResult.Success] here means only that the store accepted it;
 * `DefaultBillingRepository` then asks our server. Implementations never throw except for cancellation.
 */
interface BillingProvider {
    /** True when the store SDK now runs as [userId]; idempotent. Purchases need it. */
    suspend fun logIn(userId: String): Boolean
    suspend fun logOut()
    /** Empty when the store is unavailable. */
    suspend fun plans(): List<Plan>
    suspend fun purchase(planId: String): PurchaseResult
    suspend fun restore(): PurchaseResult
    fun manageUrl(): String?
}

/** The two product ids of Recipes Pro in both stores. */
const val PLAN_MONTHLY = "monthly"
const val PLAN_YEARLY = "yearly"
const val ENTITLEMENT_ID = "recipes_pro"

/**
 * RevenueCat's Kotlin Multiplatform SDK; its API is common, so one class serves Android and iOS.
 * [apiKey] is the public SDK key of the platform's store (not a secret); empty means "store unavailable"
 * and the SDK is never configured. [manageFallback] is the store's subscription page, used when RevenueCat
 * reports no management URL.
 */
class RevenueCatBillingProvider(private val apiKey: String, private val manageFallback: String?) : BillingProvider {
    private var customerInfo: CustomerInfo? = null

    private val purchases: Purchases? by lazy {
        if (apiKey.isEmpty()) null else if (Purchases.isConfigured) Purchases.sharedInstance else Purchases.configure(apiKey)
    }

    override suspend fun logIn(userId: String): Boolean {
        val sdk = purchases ?: return false
        if (sdk.appUserID != userId) storeCall { customerInfo = sdk.awaitLogIn(userId).customerInfo }
        return sdk.appUserID == userId
    }

    override suspend fun logOut() {
        val sdk = purchases ?: return
        customerInfo = null
        if (!sdk.isAnonymous) storeCall { sdk.awaitLogOut() }
    }

    override suspend fun plans(): List<Plan> = packages().map { (id, pkg) -> pkg.toPlan(id) }

    override suspend fun purchase(planId: String): PurchaseResult {
        val sdk = purchases ?: return PurchaseResult.StoreUnavailable
        val pkg = packages()[planId] ?: return PurchaseResult.StoreUnavailable
        return try {
            customerInfo = sdk.awaitPurchase(pkg).customerInfo
            PurchaseResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: PurchasesTransactionException) {
            resultOf(e.code, e.userCancelled)
        } catch (e: PurchasesException) {
            resultOf(e.code, userCancelled = false)
        }
    }

    override suspend fun restore(): PurchaseResult {
        val sdk = purchases ?: return PurchaseResult.StoreUnavailable
        return try {
            val info = sdk.awaitRestore().also { customerInfo = it }
            if (info.entitlements.active.containsKey(ENTITLEMENT_ID)) PurchaseResult.Success else PurchaseResult.Failed
        } catch (e: CancellationException) {
            throw e
        } catch (e: PurchasesException) {
            resultOf(e.code, userCancelled = false)
        }
    }

    override fun manageUrl(): String? = customerInfo?.managementUrlString ?: manageFallback.takeIf { purchases != null }

    /** The current offering's monthly and annual packages under our plan ids. */
    private suspend fun packages(): Map<String, Package> {
        val sdk = purchases ?: return emptyMap()
        val offering = storeCall { sdk.awaitOfferings().current } ?: return emptyMap()
        return listOfNotNull(offering.monthly?.let { PLAN_MONTHLY to it }, offering.annual?.let { PLAN_YEARLY to it }).toMap()
    }

    private fun Package.toPlan(id: String) =
        Plan(id, if (id == PLAN_YEARLY) PlanPeriod.Year else PlanPeriod.Month, storeProduct.price.formatted)

    private suspend fun <T> storeCall(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: PurchasesException) {
        null
    }

    companion object {
        fun resultOf(code: PurchasesErrorCode, userCancelled: Boolean): PurchaseResult = when {
            userCancelled || code == PurchasesErrorCode.PurchaseCancelledError -> PurchaseResult.Cancelled
            code == PurchasesErrorCode.PaymentPendingError -> PurchaseResult.Pending
            code in UNAVAILABLE -> PurchaseResult.StoreUnavailable
            else -> PurchaseResult.Failed
        }

        private val UNAVAILABLE = setOf(
            PurchasesErrorCode.StoreProblemError,
            PurchasesErrorCode.PurchaseNotAllowedError,
            PurchasesErrorCode.InsufficientPermissionsError,
            PurchasesErrorCode.ProductNotAvailableForPurchaseError,
            PurchasesErrorCode.ConfigurationError,
            PurchasesErrorCode.InvalidCredentialsError,
        )
    }
}
