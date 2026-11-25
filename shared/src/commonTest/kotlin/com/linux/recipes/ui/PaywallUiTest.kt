package com.linux.recipes.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import com.linux.recipes.App
import com.linux.recipes.AppConfig
import com.linux.recipes.UiTest
import com.linux.recipes.data.DefaultBillingRepository
import com.linux.recipes.data.FakeServer
import com.linux.recipes.data.ImportSource
import com.linux.recipes.data.ImportStart
import com.linux.recipes.data.PurchaseResult
import com.linux.recipes.data.fixture
import com.linux.recipes.data.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

private const val LINK = "https://example.com/pasta-e-ceci"
private const val PAYWALL = "You have used your free import."
private const val RENEWAL =
    "The subscription renews automatically at the same price until you cancel it. You can cancel any time in the subscription " +
        "settings of Google Play or the App Store. Recipes Pro then stays active until the end of the period you have paid for."

/** AC21: the paywall after the free import, with the real billing repository over a fake store and server. */
@OptIn(ExperimentalTestApi::class)
class PaywallUiTest : UiTest() {
    private val store = FakeBillingProvider()
    private val server = FakeServer().apply { handler = { json(fixture("entitlement_refresh.json")) } }
    private val uri = RecordingUriHandler()
    private val imports = FakeImportRepository().apply {
        quotaResult = quotaResult!!.copy(freeImportUsed = true)
        startResult = ImportStart.QuotaExhausted
    }

    private fun ComposeUiTest.app() {
        val session = FakeSessionRepository(signedIn = true)
        val billing = DefaultBillingRepository(server.api(), store, session, CoroutineScope(Dispatchers.Unconfined)) {}
        setContent {
            CompositionLocalProvider(LocalUriHandler provides uri) {
                App(session, FakeRecipeRepository(listOf(shakshuka)), false, null, imports, FakeConsentRepository(), billing)
            }
        }
    }

    /** Scrolls the sheet or screen (a plain scrolling column) until [text] is visible. */
    private fun ComposeUiTest.show(text: String) {
        onNodeWithText(text).performScrollTo()
    }

    /** The free import is used: the next import answers 402 and opens the paywall. */
    private fun ComposeUiTest.openPaywall() {
        app()
        waitForText("Import")
        click("Import")
        waitForText("Link to a recipe")
        onNodeWithText("Link to a recipe").performTextReplacement(LINK)
        onAllNodesWithText("Import").onLast().performClick()
        waitForText(PAYWALL)
    }

    @Test
    fun theUsedFreeImportLeadsToThePaywallAndSubscribingContinuesTheImport() = runComposeUiTest {
        openPaywall()
        waitForText("0,49 € a month")
        for (text in listOf("Recipes Pro", "Monthly", "Yearly", "4,99 € a year", "30 AI imports a month, from links and photos", "Share recipes with a link")) {
            show(text)
            onNodeWithText(text).assertIsDisplayed()
        }
        show(RENEWAL)
        onNodeWithText(RENEWAL).assertIsDisplayed()
        show("Terms of use")
        click("Terms of use")
        show("Privacy policy")
        click("Privacy policy")
        assertEquals(listOf(AppConfig.TERMS_URL, AppConfig.PRIVACY_URL), uri.opened)
        assertEquals("https://recipe.munich-tech.de/terms", AppConfig.TERMS_URL)

        show("Yearly")
        click("Yearly")
        onNodeWithText("Yearly").assertIsSelected()
        imports.startResult = ImportStart.Started("")
        show("Subscribe")
        click("Subscribe")
        waitForNoText(PAYWALL)
        assertEquals(listOf("yearly"), store.purchases)
        assertEquals(listOf("/v1/entitlement/refresh"), server.paths())
        assertEquals(listOf(ImportSource.Link(LINK), ImportSource.Link(LINK)), imports.starts.map { it.first })
        assertTrue(imports.starts[0].second != imports.starts[1].second, "the retry is a new job, the first was refused")
    }

