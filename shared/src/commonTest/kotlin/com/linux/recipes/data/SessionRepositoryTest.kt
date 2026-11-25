package com.linux.recipes.data

import com.linux.recipes.AppGraph
import com.linux.recipes.platform.AuthResult
import com.linux.recipes.platform.TurnstileGate
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path

class SessionRepositoryTest {
    private val server = FakeServer()
    private val secure = MemorySecureStore()
    private val tokens = TokenStore(secure)
    private val auth = FakeAuthProvider()
    private val gate = TurnstileGate()
    private var cleared = 0
    private val repo = DefaultSessionRepository(server.api { tokens.token }, auth, gate, tokens) { cleared++ }

    private val sessionToken = Api.apiJson.decodeFromString(SessionResponse.serializer(), fixture("session_response.json")).token
    private val userId = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_AbCdE"

    private fun serveSession(sessionStatus: Int = 200, sessionCode: String = "") {
        server.handler = { request ->
            when (request.url.encodedPath) {
                "/v1/session/nonce" -> json("""{ "nonce": "n1.server" }""")
                "/v1/session" -> if (request.method == HttpMethod.Delete) {
                    empty(204)
                } else if (sessionStatus == 200) {
                    json(fixture("session_response.json"))
                } else {
                    error(sessionStatus, sessionCode)
                }
                else -> error(404, "not_found")
            }
        }
    }

    /** Runs sign-in and answers the Turnstile check with [turnstileToken] once the flow asks for it. */
    private suspend fun TestScope.signIn(turnstileToken: String? = "ts-token"): SignInResult {
        val result = async { repo.signIn(ageConfirmed = true) }
        if (auth.result is AuthResult.Success) {
            gate.pending.first { it }
            gate.deliver(turnstileToken)
        }
        return result.await()
    }

    @Test
    fun successfulSignInStoresTheTokenAndSendsTheContractRequest() = runTest {
        serveSession()
        assertEquals(SignInResult.Success, signIn())

        assertEquals(SessionState.SignedIn(userId), repo.state.value)
        assertEquals(sessionToken, secure.values["session_token"])
        assertEquals(userId, secure.values["session_user_id"], "the opaque token is never parsed for the user id")
        assertEquals(listOf("n1.server"), auth.nonces, "the platform sign-in gets the server nonce")
        val body = server.requests.single { it.url.encodedPath == "/v1/session" }.bodyText()
        assertEquals(
            jsonOf("""{"provider":"apple","idToken":"id-token","nonce":"n1.server","turnstileToken":"ts-token","ageConfirmed":true,"appleAuthCode":"auth-code"}"""),
            jsonOf(body),
        )
        assertEquals(listOf("/v1/session/nonce", "/v1/session"), server.paths())
        assertEquals(false, gate.pending.value)
    }

    @Test
    fun ageNotConfirmedNeverCallsTheServer() = runTest {
        assertEquals(SignInResult.Failed(SignInResult.Failed.Reason.AgeNotConfirmed), repo.signIn(ageConfirmed = false))
        assertEquals(emptyList(), server.requests)
    }

    @Test
    fun cancelledOrFailedPlatformSignIn() = runTest {
        serveSession()
        auth.result = AuthResult.Cancelled
        assertEquals(SignInResult.Cancelled, signIn())
        auth.result = AuthResult.Failed("no account")
        assertEquals(SignInResult.Failed(SignInResult.Failed.Reason.Rejected), signIn())
        assertEquals(SessionState.SignedOut, repo.state.value)
    }

    @Test
    fun closingTheTurnstileCheckCancels() = runTest {
        serveSession()
        assertEquals(SignInResult.Cancelled, signIn(turnstileToken = null))
        assertTrue(server.requests.none { it.url.encodedPath == "/v1/session" })
    }

    @Test
    fun aTurnstilePageThatCannotBeShownFailsTheSignIn() = runTest {
        serveSession()
        val result = async { repo.signIn(true) }
        gate.pending.first { it }
        gate.fail()
        assertEquals(SignInResult.Failed(SignInResult.Failed.Reason.Rejected), result.await())
        assertTrue(server.requests.none { it.url.encodedPath == "/v1/session" })
    }

