package com.linux.recipes.ui.imports

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.linux.recipes.data.ConsentRepository
import com.linux.recipes.data.ConsentState
import com.linux.recipes.data.ImportRepository
import com.linux.recipes.data.ImportSource
import com.linux.recipes.data.ImportStart
import com.linux.recipes.data.LocalRead
import com.linux.recipes.data.Quota
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/** Why an import did not start; each has its own message in the status card. */
enum class StartProblem { Offline, AttemptLimit, InvalidUrl, Rejected, Picture }

/**
 * The import flow on the list screen: the sheet, reading a photo on the phone, the AI notice, starting a job,
 * the paywall and the problems that stop a start. The running job itself is only ever read from
 * [ImportRepository.activeJob]. A photo is read on the phone first, free and without consent; AI is only
 * used when the user asks for it ([tidyUp], [readWithAi]).
 */
class ImportViewModel(
    private val imports: ImportRepository,
    private val consent: ConsentRepository,
    private val newJobId: () -> String = { Uuid.random().toString() },
) : ViewModel() {
    val activeJob = imports.activeJob

    var sheetOpen by mutableStateOf(false)
        private set
    var link by mutableStateOf("")
    var quota by mutableStateOf<Quota?>(null)
        private set
    /** The source waiting for the AI notice; non-null while the notice is shown. */
    var consentFor by mutableStateOf<ImportSource?>(null)
        private set
    var paywallOpen by mutableStateOf(false)
        private set
    var problem by mutableStateOf<StartProblem?>(null)
        private set
    /** True from the tap until the server has answered the start. */
    var starting by mutableStateOf(false)
        private set
    /** True while the phone reads a photo. */
    var reading by mutableStateOf(false)
        private set
    /** A photo read on the phone, waiting for or open in the review editor; cleared when that editor closes. */
    var localDraft by mutableStateOf<LocalRead.Draft?>(null)
        private set
    /** A photo the phone could not read; non-null while the sheet that offers AI is shown. */
    var unreadable by mutableStateOf<ByteArray?>(null)
        private set

    private var retrySource: ImportSource? = null

    /** The Idempotency-Key of the current attempt: kept after Offline so a retry is never charged twice. */
    private var attemptId: String? = null
    private var polling: Job? = null

    fun openSheet() {
        problem = null
        sheetOpen = true
        loadQuota()
    }

    fun closeSheet() {
        sheetOpen = false
    }

    fun loadQuota() {
        viewModelScope.launch { quota = imports.quota() }
    }

    fun importLink() {
        val url = link.trim()
        if (url.isNotEmpty()) submit(ImportSource.Link(url))
    }

    /** Reads the photo on the phone: no AI notice, no consent and no quota on this path. */
    fun importPhoto(jpeg: ByteArray) {
        sheetOpen = false
        problem = null
        reading = true
        viewModelScope.launch {
            when (val read = imports.readOnDevice(jpeg)) {
                is LocalRead.Draft -> localDraft = read
                LocalRead.Unreadable -> unreadable = jpeg
            }
            reading = false
        }
    }

    /** The review editor of [localDraft] was left: saved or discarded. */
    fun closeLocalDraft() {
        localDraft = null
    }

    /** Discards [localDraft] and lets AI structure the text the phone read; the user confirmed leaving the editor. */
    fun tidyUp() {
        val text = localDraft?.text ?: return
        localDraft = null
        submit(ImportSource.Text(text))
    }

    /** Sends the photo the phone could not read to AI. */
    fun readWithAi() {
        val jpeg = unreadable ?: return
        unreadable = null
        submit(ImportSource.Photo(jpeg))
    }

    fun closeUnreadable() {
        unreadable = null
    }

    fun pictureFailed() {
        sheetOpen = false
        problem = StartProblem.Picture
    }

    /** Nothing is sent before the current AI notice is accepted. */
    private fun submit(source: ImportSource) {
        sheetOpen = false
        problem = null
        if (consent.state.value != ConsentState.Given(consent.currentVersion)) consentFor = source else start(source)
    }

    fun acceptConsent() {
        val source = consentFor ?: return
        consentFor = null
        starting = true
        viewModelScope.launch {
            if (consent.accept()) {
                start(source)
            } else {
                starting = false
                retrySource = source
                problem = StartProblem.Offline
            }
        }
    }

    fun declineConsent() {
        consentFor = null
    }

    fun retry() {
        val source = retrySource ?: return
        retrySource = null
        submit(source)
    }

    fun dismissProblem() {
        problem = null
        retrySource = null
    }

    fun closePaywall() {
        paywallOpen = false
    }

    fun acknowledge(jobId: String) = imports.acknowledge(jobId)

    /** Called when the list appears: follows a job that is still open, also one left from before a restart. */
    fun followOpenJob() {
        val job = activeJob.value ?: return
        if (job.outcome == null) poll()
    }

    private fun start(source: ImportSource) {
        val id = attemptId ?: newJobId().also { attemptId = it }
        starting = true
        viewModelScope.launch {
            val result = imports.start(source, id)
            starting = false
            // Only an attempt that never reached the server keeps its id; any answer ends the attempt.
            if (result != ImportStart.Offline) attemptId = null
            when (result) {
                is ImportStart.Started -> {
                    link = ""
                    poll()
                }
                ImportStart.AlreadyRunning -> poll()
                ImportStart.ConsentRequired -> consentFor = source
                ImportStart.QuotaExhausted -> paywallOpen = true
                ImportStart.AttemptLimit -> problem = StartProblem.AttemptLimit
                ImportStart.Offline -> {
                    retrySource = source
                    problem = StartProblem.Offline
                }
                is ImportStart.Rejected -> problem = if (result.code == "invalid_url") StartProblem.InvalidUrl else StartProblem.Rejected
            }
        }
    }

    private fun poll() {
        if (polling?.isActive == true) return
        polling = viewModelScope.launch { imports.resume() }
    }
}
