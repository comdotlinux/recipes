package com.linux.recipes.data

import com.linux.recipes.AppGraph
import com.linux.recipes.platform.Recognition
import com.linux.recipes.platform.TextRecognizer
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.io.files.Path

class ImportRepositoryTest {
    private val server = FakeServer()
    private val dir = tempDir()
    private var token: String? = "v1.user.secret.tag"
    private var unauthorizedCalls = 0

    /** What `GET /me` answers. */
    private var me = Api.apiJson.decodeFromString(Me.serializer(), fixture("me.json")).copy(consentVersion = 1)

    /** What `POST /imports` answers. */
    private var startAnswer: suspend MockRequestHandleScope.() -> HttpResponseData = { json(fixture("import_job_pending.json"), HttpStatusCode.Accepted) }

    /** `GET /jobs/:id` answers, one per poll; null means the network is down for that poll. */
    private val polls = ArrayDeque<(suspend MockRequestHandleScope.() -> HttpResponseData)?>()

    private var recognition: Recognition = Recognition.Unreadable
    private val recognized = mutableListOf<ByteArray>()
    private val recognizer = object : TextRecognizer {
        override suspend fun recognize(jpeg: ByteArray): Recognition = recognition.also { recognized += jpeg }
    }

    private val delays = mutableListOf<Long>()
    private val offlineWhileWaiting = mutableListOf<Boolean>()
    private lateinit var current: DefaultImportRepository

    private val jobId = "4c0e9a1b-7d2f-4e3a-9b8c-1f2e3d4c5b6a"
    private val jpeg = byteArrayOf(-1, -40, -1, 7, 7, 7)

    init {
        server.handler = { request -> route(request) }
    }

    private suspend fun MockRequestHandleScope.route(request: HttpRequestData): HttpResponseData = when (request.url.encodedPath) {
        "/v1/me" -> json(Api.apiJson.encodeToString(Me.serializer(), me))
        "/v1/imports" -> startAnswer()
        "/v1/jobs/$jobId" -> (polls.removeFirstOrNull() ?: throw IOException("network down"))()
        "/v1/reports" -> empty()
        "/v1/session" -> empty()
        else -> error(404, "not_found")
    }

    private fun consent() = DefaultConsentRepository(server.api { token }, currentToken = { token }) { unauthorizedCalls++ }

    private fun repository(consent: DefaultConsentRepository = consent()) = DefaultImportRepository(
        api = server.api { token },
        cache = RecipeCache(dir),
        consent = consent,
        recognizer = recognizer,
        currentToken = { token },
        delay = {
            delays += it
            offlineWhileWaiting += current.activeJob.value?.offline == true
        },
    ) { unauthorizedCalls++ }.also { current = it }

    private fun jobJson(status: String) =
        if (status == "done") fixture("import_job_done.json") else fixture("import_job_pending.json").replace("\"pending\"", "\"$status\"")

    private fun poll(status: String) = polls.addLast { json(jobJson(status)) }

    private fun importRequests() = server.requests.filter { it.url.encodedPath == "/v1/imports" }

    @Test
    fun noImportRouteIsCalledWithoutConsentForTheCurrentVersion() = runTest {
        me = me.copy(consentVersion = null)
        val consent = consent()
        val repo = repository(consent)
        assertEquals(ImportStart.ConsentRequired, repo.start(ImportSource.Link("https://example.org/a"), jobId))
        assertEquals(ImportStart.ConsentRequired, repo.start(ImportSource.Photo(jpeg), jobId))
        assertEquals(ConsentState.NotGiven, consent.state.value)
        assertTrue(recognized.isEmpty(), "the picture is not even read without consent")

        me = me.copy(consentVersion = 0) // an older notice
        assertEquals(ImportStart.ConsentRequired, repository().start(ImportSource.Link("https://example.org/a"), jobId))

        server.offline = true // consent cannot be read
        assertEquals(ImportStart.Offline, repository().start(ImportSource.Link("https://example.org/a"), jobId))

        assertEquals(emptyList(), importRequests())
        assertTrue(server.paths().all { it == "/v1/me" })
    }

    @Test
    fun consentGivenInThisSessionIsUsedWithoutAskingAgain() = runTest {
        val consent = consent()
        me = me.copy(consentVersion = null)
        val repo = repository(consent)
        server.handler = { request -> if (request.url.encodedPath == "/v1/me/consent") json("""{ "consentVersion": 1 }""") else route(request) }
        assertTrue(consent.accept())
        assertEquals(ConsentState.Given(1), consent.state.value)
        assertEquals(ImportStart.Started(jobId), repo.start(ImportSource.Link("https://example.org/a"), jobId))
        assertEquals(listOf("/v1/me/consent", "/v1/imports"), server.paths())
    }