    @Test
    fun serverErrorsMapToReasons() = runTest {
        mapOf(
            (400 to "age_confirmation_required") to SignInResult.Failed.Reason.AgeNotConfirmed,
            (400 to "invalid_body") to SignInResult.Failed.Reason.Rejected,
            (401 to "invalid_turnstile") to SignInResult.Failed.Reason.Rejected,
            (401 to "invalid_nonce") to SignInResult.Failed.Reason.Rejected,
            (401 to "invalid_token") to SignInResult.Failed.Reason.Rejected,
            (409 to "account_deleting") to SignInResult.Failed.Reason.AccountDeleting,
            (429 to "rate_limited") to SignInResult.Failed.Reason.RateLimited,
            (411 to "length_required") to SignInResult.Failed.Reason.Rejected,
            (500 to "internal") to SignInResult.Failed.Reason.Network,
            (502 to "bad_gateway") to SignInResult.Failed.Reason.Network,
            (503 to "provider_unavailable") to SignInResult.Failed.Reason.Network,
        ).forEach { (error, reason) ->
            serveSession(error.first, error.second)
            assertEquals(SignInResult.Failed(reason), signIn(), "${error.second}")
        }
        assertNull(secure.values["session_token"])
    }

    @Test
    fun aTokenWithoutItsUserIdIsClearedAtStart() {
        secure.put("session_token", sessionToken) // the user id failed to decrypt and reads as absent
        val repo = DefaultSessionRepository(server.api(), auth, gate, TokenStore(secure)) {}
        assertEquals(SessionState.SignedOut, repo.state.value)
        assertEquals(emptyMap(), secure.values, "the token is not kept and sent while signed out")
    }

    @Test
    fun signInWipesAnEarlierUsersCacheAndSyncsFromZero() = runTest {
        val filesDir = tempDir()
        AppGraph.clearStaleTokenOnFirstLaunch(filesDir, secure)
        // An earlier user's data survived (for example a failed wipe), with no session.
        RecipeCache(Path(filesDir, AppGraph.CACHE_DIR)).apply {
            writeRecipes(listOf(recipe(ID_B, 50, imageIds = listOf(IMG_2))))
            writeSettings(RecipeCache.Settings(cursor = 50))
            writeImage(IMG_2, byteArrayOf(2))
        }
        val graph = AppGraph(filesDir = filesDir.toString(), secureStore = secure, authProvider = auth, httpClient = server.client, baseUrl = BASE)
        server.handler = { request ->
            when (request.url.encodedPath) {
                "/v1/session/nonce" -> json("""{ "nonce": "n" }""")
                "/v1/session" -> json(fixture("session_response.json"))
                else -> json(recipeListJson(1, recipe(ID_A, 1)))
            }
        }
        val signIn = async { graph.session.signIn(true) }
        graph.turnstile.pending.first { it }
        graph.turnstile.deliver("ts")
        assertEquals(SignInResult.Success, signIn.await())
        assertEquals(emptyList(), graph.recipes.recipes.value, "the earlier user's recipes are gone at sign-in")

        graph.recipes.sync()

        assertEquals("/v1/recipes?since=0", server.paths().last())
        assertEquals(listOf(ID_A), graph.recipes.recipes.value.map { it.id })
        assertNull(graph.recipes.imagePath(IMG_2))
    }

    @Test
    fun nonceFailures() = runTest {
        server.handler = { error(429, "rate_limited") }
        assertEquals(SignInResult.Failed(SignInResult.Failed.Reason.RateLimited), repo.signIn(true))
        server.offline = true
        assertEquals(SignInResult.Failed(SignInResult.Failed.Reason.Network), repo.signIn(true))
        assertEquals(emptyList(), auth.nonces)
    }

    @Test
    fun offlineSessionCall() = runTest {
        serveSession()
        val result = async { repo.signIn(true) }
        gate.pending.first { it }
        server.offline = true
        gate.deliver("ts")
        assertEquals(SignInResult.Failed(SignInResult.Failed.Reason.Network), result.await())
    }

