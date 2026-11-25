package com.linux.recipes.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.linux.recipes.UiTest
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class RecipeListUiTest : UiTest() {
    @Test
    fun listShowsRecipesAndSearchesTitlesAndTags() = runComposeUiTest {
        val recipes = FakeRecipeRepository(listOf(shakshuka, potatoSalad, lemonCake))
        setApp(FakeSessionRepository(signedIn = true), recipes)
        waitForText("Shakshuka")
        onNodeWithText("Kartoffelsalat").assertIsDisplayed()
        onNodeWithText("45 min  ·  4 servings").assertIsDisplayed()
        onNodeWithText("New recipe").assertIsDisplayed().assertHasClickAction()
        assertTrue(recipes.syncCalls >= 1, "the list syncs when it opens")

        onNode(hasSetTextAction()).performTextInput("breakfast")
        waitForNoText("Kartoffelsalat")
        onNodeWithText("Shakshuka").assertIsDisplayed()

        onNodeWithContentDescription("Clear search").performClick()
        onNode(hasSetTextAction()).performTextInput("kartoffel")
        waitForNoText("Shakshuka")
        onNodeWithText("Kartoffelsalat").assertIsDisplayed()

        onNodeWithContentDescription("Clear search").performClick()
        onNode(hasSetTextAction()).performTextInput("risotto")
        waitForText("No recipe matches “risotto”.")
    }

    @Test
    fun emptyListInvitesTheFirstRecipe() = runComposeUiTest {
        setApp(FakeSessionRepository(signedIn = true), FakeRecipeRepository())
        waitForText("Your recipe box is empty")
        onNodeWithText("Add the first dish you love to cook. It will be here on every device you sign in on.").assertIsDisplayed()
        click("Add a recipe")
        waitForText("New recipe")
    }

    @Test
    fun offlineIsShownQuietlyAboveTheCachedRecipes() = runComposeUiTest {
        val recipes = FakeRecipeRepository(listOf(potatoSalad)).apply { online = false }
        setApp(FakeSessionRepository(signedIn = true), recipes)
        waitForText("Offline. Showing the recipes saved on this device.")
        onNodeWithText("Kartoffelsalat").assertIsDisplayed()
    }

    @Test
    fun firstLoadShowsLoadingNotTheEmptyPrompt() = runComposeUiTest {
        val recipes = FakeRecipeRepository().apply { syncGate = CompletableDeferred() }
        setApp(FakeSessionRepository(signedIn = true), recipes)
        waitForText("Loading your recipes…")
        assertTrue(onAllNodesWithText("Your recipe box is empty").fetchSemanticsNodes().isEmpty())

        recipes.recipes.value = listOf(shakshuka)
        recipes.syncGate!!.complete(Unit)
        waitForText("Shakshuka")
        waitForNoText("Loading your recipes…")
    }
}