    @Test
    fun everyStartResultCode() = runTest {
        // Status 0: the network is down.
        val cases = listOf(
            Triple(402, "quota_exhausted", ImportStart.QuotaExhausted),
            Triple(429, "attempt_limit", ImportStart.AttemptLimit),
            Triple(429, "rate_limited", ImportStart.Offline),
            Triple(409, "job_running", ImportStart.AlreadyRunning),
            Triple(400, "invalid_url", ImportStart.Rejected("invalid_url")),
            Triple(400, "invalid_body", ImportStart.Rejected("invalid_body")),
            Triple(413, "text_too_large", ImportStart.Rejected("text_too_large")),
            Triple(413, "image_too_large", ImportStart.Rejected("image_too_large")),
            Triple(415, "unsupported_media_type", ImportStart.Rejected("unsupported_media_type")),
            Triple(503, "unavailable", ImportStart.Offline),
            Triple(0, "", ImportStart.Offline),
            Triple(403, "consent_required", ImportStart.ConsentRequired),
        )
        for ((status, code, expected) in cases) {
            val consent = consent()
            val repo = repository(consent)
            startAnswer = { if (status == 0) throw IOException("network down") else error(status, code) }
            assertEquals(expected, repo.start(ImportSource.Link("https://example.org/a"), jobId), expected.toString())
            assertNull(repo.activeJob.value, "no job is tracked after $expected")
            if (expected == ImportStart.ConsentRequired) assertEquals(ConsentState.NotGiven, consent.state.value)
        }
        assertEquals(0, unauthorizedCalls)

        startAnswer = { error(401, "unauthenticated") }
        assertEquals(ImportStart.Rejected("unauthenticated"), repository().start(ImportSource.Link("https://example.org/a"), jobId))
        assertEquals(1, unauthorizedCalls)

        startAnswer = { json(fixture("import_job_pending.json"), HttpStatusCode.Accepted) }
        val repo = repository()
        assertEquals(ImportStart.Started(jobId), repo.start(ImportSource.Link("https://example.org/a"), jobId))
        assertEquals(ImportJobState(jobId, "text", null, false), repo.activeJob.value)
    }

    @Test
    fun photoWithEnoughReadableTextIsSentAsText() = runTest {
        val text = "Kartoffelsalat\n1 kg Kartoffeln\nKochen!!!" // 40 characters
        assertEquals(DefaultImportRepository.HANDOVER_MIN_CHARS, text.length)
        recognition = Recognition.Text("  $text \n")
        repository().start(ImportSource.Photo(jpeg), jobId)
        assertContentEquals(jpeg, recognized.single(), "the picture is read on the device first")
        val sent = importRequests().single()
        assertEquals("application/json", sent.body.contentType.toString())
        assertEquals(jsonOf(Api.apiJson.encodeToString(ImportRequest.serializer(), ImportRequest("text", text = text))), jsonOf(sent.bodyText()))
    }

    @Test
    fun photoWithTooLittleTextOrUnreadableIsSentAsJpeg() = runTest {
        for (result in listOf(Recognition.Text(" Kartoffelsalat\n1 kg Kartoffeln\nKochen!! ") /* 39 after trimming */, Recognition.Text("   "), Recognition.Unreadable)) {
            server.requests.clear()
            recognition = result
            repository().start(ImportSource.Photo(jpeg), jobId)
            val sent = importRequests().single()
            assertEquals("image/jpeg", sent.body.contentType.toString(), result.toString())
            assertContentEquals(jpeg, sent.bodyBytes())
            current.acknowledge(jobId)
        }
    }

    @Test
    fun linkIsSentAsUrlWithoutRecognition() = runTest {
        repository().start(ImportSource.Link("https://example.org/kartoffelsalat"), jobId)
        assertEquals(jsonOf(fixture("import_request_url.json")), jsonOf(importRequests().single().bodyText()))
        assertEquals(jobId, importRequests().single().headers["Idempotency-Key"])
        assertTrue(recognized.isEmpty())
    }

    @Test
    fun pollingEndsInEachOfTheFiveOutcomes() = runTest {
        val draft = Api.apiJson.decodeFromString(ImportJob.serializer(), fixture("import_job_done.json")).draft!!
        val expected = mapOf(
            "done" to ImportOutcome.Done(draft),
            "guard_block" to ImportOutcome.GuardBlocked,
            "no_recipe" to ImportOutcome.NoRecipe,
            "invalid" to ImportOutcome.Invalid,
            "failed" to ImportOutcome.Failed,
        )
        for ((status, outcome) in expected) {
            delays.clear()
            val repo = repository()
            repo.start(ImportSource.Link("https://example.org/a"), jobId)
            poll("pending")
            poll("running")
            poll(status)
            repo.resume()
            assertEquals(outcome, repo.activeJob.value?.outcome, status)
            assertEquals(listOf(2_000L, 2_000L), delays, "polls every 2 seconds until the outcome")
            assertTrue(polls.isEmpty())
            repo.acknowledge(jobId)
            assertNull(repo.activeJob.value)
        }
    }

