package com.linux.recipes.ui

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.linux.recipes.App
import com.linux.recipes.UiTest
import org.junit.Test

/** The system back gesture goes through the Activity's dispatcher, which only exists on Android. */
@OptIn(ExperimentalTestApi::class)
class BackGestureTest : UiTest() {
    @Test
    fun backWithUnsavedChangesAsksFirstAndBackWithoutChangesLeaves() = runComposeUiTest {
        lateinit var dispatcher: OnBackPressedDispatcher
        setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            CompositionLocalProvider(LocalUriHandler provides RecordingUriHandler()) {
                App(FakeSessionRepository(signedIn = true), FakeRecipeRepository(), darkTheme = false)
            }
        }
        click("Add a recipe")
        waitForText("New recipe")
        type("Title", "Half a thought")
        runOnIdle { dispatcher.onBackPressed() }
        waitForText("Discard your changes?")
        click("Keep editing")
        onNodeWithText("Half a thought").assertIsDisplayed()

        type("Title", "")
        runOnIdle { dispatcher.onBackPressed() }
        waitForText("Your recipe box is empty")
    }
}
