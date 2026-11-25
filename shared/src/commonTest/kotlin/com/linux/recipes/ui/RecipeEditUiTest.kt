package com.linux.recipes.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.linux.recipes.UiTest
import com.linux.recipes.data.SaveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class RecipeEditUiTest : UiTest() {
    @Test
    fun createdRecipeAppearsInDetailAndInTheList() = runComposeUiTest {
        val recipes = FakeRecipeRepository()
        setApp(FakeSessionRepository(signedIn = true), recipes)
        click("Add a recipe")
        waitForText("New recipe")
        type("Title", "Pancakes")
        type("Servings", "4")
        type("Cook", "15")
        scrollTo("Add ingredient")
        click("Add ingredient")
        type("Amount", "250")
        type("Unit", "g")
        type("Ingredient", "flour")
        scrollTo("Add step")
        click("Add step")
        type("Step 1", "Whisk everything and fry in butter.")
        type("New tag", "Breakfast")
        click("Add tag")
        click("Save")

        waitForText("250 g")
        onNodeWithText("Pancakes").assertIsDisplayed()
        onNodeWithText("flour").assertIsDisplayed()
        val (input, rev, images) = recipes.saved.single()
        assertNull(rev, "a new recipe is a create")
        assertEquals(listOf("Breakfast"), input.tags)
        assertEquals(15, input.cookMinutes)
        assertTrue(images.isEmpty())

        onNodeWithContentDescription("Back").performClick()
        waitForText("15 min  ·  4 servings")
        onNodeWithText("Pancakes").assertIsDisplayed()
    }

    @Test
    fun formRejectsOutOfRangeInput() = runComposeUiTest {
        val recipes = FakeRecipeRepository()
        setApp(FakeSessionRepository(signedIn = true), recipes)
        click("Add a recipe")
        waitForText("New recipe")
        type("Servings", "0")
        type("Prep", "10001")
        type("Language code", "x")
        click("Save")
        waitForText("Please fix the highlighted fields.")
        onNodeWithText("Required").assertIsDisplayed()
        onNodeWithText("Enter a whole number from 1 to 100").assertIsDisplayed()
        onNodeWithText("Enter a whole number from 0 to 10000").assertIsDisplayed()
        scrollTo("Use a code such as “en” or “de-AT”")

        type("Title", "x".repeat(201))
        waitForText("At most 200 characters")
        assertTrue(recipes.saved.isEmpty(), "nothing invalid reaches the repository")
    }

    @Test
    fun editWithConflictOffersReloadAndSavesAgainstTheNewRevision() = runComposeUiTest {
        val recipes = FakeRecipeRepository(listOf(potatoSalad))
        setApp(FakeSessionRepository(signedIn = true), recipes)
        click("Kartoffelsalat")
        waitForText("Ingredients")
        onNodeWithContentDescription("Edit recipe").performClick()
        waitForText("Edit recipe")
        type("Title", "Kartoffelsalat mit Gurke")
        recipes.saveResult = SaveResult.Conflict
        click("Save")
        waitForText("Changed on another device")
        assertEquals(7L, recipes.saved.single().second)

        recipes.onNextSync = {
            recipes.recipes.value = listOf(potatoSalad.copy(rev = 8, title = "Omas Kartoffelsalat"))
        }
        recipes.saveResult = null
        click("Reload")
        waitForText("Omas Kartoffelsalat")
        click("Save")
        waitForNoText("Save")
        onNodeWithText("Omas Kartoffelsalat").assertIsDisplayed()
        assertEquals(8L, recipes.saved.last().second)
    }

    @Test
    fun offlineAndRejectedSavesKeepTheForm() = runComposeUiTest {
        val recipes = FakeRecipeRepository(listOf(potatoSalad))
        setApp(FakeSessionRepository(signedIn = true), recipes)
        click("Kartoffelsalat")
        waitForText("Ingredients")
        onNodeWithContentDescription("Edit recipe").performClick()
        waitForText("Edit recipe")
        recipes.saveResult = SaveResult.Offline
        click("Save")
        waitForText("You are offline. Your changes are still here; save again once you are connected.")
        recipes.saveResult = SaveResult.Rejected("recipe_limit")
        click("Save")
        waitForText("You have reached the limit of 2,000 recipes.")
        recipes.saveResult = SaveResult.Rejected("recipe_storage_cap")
        click("Save")
        waitForText("Your recipe storage is full (20 MB). Delete some recipes and try again.")
        recipes.saveResult = SaveResult.Rejected("mystery")
        click("Save")
        waitForText("The recipe could not be saved. Please try again.")
        assertTrue(onAllNodesWithText("mystery", substring = true).fetchSemanticsNodes().isEmpty(), "no raw server code")
        onNodeWithText("Kartoffelsalat").assertIsDisplayed()
    }

    @Test
    fun closingWithChangesAsksBeforeDiscarding() = runComposeUiTest {
        setApp(FakeSessionRepository(signedIn = true), FakeRecipeRepository())
        click("Add a recipe")
        waitForText("New recipe")
        type("Title", "Half a thought")
        onNodeWithContentDescription("Close").performClick()
        waitForText("Discard your changes?")
        click("Keep editing")
        onNodeWithText("Half a thought").assertIsDisplayed()
        onNodeWithContentDescription("Close").performClick()
        click("Discard")
        waitForText("Your recipe box is empty")
    }

    private fun ComposeUiTest.openEditorWithConflict(recipes: FakeRecipeRepository) {
        setApp(FakeSessionRepository(signedIn = true), recipes)
        click("Kartoffelsalat")
        waitForText("Ingredients")
        onNodeWithContentDescription("Edit recipe").performClick()
        waitForText("Edit recipe")
        recipes.saveResult = SaveResult.Conflict
        click("Save")
        waitForText("Changed on another device")
        recipes.saveResult = null
    }

    @Test
    fun conflictOnARecipeDeletedElsewhereSavesItAsNew() = runComposeUiTest {
        val recipes = FakeRecipeRepository(listOf(potatoSalad))
        openEditorWithConflict(recipes)
        recipes.onNextSync = { recipes.recipes.value = emptyList() }
        click("Reload")
        waitForText("This recipe was deleted on another device. Save to keep your version as a new recipe.")
        onNodeWithText("Kartoffelsalat").assertIsDisplayed()
        click("Save")
        waitForNoText("Save")
        assertNull(recipes.saved.last().second, "saved as a create, not against the stale revision")
        assertEquals(potatoSalad.id, recipes.saved.last().first.id)
    }

    @Test
    fun reloadWhileOfflineKeepsTheConflictOpen() = runComposeUiTest {
        val recipes = FakeRecipeRepository(listOf(potatoSalad))
        openEditorWithConflict(recipes)
        recipes.online = false
        click("Reload")
        waitForText("You are offline, so the latest version cannot be loaded. Connect and try Reload again.")
        onNodeWithText("Changed on another device").assertIsDisplayed()

        recipes.online = true
        recipes.onNextSync = { recipes.recipes.value = listOf(potatoSalad.copy(rev = 8, title = "Omas Kartoffelsalat")) }
        click("Reload")
        waitForText("Omas Kartoffelsalat")
        waitForNoText("Changed on another device")
    }
}