    @Test
    fun signOutRevokesAndWipesEvenWhenOffline() = runTest {
        serveSession()
        signIn()
        repo.signOut()
        val revoke = server.requests.last()
        assertEquals(HttpMethod.Delete, revoke.method)
        assertEquals("Bearer $sessionToken", revoke.headers[HttpHeaders.Authorization])
        assertEquals(SessionState.SignedOut, repo.state.value)
        assertEquals(emptyMap(), secure.values)
        assertEquals(2, cleared, "wiped at sign-in and at sign-out")

        signIn()
        server.offline = true
        repo.signOut()
        assertEquals(emptyMap(), secure.values)
        assertEquals(4, cleared)
    }

    private fun storeSession() {
        secure.put("session_token", sessionToken)
        secure.put("session_user_id", userId)
    }

    @Test
    fun storedSessionIsRestoredOnlyWithBothValues() {
        storeSession()
        assertEquals(SessionState.SignedIn(userId), DefaultSessionRepository(server.api(), auth, gate, TokenStore(secure)) {}.state.value)
        secure.remove("session_user_id")
        assertEquals(SessionState.SignedOut, DefaultSessionRepository(server.api(), auth, gate, TokenStore(secure)) {}.state.value)
    }

    @Test
    fun ac9SignOutLeavesNoCacheFilePictureOrToken() = runTest {
        val filesDir = tempDir()
        val graph = AppGraph(filesDir = filesDir.toString(), secureStore = secure, authProvider = auth, httpClient = server.client, baseUrl = BASE)
        server.handler = { request ->
            when (request.url.encodedPath) {
                "/v1/session/nonce" -> json("""{ "nonce": "n" }""")
                "/v1/session" -> if (request.method == HttpMethod.Delete) empty(204) else json(fixture("session_response.json"))
                "/v1/recipes" -> json(recipeListJson(1, recipe(ID_A, 1, imageIds = listOf(IMG_1))))
                else -> respond(byteArrayOf(1, 2))
            }
        }
        val signIn = async { graph.session.signIn(true) }
        graph.turnstile.pending.first { it }
        graph.turnstile.deliver("ts")
        assertEquals(SignInResult.Success, signIn.await())
        assertTrue(graph.recipes.sync())
        assertTrue(filesBelow(Path(filesDir, AppGraph.CACHE_DIR)).isNotEmpty())
        assertTrue(graph.recipes.imagePath(IMG_1) != null)

        graph.session.signOut()

        assertEquals(emptyList(), filesBelow(Path(filesDir, AppGraph.CACHE_DIR)))
        assertEquals(listOf(AppGraph.INSTALL_MARKER), filesBelow(filesDir).map { it.name }, "only the empty install marker remains")
        assertEquals(emptyMap(), secure.values)
        assertEquals(emptyList(), graph.recipes.recipes.value)
        assertNull(graph.recipes.imagePath(IMG_1))
    }

    @Test
    fun firstLaunchAfterInstallStartsSignedOutLaterLaunchesKeepTheSession() {
        val filesDir = tempDir()
        storeSession() // left in the Keychain by a deleted installation
        val first = AppGraph(filesDir = filesDir.toString(), secureStore = secure, authProvider = auth, httpClient = server.client, baseUrl = BASE)
        assertEquals(SessionState.SignedOut, first.session.state.value)
        assertEquals(emptyMap(), secure.values)
        assertTrue(kotlinx.io.files.SystemFileSystem.exists(Path(filesDir, AppGraph.INSTALL_MARKER)))

        storeSession() // signed in during that first run
        val second = AppGraph(filesDir = filesDir.toString(), secureStore = secure, authProvider = auth, httpClient = server.client, baseUrl = BASE)
        assertEquals(SessionState.SignedIn(userId), second.session.state.value)
    }

    @Test
    fun graphEndsTheSessionWhenTheServerAnswers401() = runTest {
        val filesDir = tempDir()
        AppGraph.clearStaleTokenOnFirstLaunch(filesDir, secure)
        storeSession()
        val graph = AppGraph(filesDir = filesDir.toString(), secureStore = secure, authProvider = auth, httpClient = server.client, baseUrl = BASE)
        assertEquals(SessionState.SignedIn(userId), graph.session.state.value)
        server.handler = { request -> if (request.method == HttpMethod.Delete) empty(204) else error(401, "unauthenticated") }

        graph.recipes.sync()

        assertEquals(SessionState.SignedOut, graph.session.state.value)
        assertEquals(emptyMap(), secure.values)
    }
}