    @Test
    fun offlineWhilePollingKeepsRetryingAndRecovers() = runTest {
        val repo = repository()
        repo.start(ImportSource.Link("https://example.org/a"), jobId)
        polls.addLast(null)
        polls.addLast { error(503, "unavailable") }
        poll("running")
        poll("done")
        repo.resume()
        assertEquals(listOf(true, true, false), offlineWhileWaiting)
        val job = repo.activeJob.value!!
        assertFalse(job.offline)
        assertTrue(job.outcome is ImportOutcome.Done)
    }

    @Test
    fun aJobThatIsGoneEndsAsFailed() = runTest {
        val repo = repository()
        repo.start(ImportSource.Link("https://example.org/a"), jobId)
        polls.addLast { error(404, "not_found") }
        repo.resume()
        assertEquals(ImportOutcome.Failed, repo.activeJob.value?.outcome)
    }

    @Test
    fun resumeWithoutAJobDoesNothing() = runTest {
        repository().resume()
        assertEquals(emptyList(), server.requests)
    }

    @Test
    fun theOpenJobSurvivesARestartAndASync() = runTest {
        repository().start(ImportSource.Link("https://example.org/a"), jobId)

        // A recipe sync writes its cursor to the same settings file.
        server.handler = { request ->
            if (request.url.encodedPath == "/v1/recipes") json(recipeListJson(7, recipe(ID_A, 7))) else route(request)
        }
        DefaultRecipeRepository(server.api { token }, RecipeCache(dir), currentToken = { token }) {}.sync()
        assertEquals(7, RecipeCache(dir).readSettings().cursor)

        val restarted = repository()
        assertEquals(ImportJobState(jobId, "text", null, false), restarted.activeJob.value)
        poll("done")
        restarted.resume()
        assertTrue(restarted.activeJob.value?.outcome is ImportOutcome.Done)

        restarted.acknowledge("11111111-2222-4333-8444-555555555555")
        assertEquals(jobId, repository().activeJob.value?.jobId, "acknowledging another job changes nothing")
        current.acknowledge(jobId)
        assertNull(repository().activeJob.value)
        assertEquals(7, RecipeCache(dir).readSettings().cursor)
    }

    @Test
    fun retryWithTheSameIdIsTheSameJob() = runTest {
        val repo = repository()
        startAnswer = { throw IOException("connection lost") }
        assertEquals(ImportStart.Offline, repo.start(ImportSource.Link("https://example.org/a"), jobId))
        assertNull(repo.activeJob.value)
        startAnswer = { json(fixture("import_job_pending.json"), HttpStatusCode.Accepted) }
        // The first request may have reached the server: it answers 202 with the same job again.
        assertEquals(ImportStart.Started(jobId), repo.start(ImportSource.Link("https://example.org/a"), jobId))
        assertEquals(ImportStart.Started(jobId), repo.start(ImportSource.Link("https://example.org/a"), jobId))
        assertEquals(listOf(jobId, jobId), importRequests().map { it.headers["Idempotency-Key"] })
        assertEquals(jobId, repo.activeJob.value?.jobId)
    }

    @Test
    fun signOutEndsTheJobLocally() = runTest {
        val repo = repository()
        repo.start(ImportSource.Link("https://example.org/a"), jobId)
        repo.clearLocal()
        assertNull(repo.activeJob.value)
        assertNull(repository().activeJob.value, "not restored after a restart either")
    }

    @Test
    fun aUnauthorizedPollEndsTheJob() = runTest {
        val repo = repository()
        repo.start(ImportSource.Link("https://example.org/a"), jobId)
        polls.addLast { error(401, "unauthenticated") }
        repo.resume()
        assertNull(repo.activeJob.value)
        assertEquals(1, unauthorizedCalls)
        assertNull(repository().activeJob.value)
    }

    @Test
    fun a401WithAnOldTokenKeepsTheNewSession() = runTest {
        val repo = repository()
        repo.start(ImportSource.Link("https://example.org/a"), jobId)
        polls.addLast {
            token = "v1.other.session.tag" // signed in again while the poll ran
            error(401, "unauthenticated")
        }
        repo.resume()
        assertEquals(0, unauthorizedCalls)
        assertEquals(jobId, repo.activeJob.value?.jobId)
    }

