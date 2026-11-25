package com.linux.recipes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linux.recipes.data.ImportJobState
import com.linux.recipes.data.ImportOutcome
import com.linux.recipes.ui.FakeConsentRepository
import com.linux.recipes.ui.FakeImportRepository
import com.linux.recipes.ui.FakeRecipeRepository
import com.linux.recipes.ui.importedDraft
import com.linux.recipes.ui.testPlans
import com.linux.recipes.ui.imports.AiNoticeContent
import com.linux.recipes.ui.imports.ImportPaywallContent
import com.linux.recipes.ui.paywall.PaywallContent
import com.linux.recipes.ui.imports.ImportSheetContent
import com.linux.recipes.ui.imports.ImportViewModel
import com.linux.recipes.ui.imports.ReviewImportScreen
import com.linux.recipes.ui.imports.ReviewLocalScreen
import com.linux.recipes.ui.imports.UnreadableSheetContent
import com.linux.recipes.ui.localDraft
import com.linux.recipes.ui.FakeSessionRepository
import com.linux.recipes.ui.detail.RecipeDetailScreen
import com.linux.recipes.ui.detail.RecipeDetailViewModel
import com.linux.recipes.ui.edit.RecipeEditScreen
import com.linux.recipes.ui.edit.RecipeEditViewModel
import com.linux.recipes.ui.lemonCake
import com.linux.recipes.ui.list.RecipeListScreen
import com.linux.recipes.ui.list.RecipeListViewModel
import com.linux.recipes.ui.potatoSalad
import com.linux.recipes.ui.scrollTo
import com.linux.recipes.ui.settings.SettingsScreen
import com.linux.recipes.ui.settings.SettingsViewModel
import com.linux.recipes.ui.shakshuka
import com.linux.recipes.ui.titleOnly
import com.linux.recipes.ui.signin.SignInScreen
import com.linux.recipes.ui.signin.SignInViewModel
import com.linux.recipes.ui.theme.RecipesTheme
import org.junit.Test
import org.robolectric.RuntimeEnvironment

/** One light and one dark baseline per screen, in src/androidHostTest/screenshots. */
@OptIn(ExperimentalTestApi::class)
class AppScreenshotTest : UiTest() {
    private fun shot(name: String, dark: Boolean, before: ComposeUiTest.() -> Unit, content: @Composable () -> Unit) = runComposeUiTest {
        setContent { RecipesTheme(darkTheme = dark) { content() } }
        waitForIdle()
        before()
        waitForIdle()
        onRoot().captureRoboImage("src/androidHostTest/screenshots/${name}_${if (dark) "dark" else "light"}.png")
    }

    private fun both(name: String, before: ComposeUiTest.() -> Unit = {}, content: @Composable () -> Unit) {
        shot(name, dark = false, before, content)
        shot(name, dark = true, before, content)
    }

    private fun recipes(online: Boolean = true) =
        FakeRecipeRepository(listOf(shakshuka, potatoSalad, lemonCake)).apply { this.online = online }

    @Test
    fun appLight() = runComposeUiTest {
        setContent { App(FakeSessionRepository(), FakeRecipeRepository(), darkTheme = false) }
        onRoot().captureRoboImage("src/androidHostTest/screenshots/app_light.png")
    }

    @Test
    fun appDark() = runComposeUiTest {
        setContent { App(FakeSessionRepository(), FakeRecipeRepository(), darkTheme = true) }
        onRoot().captureRoboImage("src/androidHostTest/screenshots/app_dark.png")
    }

    @Test
    fun list() = both("list") { RecipeListScreen(remember { RecipeListViewModel(recipes()) }, {}, {}, {}) }

    @Test
    fun listOffline() = both("list_offline") { RecipeListScreen(remember { RecipeListViewModel(recipes(online = false)) }, {}, {}, {}) }

    @Test
    fun listEmpty() = both("list_empty") { RecipeListScreen(remember { RecipeListViewModel(FakeRecipeRepository()) }, {}, {}, {}) }

    @Test
    fun detail() = both("detail") {
        RecipeDetailScreen(remember { RecipeDetailViewModel(recipes(), potatoSalad.id) }, {}, {})
    }

    @Test
    fun detailUnfinished() = both("detail_unfinished") {
        RecipeDetailScreen(remember { RecipeDetailViewModel(FakeRecipeRepository(listOf(titleOnly)), titleOnly.id) }, {}, {})
    }

    @Test
    fun detailMethod() = both("detail_method", before = { scrollTo("Method") }) {
        RecipeDetailScreen(remember { RecipeDetailViewModel(recipes(), potatoSalad.id) }, {}, {})
    }

    @Test
    fun editorRows() = both("editor_rows", before = { scrollTo("Add ingredient") }) {
        RecipeEditScreen(remember { RecipeEditViewModel(recipes(), potatoSalad.id, "en") }, {}, {})
    }

