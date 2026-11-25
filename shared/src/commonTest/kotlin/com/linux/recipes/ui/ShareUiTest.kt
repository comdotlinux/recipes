package com.linux.recipes.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import com.linux.recipes.UiTest
import com.linux.recipes.data.FakeServer
import com.linux.recipes.data.ReportReason
import com.linux.recipes.data.SessionState
import com.linux.recipes.data.ShareAccept
import com.linux.recipes.data.ShareCreate
import com.linux.recipes.data.ShareOpen
import com.linux.recipes.data.PdfDownload
import com.linux.recipes.data.error
import com.linux.recipes.ui.share.LocalFileActions
import com.linux.recipes.ui.share.SharingServices
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Sharing by link and PDF on the detail screen, and the preview of an opened link (AC25, AC33). */
@OptIn(ExperimentalTestApi::class)
class ShareUiTest : UiTest() {
    private val server = FakeServer()
    private val recipes = FakeRecipeRepository(listOf(potatoSalad))
    private val shares = FakeShareRepository(recipes)
    private val consent = FakeConsentRepository(given = false)
    private val files = FakeFileActions()
    private val sharing = SharingServices(shares, PdfDownload(server.api()), backup = null, consent = consent)

    private fun ComposeUiTest.start(session: FakeSessionRepository = FakeSessionRepository(signedIn = true)) {
        setScreen {
            CompositionLocalProvider(LocalFileActions provides files.actions) {
                com.linux.recipes.App(session, recipes, darkTheme = false)
            }
        }
    }

    private fun ComposeUiTest.startWithSharing(session: FakeSessionRepository = FakeSessionRepository(signedIn = true)) {
        setScreen {
            CompositionLocalProvider(LocalFileActions provides files.actions, com.linux.recipes.ui.share.LocalSharing provides sharing) {
                com.linux.recipes.App(session, recipes, darkTheme = false)
            }
        }
    }

    private fun ComposeUiTest.openShareSheet() {
        click("Kartoffelsalat")
        waitForText("Ingredients")
        onNodeWithContentDescription("Share with a link").performClick()
        waitForText("Share this recipe")
    }

    @Test
    fun withoutSharingServicesTheDetailHasNoShareOrPdf() = runComposeUiTest {
        start()
        click("Kartoffelsalat")
        waitForText("Ingredients")
        assertTrue(onAllNodesWithDescription("Share with a link") == 0)
        assertTrue(onAllNodesWithDescription("Save as PDF") == 0)
    }

    @Test
    fun createHandsTheLinkToTheShareSheetAndOffersCopy() = runComposeUiTest {
        startWithSharing()
        openShareSheet()
        click("Create link")
        waitForText("Works until 2026-12-25")
        assertEquals(listOf(testLink.url), files.sharedTexts)
        assertEquals(listOf(potatoSalad.id), shares.created)
        click("Copy link")
        waitForText("Link copied.")
        click("Share")
        assertEquals(listOf(testLink.url, testLink.url), files.sharedTexts)
    }

    @Test
    fun notSubscribedOpensThePaywall() = runComposeUiTest {
        shares.createResults = mutableListOf(ShareCreate.SubscriptionRequired)
        startWithSharing()
        openShareSheet()
        click("Create link")
        waitForText("Share recipes with a link")
        assertTrue(files.sharedTexts.isEmpty())
    }

    @Test
    fun subscribingOnThePaywallCreatesTheLink() = runComposeUiTest {
        shares.createResults = mutableListOf(ShareCreate.SubscriptionRequired, ShareCreate.Created(testLink))
        val store = FakeBillingProvider()
        setApp(FakeSessionRepository(signedIn = true), recipes, sharing = sharing, billing = FakeBillingRepository(store))
        openShareSheet()
        click("Create link")
        waitForText("Share recipes with a link")
        waitForText("Monthly")
        click("Monthly")
        onNode(androidx.compose.ui.test.hasText("Subscribe")).performScrollTo()
        click("Subscribe")
        waitForText("Works until 2026-12-25")
        assertEquals(listOf("monthly"), store.purchases)
        assertEquals(2, shares.created.size)
    }

    @Test
    fun consentMissingShowsTheAiNoticeThenCreates() = runComposeUiTest {
        shares.createResults = mutableListOf(ShareCreate.ConsentRequired, ShareCreate.Created(testLink))
        startWithSharing()
        openShareSheet()
        click("Create link")
        waitForText("Importing with AI.")
        click("Accept")
        waitForText("Works until 2026-12-25")
        assertEquals(1, consent.acceptCalls)
        assertEquals(2, shares.created.size)
        assertEquals(listOf(testLink.url), files.sharedTexts)
    }

