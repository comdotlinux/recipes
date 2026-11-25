package com.linux.recipes.data

import kotlinx.coroutines.flow.StateFlow

/**
 * [TranslationRepository] over [Api] (docs/api.md, "Translations"). It owns no job state: a started translation
 * becomes the one open job of [imports], whose polling, restart handling and sign-out apply unchanged.
 * Nothing is sent before [consent] is given for its current version: the same AI consent as for imports.
 */
class DefaultTranslationRepository(
    private val api: Api,
    private val cache: RecipeCache,
    private val consent: DefaultConsentRepository,
    private val imports: DefaultImportRepository,
    private val currentToken: () -> String? = { null },
) : TranslationRepository {
    override val activeJob: StateFlow<ImportJobState?> = imports.activeJob

    override suspend fun start(recipeId: String, language: String, jobId: String): TranslationStart {
        // The same key again after Offline is the same job: the server answers 202 with it and charges nothing.
        activeJob.value?.takeIf { it.jobId == jobId }?.let { return TranslationStart.Started(jobId) }
        val token = currentToken()
        val started = imports.generation
        if (consent.state.value == ConsentState.Unknown && consent.loadMe() == null) return TranslationStart.Offline
        if (!consent.isGiven) return TranslationStart.ConsentRequired
        return when (val result = api.startTranslation(recipeId, jobId, language)) {
            is ApiResult.Ok -> {
                // Signed out while the request ran: the job belongs to the old session.
                if (started == imports.generation) imports.track(result.value)
                TranslationStart.Started(result.value.id)
            }
            ApiResult.Offline -> TranslationStart.Offline
            is ApiResult.Failure -> when {
                result.status == 401 -> TranslationStart.Rejected(result.wire).also { imports.unauthorized(token) }
                result.status >= 500 || result.code == ErrorCode.RateLimited -> TranslationStart.Offline
                result.code == ErrorCode.ConsentRequired -> TranslationStart.ConsentRequired.also { consent.markNotGiven() }
                result.code == ErrorCode.SubscriptionRequired -> TranslationStart.SubscriptionRequired
                result.code == ErrorCode.QuotaExhausted -> TranslationStart.QuotaExhausted
                result.code == ErrorCode.AttemptLimit -> TranslationStart.AttemptLimit
                // The open job is named in `GET /me`; loadMe() adopts it through the import repository.
                result.code == ErrorCode.JobRunning ->
                    TranslationStart.AlreadyRunning.also { if (activeJob.value == null) consent.loadMe() }
                // invalid_body (not one of the ten languages), not_found (deleted meanwhile), ...
                else -> TranslationStart.Rejected(result.wire)
            }
        }
    }

    override suspend fun resume() = imports.resume()

    override fun acknowledge(jobId: String) = imports.acknowledge(jobId)

    override fun displayLanguage(recipeId: String): String? = cache.readSettings().displayLanguages[recipeId]

    override fun setDisplayLanguage(recipeId: String, language: String?) {
        if (displayLanguage(recipeId) == language) return
        cache.updateSettings { s ->
            s.copy(displayLanguages = if (language == null) s.displayLanguages - recipeId else s.displayLanguages + (recipeId to language))
        }
    }

    override fun jobIdFor(recipeId: String, language: String): String? =
        cache.readSettings().translationJobs.lastOrNull { it.recipeId == recipeId && it.language == language }?.jobId

    override suspend fun report(jobId: String, reason: ReportReason, note: String): Boolean {
        val token = currentToken()
        val wire = when (reason) {
            ReportReason.WrongContent -> "wrong_content"
            ReportReason.Offensive -> "offensive"
            ReportReason.Other -> "other"
        }
        val result = api.report(Report(kind = DefaultImportRepository.TRANSLATION, jobId = jobId, reason = wire, note = note))
        if (result is ApiResult.Failure && result.status == 401) imports.unauthorized(token)
        return result is ApiResult.Ok
    }
}
