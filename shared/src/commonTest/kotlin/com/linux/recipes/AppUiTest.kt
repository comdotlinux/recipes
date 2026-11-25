package com.linux.recipes

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.linux.recipes.ui.FakeRecipeRepository
import com.linux.recipes.ui.FakeSessionRepository
import com.linux.recipes.ui.setScreen
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class AppUiTest : UiTest() {
    @Test
    fun showsAppName() = runComposeUiTest {
        setScreen { App(FakeSessionRepository(), FakeRecipeRepository(), darkTheme = false) }
        // Literal on purpose: the test must fail if the visible name changes.
        onNodeWithText("Recipes").assertIsDisplayed()
    }
}