    @Test
    fun consentOfflineSaysSo() = runComposeUiTest {
        shares.createResults = mutableListOf(ShareCreate.ConsentRequired)
        consent.online = false
        startWithSharing()
        openShareSheet()
        click("Create link")
        waitForText("Importing with AI.")
        click("Accept")
        waitForText("The server cannot be reached right now. Please check your connection and try again.")
    }

    @Test
    fun eachCreateRefusalHasItsMessage() {
        mapOf(
            ShareCreate.PictureRejected to "A picture of this recipe cannot be shared. Remove that picture from the recipe and try again.",
            ShareCreate.ShareLimit to "You have made as many links as you can today. Please try again tomorrow.",
            ShareCreate.ScreeningUnavailable to "The pictures cannot be checked right now. Please try again later.",
            ShareCreate.Offline to "The server cannot be reached right now. Please check your connection and try again.",
            ShareCreate.Rejected("not_found") to "The link could not be made.",
        ).forEach { (result, message) ->
            runComposeUiTest {
                shares.createResults = mutableListOf(result)
                startWithSharing()
                openShareSheet()
                click("Create link")
                waitForText(message)
            }
        }
        assertTrue(files.sharedTexts.isEmpty())
    }

    @Test
    fun revokeAsksFirst() = runComposeUiTest {
        shares.links.value = listOf(testLink)
        startWithSharing()
        openShareSheet()
        waitForText("Works until 2026-12-25")
        click("Revoke")
        waitForText("Revoke this link?")
        click("Cancel")
        waitForNoText("Revoke this link?")
        assertEquals(1, shares.links.value.size)
        shares.revokeResult = false
        click("Revoke")
        clickDialogConfirm("Revoke")
        waitForText("The link could not be revoked. Please try again.")
        shares.revokeResult = true
        click("Revoke")
        clickDialogConfirm("Revoke")
        waitForNoText("Works until 2026-12-25")
        assertEquals(emptyList(), shares.links.value)
    }

    @Test
    fun linksOfOtherRecipesAreNotListed() = runComposeUiTest {
        shares.links.value = listOf(testLink.copy(recipeId = shakshuka.id))
        startWithSharing()
        openShareSheet()
        waitForNoText("Active links")
    }

