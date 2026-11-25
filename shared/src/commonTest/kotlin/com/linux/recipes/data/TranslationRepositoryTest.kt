package com.linux.recipes.data

import com.linux.recipes.AppGraph
import com.linux.recipes.platform.Recognition
import com.linux.recipes.platform.TextRecognizer
import com.linux.recipes.ui.theme.Appearance
import com.linux.recipes.ui.theme.Palettes
import com.linux.recipes.ui.theme.ThemeMode
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

class TranslationRepositoryTest {
    private val server = FakeServer()
    private val dir = tempDir()
    private var token: String? = "v1.user.secret.tag"
    private var unauthorizedCalls = 0
    private var syncs = 0

    private var me = Api.apiJson.decodeFromString(Me.serializer(), fixture("me.json")).copy(consentVersion = 1)
    private var startAnswer: suspend MockRequestHandleScope.() -> HttpResponseData =
        { json(fixture("translation_job_pending.json"), HttpStatusCode.Accepted) }

    /** `GET /jobs/:id` answers, one per poll; none left means the network is down for that poll. */
    private val polls = ArrayDeque<suspend MockRequestHandleScope.() -> HttpResponseData>()

    private val jobId = "6d1f2a3b-4c5d-4e6f-8a9b-0c1d2e3f4a5b"
    private val recipeId = "0b9f6c1e-3a52-4d0f-9a43-2f1d0c6b7e11"
    private val recognizer = object : TextRecognizer {
        override suspend fun recognize(jpeg: ByteArray): Recognition = Recognition.Unreadable
    }

    init {
        server.handler = { request -> route(request) }
    }

    private suspend fun MockRequestHandleScope.route(request: HttpRequestData): HttpResponseData = when (request.url.encodedPath) {
        "/v1/me" -> json(Api.apiJson.encodeToString(Me.serializer(), me))
        "/v1/recipes/$recipeId/translations" -> startAnswer()
        "/v1/jobs/$jobId" -> (polls.removeFirstOrNull() ?: throw IOException("network down"))()
        "/v1/recipes" -> json("""{ "recipes": [${fixture("recipe_translated.json")}], "rev": 8, "more": false }""")
        "/v1/reports", "/v1/session" -> empty()
        else -> error(404, "not_found")
    }

    private fun jobJson(status: String) = fixture("translation_job_pending.json").replace("\"pending\"", "\"$status\"")

    private fun poll(status: String) = polls.addLast { json(jobJson(status)) }

    private class Repos(val imports: DefaultImportRepository, val translations: DefaultTranslationRepository, val consent: DefaultConsentRepository)

    private fun repos(onTranslated: suspend () -> Unit = { syncs++ }): Repos {
        val api = server.api { token }
        val cache = RecipeCache(dir)
        val consent = DefaultConsentRepository(api, currentToken = { token }) { unauthorizedCalls++ }
        val imports = DefaultImportRepository(api, cache, consent, recognizer, currentToken = { token }, delay = {}, onTranslated = onTranslated) {
            unauthorizedCalls++
        }
        return Repos(imports, DefaultTranslationRepository(api, cache, consent, imports, currentToken = { token }), consent)
    }

    private fun translationRequests() = server.requests.filter { it.url.encodedPath.endsWith("/translations") }

    @Test
    fun startSendsTheKeyAndLanguageAndOpensTheSharedJob() = runTest {
        val r = repos()
        assertEquals(TranslationStart.Started(jobId), r.translations.start(recipeId, "en", jobId))
        val request = translationRequests().single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals(jobId, request.headers[Api.IDEMPOTENCY_KEY])
        assertEquals(jsonOf(fixture("translation_request.json")), jsonOf(request.bodyText()))
        val expected = ImportJobState(jobId, "translation", null, offline = false, recipeId = recipeId, language = "en")
        assertEquals(expected, r.translations.activeJob.value)
        assertEquals(expected, r.imports.activeJob.value, "one open job for both kinds")
        assertEquals(jobId, r.translations.jobIdFor(recipeId, "en"))
        assertEquals(expected, repos().imports.activeJob.value, "kept across a restart with its recipe and language")

        // The same key again (after Offline) is the same job: nothing more is sent.
        assertEquals(TranslationStart.Started(jobId), r.translations.start(recipeId, "en", jobId))
        assertEquals(1, translationRequests().size)
    }

