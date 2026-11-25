package com.linux.recipes.data

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * A recipe as the server returns it (docs/api.md, "Recipes"). A deletion marker carries only
 * `id`, `rev`, `deleted = true` and `updatedAt`, so every content field has a default.
 */
@Serializable
data class Recipe(
    val id: String,
    val rev: Long,
    val deleted: Boolean = false,
    val updatedAt: String,
    val title: String = "",
    val description: String = "",
    val servings: Int? = null,
    val prepMinutes: Int? = null,
    val cookMinutes: Int? = null,
    val ingredients: List<Ingredient> = emptyList(),
    val steps: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val sourceUrl: String? = null,
    /** "manual" | "photo" | "url" | "shared" */
    val sourceType: String = "manual",
    /** BCP 47 tag. */
    val language: String = "",
    val imageIds: List<String> = emptyList(),
    /** Milestone M4, server to app only: absent when the recipe has no translation, so it is never written as `[]`. */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val translations: List<RecipeTranslation> = emptyList(),
)

/** What the client sends: a [Recipe] without `rev`, `deleted` and `updatedAt`. */
@Serializable
data class RecipeInput(
    val id: String,
    val title: String,
    val description: String = "",
    val servings: Int? = null,
    val prepMinutes: Int? = null,
    val cookMinutes: Int? = null,
    val ingredients: List<Ingredient> = emptyList(),
    val steps: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val sourceUrl: String? = null,
    val sourceType: String = "manual",
    val language: String,
    val imageIds: List<String> = emptyList(),
)

@Serializable
data class Ingredient(
    val quantity: String = "",
    val unit: String = "",
    val item: String,
    val group: String? = null,
)

fun Recipe.toInput(): RecipeInput = RecipeInput(
    id = id,
    title = title,
    description = description,
    servings = servings,
    prepMinutes = prepMinutes,
    cookMinutes = cookMinutes,
    ingredients = ingredients,
    steps = steps,
    tags = tags,
    sourceUrl = sourceUrl,
    sourceType = sourceType,
    language = language,
    imageIds = imageIds,
)
