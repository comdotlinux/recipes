package com.linux.recipes.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ConsentRepositoryTest {
    private val server = FakeServer()
    private var token: String? = "v1.user.secret.tag"
    private var unauthorizedCalls = 0
    private val repo = DefaultConsentRepository(server.api { token }, currentToken = { token }) { unauthorizedCalls++ }
    private val me = Api.apiJson.decodeFromString(Me.serializer(), fixture("me.json"))

    @Test
    fun stateComesFromMe() = runTest {
        assertEquals(ConsentState.Unknown, repo.state.value)
        server.handler = { json(fixture("me.json")) }
        assertTrue(repo.refresh())
        assertEquals(ConsentState.NotGiven, repo.state.value)
        assertFalse(repo.isGiven)

        server.handler = { json(Api.apiJson.encodeToString(Me.serializer(), me.copy(consentVersion = 1))) }
        assertTrue(repo.refresh())
        assertEquals(ConsentState.Given(1), repo.state.value)
        assertTrue(repo.isGiven)

        server.offline = true
        assertFalse(repo.refresh())
        assertEquals(ConsentState.Given(1), repo.state.value, "offline keeps the last known state")
    }

    @Test
    fun acceptSendsTheCurrentVersion() = runTest {
        server.handler = { json("""{ "consentVersion": 1 }""") }
        assertTrue(repo.accept())
        assertEquals(jsonOf(fixture("consent_request.json")), jsonOf(server.requests.single().bodyText()))
        assertEquals(ConsentState.Given(repo.currentVersion), repo.state.value)
    }

    @Test
    fun acceptFailures() = runTest {
        server.handler = { error(400, "consent_version_unknown") }
        assertFalse(repo.accept())
        server.offline = true
        assertFalse(repo.accept())
        assertEquals(ConsentState.Unknown, repo.state.value)
        assertEquals(0, unauthorizedCalls)
    }

    @Test
    fun withdraw() = runTest {
        server.handler = { json("""{ "consentVersion": 1 }""") }
        repo.accept()
        server.handler = { empty() }
        assertTrue(repo.withdraw())
        assertEquals(ConsentState.NotGiven, repo.state.value)
        server.offline = true
        assertFalse(repo.withdraw())
    }

    @Test
    fun unauthorizedEndsTheSessionOnlyForTheCurrentToken() = runTest {
        server.handler = { error(401, "unauthenticated") }
        assertFalse(repo.accept())
        assertFalse(repo.withdraw())
        assertFalse(repo.refresh())
        assertEquals(3, unauthorizedCalls)

        server.handler = {
            token = "v1.newer.session.tag"
            error(401, "unauthenticated")
        }
        assertFalse(repo.accept())
        assertEquals(3, unauthorizedCalls)
    }

    @Test
    fun clearLocalForgetsTheState() = runTest {
        server.handler = { json("""{ "consentVersion": 1 }""") }
        repo.accept()
        repo.clearLocal()
        assertEquals(ConsentState.Unknown, repo.state.value)
    }
}