    @Test
    fun everyStartResultCode() = runTest {
        // Status 0: the network is down.
        val cases = listOf(
            Triple(402, "subscription_required", TranslationStart.SubscriptionRequired),
            Triple(402, "quota_exhausted", TranslationStart.QuotaExhausted),
            Triple(403, "consent_required", TranslationStart.ConsentRequired),
            Triple(409, "job_running", TranslationStart.AlreadyRunning),
            Triple(429, "attempt_limit", TranslationStart.AttemptLimit),
            Triple(429, "rate_limited", TranslationStart.Offline),
            Triple(503, "unavailable", TranslationStart.Offline),
            Triple(0, "", TranslationStart.Offline),
            Triple(400, "invalid_body", TranslationStart.Rejected("invalid_body")),
            Triple(404, "not_found", TranslationStart.Rejected("not_found")),
            Triple(411, "length_required", TranslationStart.Rejected("length_required")),
        )
        for ((status, code, expected) in cases) {
            val r = repos()
            startAnswer = { if (status == 0) throw IOException("network down") else error(status, code) }
            assertEquals(expected, r.translations.start(recipeId, "en", jobId), expected.toString())
            assertNull(r.translations.activeJob.value, "no job is tracked after $expected")
            if (expected == TranslationStart.ConsentRequired) assertEquals(ConsentState.NotGiven, r.consent.state.value)
        }
        assertEquals(0, unauthorizedCalls)
    }

    @Test
    fun alreadyRunningAdoptsTheOpenJobFromMe() = runTest {
        val r = repos()
        startAnswer = { error(409, "job_running") }
        me = me.copy(openJobId = jobId)
        assertEquals(TranslationStart.AlreadyRunning, r.translations.start(recipeId, "en", "11111111-1111-4111-8111-111111111111"))
        assertEquals(jobId, r.translations.activeJob.value?.jobId)
    }

    @Test
    fun consentIsEnforcedBeforeAnyTranslationRequest() = runTest {
        me = me.copy(consentVersion = null)
        val r = repos()
        assertEquals(TranslationStart.ConsentRequired, r.translations.start(recipeId, "en", jobId))
        me = me.copy(consentVersion = 0)
        assertEquals(TranslationStart.ConsentRequired, repos().translations.start(recipeId, "en", jobId))
        server.offline = true
        assertEquals(TranslationStart.Offline, repos().translations.start(recipeId, "en", jobId))
        assertTrue(translationRequests().isEmpty())
    }

    @Test
    fun aFinishedTranslationSyncsTheRecipeAndClosesTheJob() = runTest {
        val api = server.api { token }
        val cache = RecipeCache(dir)
        val recipes = DefaultRecipeRepository(api, cache, currentToken = { token }) { unauthorizedCalls++ }
        val r = repos(onTranslated = { recipes.sync() })
        r.translations.start(recipeId, "en", jobId)
        poll("running")
        poll("done")
        r.translations.resume()

        assertNull(r.translations.activeJob.value, "nothing to review: the job is acknowledged")
        assertEquals(listOf("/v1/recipes/$recipeId/translations", "/v1/jobs/$jobId", "/v1/jobs/$jobId", "/v1/recipes?since=0"), server.paths().filter { it != "/v1/me" })
        val translation = recipes.recipes.value.single().translations.single()
        assertEquals("Potato salad", translation.title)
        assertEquals(jobId, r.translations.jobIdFor(recipeId, "en"), "still known for a report")
        assertNull(repos().imports.activeJob.value, "and not resumed after a restart")
    }

    @Test
    fun aFailedTranslationStaysUntilAcknowledged() = runTest {
        val r = repos()
        r.translations.start(recipeId, "en", jobId)
        poll("guard_block")
        r.translations.resume()
        assertEquals(ImportOutcome.GuardBlocked, r.translations.activeJob.value?.outcome)
        assertEquals(0, syncs)
        r.translations.acknowledge(jobId)
        assertNull(r.translations.activeJob.value)
    }

    @Test
    fun aJobAdoptedFromAnotherDeviceLearnsItsKindAndTargetFromThePoll() = runTest {
        me = me.copy(openJobId = jobId)
        val r = repos()
        r.imports.quota()
        poll("running")
        poll("done")
        r.imports.resume()
        assertEquals(1, syncs)
        assertEquals(jobId, r.translations.jobIdFor(recipeId, "en"))
    }

