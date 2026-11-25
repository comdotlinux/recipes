package com.linux.recipes.ui

import com.linux.recipes.data.ImportJobState
import com.linux.recipes.data.Ingredient
import com.linux.recipes.data.RecipeTranslation
import com.linux.recipes.data.ReportReason
import com.linux.recipes.data.TranslationRepository
import com.linux.recipes.data.TranslationStart
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Answers [start] with [startResult]; [TranslationStart.Started] opens a translation job in [activeJob], as the
 * real repository does. Tests finish it by changing [activeJob] and the recipe themselves.
 */
class FakeTranslationRepository(job: ImportJobState? = null) : TranslationRepository {
    override val activeJob = MutableStateFlow(job)
    var startResult: TranslationStart = TranslationStart.Started("")
    var reportResult = true
    val starts = mutableListOf<Triple<String, String, String>>()
    val acknowledged = mutableListOf<String>()
    val reports = mutableListOf<Triple<String, ReportReason, String>>()
    val displayLanguages = mutableMapOf<String, String>()
    /** Job ids by recipe id and language, as the cache settings keep them. */
    val jobIds = mutableMapOf<Pair<String, String>, String>()
    var resumeCalls = 0

    override suspend fun start(recipeId: String, language: String, jobId: String): TranslationStart {
        starts += Triple(recipeId, language, jobId)
        val result = startResult
        if (result is TranslationStart.Started) {
            activeJob.value = ImportJobState(jobId, "translation", null, offline = false, recipeId = recipeId, language = language)
            jobIds[recipeId to language] = jobId
        }
        return result
    }

    override suspend fun resume() {
        resumeCalls++
    }

    override fun acknowledge(jobId: String) {
        acknowledged += jobId
        if (activeJob.value?.jobId == jobId) activeJob.value = null
    }

    override fun displayLanguage(recipeId: String): String? = displayLanguages[recipeId]

    override fun setDisplayLanguage(recipeId: String, language: String?) {
        if (language == null) displayLanguages.remove(recipeId) else displayLanguages[recipeId] = language
    }

    override fun jobIdFor(recipeId: String, language: String): String? = jobIds[recipeId to language]

    override suspend fun report(jobId: String, reason: ReportReason, note: String): Boolean {
        reports += Triple(jobId, reason, note)
        return reportResult
    }
}

val potatoSaladEnglish = RecipeTranslation(
    language = "en",
    title = "Potato salad",
    description = "Warm potatoes in broth and vinegar.",
    ingredients = listOf(
        Ingredient("1", "kg", "Waxy potatoes"),
        Ingredient("1", "", "Red onion"),
        Ingredient("250", "ml", "Hot beef broth", group = "Dressing"),
        Ingredient("4", "tbsp", "White wine vinegar", group = "Dressing"),
        Ingredient("1", "tsp", "Mustard", group = "Dressing"),
    ),
    steps = listOf("Boil the potatoes.", "Peel and slice them.", "Pour over the dressing."),
    tags = listOf("Side dish", "Summer"),
)

val potatoSaladFrench = RecipeTranslation(
    language = "fr",
    stale = true,
    title = "Salade de pommes de terre",
    ingredients = potatoSaladEnglish.ingredients.map { it.copy(item = "pommes de terre") },
    steps = listOf("Cuire.", "Couper.", "Assaisonner."),
    tags = listOf("Accompagnement", "Été"),
)

/** The German original with an English translation and an out-of-date French one. */
val translatedSalad = potatoSalad.copy(imageIds = emptyList(), translations = listOf(potatoSaladEnglish, potatoSaladFrench))
