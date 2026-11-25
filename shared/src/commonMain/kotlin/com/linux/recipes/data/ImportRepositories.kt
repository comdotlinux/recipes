package com.linux.recipes.data

import kotlinx.coroutines.flow.StateFlow

// Signatures fixed by docs/api.md, "Client, milestone M2". Change only together with that section.

sealed interface ImportSource {
    data class Link(val url: String) : ImportSource

    /** Text already read on the phone; the server only structures it (kind `text`). */
    data class Text(val text: String) : ImportSource

    /** A picture the phone could not read, JPEG of at most 1.5 MB, longest side 2,048 px; the server reads and structures it (kind `photo`). */
    class Photo(val jpeg: ByteArray) : ImportSource
}

/** A photo read on the phone: a draft and the recognised text it came from, or nothing usable. */
sealed interface LocalRead {
    data class Draft(val draft: RecipeInput, val text: String) : LocalRead
    data object Unreadable : LocalRead
}

sealed interface ImportStart {
    data class Started(val jobId: String) : ImportStart
    data object ConsentRequired : ImportStart            // 403 consent_required
    data object QuotaExhausted : ImportStart             // 402 quota_exhausted
    data object AttemptLimit : ImportStart               // 429 attempt_limit
    data object AlreadyRunning : ImportStart             // 409 job_running; activeJob holds it
    data object Offline : ImportStart                    // no connection or a 5xx answer
    data class Rejected(val code: String) : ImportStart  // invalid_url, text_too_large, image_too_large, ...
}

sealed interface ImportOutcome {
    data class Done(val draft: RecipeInput) : ImportOutcome
    data object GuardBlocked : ImportOutcome
    data object NoRecipe : ImportOutcome
    data object Invalid : ImportOutcome
    data object Failed : ImportOutcome
}

/** The one open job. It survives leaving the screen and an app restart (the job id is kept in the cache settings). */
data class ImportJobState(val jobId: String, val kind: String, val outcome: ImportOutcome?, val offline: Boolean)

/** [entitledUntil]: the end of the subscription (ISO 8601) while [entitled], from `entitlement.expiresAt`. */
data class Quota(val freeImportUsed: Boolean, val monthUsed: Int, val monthLimit: Int, val entitled: Boolean, val entitledUntil: String? = null)

enum class ReportReason { WrongContent, Offensive, Other }

interface ImportRepository {
    val activeJob: StateFlow<ImportJobState?>

    /** Reads [jpeg] with the on-device recognizer and parser. Sends nothing; needs no consent and no session. */
    suspend fun readOnDevice(jpeg: ByteArray): LocalRead

    /** `jobId` is the Idempotency-Key. The caller keeps it across retries after Offline, so nothing is charged twice. */
    suspend fun start(source: ImportSource, jobId: String): ImportStart

    /** Polls `GET /jobs/:id` every 2 seconds into `activeJob` until an outcome arrives; keeps retrying while offline. */
    suspend fun resume()

    /** Clears `activeJob` after the user has opened or dismissed the outcome. */
    fun acknowledge(jobId: String)

    /** From `GET /me`; null when offline. */
    suspend fun quota(): Quota?

    suspend fun report(jobId: String, reason: ReportReason, note: String): Boolean
}

sealed interface ConsentState {
    data object Unknown : ConsentState
    data object NotGiven : ConsentState
    data class Given(val version: Int) : ConsentState
}

interface ConsentRepository {
    val state: StateFlow<ConsentState>

    /** The notice version this build shows. */
    val currentVersion: Int

    /** Loads [state] from `GET /me`. False when offline; the state then stays as it was. */
    suspend fun refresh(): Boolean

    /** `PUT /me/consent` */
    suspend fun accept(): Boolean

    /** `DELETE /me/consent` */
    suspend fun withdraw(): Boolean
}