    private fun purchaseShows(result: PurchaseResult, message: String) = runComposeUiTest {
        store.purchaseResult = result
        openPaywall()
        waitForText("0,49 € a month")
        show("Subscribe")
        click("Subscribe")
        waitForText(message)
        onNodeWithText(PAYWALL).assertIsDisplayed()
        assertEquals(1, imports.starts.size, "nothing is started again")
    }

    @Test
    fun cancelling() = purchaseShows(PurchaseResult.Cancelled, "The purchase was cancelled. Nothing was charged.")

    @Test
    fun aPendingPayment() = purchaseShows(PurchaseResult.Pending, "Your payment is not confirmed yet. Recipes Pro starts as soon as it is.")

    @Test
    fun aFailedPurchase() = purchaseShows(PurchaseResult.Failed, "The purchase did not go through. Please try again.")

    @Test
    fun theStoreSaidYesButOurServerNotYet() {
        server.handler = { json(fixture("entitlement_refresh.json").replace("\"active\": true", "\"active\": false")) }
        purchaseShows(PurchaseResult.Success, "Your payment is not confirmed yet. Recipes Pro starts as soon as it is.")
    }

    @Test
    fun withoutAStoreThereIsNothingToBuy() = runComposeUiTest {
        store.plans = emptyList()
        openPaywall()
        waitForText("The store is not available right now. Please try again later.")
        show("Subscribe")
        onNodeWithText("Subscribe").assertIsNotEnabled()
        show("Type it in instead")
        click("Type it in instead")
        waitForText("Basics")
    }

    @Test
    fun restoringUnlocksTheImport() = runComposeUiTest {
        openPaywall()
        imports.startResult = ImportStart.Started("")
        show("Restore purchases")
        click("Restore purchases")
        waitForNoText(PAYWALL)
        assertEquals(1, store.restores)
        assertEquals(2, imports.starts.size)
    }

    @Test
    fun restoringWithNothingToRestore() = runComposeUiTest {
        store.restoreResult = PurchaseResult.Failed
        openPaywall()
        show("Restore purchases")
        click("Restore purchases")
        waitForText("No subscription was found to restore.")
        assertTrue(server.requests.isEmpty())
    }

    private fun ComposeUiTest.openSettings() {
        app()
        waitForText("New recipe")
        onNodeWithContentDescription("Settings").performClick()
        waitForText("Recipes Pro")
    }

    @Test
    fun settingsShowsTheFreePlanAndOpensThePaywall() = runComposeUiTest {
        openSettings()
        waitForText("Free plan")
        click("Free plan")
        waitForText("0,49 € a month")
        assertTrue(onAllNodesWithText(PAYWALL).fetchSemanticsNodes().isEmpty(), "no import headline outside an import")
        assertTrue(onAllNodesWithText("Type it in instead").fetchSemanticsNodes().isEmpty())
        imports.quotaResult = imports.quotaResult!!.copy(entitled = true, entitledUntil = "2026-12-25T10:00:00Z")
        show("Subscribe")
        click("Subscribe")
        waitForText("Active until 2026-12-25")
    }

    @Test
    fun settingsShowsTheSubscriptionAndOpensTheStoresPage() = runComposeUiTest {
        imports.quotaResult = imports.quotaResult!!.copy(entitled = true, entitledUntil = "2026-12-25T10:00:00Z")
        openSettings()
        waitForText("Active until 2026-12-25")
        onNodeWithText("Manage subscription").assertIsDisplayed()
        click("Active until 2026-12-25")
        assertEquals(listOf(MANAGE_URL), uri.opened)
    }

    @Test
    fun settingsRestores() = runComposeUiTest {
        store.restoreResult = PurchaseResult.Cancelled
        openSettings()
        show("Restore purchases")
        click("Restore purchases")
        waitForText("The purchase was cancelled. Nothing was charged.")
        store.restoreResult = PurchaseResult.Success
        click("Restore purchases")
        waitForText("Recipes Pro is active.")
    }
}
