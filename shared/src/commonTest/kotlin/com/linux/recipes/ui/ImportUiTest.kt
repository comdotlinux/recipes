package com.linux.recipes.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import com.linux.recipes.UiTest
import com.linux.recipes.data.ConsentState
import com.linux.recipes.data.ImportJobState
import com.linux.recipes.data.ImportOutcome
import com.linux.recipes.data.ImportSource
import com.linux.recipes.data.ImportStart
import com.linux.recipes.data.ReportReason
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.linux.recipes.data.LocalRead
import com.linux.recipes.ui.imports.ImportViewModel
import com.linux.recipes.ui.list.RecipeListScreen
import com.linux.recipes.ui.list.RecipeListViewModel
import com.linux.recipes.ui.theme.RecipesTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val LOWERCASE_UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
private const val LINK = "https://example.com/pasta-e-ceci"
private const val PHOTOS_FREE = "Photos are read on your phone, free and unlimited."
private const val FIRST_FREE = "One AI import is free: a link, or AI help with a photo. After that, AI imports come with Recipes Pro."
private const val UNREADABLE = "Your phone could not read this picture."
private const val RUNNING = "Reading your recipe… this can take up to a minute. You can keep using the app."

@OptIn(ExperimentalTestApi::class)
class ImportUiTest : UiTest() {
    private class Setup(val imports: FakeImportRepository, val consent: FakeConsentRepository, val recipes: FakeRecipeRepository)

    private fun ComposeUiTest.app(
        imports: FakeImportRepository = FakeImportRepository(),
        consent: FakeConsentRepository = FakeConsentRepository(),
        recipes: FakeRecipeRepository = FakeRecipeRepository(listOf(shakshuka)),
    ): Setup {
        setApp(FakeSessionRepository(signedIn = true), recipes, imports = imports, consent = consent)
        return Setup(imports, consent, recipes)
    }

    /** Opens the sheet from the list's Import button, pastes [url] and taps the sheet's Import button. */
    private fun ComposeUiTest.importLink(url: String = LINK) {
        waitForText("Import")
        click("Import")
        waitForText("Link to a recipe")
        onNodeWithText("Link to a recipe").performTextReplacement(url)
        // The list's Import button stays behind the sheet; the sheet's is the last one.
        onAllNodesWithText("Import").onLast().performClick()
    }

    @Test
    fun importButtonOpensTheSheetWithItsThreeSources() = runComposeUiTest {
        app()
        waitForText("New recipe")
        click("Import")
        waitForText(FIRST_FREE)
        onNodeWithText(PHOTOS_FREE).assertIsDisplayed()
        onNodeWithText("Paste a link to a recipe page, or photograph a recipe from a book or card.").assertIsDisplayed()
        onNodeWithText("Take photo").assertIsDisplayed()
        onNodeWithText("Choose from gallery").assertIsDisplayed()
        onAllNodesWithText("Import").onLast().assertIsNotEnabled()
    }

