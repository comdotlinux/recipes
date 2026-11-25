package com.linux.recipes.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import com.linux.recipes.UiTest
import com.linux.recipes.data.ImportJobState
import com.linux.recipes.data.ImportOutcome
import com.linux.recipes.data.ReportReason
import com.linux.recipes.data.TranslationStart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class TranslationUiTest : UiTest() {
    private val id = translatedSalad.id

    private fun ComposeUiTest.openSalad(
        translations: FakeTranslationRepository,
        consent: FakeConsentRepository = FakeConsentRepository(),
        recipes: FakeRecipeRepository = FakeRecipeRepository(listOf(translatedSalad)),
        imports: FakeImportRepository? = null,
    ) {
        setApp(FakeSessionRepository(signedIn = true), recipes, consent = consent, translations = translations, imports = imports)
        click(recipes.recipes.value.single().title)
        waitForText("Serves")
    }

    private fun ComposeUiTest.openSheet() {
        onNodeWithContentDescription("Show in another language").performClick()
        waitForText("Translations are made by AI. Each new one needs Recipes Pro and uses one of your monthly imports.")
    }

    /** A row of the sheet, which scrolls: the lower languages are below the fold at 640 dp. */
    private fun ComposeUiTest.row(text: String) = onNodeWithText(text).performScrollTo().performClick()

    private fun ComposeUiTest.count(text: String) = onAllNodesWithText(text).fetchSemanticsNodes().size

    @Test
    fun sheetMarksTheOriginalAvailableAndOutOfDateTranslations() = runComposeUiTest {
        openSalad(FakeTranslationRepository())
        openSheet()
        listOf("Deutsch", "Original", "English", "Translation available", "Français", "Out of date", "Translate again", "Türkçe", "Svenska")
            .forEach { waitForText(it) }
        assertEquals(7, count("Translate with AI"), "the seven languages without a translation")
    }

    @Test
    fun anExistingTranslationSwitchesAtOnceAndBack() = runComposeUiTest {
        val translations = FakeTranslationRepository()
        openSalad(translations)
        openSheet()
        row("English")
        waitForText("Potato salad")
        waitForText("Translated by AI from Deutsch")
        assertEquals(0, count("Kartoffelsalat"))
        assertEquals("en", translations.displayLanguages[id])
        scrollTo("Waxy potatoes")
        assertTrue(count("1 kg") > 0, "quantities and units are the original's")
        scrollTo("Boil the potatoes.")
        assertTrue(translations.starts.isEmpty(), "nothing is sent for an existing translation")

        openSheet()
        row("Deutsch")
        waitForText("Kartoffelsalat")
        waitForNoText("Translated by AI from Deutsch")
        assertNull(translations.displayLanguages[id])
    }

    @Test
    fun theRememberedLanguageIsShownWhenTheRecipeOpens() = runComposeUiTest {
        val translations = FakeTranslationRepository().apply { displayLanguages[id] = "fr" }
        openSalad(translations)
        waitForText("Salade de pommes de terre")
        waitForText("The recipe changed after this translation was made.")
    }

    @Test
    fun aMissingLanguageIsTranslatedAndShownWhenItArrives() = runComposeUiTest {
        val translations = FakeTranslationRepository()
        val recipes = FakeRecipeRepository(listOf(translatedSalad))
        openSalad(translations, recipes = recipes)
        openSheet()
        row("Italiano")
        waitForText("Translating this recipe…")
        assertEquals(listOf("it"), translations.starts.map { it.second })
        assertTrue(translations.resumeCalls > 0, "the job is followed")

        val italian = potatoSaladEnglish.copy(language = "it", title = "Insalata di patate")
        recipes.recipes.value = listOf(translatedSalad.copy(translations = translatedSalad.translations + italian))
        translations.activeJob.value = null
        waitForText("Insalata di patate")
        waitForNoText("Translating this recipe…")
    }

    @Test
    fun anOutOfDateTranslationCanBeTranslatedAgain() = runComposeUiTest {
        val translations = FakeTranslationRepository()
        openSalad(translations)
        openSheet()
        row("Translate again")
        waitForText("Translating this recipe…")
        assertEquals(listOf("fr"), translations.starts.map { it.second })
    }

    private fun gate(result: TranslationStart, message: String) = runComposeUiTest {
        val translations = FakeTranslationRepository().apply { startResult = result }
        openSalad(translations)
        openSheet()
        row("Polski")
        waitForText(message)
        assertEquals(1, translations.starts.size)
        assertNull(translations.displayLanguages[id], "the original stays shown")
    }

    @Test
    fun notSubscribedOpensThePaywall() = gate(TranslationStart.SubscriptionRequired, "The store is not available right now. Please try again later.")

    @Test
    fun quotaExhaustedHasItsOwnMessage() =
        gate(TranslationStart.QuotaExhausted, "You have used all of this month's imports and translations. More are available next month.")

    @Test
    fun attemptLimitHasItsOwnMessage() = gate(TranslationStart.AttemptLimit, "Too many tries today. Please try again tomorrow.")

    @Test
    fun alreadyRunningHasItsOwnMessage() =
        gate(TranslationStart.AlreadyRunning, "Another import or translation is still running. Please wait until it ends.")

    @Test
    fun rejectedHasAMessageNotACode() = gate(TranslationStart.Rejected("not_found"), "This recipe cannot be translated right now.")

    @Test
    fun offlineKeepsTheJobIdForTheRetry() = runComposeUiTest {
        val translations = FakeTranslationRepository().apply { startResult = TranslationStart.Offline }
        openSalad(translations)
        openSheet()
        row("Polski")
        waitForText("The translation could not start. Check your connection and try again.")
        translations.startResult = TranslationStart.Started("")
        click("Try again")
        waitForText("Translating this recipe…")
        assertEquals(2, translations.starts.size)
        assertEquals(translations.starts[0].third, translations.starts[1].third, "the same Idempotency-Key")
    }

    @Test
    fun missingConsentShowsTheAiNoticeFirst() = runComposeUiTest {
        val translations = FakeTranslationRepository()
        val consent = FakeConsentRepository(given = false)
        openSalad(translations, consent)
        openSheet()
        row("Polski")
        waitForText("Importing with AI.")
        assertTrue(translations.starts.isEmpty(), "nothing is sent before the notice is accepted")
        click("Accept")
        waitForText("Translating this recipe…")
        assertEquals(1, consent.acceptCalls)
        assertEquals(listOf("pl"), translations.starts.map { it.second })
    }

    @Test
    fun aTranslationCanBeReportedWithItsJob() = runComposeUiTest {
        val translations = FakeTranslationRepository().apply {
            displayLanguages[id] = "en"
            jobIds[id to "en"] = "job-en"
        }
        openSalad(translations)
        waitForText("Report a problem with this translation")
        click("Report a problem with this translation")
        waitForText("Report a problem")
        click("The recipe is wrong or incomplete")
        click("Send")
        waitForText("Thank you. We will look at this result.")
        assertEquals(listOf(Triple("job-en", ReportReason.WrongContent, "")), translations.reports)
    }

    @Test
    fun theReportActionIsHiddenWithoutAKnownJob() = runComposeUiTest {
        openSalad(FakeTranslationRepository().apply { displayLanguages[id] = "en" })
        waitForText("Translated by AI from Deutsch")
        assertEquals(0, count("Report a problem with this translation"))
    }

    @Test
    fun aFailedTranslationIsShownAndDismissed() = runComposeUiTest {
        val job = ImportJobState("job-t", "translation", ImportOutcome.Failed, offline = false, recipeId = id, language = "it")
        val translations = FakeTranslationRepository(job)
        openSalad(translations)
        waitForText("The translation did not work and was not counted. Please try again later.")
        click("Dismiss")
        waitForNoText("The translation did not work and was not counted. Please try again later.")
        assertEquals(listOf("job-t"), translations.acknowledged)
    }

    @Test
    fun theListShowsARunningTranslationInTheStatusCard() = runComposeUiTest {
        val job = ImportJobState("job-t", "translation", null, offline = false, recipeId = id, language = "it")
        setApp(
            FakeSessionRepository(signedIn = true), FakeRecipeRepository(listOf(translatedSalad)),
            imports = FakeImportRepository(job), consent = FakeConsentRepository(),
        )
        waitForText("Translating this recipe…")
    }
}
