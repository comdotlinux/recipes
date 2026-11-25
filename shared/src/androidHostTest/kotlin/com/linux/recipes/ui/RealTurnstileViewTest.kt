package com.linux.recipes.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import com.linux.recipes.App
import com.linux.recipes.UiTest
import com.linux.recipes.platform.TurnstileGate
import com.linux.recipes.ui.signin.TurnstileChallenge
import org.junit.Test

/**
 * The sign-in dialog with the real Android `TurnstileView` (a WebView), without the test double of `setApp`.
 * iOS cannot host its web view in the Compose test harness; the real FileKit launchers are composed by
 * `BackGestureTest` and `AccessibilityTest.editorClickablesAreLabelled`.
 */
@OptIn(ExperimentalTestApi::class)
class RealTurnstileViewTest : UiTest() {
    @Test
    fun turnstileCheckWithTheRealWebViewIsShownAndClosingItCancelsSignIn() = runComposeUiTest {
        val gate = TurnstileGate()
        setContent { App(FakeSessionRepository(turnstile = gate), FakeRecipeRepository(), darkTheme = false, TurnstileChallenge(gate, "https://example.com")) }
        closeThenPassTheTurnstileCheck(gate)
    }
}
