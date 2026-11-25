package com.linux.recipes.data

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

// Signatures fixed by docs/api.md, "Client, milestone M4". Change only together with that section.

/** One entry of `Recipe.translations`. Quantities and units are those of the original by contract, so none are here. */
@Serializable
data class RecipeTranslation(
    val language: String,
    val stale: Boolean = false,
    val title: String,
    val description: String = "",
    val ingredients: List<Ingredient> = emptyList(),
    val steps: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
)

/** `POST /recipes/:id/translations` body. */
@Serializable data class TranslationRequest(val language: String)

/** A translation job this device knows, kept in the cache settings so a report can name it. */
@Serializable data class TranslationJobRef(val jobId: String, val recipeId: String, val language: String)

/** [name] is the language's own name ("Deutsch", "Türkçe"): data shown as is, never translated. */
data class TranslationLanguage(val code: String, val name: String)

/** The ten languages the server translates into, in this order. */
val TranslationLanguages: List<TranslationLanguage> = listOf(
    TranslationLanguage("en", "English"),
    TranslationLanguage("de", "Deutsch"),
    TranslationLanguage("fr", "Français"),
    TranslationLanguage("es", "Español"),
    TranslationLanguage("it", "Italiano"),
    TranslationLanguage("pt", "Português"),
    TranslationLanguage("nl", "Nederlands"),
    TranslationLanguage("pl", "Polski"),
    TranslationLanguage("tr", "Türkçe"),
    TranslationLanguage("sv", "Svenska"),
)

/** The primary subtag of a BCP 47 tag, lowercased: "de-DE" is "de". */
fun primaryLanguage(tag: String): String = tag.substringBefore('-').substringBefore('_').lowercase()

sealed interface TranslationStart {
    data class Started(val jobId: String) : TranslationStart
    data object SubscriptionRequired : TranslationStart    // 402 subscription_required
    data object QuotaExhausted : TranslationStart          // 402 quota_exhausted
    data object ConsentRequired : TranslationStart         // 403 consent_required
    data object AlreadyRunning : TranslationStart          // 409 job_running; activeJob holds it
    data object AttemptLimit : TranslationStart            // 429 attempt_limit
    data object Offline : TranslationStart                 // no connection, a 5xx answer or 429 rate_limited
    data class Rejected(val code: String) : TranslationStart // invalid_body, not_found, ...
}

interface TranslationRepository {
    /** The one open job of either kind: the same flow as [ImportRepository.activeJob]. */
    val activeJob: StateFlow<ImportJobState?>

    /** `jobId` is the Idempotency-Key. The caller keeps it across retries after Offline, so nothing is charged twice. */
    suspend fun start(recipeId: String, language: String, jobId: String): TranslationStart

    /** Polls the open job like [ImportRepository.resume]; a finished translation triggers a recipe sync. */
    suspend fun resume()

    /** Clears `activeJob` after the user has dismissed a translation that did not work. */
    fun acknowledge(jobId: String)

    /** The language [recipeId] is shown in, or null for its original. Kept in the cache settings. */
    fun displayLanguage(recipeId: String): String?

    fun setDisplayLanguage(recipeId: String, language: String?)

    /** The job that made the translation of [recipeId] into [language], when this device knows it; null hides "Report". */
    fun jobIdFor(recipeId: String, language: String): String?

    /** `POST /reports` with kind `translation`. */
    suspend fun report(jobId: String, reason: ReportReason, note: String): Boolean
}
