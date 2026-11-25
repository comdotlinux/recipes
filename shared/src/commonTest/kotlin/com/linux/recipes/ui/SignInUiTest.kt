package com.linux.recipes.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.linux.recipes.App
import com.linux.recipes.UiTest
import com.linux.recipes.platform.TurnstileGate
import com.linux.recipes.ui.signin.TurnstileChallenge
import com.linux.recipes.data.SignInResult
import com.linux.recipes.data.SignInResult.Failed.Reason
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SignInUiTest : UiTest() {
    @Test
    fun signInNeedsTheAgeConfirmationAndOpensTheList() = runComposeUiTest {
        val session = FakeSessionRepository()
        setApp(session, FakeRecipeRepository())
        onNodeWithText("Recipes").assertIsDisplayed()
        onNodeWithText("Your own recipes, in one calm place, on every device you cook with.").assertIsDisplayed()
        onNodeWithText("Please confirm your age to continue.").assertIsDisplayed()
        onNodeWithText("Sign in").assertIsNotEnabled()

        click("I am 16 or older")
        onNodeWithText("Sign in").assertIsEnabled()
        session.gate = CompletableDeferred()
        click("Sign in")
        waitForText("Signing in…")
        onNodeWithText("Sign in").assertIsNotEnabled()

        session.gate!!.complete(Unit)
        waitForText("Your recipe box is empty")
        assertEquals(listOf(true), session.ageArguments)
    }

    @Test
    fun everyUnsuccessfulResultHasItsOwnMessage() = runComposeUiTest {
        val session = FakeSessionRepository()
        setApp(session, FakeRecipeRepository())
        click("I am 16 or older")
        val expected = listOf(
            SignInResult.Cancelled to "Sign-in was cancelled. You can try again whenever you are ready.",
            SignInResult.Failed(Reason.Network) to "No connection. Check your internet and try again.",
            SignInResult.Failed(Reason.Rejected) to "Sign-in did not work. Please try again.",
            SignInResult.Failed(Reason.AgeNotConfirmed) to "Please confirm that you are 16 or older.",
            SignInResult.Failed(Reason.AccountDeleting) to
                "This account is being deleted. You can sign in again once that is finished.",
            SignInResult.Failed(Reason.RateLimited) to "Too many attempts. Please wait a moment and try again.",
        )
        for ((result, message) in expected) {
            session.result = result
            click("Sign in")
            waitForText(message)
            onNodeWithText(message).assertIsDisplayed()
        }
        assertEquals(expected.size, session.ageArguments.size)
    }

    @Test
    fun turnstileCheckIsShownAndClosingItCancelsSignIn() = runComposeUiTest {
        val gate = TurnstileGate()
        val session = FakeSessionRepository(turnstile = gate)
        setContent { App(session, FakeRecipeRepository(), darkTheme = false, TurnstileChallenge(gate, "https://example.com")) }
        click("I am 16 or older")
        click("Sign in")
        waitForText("One quick check")
        click("Cancel")
        waitForText("Sign-in was cancelled. You can try again whenever you are ready.")
        waitForNoText("One quick check")

        click("Sign in")
        waitForText("One quick check")
        runOnIdle { gate.deliver("token") }
        waitForText("Your recipe box is empty")
    }
}
