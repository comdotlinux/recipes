package com.linux.recipes.android

import com.linux.recipes.data.Plan
import com.linux.recipes.data.PlanPeriod
import com.linux.recipes.data.PurchaseResult
import com.linux.recipes.platform.BillingProvider
import com.linux.recipes.platform.PLAN_MONTHLY
import com.linux.recipes.platform.PLAN_YEARLY
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Debug builds only (source set `debug`): a store for the emulator, which has none. It offers both plans at
 * fixed test prices, labelled as such, and a purchase turns on the signed-in user's subscription with the local
 * Worker's switch `POST /v1/testing/entitlement` (backend/README.md, "Try the subscription locally"). The release
 * APK must not contain this class; `:androidApp:verifyReleaseIsolation` checks it.
 */
class DebugBillingProvider(private val baseUrl: String, private val sessionToken: () -> String?) : BillingProvider {
    override suspend fun logIn(userId: String) = true

    override suspend fun logOut() = Unit

    override suspend fun plans() = listOf(
        Plan(PLAN_MONTHLY, PlanPeriod.Month, "0,49 € (test)"),
        Plan(PLAN_YEARLY, PlanPeriod.Year, "4,99 € (test)"),
    )

    override suspend fun purchase(planId: String) = subscribe()

    /** Nothing to restore locally: the server's switch is the subscription, so the refresh tells. */
    override suspend fun restore() = PurchaseResult.Success

    override fun manageUrl(): String? = null

    private suspend fun subscribe(): PurchaseResult = withContext(Dispatchers.IO) {
        val token = sessionToken() ?: return@withContext PurchaseResult.Failed
        try {
            val connection = URL("$baseUrl/v1/testing/entitlement").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write("""{"active":true}""".toByteArray()) }
            if (connection.responseCode == 200) PurchaseResult.Success else PurchaseResult.Failed
        } catch (_: IOException) {
            PurchaseResult.StoreUnavailable
        }
    }
}
