package com.linux.recipes.ui.translate

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.linux.recipes.data.ConsentRepository
import com.linux.recipes.data.ConsentState
import com.linux.recipes.data.ReportReason
import com.linux.recipes.data.Recipe
import com.linux.recipes.data.RecipeTranslation
import com.linux.recipes.data.TranslationRepository
import com.linux.recipes.data.TranslationStart
import com.linux.recipes.data.primaryLanguage
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/** Why a translation did not start; each has its own message on the detail screen. */
enum class TranslateProblem { QuotaExhausted, AttemptLimit, AlreadyRunning, Offline, Rejected }

/**
 * The language of one recipe's detail screen: the sheet, the shown language, starting a translation through
 * the AI notice and the paywall, and reporting a translation. The running job is only read from
 * [TranslationRepository.activeJob], the one open job shared with imports.
 */
class TranslateViewModel(
    val recipeId: String,
    private val translations: TranslationRepository,
    private val consent: ConsentRepository,
    private val newJobId: () -> String = { Uuid.random().toString() },
) : ViewModel() {
    val activeJob = translations.activeJob

    var sheetOpen by mutableStateOf(false)
        private set

    /** The chosen language, or null for the original. Shown once its translation is on the device. */
    var display by mutableStateOf(translations.displayLanguage(recipeId))
        private set

    /** The language waiting for the AI notice; non-null while the notice is shown. */
    var consentFor by mutableStateOf<String?>(null)
        private set

    /** The language that needs Recipes Pro; non-null while the paywall is shown. */
    var paywallFor by mutableStateOf<String?>(null)
        private set
    var problem by mutableStateOf<TranslateProblem?>(null)
        private set

    /** True from the tap until the server has answered the start. */
    var starting by mutableStateOf(false)
        private set

    /** Null: not reported yet; then whether the server took the report. */
    var reported by mutableStateOf<Boolean?>(null)
        private set

    private var retryLanguage: String? = null

    /** The Idempotency-Key of the current attempt and its language: kept after Offline, so a retry is never charged twice. */
    private var attempt: Pair<String, String>? = null
    private var polling: Job? = null

    fun openSheet() {
        sheetOpen = true
    }

    fun closeSheet() {
        sheetOpen = false
    }

    /** The translation shown for [recipe], or null when the original is shown. */
    fun shownTranslation(recipe: Recipe): RecipeTranslation? = display?.let { lang -> recipe.translations.firstOrNull { it.language == lang } }

    /** The original, or a translation on the original's quantities and units. */
    fun shown(recipe: Recipe): Recipe = shownTranslation(recipe)?.let { recipe.translatedTo(it) } ?: recipe

    /** The original and existing translations switch at once; a missing one is translated. */
    fun choose(recipe: Recipe, language: String) {
        sheetOpen = false
        when {
            language == primaryLanguage(recipe.language) -> show(null)
            recipe.translations.any { it.language == language } -> show(language)
            else -> translate(language)
        }
    }

    /** Also "translate again" for a translation that is out of date. Nothing is sent before the AI notice is accepted. */
    fun translate(language: String) {
        sheetOpen = false
        problem = null
        if (consent.state.value != ConsentState.Unknown) return decide(language)
        // Not loaded yet: ask the server first, so a user who already agreed is not shown the notice again.
        starting = true
        viewModelScope.launch {
            val loaded = consent.refresh()
            starting = false
            if (loaded) decide(language) else offline(language)
        }
    }

    private fun decide(language: String) {
        if (consent.state.value != ConsentState.Given(consent.currentVersion)) consentFor = language else start(language)
    }

    fun acceptConsent() {
        val language = consentFor ?: return
        consentFor = null
        starting = true
        viewModelScope.launch {
            if (consent.accept()) {
                start(language)
            } else {
                starting = false
                offline(language)
            }
        }
    }

    fun declineConsent() {
        consentFor = null
    }

    fun closePaywall() {
        paywallFor = null
    }

    /** Recipes Pro is active: the translation that needed it starts. */
    fun subscribed() {
        val language = paywallFor
        closePaywall()
        if (language != null) start(language)
    }

    fun retry() {
        val language = retryLanguage ?: return
        retryLanguage = null
        translate(language)
    }

    fun dismissProblem() {
        problem = null
        retryLanguage = null
    }

    fun acknowledge(jobId: String) = translations.acknowledge(jobId)

    /** Called when the detail screen appears: follows a job that is still open. */
    fun followOpenJob() {
        val job = activeJob.value ?: return
        if (job.outcome == null) poll()
    }

    fun jobIdFor(language: String): String? = translations.jobIdFor(recipeId, language)

    fun report(jobId: String, reason: ReportReason, note: String) {
        viewModelScope.launch { reported = translations.report(jobId, reason, note.trim()) }
    }

    private fun start(language: String) {
        val id = attempt?.takeIf { it.first == language }?.second ?: newJobId().also { attempt = language to it }
        starting = true
        viewModelScope.launch {
            val result = translations.start(recipeId, language, id)
            starting = false
            // Only an attempt that never reached the server keeps its id; any answer ends the attempt.
            if (result != TranslationStart.Offline) attempt = null
            when (result) {
                is TranslationStart.Started -> {
                    // Shown as soon as the result arrives with the next sync.
                    show(language)
                    poll()
                }
                TranslationStart.AlreadyRunning -> {
                    problem = TranslateProblem.AlreadyRunning
                    poll()
                }
                TranslationStart.ConsentRequired -> consentFor = language
                TranslationStart.SubscriptionRequired -> paywallFor = language
                TranslationStart.QuotaExhausted -> problem = TranslateProblem.QuotaExhausted
                TranslationStart.AttemptLimit -> problem = TranslateProblem.AttemptLimit
                TranslationStart.Offline -> offline(language)
                is TranslationStart.Rejected -> problem = TranslateProblem.Rejected
            }
        }
    }

    private fun offline(language: String) {
        retryLanguage = language
        problem = TranslateProblem.Offline
    }

    private fun show(language: String?) {
        display = language
        reported = null
        translations.setDisplayLanguage(recipeId, language)
    }

    private fun poll() {
        if (polling?.isActive == true) return
        polling = viewModelScope.launch { translations.resume() }
    }
}

/** [translation]'s text on this recipe; quantities and units stay those of the original, by contract. */
internal fun Recipe.translatedTo(translation: RecipeTranslation): Recipe = copy(
    title = translation.title,
    description = translation.description,
    ingredients = ingredients.mapIndexed { i, original ->
        translation.ingredients.getOrNull(i)?.let { original.copy(item = it.item, group = it.group) } ?: original
    },
    steps = steps.indices.map { translation.steps.getOrNull(it) ?: steps[it] },
    tags = tags.indices.map { translation.tags.getOrNull(it) ?: tags[it] },
)
