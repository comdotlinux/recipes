package com.linux.recipes.ui.edit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.linux.recipes.data.Ingredient
import com.linux.recipes.data.Recipe
import com.linux.recipes.data.RecipeInput

/** Limits from docs/api.md, "Recipes". */
object Limits {
    const val TITLE = 200
    const val DESCRIPTION = 2000
    const val SERVINGS_MIN = 1
    const val SERVINGS_MAX = 100
    const val MINUTES_MAX = 10000
    const val INGREDIENTS = 100
    const val QUANTITY = 40
    const val UNIT = 40
    const val ITEM = 200
    const val GROUP = 80
    const val STEPS = 100
    const val STEP = 4000
    const val TAGS = 20
    const val TAG = 40
    const val SOURCE_URL = 2048
    const val LANGUAGE_MIN = 2
    const val LANGUAGE_MAX = 35
    const val IMAGES = 5
    /** 1.5 MB, the server's per-image limit. */
    const val IMAGE_BYTES = 1_572_864
}

sealed interface FieldError {
    data object Required : FieldError
    data class TooLong(val max: Int) : FieldError
    data class Range(val min: Int, val max: Int) : FieldError
    data class TooMany(val max: Int) : FieldError
    data object DuplicateTag : FieldError
    data object Language : FieldError
}

private var nextKey = 0L
private fun key() = ++nextKey

/** One editor row: an ingredient, or a section heading that sets the group of the rows below it. */
class IngredientRow(val isGroup: Boolean, quantity: String = "", unit: String = "", item: String = "") {
    val key = key()
    var quantity by mutableStateOf(quantity)
    var unit by mutableStateOf(unit)
    /** The ingredient, or the section name for a group row. */
    var item by mutableStateOf(item)
    val isBlank get() = quantity.isBlank() && unit.isBlank() && item.isBlank()
}

class StepRow(text: String = "") {
    val key = key()
    var text by mutableStateOf(text)
}

/** A picture in the form: already on the server (bytes null) or newly chosen (compressed JPEG bytes). */
class FormImage(val id: String, val bytes: ByteArray?)

/** Field keys used in [RecipeForm.validate]'s result. */
object Fields {
    const val TITLE = "title"
    const val DESCRIPTION = "description"
    const val SERVINGS = "servings"
    const val PREP = "prep"
    const val COOK = "cook"
    const val INGREDIENTS = "ingredients"
    const val STEPS = "steps"
    const val TAGS = "tags"
    const val TAG_DRAFT = "tagDraft"
    const val SOURCE = "source"
    const val LANGUAGE = "language"
    fun quantity(row: IngredientRow) = "quantity.${row.key}"
    fun unit(row: IngredientRow) = "unit.${row.key}"
    fun item(row: IngredientRow) = "item.${row.key}"
    fun step(row: StepRow) = "step.${row.key}"
}

private val LANGUAGE_TAG = Regex("^[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*$")

/** The editable state of one recipe. Compose snapshot state, so the screen recomposes on change. */
class RecipeForm(val id: String, defaultLanguage: String) {
    var title by mutableStateOf("")
    var description by mutableStateOf("")
    var servings by mutableStateOf("")
    var prep by mutableStateOf("")
    var cook by mutableStateOf("")
    var sourceUrl by mutableStateOf("")
    var language by mutableStateOf(defaultLanguage)
    var sourceType = "manual"
        private set
    var tagDraft by mutableStateOf("")
    val ingredients = mutableStateListOf<IngredientRow>()
    val steps = mutableStateListOf<StepRow>()
    val tags = mutableStateListOf<String>()
    val images = mutableStateListOf<FormImage>()
    private var baseline = snapshot()

    fun load(recipe: Recipe) {
        title = recipe.title
        description = recipe.description
        servings = recipe.servings?.toString().orEmpty()
        prep = recipe.prepMinutes?.toString().orEmpty()
        cook = recipe.cookMinutes?.toString().orEmpty()
        sourceUrl = recipe.sourceUrl.orEmpty()
        language = recipe.language
        sourceType = recipe.sourceType
        tagDraft = ""
        ingredients.clear()
        var group: String? = null
        recipe.ingredients.forEach {
            val g = it.group?.ifBlank { null }
            if (g != group && g != null) ingredients += IngredientRow(isGroup = true, item = g)
            group = g
            ingredients += IngredientRow(isGroup = false, quantity = it.quantity, unit = it.unit, item = it.item)
        }
        steps.clear()
        recipe.steps.forEach { steps += StepRow(it) }
        tags.clear()
        tags += recipe.tags
        images.clear()
        recipe.imageIds.forEach { images += FormImage(it, null) }
        baseline = snapshot()
    }

    /** True when anything differs from the loaded (or empty) recipe; blank rows count as a change. */
    val isDirty get() = snapshot() != baseline

