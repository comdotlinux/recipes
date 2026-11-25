package com.linux.recipes.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import com.linux.recipes.UiTest
import com.linux.recipes.data.tempDir
import com.linux.recipes.ui.theme.Appearance
import com.linux.recipes.ui.theme.AppTheme
import com.linux.recipes.ui.theme.AppearanceStore
import com.linux.recipes.ui.theme.Palettes
import com.linux.recipes.ui.theme.ThemeMode
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class AppearanceUiTest : UiTest() {
    private val file = Path(tempDir(), "appearance.json")

    @Test
    fun thePickerAppliesAtOnceAndIsReadAgainOnTheNextStart() = runComposeUiTest {
        val store = AppearanceStore(file)
        setApp(FakeSessionRepository(signedIn = true), FakeRecipeRepository(listOf(shakshuka)), appearance = store)
        waitForText("Shakshuka")
        onNodeWithContentDescription("Settings").performClick()
        waitForText("Support ID")
        onNodeWithText("Herb").performScrollTo().assertIsSelected()
        onNodeWithText("Same as the phone").performScrollTo().assertIsSelected()

        onNodeWithText("Ocean").performScrollTo().performClick()
        onNodeWithText("Ocean").assertIsSelected()
        onNodeWithText("Herb").assertIsNotSelected()
        onNodeWithText("Dark").performScrollTo().performClick()
        onNodeWithText("Dark").assertIsSelected()
        assertEquals(Appearance(Palettes.Ocean.id, ThemeMode.Dark), store.state.value)

        assertEquals(Appearance(Palettes.Ocean.id, ThemeMode.Dark), AppearanceStore(file).state.value, "read from the file on the next start")
    }

    @Test
    fun theThemeFollowsTheChoiceAtOnce() = runComposeUiTest {
        val store = AppearanceStore(file)
        var primary = Color.Unspecified
        setScreen { AppTheme(store, systemDark = false) { primary = MaterialTheme.colorScheme.primary } }
        waitForIdle()
        assertEquals(Palettes.Herb.light.primary, primary)

        store.set(Appearance(Palettes.Tomato.id, ThemeMode.System))
        waitForIdle()
        assertEquals(Palettes.Tomato.light.primary, primary)

        store.set(Appearance(Palettes.Charcoal.id, ThemeMode.Dark))
        waitForIdle()
        assertEquals(Palettes.Charcoal.dark.primary, primary)
    }

    @Test
    fun systemModeFollowsThePhoneAndAnUnknownPaletteFallsBackToHerb() = runComposeUiTest {
        val store = AppearanceStore(file).apply { set(Appearance("removed", ThemeMode.System)) }
        var primary = Color.Unspecified
        setScreen { AppTheme(store, systemDark = true) { primary = MaterialTheme.colorScheme.primary } }
        waitForIdle()
        assertEquals(Palettes.Herb.dark.primary, primary)
    }
}
