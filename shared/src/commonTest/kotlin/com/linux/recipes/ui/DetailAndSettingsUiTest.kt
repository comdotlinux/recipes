package com.linux.recipes.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.linux.recipes.UiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DetailAndSettingsUiTest : UiTest() {
    @Test
    fun detailShowsEverySectionAndAPendingPicture() = runComposeUiTest {
        val uri = RecordingUriHandler()
        setApp(FakeSessionRepository(signedIn = true), FakeRecipeRepository(listOf(potatoSalad)), uri)
        click("Kartoffelsalat")
        waitForText("The Swabian way: warm potatoes, broth and vinegar, no mayonnaise.")
        onNodeWithText("This picture is not on this device yet. It will appear after the next sync.").assertIsDisplayed()
        onNodeWithText("Serves").assertIsDisplayed()
        onNodeWithText("25 min").assertIsDisplayed()
        scrollTo("Dressing")
        scrollTo("hot beef broth")
        scrollTo("Pour over the hot broth with vinegar and mustard, toss gently and rest for 30 minutes.")
        scrollTo("Summer")
        scrollTo("https://example.com/kartoffelsalat")
        click("https://example.com/kartoffelsalat")
        assertEquals(listOf("https://example.com/kartoffelsalat"), uri.opened)
    }

    @Test
    fun deleteAsksForConfirmation() = runComposeUiTest {
        val recipes = FakeRecipeRepository(listOf(potatoSalad, shakshuka))
        setApp(FakeSessionRepository(signedIn = true), recipes)
        click("Kartoffelsalat")
        waitForText("Ingredients")
        onNodeWithContentDescription("Delete recipe").performClick()
        waitForText("Delete this recipe?")
        onNodeWithText("“Kartoffelsalat” will be removed from all your devices. This cannot be undone.").assertIsDisplayed()
        click("Cancel")
        waitForNoText("Delete this recipe?")
        assertEquals(2, recipes.recipes.value.size)

        recipes.deleteOk = false
        onNodeWithContentDescription("Delete recipe").performClick()
        click("Delete")
        waitForText("The recipe could not be deleted. Check your connection and try again.")

        recipes.deleteOk = true
        onNodeWithContentDescription("Delete recipe").performClick()
        click("Delete")
        waitForText("Shakshuka")
        waitForNoText("Kartoffelsalat")
        assertEquals(listOf(shakshuka), recipes.recipes.value)
    }

    @Test
    fun settingsShowsTheSupportIdOpensLegalPagesAndSignsOut() = runComposeUiTest {
        val session = FakeSessionRepository(signedIn = true)
        val uri = RecordingUriHandler()
        setApp(session, FakeRecipeRepository(listOf(shakshuka)), uri)
        waitForText("Shakshuka")
        onNodeWithContentDescription("Settings").performClick()
        waitForText("Support ID")
        onNodeWithText(USER_ID).assertIsDisplayed()
        onNodeWithContentDescription("Copy Support ID").performClick()
        waitForText("Support ID copied")

        click("Privacy policy")
        click("Imprint")
        click("Terms of use")
        assertEquals(
            listOf(
                "https://recipe.munich-tech.de/privacy",
                "https://recipe.munich-tech.de/imprint",
                "https://recipe.munich-tech.de/terms",
            ),
            uri.opened,
        )

        click("Sign out")
        waitForText("Sign out of Recipes?")
        click("Cancel")
        assertEquals(0, session.signOutCalls)
        click("Sign out")
        waitForText("Your recipes stay in your account. This device forgets them until you sign in again.")
        clickDialogConfirm("Sign out")
        waitForText("I am 16 or older")
        assertEquals(1, session.signOutCalls)
    }

    @Test
    fun recipeWithoutIngredientsAndStepsInvitesToEditIt() = runComposeUiTest {
        val onlySteps = shakshuka.copy(id = "5e6f7a8b-9c0d-4e1f-a2b3-c4d5e6f7a8b9", title = "Only steps", ingredients = emptyList())
        setApp(FakeSessionRepository(signedIn = true), FakeRecipeRepository(listOf(titleOnly, onlySteps)))
        click("Only steps")
        waitForText("Method")
        assertTrue(onAllNodesWithText("No ingredients or steps yet.").fetchSemanticsNodes().isEmpty())
        onNodeWithContentDescription("Back").performClick()

        click("Grandma's goulash")
        waitForText("No ingredients or steps yet.")
        assertTrue(
            onAllNodesWithText("This picture is not on this device yet.", substring = true).fetchSemanticsNodes().isEmpty(),
            "no picture placeholder for a recipe without pictures",
        )
        click("Add ingredients and steps")
        waitForText("Edit recipe")
        onNodeWithText("Grandma's goulash").assertIsDisplayed()
    }
}