    private fun snapshot(): List<Any?> = listOf(
        title, description, servings, prep, cook, sourceUrl, language, tagDraft,
        ingredients.map { listOf(it.isGroup, it.quantity, it.unit, it.item) },
        steps.map { it.text }, tags.toList(), images.map { it.id },
    )

    fun <T> move(list: MutableList<T>, index: Int, by: Int) {
        val to = index + by
        if (index !in list.indices || to !in list.indices) return
        list.add(to, list.removeAt(index))
    }

    /** Adds the drafted tag; returns the error that stopped it, if any. */
    fun addTag(): FieldError? {
        val tag = tagDraft.trim()
        val error = when {
            tag.isEmpty() -> FieldError.Required
            tag.length > Limits.TAG -> FieldError.TooLong(Limits.TAG)
            tags.any { it.equals(tag, ignoreCase = true) } -> FieldError.DuplicateTag
            tags.size >= Limits.TAGS -> FieldError.TooMany(Limits.TAGS)
            else -> null
        }
        if (error == null) {
            tags += tag
            tagDraft = ""
        }
        return error
    }

    fun validate(): Map<String, FieldError> {
        val e = linkedMapOf<String, FieldError>()
        fun length(key: String, value: String, max: Int, required: Boolean = false) {
            val v = value.trim()
            if (required && v.isEmpty()) e[key] = FieldError.Required
            else if (v.length > max) e[key] = FieldError.TooLong(max)
        }
        fun number(key: String, value: String, min: Int, max: Int) {
            val v = value.trim()
            if (v.isNotEmpty() && v.toIntOrNull()?.let { it in min..max } != true) e[key] = FieldError.Range(min, max)
        }
        length(Fields.TITLE, title, Limits.TITLE, required = true)
        length(Fields.DESCRIPTION, description, Limits.DESCRIPTION)
        number(Fields.SERVINGS, servings, Limits.SERVINGS_MIN, Limits.SERVINGS_MAX)
        number(Fields.PREP, prep, 0, Limits.MINUTES_MAX)
        number(Fields.COOK, cook, 0, Limits.MINUTES_MAX)
        ingredients.filterNot { it.isBlank }.forEach { row ->
            if (row.isGroup) {
                length(Fields.item(row), row.item, Limits.GROUP)
            } else {
                length(Fields.quantity(row), row.quantity, Limits.QUANTITY)
                length(Fields.unit(row), row.unit, Limits.UNIT)
                length(Fields.item(row), row.item, Limits.ITEM, required = true)
            }
        }
        if (ingredients.count { !it.isGroup && !it.isBlank } > Limits.INGREDIENTS) {
            e[Fields.INGREDIENTS] = FieldError.TooMany(Limits.INGREDIENTS)
        }
        steps.forEach { length(Fields.step(it), it.text, Limits.STEP) }
        if (steps.count { it.text.isNotBlank() } > Limits.STEPS) e[Fields.STEPS] = FieldError.TooMany(Limits.STEPS)
        if (tags.size > Limits.TAGS) e[Fields.TAGS] = FieldError.TooMany(Limits.TAGS)
        if (tagDraft.trim().length > Limits.TAG) e[Fields.TAG_DRAFT] = FieldError.TooLong(Limits.TAG)
        length(Fields.SOURCE, sourceUrl, Limits.SOURCE_URL)
        val lang = language.trim()
        if (lang.length !in Limits.LANGUAGE_MIN..Limits.LANGUAGE_MAX || !LANGUAGE_TAG.matches(lang)) {
            e[Fields.LANGUAGE] = FieldError.Language
        }
        return e
    }

    /** The form as the API's [RecipeInput]; blank rows are dropped, a pending tag draft is kept. */
    fun toInput(): RecipeInput {
        var group: String? = null
        val ingredientList = buildList {
            ingredients.forEach { row ->
                if (row.isGroup) {
                    group = row.item.trim().ifEmpty { null }
                } else if (!row.isBlank) {
                    add(Ingredient(row.quantity.trim(), row.unit.trim(), row.item.trim(), group))
                }
            }
        }
        val draft = tagDraft.trim()
        return RecipeInput(
            id = id,
            title = title.trim(),
            description = description.trim(),
            servings = servings.trim().toIntOrNull(),
            prepMinutes = prep.trim().toIntOrNull(),
            cookMinutes = cook.trim().toIntOrNull(),
            ingredients = ingredientList,
            steps = steps.map { it.text.trim() }.filter { it.isNotEmpty() },
            tags = if (draft.isNotEmpty() && tags.none { it.equals(draft, ignoreCase = true) }) tags + draft else tags.toList(),
            sourceUrl = sourceUrl.trim().ifEmpty { null },
            sourceType = sourceType,
            language = language.trim(),
            imageIds = images.map { it.id },
        )
    }
}
