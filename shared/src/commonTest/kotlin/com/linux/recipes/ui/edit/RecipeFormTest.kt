package com.linux.recipes.ui.edit

import com.linux.recipes.data.Ingredient
import com.linux.recipes.ui.potatoSalad
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecipeFormTest {
    private fun form() = RecipeForm("id-1", "en")

    @Test
    fun loadAndToInputRoundTripKeepsGroupsAndOrder() {
        val f = form().apply { load(potatoSalad) }
        assertFalse(f.isDirty)
        // One heading row is inserted before the first "Dressing" ingredient.
        assertEquals(listOf(false, false, true, false, false, false), f.ingredients.map { it.isGroup })
        val input = f.toInput()
        assertEquals(potatoSalad.ingredients, input.ingredients)
        assertEquals(potatoSalad.steps, input.steps)
        assertEquals(potatoSalad.imageIds, input.imageIds)
        assertEquals("de", input.language)
        assertEquals(4, input.servings)
    }

    @Test
    fun blankRowsAreDroppedAndATagDraftIsKept() {
        val f = form()
        f.title = "  Soup "
        f.ingredients += IngredientRow(isGroup = false)
        f.ingredients += IngredientRow(isGroup = true, item = " ")
        f.ingredients += IngredientRow(isGroup = false, quantity = "1", item = " leek ")
        f.steps += StepRow("  ")
        f.steps += StepRow("Cook.")
        f.tagDraft = "Winter"
        assertTrue(f.isDirty)
        val input = f.toInput()
        assertEquals("Soup", input.title)
        assertEquals(listOf(Ingredient("1", "", "leek", null)), input.ingredients)
        assertEquals(listOf("Cook."), input.steps)
        assertEquals(listOf("Winter"), input.tags)
        assertNull(input.sourceUrl)
        assertNull(input.servings)
    }

    @Test
    fun validateEnforcesEveryLimit() {
        val f = form()
        assertEquals(FieldError.Required, f.validate()[Fields.TITLE])
        f.title = "t".repeat(Limits.TITLE + 1)
        f.description = "d".repeat(Limits.DESCRIPTION + 1)
        f.servings = "101"
        f.prep = "-1"
        f.cook = "ten"
        f.sourceUrl = "u".repeat(Limits.SOURCE_URL + 1)
        f.language = "english language"
        val group = IngredientRow(isGroup = true, item = "g".repeat(Limits.GROUP + 1))
        val row = IngredientRow(isGroup = false, quantity = "q".repeat(Limits.QUANTITY + 1), unit = "u".repeat(Limits.UNIT + 1))
        val step = StepRow("s".repeat(Limits.STEP + 1))
        f.ingredients += listOf(group, row)
        f.steps += step
        f.tagDraft = "x".repeat(Limits.TAG + 1)
        val e = f.validate()
        assertEquals(FieldError.TooLong(Limits.TITLE), e[Fields.TITLE])
        assertEquals(FieldError.TooLong(Limits.DESCRIPTION), e[Fields.DESCRIPTION])
        assertEquals(FieldError.Range(1, 100), e[Fields.SERVINGS])
        assertEquals(FieldError.Range(0, 10000), e[Fields.PREP])
        assertEquals(FieldError.Range(0, 10000), e[Fields.COOK])
        assertEquals(FieldError.TooLong(Limits.SOURCE_URL), e[Fields.SOURCE])
        assertEquals(FieldError.Language, e[Fields.LANGUAGE])
        assertEquals(FieldError.TooLong(Limits.GROUP), e[Fields.item(group)])
        assertEquals(FieldError.TooLong(Limits.QUANTITY), e[Fields.quantity(row)])
        assertEquals(FieldError.TooLong(Limits.UNIT), e[Fields.unit(row)])
        assertEquals(FieldError.Required, e[Fields.item(row)])
        assertEquals(FieldError.TooLong(Limits.STEP), e[Fields.step(step)])
        assertEquals(FieldError.TooLong(Limits.TAG), e[Fields.TAG_DRAFT])
    }

    @Test
    fun countLimitsApply() {
        val f = form().apply { title = "Many" }
        repeat(Limits.INGREDIENTS + 1) { f.ingredients += IngredientRow(isGroup = false, item = "i$it") }
        repeat(Limits.STEPS + 1) { f.steps += StepRow("s$it") }
        repeat(Limits.TAGS + 1) { f.tags += "t$it" }
        val e = f.validate()
        assertEquals(FieldError.TooMany(Limits.INGREDIENTS), e[Fields.INGREDIENTS])
        assertEquals(FieldError.TooMany(Limits.STEPS), e[Fields.STEPS])
        assertEquals(FieldError.TooMany(Limits.TAGS), e[Fields.TAGS])
    }

    @Test
    fun validValuesPassAtTheBoundaries() {
        val f = form().apply {
            title = "t".repeat(Limits.TITLE)
            servings = "100"
            prep = "0"
            cook = "10000"
            language = "de-AT"
        }
        assertTrue(f.validate().isEmpty(), "${f.validate()}")
    }

    @Test
    fun addTagRejectsBlankDuplicateLongAndTooMany() {
        val f = form()
        assertEquals(FieldError.Required, f.addTag())
        f.tagDraft = "Soup"
        assertNull(f.addTag())
        assertEquals("", f.tagDraft)
        f.tagDraft = "soup"
        assertEquals(FieldError.DuplicateTag, f.addTag())
        f.tagDraft = "x".repeat(Limits.TAG + 1)
        assertEquals(FieldError.TooLong(Limits.TAG), f.addTag())
        repeat(Limits.TAGS - 1) { f.tags += "t$it" }
        f.tagDraft = "one more"
        assertEquals(FieldError.TooMany(Limits.TAGS), f.addTag())
    }

    @Test
    fun moveSwapsNeighboursAndIgnoresTheEdges() {
        val f = form()
        val list = mutableListOf("a", "b", "c")
        f.move(list, 0, 1)
        assertEquals(listOf("b", "a", "c"), list)
        f.move(list, 2, 1)
        f.move(list, 0, -1)
        assertEquals(listOf("b", "a", "c"), list)
        f.move(list, 2, -1)
        assertEquals(listOf("b", "c", "a"), list)
    }

    @Test
    fun compressionStepsDownUntilThePictureFits() = runTest {
        val tried = mutableListOf<Pair<Int, Int>>()
        val result = compressForUpload { q, edge ->
            tried += q to edge
            ByteArray(if (tried.size < 3) Limits.IMAGE_BYTES + 1 else 1000)
        }
        assertEquals(1000, result?.size)
        assertEquals(listOf(85 to 2048, 75 to 1600, 65 to 1280), tried)
        assertNull(compressForUpload { _, _ -> ByteArray(Limits.IMAGE_BYTES + 1) })
        assertNull(compressForUpload { _, _ -> error("not an image") })
    }
}