    @Test
    fun graphSignOutClearsTheJobAndTheConsent() = runTest {
        val filesDir = tempDir()
        val secure = MemorySecureStore()
        AppGraph.clearStaleTokenOnFirstLaunch(filesDir, secure)
        secure.put(TokenStore.KEY, "v1.user.secret.tag")
        secure.put(TokenStore.USER_ID_KEY, "user")
        val graph = AppGraph(filesDir.toString(), secure, FakeAuthProvider(), server.client, BASE, recognizer)
        assertEquals(ImportStart.Started(jobId), graph.imports.start(ImportSource.Photo(jpeg), jobId))
        assertEquals(ConsentState.Given(1), graph.consent.state.value)
        assertEquals(DefaultConsentRepository.AI_NOTICE_VERSION, graph.consent.currentVersion)

        graph.session.signOut()

        assertNull(graph.imports.activeJob.value)
        assertEquals(ConsentState.Unknown, graph.consent.state.value)
        assertEquals(emptyList(), filesBelow(Path(filesDir, AppGraph.CACHE_DIR)))
        assertTrue(server.requests.any { it.method == HttpMethod.Delete && it.url.encodedPath == "/v1/session" })
    }

    @Test
    fun aJobOpenOnTheServerIsAdoptedWheneverMeIsRead() = runTest {
        me = me.copy(openJobId = jobId)
        val repo = repository()
        repo.quota()
        assertEquals(ImportJobState(jobId, "", null, false), repo.activeJob.value, "kind unknown until the first poll")
        assertEquals(jobId, repository().activeJob.value?.jobId, "kept across a restart")

        poll("done")
        current.resume()
        assertEquals("url", current.activeJob.value?.kind)
        assertTrue(current.activeJob.value?.outcome is ImportOutcome.Done)

        // With a local job, another open id is not taken over.
        me = me.copy(openJobId = "11111111-2222-4333-8444-555555555555")
        current.quota()
        assertEquals(jobId, current.activeJob.value?.jobId)
    }

    @Test
    fun consentRefreshAlsoAdoptsTheOpenJob() = runTest {
        me = me.copy(openJobId = jobId)
        val consent = consent()
        val repo = repository(consent)
        assertTrue(consent.refresh())
        assertEquals(jobId, repo.activeJob.value?.jobId)
    }

    @Test
    fun alreadyRunningReadsMeOnceAndHoldsTheJob() = runTest {
        val consent = consent()
        val repo = repository(consent)
        assertTrue(consent.refresh()) // consent known, no open job yet
        me = me.copy(openJobId = jobId) // started on another device meanwhile
        startAnswer = { error(409, "job_running") }
        assertEquals(ImportStart.AlreadyRunning, repo.start(ImportSource.Link("https://example.org/a"), "11111111-2222-4333-8444-555555555555"))
        assertEquals(ImportJobState(jobId, "", null, false), repo.activeJob.value)
        assertEquals(listOf("/v1/me", "/v1/imports", "/v1/me"), server.paths())
    }

    @Test
    fun quotaComesFromMe() = runTest {
        me = me.copy(entitlement = Me.Entitlement(active = true), quota = Me.Quota(freeImportUsed = true, monthUsed = 3, monthLimit = 30))
        val consent = consent()
        assertEquals(Quota(freeImportUsed = true, monthUsed = 3, monthLimit = 30, entitled = true), repository(consent).quota())
        assertEquals(ConsentState.Given(1), consent.state.value, "the same answer updates the consent")

        me = Api.apiJson.decodeFromString(Me.serializer(), fixture("me.json"))
        assertEquals(Quota(freeImportUsed = false, monthUsed = 0, monthLimit = 30, entitled = false), current.quota())

        server.offline = true
        assertNull(current.quota())
    }

    @Test
    fun reportSendsTheContractBody() = runTest {
        val repo = repository()
        assertTrue(repo.report(jobId, ReportReason.WrongContent, ""))
        assertEquals(jsonOf(fixture("report.json")), jsonOf(server.requests.single().bodyText()))

        assertTrue(repo.report(jobId, ReportReason.Offensive, "rude"))
        assertTrue(repo.report(jobId, ReportReason.Other, "x"))
        assertEquals(listOf("offensive", "other"), server.requests.drop(1).map { Api.apiJson.decodeFromString(Report.serializer(), it.bodyText()).reason })

        server.handler = { error(404, "not_found") }
        assertFalse(repo.report(jobId, ReportReason.Other, ""))
        server.offline = true
        assertFalse(repo.report(jobId, ReportReason.Other, ""))
        server.offline = false
        server.handler = { error(401, "unauthenticated") }
        assertFalse(repo.report(jobId, ReportReason.Other, ""))
        assertEquals(1, unauthorizedCalls)
    }
}