    @Test
    fun pdfSavesOrSharesTheDownloadedFile() = runComposeUiTest {
        val pdf = "%PDF-1.7 test".encodeToByteArray()
        server.handler = { respond(pdf, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/pdf")) }
        startWithSharing()
        click("Kartoffelsalat")
        waitForText("Ingredients")
        onNodeWithContentDescription("Save as PDF").performClick()
        waitForText("PDF ready")
        click("Save")
        waitForText("The PDF has been saved.")
        assertEquals("recipe.pdf", files.saved.single().first)
        assertContentEquals(pdf, files.saved.single().second)
        click("Close")
        onNodeWithContentDescription("Save as PDF").performClick()
        waitForText("PDF ready")
        click("Share")
        waitForNoText("PDF ready")
        waitUntil { files.sharedFiles.isNotEmpty() }
        assertContentEquals(pdf, files.sharedFiles.single().second)
        assertEquals("/v1/recipes/${potatoSalad.id}/pdf", server.requests.last().url.encodedPath)
    }

    @Test
    fun pdfRefusalsHaveTheirMessages() {
        mapOf(
            "pdf_limit" to "You have made as many PDFs as you can today. Please try again tomorrow.",
            "pdf_unavailable" to "PDFs cannot be made right now. Please try again later.",
            "not_found" to "The PDF could not be made.",
        ).forEach { (code, message) ->
            runComposeUiTest {
                server.handler = { error(if (code == "pdf_unavailable") 503 else if (code == "pdf_limit") 429 else 404, code) }
                startWithSharing()
                click("Kartoffelsalat")
                waitForText("Ingredients")
                onNodeWithContentDescription("Save as PDF").performClick()
                waitForText(message)
            }
        }
        runComposeUiTest {
            server.offline = true
            startWithSharing()
            click("Kartoffelsalat")
            waitForText("Ingredients")
            onNodeWithContentDescription("Save as PDF").performClick()
            waitForText("The server cannot be reached right now. Please check your connection and try again.")
        }
    }

    // ---------- the preview of an opened link ----------

    @Test
    fun anOpenedLinkShowsThePreviewAndAcceptOpensTheCopy() = runComposeUiTest {
        sharing.incoming.value = SHARE_TOKEN
        startWithSharing()
        waitForText("Apfelstrudel")
        waitForText("Pictures included: 2")
        waitForText("The link works until 2026-12-25")
        reveal("2. Fill, roll and bake.")
        reveal("Add to my recipes")
        click("Add to my recipes")
        waitUntil { onAllNodesWithDescription("Edit recipe") > 0 }
        assertEquals(listOf(SHARE_TOKEN), shares.accepted)
        assertEquals(null, sharing.incoming.value)
        assertEquals("Apfelstrudel", recipes.recipes.value.first().title)
    }

    @Test
    fun aLinkArrivingWhileSignedOutOpensAfterSignIn() = runComposeUiTest {
        val session = FakeSessionRepository(signedIn = false)
        sharing.incoming.value = SHARE_TOKEN
        startWithSharing(session)
        waitForText("I am 16 or older")
        assertEquals(SHARE_TOKEN, sharing.incoming.value)
        session.state.value = SessionState.SignedIn(USER_ID)
        waitForText("Shared recipe")
        waitForText("Apfelstrudel")
        assertEquals(null, sharing.incoming.value)
    }

    @Test
    fun openRefusalsHaveTheirMessagesAndNoAdd() {
        mapOf(
            ShareOpen.Gone to "This link no longer works. It may have expired or been turned off.",
            ShareOpen.NotFound to "This is not a valid recipe link.",
            ShareOpen.OwnShare to "This is your own recipe, so you already have it.",
        ).forEach { (result, message) ->
            runComposeUiTest {
                shares.openResult = result
                sharing.incoming.value = SHARE_TOKEN
                startWithSharing()
                waitForText(message)
                waitForNoText("Add to my recipes")
            }
        }
    }

    @Test
    fun offlineOpenOffersRetry() = runComposeUiTest {
        shares.openResult = ShareOpen.Offline
        sharing.incoming.value = SHARE_TOKEN
        startWithSharing()
        waitForText("The server cannot be reached right now. Please check your connection and try again.")
        shares.openResult = ShareOpen.Preview(testPreview)
        click("Try again")
        waitForText("Add to my recipes")
    }

    @Test
    fun eachAcceptRefusalHasItsMessage() {
        mapOf(
            ShareAccept.RecipeLimit to "You have reached the maximum number of recipes. Delete some recipes and try again.",
            ShareAccept.RecipeStorageCap to "Your recipes use all of the text storage. Delete some recipes and try again.",
            ShareAccept.StorageCap to "Your picture storage is full. Free some space and try again.",
            ShareAccept.Unreadable to "This recipe has no readable text and cannot be added.",
            ShareAccept.Gone to "This link no longer works. It may have expired or been turned off.",
            ShareAccept.OwnShare to "This is your own recipe, so you already have it.",
            ShareAccept.NotFound to "This is not a valid recipe link.",
            ShareAccept.Offline to "The server cannot be reached right now. Please check your connection and try again.",
            ShareAccept.Rejected("invalid_body") to "The recipe could not be added.",
        ).forEach { (result, message) ->
            runComposeUiTest {
                shares.acceptResult = result
                sharing.incoming.value = SHARE_TOKEN
                startWithSharing()
                waitForText("Add to my recipes")
                click("Add to my recipes")
                waitForText(message)
            }
        }
    }

    @Test
    fun reportFromThePreviewRevokesTheLink() = runComposeUiTest {
        sharing.incoming.value = SHARE_TOKEN
        startWithSharing()
        waitForText("Apfelstrudel")
        shares.reportResult = false
        reveal("Report")
        click("Report")
        waitForText("Report a problem")
        click("The result is offensive")
        click("Send")
        waitForText("The report could not be sent. Please try again.")
        shares.reportResult = true
        reveal("Report")
        click("Report")
        waitForText("Report a problem")
        click("Something else")
        click("Send")
        waitForText("Thank you. The link has been turned off.")
        waitForNoText("Add to my recipes")
        assertEquals(listOf(ReportReason.Offensive, ReportReason.Other), shares.reports.map { it.second })
        assertEquals(SHARE_TOKEN, shares.reports.last().first)
    }

    @Test
    fun backFromThePreviewReturnsToTheList() = runComposeUiTest {
        sharing.incoming.value = SHARE_TOKEN
        startWithSharing()
        waitForText("Apfelstrudel")
        onNodeWithContentDescription("Back").performClick()
        waitForText("Kartoffelsalat")
    }

    /** Scrolls a plain scrolling column (not a lazy list) until the node with [text] is on screen. */
    private fun ComposeUiTest.reveal(text: String) =
        onNode(androidx.compose.ui.test.hasText(text)).performScrollTo()

    private fun ComposeUiTest.onAllNodesWithDescription(label: String): Int =
        onAllNodes(androidx.compose.ui.test.hasContentDescription(label)).fetchSemanticsNodes().size
}
