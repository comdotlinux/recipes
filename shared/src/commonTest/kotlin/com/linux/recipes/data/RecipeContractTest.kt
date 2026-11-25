package com.linux.recipes.data

import kotlin.test.Test
import kotlin.test.assertEquals

class RecipeContractTest {
    @Test
    fun recipeFixtureRoundTripsAndConvertsToTheInputFixture() {
        val recipe = Api.apiJson.decodeFromString(Recipe.serializer(), fixture("recipe.json"))
        assertEquals(jsonOf(fixture("recipe.json")), jsonOf(Api.apiJson.encodeToString(Recipe.serializer(), recipe)))
        val input = recipe.toInput()
        assertEquals(Api.apiJson.decodeFromString(RecipeInput.serializer(), fixture("recipe_input.json")), input)
        assertEquals(jsonOf(fixture("recipe_input.json")), jsonOf(Api.apiJson.encodeToString(RecipeInput.serializer(), input)))
    }

    @Test
    fun deletionMarkerDecodesWithDefaults() {
        val marker = Api.apiJson.decodeFromString(Recipe.serializer(), """{ "id": "$ID_B", "rev": 8, "deleted": true, "updatedAt": "2026-11-24T18:05:00Z" }""")
        assertEquals(Recipe(id = ID_B, rev = 8, deleted = true, updatedAt = "2026-11-24T18:05:00Z"), marker)
    }
}
