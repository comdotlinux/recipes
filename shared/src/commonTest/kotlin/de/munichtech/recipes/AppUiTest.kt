package de.munichtech.recipes

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class AppUiTest : UiTest() {
    @Test
    fun showsAppName() = runComposeUiTest {
        setContent { App(darkTheme = false) }
        // Literal on purpose: the test must fail if the visible name changes.
        onNodeWithText("Recipes").assertIsDisplayed()
    }
}
