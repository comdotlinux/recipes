package com.linux.recipes.data

import com.linux.recipes.platform.Recognition
import com.linux.recipes.platform.TextRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.uuid.Uuid

/**
 * [ImportRepository] over [Api] (docs/api.md, "Imports"). The open job id and kind are kept in the cache
 * settings, so [activeJob] is restored after an app restart and [resume] polls it again.
 *
 * Nothing is sent to an import route unless [consent] is given for its current version. On a 401 with the
 * current token, the job ends locally and [onUnauthorized] runs; sign-out calls [clearLocal]. A 5xx answer
 * counts as "server not reachable right now", as in [DefaultRecipeRepository].
 */
class DefaultImportRepository(
    private val api: Api,
    private val cache: RecipeCache,
    private val consent: DefaultConsentRepository,
    private val recognizer: TextRecognizer,
    private val currentToken: () -> String? = { null },
    private val delay: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    private val onUnauthorized: suspend () -> Unit,
) : ImportRepository {
    private var generation = 0
    private val _activeJob = MutableStateFlow(
        cache.readSettings().let { s -> s.importJobId?.let { ImportJobState(it, s.importJobKind.orEmpty(), null, false) } },
    )
    override val activeJob: StateFlow<ImportJobState?> = _activeJob.asStateFlow()

    init {
        // Every `GET /me`, wherever it is read, may name a job started on another device or before a reinstall.
        consent.onMe = { adopt(it.openJobId) }
    }

    override suspend fun start(source: ImportSource, jobId: String): ImportStart {
        // The same key again after Offline is the same job: the server answers 202 with it and charges nothing.
        _activeJob.value?.takeIf { it.jobId == jobId }?.let { return ImportStart.Started(jobId) }
        val token = currentToken()
        val started = generation
        if (consent.state.value == ConsentState.Unknown && consent.loadMe() == null) return ImportStart.Offline
        if (!consent.isGiven) return ImportStart.ConsentRequired
        val result = when (source) {
            is ImportSource.Link -> api.startImport(jobId, ImportRequest(kind = "url", url = source.url))
            is ImportSource.Text -> api.startImport(jobId, ImportRequest(kind = "text", text = source.text))
            is ImportSource.Photo -> api.startPhotoImport(jobId, source.jpeg)
        }
        return when (result) {
            is ApiResult.Ok -> {
                // Signed out while the request ran: the job belongs to the old session.
                if (started == generation) track(result.value)
                ImportStart.Started(result.value.id)
            }
            ApiResult.Offline -> ImportStart.Offline
            is ApiResult.Failure -> when {
                result.status == 401 -> ImportStart.Rejected(result.wire).also { unauthorized(token) }
                // 5xx and rate_limited: try again later with the same id, as M1 does for saves.
                result.status >= 500 || result.code == ErrorCode.RateLimited -> ImportStart.Offline
                result.code == ErrorCode.ConsentRequired -> ImportStart.ConsentRequired.also { consent.markNotGiven() }
                result.code == ErrorCode.QuotaExhausted -> ImportStart.QuotaExhausted
                result.code == ErrorCode.AttemptLimit -> ImportStart.AttemptLimit
                // The open job is named in `GET /me`; loadMe() adopts it through onMe.
                result.code == ErrorCode.JobRunning -> ImportStart.AlreadyRunning.also { if (_activeJob.value == null) consent.loadMe() }
                // invalid_url, text_too_large, image_too_large, unsupported_media_type, ...
                else -> ImportStart.Rejected(result.wire)
            }
        }
    }

    override suspend fun readOnDevice(jpeg: ByteArray): LocalRead =
        handOverText(recognizer.recognize(jpeg))?.let { LocalRead.Draft(parseRecipeText(it, Uuid.random().toString()), it) }
            ?: LocalRead.Unreadable

    override suspend fun resume() {
        while (true) {
            val job = _activeJob.value ?: return
            if (job.outcome != null) return
            val token = currentToken()
            val next = when (val result = api.job(job.jobId)) {
                is ApiResult.Ok -> job.copy(kind = result.value.kind, outcome = outcomeOf(result.value), offline = false)
                ApiResult.Offline -> job.copy(offline = true)
                is ApiResult.Failure -> when {
                    result.status == 401 -> return unauthorized(token)
                    // Unknown or older than 7 days: the result is gone, nothing more will come.
                    result.status == 404 -> job.copy(outcome = ImportOutcome.Failed, offline = false)
                    else -> job.copy(offline = true)
                }
            }
            // Only the job that was polled: acknowledge or sign-out may have cleared it meanwhile.
            _activeJob.update { if (it?.jobId == job.jobId) next else it }
            if (next.outcome != null) return
            delay(POLL_INTERVAL_MS)
        }
    }

    override fun acknowledge(jobId: String) {
        if (_activeJob.value?.jobId != jobId) return
        _activeJob.value = null
        cache.updateSettings { it.copy(importJobId = null, importJobKind = null) }
    }

    override suspend fun quota(): Quota? = consent.loadMe()?.let {
        Quota(it.quota.freeImportUsed, it.quota.monthUsed, it.quota.monthLimit, entitled = it.entitlement.active)
    }

    override suspend fun report(jobId: String, reason: ReportReason, note: String): Boolean {
        val token = currentToken()
        val wire = when (reason) {
            ReportReason.WrongContent -> "wrong_content"
            ReportReason.Offensive -> "offensive"
            ReportReason.Other -> "other"
        }
        val result = api.report(Report(kind = "import_result", jobId = jobId, reason = wire, note = note))
        if (result is ApiResult.Failure && result.status == 401) unauthorized(token)
        return result is ApiResult.Ok
    }

    /** Ends the open job locally: on sign-out and on a 401. */
    fun clearLocal() {
        generation++
        _activeJob.value = null
        if (cache.readSettings().importJobId != null) cache.updateSettings { it.copy(importJobId = null, importJobKind = null) }
    }

    /** A job open on the server while none is active here; its kind is known after the first poll. */
    private fun adopt(openJobId: String?) {
        if (openJobId == null || _activeJob.value != null) return
        _activeJob.value = ImportJobState(openJobId, "", null, offline = false)
        cache.updateSettings { it.copy(importJobId = openJobId, importJobKind = null) }
    }

    private fun track(job: ImportJob) {
        _activeJob.value = ImportJobState(job.id, job.kind, outcomeOf(job), offline = false)
        cache.updateSettings { it.copy(importJobId = job.id, importJobKind = job.kind) }
    }

    private suspend fun unauthorized(usedToken: String?) {
        if (usedToken != null && usedToken == currentToken()) {
            clearLocal()
            onUnauthorized()
        }
    }

    companion object {
        const val POLL_INTERVAL_MS = 2_000L

        /**
         * Recognised text shorter than this many characters (after trimming) is not trusted: the photo counts
         * as unreadable on the phone. A starting value: the OCR bake-off sets the final one.
         */
        const val HANDOVER_MIN_CHARS = 40

        /** The trimmed text to build a draft from, or null when the photo counts as unreadable. */
        fun handOverText(recognition: Recognition): String? =
            (recognition as? Recognition.Text)?.text?.trim()?.takeIf { it.length >= HANDOVER_MIN_CHARS }

        /** Null while the job is pending or running. A status this build does not know ends the job as failed. */
        fun outcomeOf(job: ImportJob): ImportOutcome? = when (job.status) {
            "pending", "running" -> null
            "done" -> job.draft?.let { ImportOutcome.Done(it) } ?: ImportOutcome.Invalid
            "guard_block" -> ImportOutcome.GuardBlocked
            "no_recipe" -> ImportOutcome.NoRecipe
            "invalid" -> ImportOutcome.Invalid
            else -> ImportOutcome.Failed
        }
    }
}
