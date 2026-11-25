package com.linux.recipes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.linux.recipes.data.tempDir
import com.linux.recipes.ui.FakeConsentRepository
import com.linux.recipes.ui.FakeRecipeRepository
import com.linux.recipes.ui.FakeSessionRepository
import com.linux.recipes.ui.FakeTranslationRepository
import com.linux.recipes.ui.detail.RecipeDetailScreen
import com.linux.recipes.ui.detail.RecipeDetailViewModel
import com.linux.recipes.ui.lemonCake
import com.linux.recipes.ui.list.RecipeListScreen
import com.linux.recipes.ui.list.RecipeListViewModel
import com.linux.recipes.ui.potatoSalad
import com.linux.recipes.ui.settings.SettingsScreen
import com.linux.recipes.ui.settings.SettingsViewModel
import com.linux.recipes.ui.shakshuka
import com.linux.recipes.ui.theme.AppearanceStore
import com.linux.recipes.ui.theme.LocalAppearance
import com.linux.recipes.ui.theme.Palette
import com.linux.recipes.ui.theme.Palettes
import com.linux.recipes.ui.theme.RecipesTheme
import com.linux.recipes.ui.translate.LanguageSheetContent
import com.linux.recipes.ui.translate.TranslateViewModel
import com.linux.recipes.ui.translatedSalad
import kotlinx.io.files.Path
import org.junit.Test
import org.robolectric.RuntimeEnvironment

/** M4 baselines: the language sheet, a translated recipe, the list in every new palette and the Appearance section. */
@OptIn(ExperimentalTestApi::class)
class M4ScreenshotTest : UiTest() {
    private fun shot(
        name: String,
        dark: Boolean,
        palette: Palette = Palettes.Herb,
        before: ComposeUiTest.() -> Unit = {},
        content: @Composable () -> Unit,
    ) = runComposeUiTest {
        setContent { RecipesTheme(darkTheme = dark, palette = palette) { content() } }
        waitForIdle()
        before()
        waitForIdle()
        onRoot().captureRoboImage("src/androidHostTest/screenshots/${name}_${if (dark) "dark" else "light"}.png")
    }

    private fun both(name: String, palette: Palette = Palettes.Herb, before: ComposeUiTest.() -> Unit = {}, content: @Composable () -> Unit) {
        shot(name, dark = false, palette, before, content)
        shot(name, dark = true, palette, before, content)
    }

    /** The sheet's content on the sheet colour, as the bottom sheet shows it. */
    @Composable
    private fun OnSheet(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.extraLarge) {
                Box(Modifier.padding(top = 28.dp)) { content() }
            }
        }
    }

    @Composable
    private fun LanguageSheet() = OnSheet { LanguageSheetContent(translatedSalad, shown = "en", onChoose = {}, onTranslateAgain = {}) }

    @Composable
    private fun translatedDetail() = RecipeDetailScreen(
        remember { RecipeDetailViewModel(FakeRecipeRepository(listOf(translatedSalad)), translatedSalad.id) },
        {}, {},
        translate = remember {
            val translations = FakeTranslationRepository().apply {
                displayLanguages[translatedSalad.id] = "en"
                jobIds[translatedSalad.id to "en"] = "job-en"
            }
            TranslateViewModel(translatedSalad.id, translations, FakeConsentRepository())
        },
    )

    @Composable
    private fun list() = RecipeListScreen(
        remember { RecipeListViewModel(FakeRecipeRepository(listOf(shakshuka, potatoSalad, lemonCake))) }, {}, {}, {},
    )

    @Composable
    private fun settingsWithAppearance() {
        val store = remember { AppearanceStore(Path(tempDir(), "appearance.json")) }
        CompositionLocalProvider(LocalAppearance provides store) {
            SettingsScreen(remember { SettingsViewModel(FakeSessionRepository(signedIn = true), FakeConsentRepository()) }, {})
        }
    }

    @Test
    fun languageSheet() = both("translate_sheet") { LanguageSheet() }

    @Test
    fun detailTranslated() = both("detail_translated") { translatedDetail() }

    @Test
    fun listTomato() = both("list_tomato", Palettes.Tomato) { list() }

    @Test
    fun listOcean() = both("list_ocean", Palettes.Ocean) { list() }

    @Test
    fun listCharcoal() = both("list_charcoal", Palettes.Charcoal) { list() }

    @Test
    fun settingsAppearance() = both("settings_appearance", before = { onNodeWithText("Same as the phone").performScrollTo() }) {
        settingsWithAppearance()
    }

    /** 200 % font scale. */
    @Test
    fun largeFont() {
        RuntimeEnvironment.setFontScale(2f)
        shot("large_font_translate_sheet", dark = false) { LanguageSheet() }
        shot("large_font_detail_translated", dark = false) { translatedDetail() }
        shot("large_font_settings_appearance", dark = false, before = { onNodeWithText("Charcoal").performScrollTo() }) {
            settingsWithAppearance()
        }
    }
}