    @Test
    fun theSheetDoesNotPromiseAFreeImportOnceItIsUsed() = runComposeUiTest {
        app(FakeImportRepository().apply { quotaResult = quotaResult!!.copy(freeImportUsed = true) })
        waitForText("New recipe")
        click("Import")
        waitForText("Choose from gallery")
        onNodeWithText(PHOTOS_FREE).assertIsDisplayed()
        assertTrue(onAllNodesWithText(FIRST_FREE).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun emptyListOffersAnImportToo() = runComposeUiTest {
        app(recipes = FakeRecipeRepository())
        waitForText("Your recipe box is empty")
        click("Import a recipe")
        waitForText("Paste a link to a recipe page, or photograph a recipe from a book or card.")
    }

    /** The camera and gallery pickers are platform UI; their results are fed to the view model directly. */
    @Test
    fun aPhotoIsReadOnThePhoneAndAPictureThatCannotBeUsedSaysSo() = runComposeUiTest {
        val imports = FakeImportRepository()
        val vm = ImportViewModel(imports, FakeConsentRepository())
        var reviews = 0
        setContent {
            RecipesTheme(darkTheme = false) {
                RecipeListScreen(remember { RecipeListViewModel(FakeRecipeRepository(listOf(shakshuka))) }, {}, {}, {}, imports = vm, onReviewLocal = { reviews++ })
            }
        }
        waitForText("New recipe")
        runOnIdle { vm.pictureFailed() }
        waitForText("That picture could not be used. Try another one.")
        runOnIdle { vm.importPhoto(byteArrayOf(1, 2, 3)) }
        waitUntil { vm.localDraft != null }
        waitForIdle()
        assertEquals(1, reviews)
        assertTrue(imports.reads.single().contentEquals(byteArrayOf(1, 2, 3)))
        assertTrue(imports.starts.isEmpty(), "reading a photo sends nothing")
        assertEquals(LOCAL_TEXT, vm.localDraft?.text)
    }

    /**
     * The whole app, with a handle on the list's [ImportViewModel] so a test can hand it a picture as the
     * camera or gallery would.
     */
    private fun ComposeUiTest.appWithPhotos(
        imports: FakeImportRepository = FakeImportRepository(),
        consent: FakeConsentRepository = FakeConsentRepository(),
        recipes: FakeRecipeRepository = FakeRecipeRepository(listOf(shakshuka)),
    ): Pair<Setup, () -> ImportViewModel> {
        lateinit var nav: NavHostController
        setContent {
            RecipesTheme(darkTheme = false) {
                nav = rememberNavController()
                RecipesNavHost(FakeSessionRepository(signedIn = true), recipes, imports = imports, consent = consent, nav = nav)
            }
        }
        waitForText("New recipe")
        val vm = {
            // The list created it; the factory only runs if it did not.
            ViewModelProvider.create(nav.getBackStackEntry<ListRoute>(), viewModelFactory { initializer { error("no import view model on the list") } })[ImportViewModel::class]
        }
        return Setup(imports, consent, recipes) to vm
    }

    private val photo = byteArrayOf(-1, -40, -1, 9)

    @Test
    fun aPhotoReadOnThePhoneOpensTheReviewAndSavesWithoutConsentOrQuota() = runComposeUiTest {
        val (s, vm) = appWithPhotos(consent = FakeConsentRepository(given = false))
        runOnIdle { vm().importPhoto(photo) }
        waitForText("Review import")
        onNodeWithText("Pfannkuchen").assertIsDisplayed()
        onNodeWithText("Tidy up with AI").assertIsDisplayed()
        onNodeWithText("This photo was read on your phone. Nothing was sent.").assertIsDisplayed()
        assertTrue(onAllNodesWithText("Report a problem with this result").fetchSemanticsNodes().isEmpty(), "nothing was sent, so nothing to report")

        click("Save")
        waitUntil { s.recipes.saved.isNotEmpty() }
        val (saved, expectedRev, _) = s.recipes.saved.single()
        assertEquals(localDraft, saved)
        assertNull(expectedRev, "a reviewed photo is created, never updated")
        waitForNoText("Review import")
        assertTrue(s.imports.reads.single().contentEquals(photo))
        assertTrue(s.imports.starts.isEmpty())
        assertEquals(0, s.consent.acceptCalls)
        assertEquals(0, s.imports.quotaCalls)
        assertTrue(onAllNodesWithText("Importing with AI.").fetchSemanticsNodes().isEmpty())
        assertNull(vm().localDraft)
    }

    @Test
    fun closingTheLocalReviewAsksFirstAndStaysOnTheList() = runComposeUiTest {
        val (s, vm) = appWithPhotos()
        runOnIdle { vm().importPhoto(photo) }
        waitForText("Review import")
        onNodeWithContentDescription("Close").performClick()
        waitForText("Discard this import?")
        clickDialogConfirm("Discard")
        waitForText("New recipe")
        waitForIdle()
        assertTrue(onAllNodesWithText("Review import").fetchSemanticsNodes().isEmpty(), "the editor does not open again")
        assertNull(vm().localDraft)
        assertTrue(s.recipes.saved.isEmpty())
    }

    @Test
    fun tidyUpAsksFirstThenGoesThroughTheAiNotice() = runComposeUiTest {
        val (s, vm) = appWithPhotos(consent = FakeConsentRepository(given = false))
        runOnIdle { vm().importPhoto(photo) }
        waitForText("Review import")
        click("Tidy up with AI")
        waitForText("Tidy up with AI?")
        onNodeWithText("AI makes a new draft from the text your phone read. This draft and your changes to it are not kept.").assertIsDisplayed()
        click("Keep editing")
        waitForNoText("Tidy up with AI?")
        onNodeWithText("Review import").assertIsDisplayed()
        assertEquals(LOCAL_TEXT, vm().localDraft?.text, "the draft is kept until the user confirms")

        click("Tidy up with AI")
        clickDialogConfirm("Tidy up")
        waitForText("Importing with AI.")
        assertTrue(s.imports.starts.isEmpty(), "nothing is sent before the notice is accepted")
        click("Accept")
        waitForText(RUNNING)
        assertEquals(ImportSource.Text(LOCAL_TEXT), s.imports.starts.single().first)
        assertNull(vm().localDraft)
    }

    @Test
    fun tidyUpWithoutImportsLeftShowsThePaywall() = runComposeUiTest {
        val (s, vm) = appWithPhotos(FakeImportRepository().apply { startResult = ImportStart.QuotaExhausted })
        runOnIdle { vm().importPhoto(photo) }
        waitForText("Review import")
        click("Tidy up with AI")
        clickDialogConfirm("Tidy up")
        waitForText("You have used your free import.")
        assertEquals(ImportSource.Text(LOCAL_TEXT), s.imports.starts.single().first)
    }

    private fun ComposeUiTest.unreadable(consent: FakeConsentRepository = FakeConsentRepository()): Setup {
        val (s, vm) = appWithPhotos(FakeImportRepository().apply { readResult = LocalRead.Unreadable }, consent)
        runOnIdle { vm().importPhoto(photo) }
        waitForText(UNREADABLE)
        onNodeWithText("AI can try to read it, for example handwriting. The photo is then sent to our server.").assertIsDisplayed()
        assertTrue(s.imports.starts.isEmpty())
        return s
    }

    @Test
    fun anUnreadablePhotoCanBeReadWithAiAfterTheNotice() = runComposeUiTest {
        val s = unreadable(FakeConsentRepository(given = false))
        click("Read it with AI")
        waitForText("Importing with AI.")
        click("Accept")
        waitForText(RUNNING)
        val sent = s.imports.starts.single().first
        assertTrue(sent is ImportSource.Photo && sent.jpeg.contentEquals(photo))
        assertTrue(onAllNodesWithText(UNREADABLE).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun anUnreadablePhotoCanBeTypedIn() = runComposeUiTest {
        val s = unreadable()
        click("Type it in instead")
        waitForText("Basics")
        assertTrue(s.imports.starts.isEmpty())
    }

    @Test
    fun theUnreadableSheetCloses() = runComposeUiTest {
        val s = unreadable()
        click("Close")
        waitForNoText(UNREADABLE)
        onNodeWithText("New recipe").assertIsDisplayed()
        assertTrue(s.imports.starts.isEmpty())
    }


    @Test
    fun withoutImportRepositoriesNoImportEntryIsShown() = runComposeUiTest {
        setApp(FakeSessionRepository(signedIn = true), FakeRecipeRepository(listOf(shakshuka)))
        waitForText("New recipe")
        assertTrue(onAllNodesWithText("Import").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun linkImportRunsThenReviewAndSaveCreatesTheRecipeAndAcknowledges() = runComposeUiTest {
        val s = app()
        importLink()
        waitForText(RUNNING)
        val (source, jobId) = s.imports.starts.single()
        assertEquals(ImportSource.Link(LINK), source)
        assertTrue(LOWERCASE_UUID.matches(jobId), "job id is a lowercase UUID: $jobId")
        assertTrue(s.imports.resumeCalls >= 1, "the started job is followed")

        s.imports.finish(ImportOutcome.Done(importedDraft))
        waitForText("Your recipe is ready")
        click("Review")
        waitForText("Review import")
        onNodeWithText("Pasta e ceci").assertIsDisplayed()
        onNodeWithText("Check the recipe before you save it. Nothing is in your recipes until you save.").assertIsDisplayed()
        assertTrue(s.imports.acknowledged.isEmpty(), "the draft stays until it is saved or discarded")

        click("Save")
        waitForText("Chickpeas and small pasta in one pot.")
        val (saved, expectedRev, _) = s.recipes.saved.single()
        assertEquals(importedDraft.id, saved.id)
        assertEquals("url", saved.sourceType)
        assertEquals(LINK, saved.sourceUrl)
        assertNull(expectedRev, "a reviewed import is created, never updated")
        assertEquals(listOf(jobId), s.imports.acknowledged)
    }

    @Test
    fun closingTheReviewAsksFirstThenAcknowledges() = runComposeUiTest {
        val s = app(FakeImportRepository(ImportJobState("job-1", "url", ImportOutcome.Done(importedDraft), offline = false)))
        waitForText("Your recipe is ready")
        click("Review")
        waitForText("Review import")
        onNodeWithContentDescription("Close").performClick()
        waitForText("Discard this import?")
        clickDialogConfirm("Discard")
        waitForText("New recipe")
        assertEquals(listOf("job-1"), s.imports.acknowledged)
        assertTrue(s.recipes.saved.isEmpty())
        assertTrue(onAllNodesWithText("Your recipe is ready").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun reportFromTheReviewSendsReasonAndNoteThenConfirms() = runComposeUiTest {
        val s = app(FakeImportRepository(ImportJobState("job-2", "photo", ImportOutcome.Done(importedDraft), offline = false)))
        waitForText("Your recipe is ready")
        click("Review")
        waitForText("Report a problem with this result")
        click("Report a problem with this result")
        waitForText("Report a problem")
        onNodeWithText("The recipe is wrong or incomplete").assertIsDisplayed()
        onNodeWithText("Something else").assertIsDisplayed()
        click("The result is offensive")
        onNodeWithText("Note (optional)").performTextReplacement("  Not a recipe at all  ")
        click("Send")
        waitForText("Thank you. We will look at this result.")
        assertEquals(listOf(Triple("job-2", ReportReason.Offensive, "Not a recipe at all")), s.imports.reports)
    }

    @Test
    fun aFailedReportSaysSoAndCanBeSentAgain() = runComposeUiTest {
        val imports = FakeImportRepository(ImportJobState("job-3", "url", ImportOutcome.Done(importedDraft), offline = false))
        imports.reportResult = false
        app(imports)
        waitForText("Your recipe is ready")
        click("Review")
        waitForText("Report a problem with this result")
        click("Report a problem with this result")
        click("Something else")
        click("Send")
        waitForText("The report could not be sent. Please try again.")
        onNodeWithText("Report a problem with this result").assertIsDisplayed()
    }

    @Test
    fun aiNoticeComesFirstAndNotNowSendsNothing() = runComposeUiTest {
        val s = app(consent = FakeConsentRepository(given = false))
        importLink()
        waitForText("Importing with AI.")
        onNodeWithText("Cloudflare's AI models turn it into a recipe draft. We delete what you sent as soon as the import ends and keep the draft for 7 days. Cloudflare does not use it to train AI models.")
            .assertIsDisplayed()
        click("Not now")
        waitForNoText("Importing with AI.")
        assertTrue(s.imports.starts.isEmpty(), "nothing is sent without consent")
        assertEquals(0, s.consent.acceptCalls)
    }

    @Test
    fun acceptingTheNoticeGivesConsentThenStarts() = runComposeUiTest {
        val s = app(consent = FakeConsentRepository(given = false))
        importLink()
        waitForText("Importing with AI.")
        click("Accept")
        waitForText(RUNNING)
        assertEquals(1, s.consent.acceptCalls)
        assertEquals(ConsentState.Given(1), s.consent.state.value)
        assertEquals(1, s.imports.starts.size)
    }

    @Test
    fun aNoticeOfAnOlderVersionIsShownAgain() = runComposeUiTest {
        val consent = FakeConsentRepository().apply { state.value = ConsentState.Given(0) }
        val s = app(consent = consent)
        importLink()
        waitForText("Importing with AI.")
        assertTrue(s.imports.starts.isEmpty())
    }

    @Test
    fun consentThatCannotBeSavedOfflineSendsNothing() = runComposeUiTest {
        val consent = FakeConsentRepository(given = false).apply { online = false }
        val s = app(consent = consent)
        importLink()
        click("Accept")
        waitForText("Connect to the internet to import.")
        assertTrue(s.imports.starts.isEmpty())
    }

    @Test
    fun serverAskingForConsentShowsTheNotice() = runComposeUiTest {
        val s = app(FakeImportRepository().apply { startResult = ImportStart.ConsentRequired })
        importLink()
        waitForText("Importing with AI.")
        assertEquals(1, s.imports.starts.size)
    }

    @Test
    fun offlineAtStartRetriesWithTheSameIdAndANewAttemptGetsANewId() = runComposeUiTest {
        val s = app(FakeImportRepository().apply { startResult = ImportStart.Offline })
        importLink()
        waitForText("Connect to the internet to import.")
        s.imports.startResult = ImportStart.Started("")
        click("Try again")
        waitForText(RUNNING)
        val (first, second) = s.imports.starts.map { it.second }
        assertEquals(first, second, "a retry after Offline reuses the Idempotency-Key")

        s.imports.finish(ImportOutcome.NoRecipe)
        waitForText("No recipe found in this link or photo.")
        click("Dismiss")
        waitForNoText("No recipe found in this link or photo.")
        importLink("https://example.com/other")
        waitForText(RUNNING)
        assertNotEquals(first, s.imports.starts.last().second, "a new attempt after an outcome gets a new id")
        assertTrue(LOWERCASE_UUID.matches(s.imports.starts.last().second))
    }

    @Test
    fun attemptLimitSaysTryTomorrowAndOffersTyping() = runComposeUiTest {
        app(FakeImportRepository().apply { startResult = ImportStart.AttemptLimit })
        importLink()
        waitForText("Too many imports today. Try again tomorrow.")
        click("Type it in instead")
        waitForText("Basics")
    }

    @Test
    fun anInvalidLinkHasItsOwnMessage() = runComposeUiTest {
        app(FakeImportRepository().apply { startResult = ImportStart.Rejected("invalid_url") })
        importLink("not a link")
        waitForText("This link cannot be imported.")
    }

    @Test
    fun otherRejectionsShowOneGenericTextAndNeverTheCode() = runComposeUiTest {
        app(FakeImportRepository().apply { startResult = ImportStart.Rejected("text_too_large") })
        importLink()
        waitForText("This could not be imported. Try another link or photo.")
        assertTrue(onAllNodesWithText("text_too_large", substring = true).fetchSemanticsNodes().isEmpty())
        click("Dismiss")
        waitForNoText("This could not be imported. Try another link or photo.")
    }

    @Test
    fun alreadyRunningShowsTheRunningCard() = runComposeUiTest {
        val s = app(FakeImportRepository().apply { startResult = ImportStart.AlreadyRunning })
        importLink()
        waitForText(RUNNING)
        assertEquals("running-job", s.imports.activeJob.value?.jobId)
    }

    @Test
    fun quotaExhaustedOpensThePaywallWhoseTypeInOpensTheEditor() = runComposeUiTest {
        app(FakeImportRepository().apply { startResult = ImportStart.QuotaExhausted })
        importLink()
        waitForText("You have used your free import.")
        onNodeWithText("More AI imports come with Recipes Pro. Typing recipes in stays free and unlimited.").assertIsDisplayed()
        click("Type it in instead")
        waitForText("Basics")
        assertTrue(onAllNodesWithText("You have used your free import.").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun thePaywallCloses() = runComposeUiTest {
        app(FakeImportRepository().apply { startResult = ImportStart.QuotaExhausted })
        importLink()
        waitForText("You have used your free import.")
        clickDialogConfirm("Close")
        waitForNoText("You have used your free import.")
        onNodeWithText("New recipe").assertIsDisplayed()
    }

    private fun outcomeShows(outcome: ImportOutcome, message: String) = runComposeUiTest {
        val s = app(FakeImportRepository(ImportJobState("job-x", "url", outcome, offline = false)))
        waitForText(message)
        click("Dismiss")
        waitForNoText(message)
        assertEquals(listOf("job-x"), s.imports.acknowledged)
    }

    @Test
    fun guardBlockedHasItsOwnMessage() = outcomeShows(ImportOutcome.GuardBlocked, "This content cannot be imported.")

    @Test
    fun noRecipeHasItsOwnMessage() = outcomeShows(ImportOutcome.NoRecipe, "No recipe found in this link or photo.")

    @Test
    fun invalidHasItsOwnMessage() = outcomeShows(ImportOutcome.Invalid, "The result was incomplete. Try another source.")

    @Test
    fun failedSaysTheFreeImportWasNotUsedWhileItIsStillAvailable() = runComposeUiTest {
        app(FakeImportRepository(ImportJobState("job-f", "url", ImportOutcome.Failed, offline = false)))
        waitForText("The import did not finish. Please try again.")
        waitForText("Your free import was not used.")
    }

    @Test
    fun failedAfterTheFreeImportSaysNothingAboutIt() = runComposeUiTest {
        val imports = FakeImportRepository(ImportJobState("job-f", "url", ImportOutcome.Failed, offline = false))
        imports.quotaResult = imports.quotaResult!!.copy(freeImportUsed = true)
        app(imports)
        waitForText("The import did not finish. Please try again.")
        waitForIdle()
        assertTrue(onAllNodesWithText("Your free import was not used.").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun typeItInFromAFailureAcknowledgesAndOpensTheEditor() = runComposeUiTest {
        val s = app(FakeImportRepository(ImportJobState("job-g", "url", ImportOutcome.GuardBlocked, offline = false)))
        waitForText("This content cannot be imported.")
        click("Type it in instead")
        waitForText("Basics")
        assertEquals(listOf("job-g"), s.imports.acknowledged)
    }

    @Test
    fun offlineWhilePollingSaysTheImportContinues() = runComposeUiTest {
        app(FakeImportRepository(ImportJobState("job-o", "url", outcome = null, offline = true)))
        waitForText("You are offline. The import continues when you are back.")
    }

    @Test
    fun anOpenJobFromBeforeARestartIsFollowedWhenTheListAppears() = runComposeUiTest {
        val s = app(FakeImportRepository(ImportJobState("job-r", "photo", outcome = null, offline = false)))
        waitForText(RUNNING)
        waitUntil { s.imports.resumeCalls == 1 }
    }

    @Test
    fun noJobMeansNoPollingAndNoCard() = runComposeUiTest {
        val s = app()
        waitForText("New recipe")
        waitForIdle()
        assertEquals(0, s.imports.resumeCalls)
        assertTrue(onAllNodesWithText(RUNNING).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun settingsWithdrawsConsentAfterConfirming() = runComposeUiTest {
        val s = app()
        waitForText("New recipe")
        onNodeWithContentDescription("Settings").performClick()
        waitForText("AI imports")
        onNodeWithText("You allowed imports with AI.").assertIsDisplayed()
        click("Withdraw consent")
        waitForText("Withdraw consent for AI imports?")
        clickDialogConfirm("Withdraw")
        waitForText("Recipes asks for your consent before your first import with AI.")
        assertEquals(ConsentState.NotGiven, s.consent.state.value)
        assertTrue(onAllNodesWithText("Withdraw consent").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun withdrawingOfflineSaysItDidNotWork() = runComposeUiTest {
        val s = app(consent = FakeConsentRepository().apply { online = false })
        waitForText("New recipe")
        onNodeWithContentDescription("Settings").performClick()
        click("Withdraw consent")
        clickDialogConfirm("Withdraw")
        waitForText("Consent could not be withdrawn. Check your connection and try again.")
        assertEquals(ConsentState.Given(1), s.consent.state.value)
    }

    @Test
    fun cancellingTheWithdrawDialogKeepsConsent() = runComposeUiTest {
        val s = app()
        waitForText("New recipe")
        onNodeWithContentDescription("Settings").performClick()
        click("Withdraw consent")
        click("Cancel")
        waitForNoText("Withdraw consent for AI imports?")
        assertEquals(ConsentState.Given(1), s.consent.state.value)
    }
}