    @Test
    fun translationsDecodeWithTheStaleFlagAndARecipeWithoutThemIsUnchanged() {
        val translated = Api.apiJson.decodeFromString(Recipe.serializer(), fixture("recipe_translated.json"))
        assertEquals(listOf("en"), translated.translations.map { it.language })
        assertEquals(false, translated.translations.single().stale)
        val stale = Api.apiJson.decodeFromString(Recipe.serializer(), fixture("recipe_translated.json").replace("\"stale\": false", "\"stale\": true"))
        assertTrue(stale.translations.single().stale)
        assertEquals(jsonOf(fixture("recipe_translated.json")), jsonOf(Api.apiJson.encodeToString(Recipe.serializer(), translated)))

        val plain = Api.apiJson.decodeFromString(Recipe.serializer(), fixture("recipe.json"))
        assertEquals(emptyList(), plain.translations)
        assertTrue("translations" !in Api.apiJson.encodeToString(Recipe.serializer(), plain))

        val cache = RecipeCache(dir)
        cache.writeRecipes(listOf(translated, stale.copy(id = ID_B)))
        assertEquals(listOf(translated, stale.copy(id = ID_B)), cache.readRecipes(), "the cache keeps them")
    }

    @Test
    fun theDisplayLanguageIsRememberedPerRecipe() {
        val r = repos()
        assertNull(r.translations.displayLanguage(recipeId))
        r.translations.setDisplayLanguage(recipeId, "en")
        r.translations.setDisplayLanguage(ID_B, "fr")
        assertEquals("en", repos().translations.displayLanguage(recipeId), "kept across a restart")
        assertEquals("fr", repos().translations.displayLanguage(ID_B))
        r.translations.setDisplayLanguage(recipeId, null)
        assertNull(repos().translations.displayLanguage(recipeId))
        assertEquals("fr", repos().translations.displayLanguage(ID_B))
    }

    @Test
    fun aReportNamesTheTranslationJob() = runTest {
        val r = repos()
        assertTrue(r.translations.report(jobId, ReportReason.Offensive, "rude"))
        val body = server.requests.single { it.url.encodedPath == "/v1/reports" }.bodyText()
        assertEquals(jsonOf("""{ "kind": "translation", "jobId": "$jobId", "reason": "offensive", "note": "rude" }"""), jsonOf(body))

        server.handler = { error(401, "unauthenticated") }
        assertTrue(!r.translations.report(jobId, ReportReason.Other, ""))
        assertEquals(1, unauthorizedCalls)
    }

    @Test
    fun a401OnStartEndsTheSession() = runTest {
        startAnswer = { error(401, "unauthenticated") }
        assertEquals(TranslationStart.Rejected("unauthenticated"), repos().translations.start(recipeId, "en", jobId))
        assertEquals(1, unauthorizedCalls)
    }

    @Test
    fun theAppearanceSurvivesSignOutAndARestartWhileAccountDataIsWiped() = runTest {
        val filesDir = Path(dir, "files")
        val secure = MemorySecureStore()
        AppGraph.clearStaleTokenOnFirstLaunch(filesDir, secure)
        secure.put(TokenStore.KEY, "v1.user.secret.tag")
        secure.put(TokenStore.USER_ID_KEY, "user")
        val graph = AppGraph(filesDir.toString(), secure, FakeAuthProvider(), server.client, BASE, recognizer)
        assertEquals(Appearance(), graph.appearance.state.value)
        graph.appearance.set(Appearance(Palettes.Charcoal.id, ThemeMode.Dark))
        graph.translations.setDisplayLanguage(recipeId, "en")
        assertEquals(TranslationStart.Started(jobId), graph.translations.start(recipeId, "en", jobId))

        graph.session.signOut()

        assertEquals(Appearance(Palettes.Charcoal.id, ThemeMode.Dark), graph.appearance.state.value)
        assertNull(graph.translations.activeJob.value)
        assertEquals(emptyList(), filesBelow(Path(filesDir, AppGraph.CACHE_DIR)), "the account cache is wiped")
        assertTrue(SystemFileSystem.exists(Path(filesDir, AppGraph.APPEARANCE_FILE)))

        val restarted = AppGraph(filesDir.toString(), secure, FakeAuthProvider(), server.client, BASE, recognizer)
        assertEquals(Appearance(Palettes.Charcoal.id, ThemeMode.Dark), restarted.appearance.state.value)
        assertNull(restarted.translations.displayLanguage(recipeId))
        assertNull(restarted.translations.jobIdFor(recipeId, "en"))
    }
}