    @Test
    fun editor() = both("editor") {
        RecipeEditScreen(remember { RecipeEditViewModel(recipes(), potatoSalad.id, "en") }, {}, {})
    }

    @Test
    fun editorNew() = both("editor_new") {
        RecipeEditScreen(remember { RecipeEditViewModel(recipes(), null, "en") }, {}, {})
    }

    /** 200 % font scale, the largest Android offers, on the densest screens. */
    @Test
    fun largeFont() {
        RuntimeEnvironment.setFontScale(2f)
        shot("large_font_signin", dark = false, before = {}) { SignInScreen(remember { SignInViewModel(FakeSessionRepository()) }) }
        shot("large_font_list", dark = false, before = {}) { RecipeListScreen(remember { RecipeListViewModel(recipes()) }, {}, {}, {}) }
        shot("large_font_editor", dark = false, before = {}) {
            RecipeEditScreen(remember { RecipeEditViewModel(recipes(), potatoSalad.id, "en") }, {}, {})
        }
    }

    @Test
    fun settings() = both("settings") {
        SettingsScreen(remember { SettingsViewModel(FakeSessionRepository(signedIn = true), FakeConsentRepository()) }, {})
    }

    // ---------- M2: AI import ----------

    /** The sheets' content on the sheet colour, as the bottom sheet shows it (sheets open in their own window). */
    @Composable
    private fun OnSheet(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.extraLarge) {
                Box(Modifier.padding(top = 28.dp)) { content() }
            }
        }
    }

    @Composable
    private fun listWithJob(job: ImportJobState?) = RecipeListScreen(
        remember { RecipeListViewModel(recipes()) }, {}, {}, {},
        imports = remember { ImportViewModel(FakeImportRepository(job), FakeConsentRepository()) },
    )

    @Test
    fun importSheet() = both("import_sheet") {
        OnSheet { ImportSheetContent("https://example.com/pasta-e-ceci", {}, firstFree = true, {}, {}, {}) }
    }

    @Test
    fun aiNotice() = both("ai_notice") { AiNoticeContent({}, {}) }

    @Test
    fun importRunning() = both("import_running") { listWithJob(ImportJobState("job", "url", outcome = null, offline = false)) }

    @Test
    fun importReady() = both("import_ready") { listWithJob(ImportJobState("job", "url", ImportOutcome.Done(importedDraft), offline = false)) }

    @Test
    fun importFailed() = both("import_failed") { listWithJob(ImportJobState("job", "url", ImportOutcome.Failed, offline = false)) }

    @Test
    fun importReview() = both("import_review") {
        ReviewImportScreen(remember { RecipeEditViewModel(recipes(), null, "en", importedDraft) }, FakeImportRepository(), "job", {}, {})
    }

    /** Without a store (no billing provided): the plans are replaced by the "store not available" note. */
    @Test
    fun importReviewLocal() = both("import_review_local") {
        ReviewLocalScreen(remember { RecipeEditViewModel(recipes(), null, "en", localDraft) }, {}, {}, {})
    }

    @Test
    fun importUnreadable() = both("import_unreadable") { OnSheet { UnreadableSheetContent({}, {}, {}) } }

    @Test
    fun importPaywall() = both("import_paywall") { OnSheet { ImportPaywallContent({}, {}) } }

    @Test
    fun paywall() = both("paywall") { OnSheet { paywallWithPlans() } }

    @Composable
    private fun paywallWithPlans() =
        PaywallContent(testPlans, "monthly", {}, working = false, message = null, {}, {}, onTypeIn = {}, onClose = {})

    /** 200 % font on the import screens. */
    @Test
    fun largeFontImport() {
        RuntimeEnvironment.setFontScale(2f)
        shot("large_font_import_ready", dark = false, before = {}) {
            listWithJob(ImportJobState("job", "url", ImportOutcome.Done(importedDraft), offline = false))
        }
        shot("large_font_ai_notice", dark = false, before = {}) { AiNoticeContent({}, {}) }
        shot("large_font_import_sheet", dark = false, before = {}) {
            OnSheet { ImportSheetContent("", {}, firstFree = true, {}, {}, {}) }
        }
        shot("large_font_import_review_local", dark = false, before = {}) {
            ReviewLocalScreen(remember { RecipeEditViewModel(recipes(), null, "en", localDraft) }, {}, {}, {})
        }
        shot("large_font_import_unreadable", dark = false, before = {}) { OnSheet { UnreadableSheetContent({}, {}, {}) } }
        shot("large_font_paywall", dark = false, before = {}) { OnSheet { paywallWithPlans() } }
        shot("large_font_paywall_plans", dark = false, before = { onNodeWithText("Subscribe").performScrollTo() }) { OnSheet { paywallWithPlans() } }
    }
}
