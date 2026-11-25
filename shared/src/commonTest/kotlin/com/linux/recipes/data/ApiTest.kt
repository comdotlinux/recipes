package com.linux.recipes.data

import io.ktor.http.HttpHeaders
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class ApiTest {
    private val server = FakeServer()
    private val api = server.api()

    private fun assertFailure(result: ApiResult<*>, status: Int, code: ErrorCode) {
        assertIs<ApiResult.Failure>(result)
        assertEquals(status, result.status)
        assertEquals(code, result.code)
        assertEquals(code.wire, result.wire)
    }

    @Test
    fun nonce() = runTest {
        server.handler = { json("""{ "nonce": "n1.abc" }""") }
        assertEquals(ApiResult.Ok(NonceResponse("n1.abc")), api.nonce())
        val request = server.requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("$BASE/v1/session/nonce", request.url.toString())
        assertNull(request.headers[HttpHeaders.Authorization], "nonce is unauthenticated")

        server.handler = { error(429, "rate_limited") }
        assertFailure(api.nonce(), 429, ErrorCode.RateLimited)
    }

    @Test
    fun createSessionSendsTheContractBody() = runTest {
        server.handler = { json(fixture("session_response.json")) }
        val request = Api.apiJson.decodeFromString(SessionRequest.serializer(), fixture("session_request.json"))
        val result = api.createSession(request)
        assertEquals(Api.apiJson.decodeFromString(SessionResponse.serializer(), fixture("session_response.json")), (result as ApiResult.Ok).value)
        assertEquals(jsonOf(fixture("session_request.json")), jsonOf(server.requests.single().bodyText()))
        assertEquals("application/json", server.requests.single().body.contentType.toString())
    }

    @Test
    fun createSessionErrors() = runTest {
        val request = SessionRequest("google", "t", "n", "ts", true)
        listOf(
            400 to ErrorCode.InvalidBody,
            400 to ErrorCode.AgeConfirmationRequired,
            401 to ErrorCode.InvalidTurnstile,
            401 to ErrorCode.InvalidNonce,
            401 to ErrorCode.InvalidToken,
            409 to ErrorCode.AccountDeleting,
            429 to ErrorCode.RateLimited,
        ).forEach { (status, code) ->
            server.handler = { error(status, code.wire) }
            assertFailure(api.createSession(request), status, code)
        }
    }

    @Test
    fun createSessionWithoutAppleCodeOmitsTheField() = runTest {
        server.handler = { json(fixture("session_response.json")) }
        api.createSession(SessionRequest("google", "t", "n", "ts", true))
        assertEquals(
            jsonOf("""{"provider":"google","idToken":"t","nonce":"n","turnstileToken":"ts","ageConfirmed":true}"""),
            jsonOf(server.requests.single().bodyText()),
        )
    }

    @Test
    fun deleteSessionAndMe() = runTest {
        server.handler = { empty(204) }
        assertEquals(ApiResult.Ok(Unit), api.deleteSession())
        assertEquals(HttpMethod.Delete, server.requests.last().method)
        assertEquals("Bearer v1.user.secret.tag", server.requests.last().headers[HttpHeaders.Authorization])

        server.handler = { json(fixture("me.json")) }
        val me = (api.me() as ApiResult.Ok).value
        assertEquals("AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_AbCdE", me.userId)
        assertEquals(52_428_800, me.storage.capBytes)
        assertEquals(30, me.quota.monthLimit)
        assertNull(me.consentVersion)

        server.handler = { error(401, "unauthenticated") }
        assertFailure(api.me(), 401, ErrorCode.Unauthenticated)
        assertFailure(api.deleteSession(), 401, ErrorCode.Unauthenticated)
    }

    @Test
    fun recipesListAndCursorExpiry() = runTest {
        server.handler = { json(fixture("recipes_list.json")) }
        val list = (api.recipes(since = 5) as ApiResult.Ok).value
        assertEquals("$BASE/v1/recipes?since=5", server.requests.single().url.toString())
        assertEquals(8, list.rev)
        assertEquals(listOf(false, true), list.recipes.map { it.deleted })
        assertEquals("Kartoffelsalat", list.recipes[0].title)
        assertEquals(Ingredient("1", "kg", "Kartoffeln", null), list.recipes[0].ingredients.single())

        server.handler = { error(410, "cursor_expired") }
        assertFailure(api.recipes(since = 1), 410, ErrorCode.CursorExpired)
    }

    @Test
    fun putRecipeCreateAndUpdatePreconditions() = runTest {
        val input = Api.apiJson.decodeFromString(RecipeInput.serializer(), fixture("recipe_input.json"))
        server.handler = { json(fixture("recipe.json")) }

        val created = api.putRecipe(input, expectedRev = null)
        assertEquals(input.id, (created as ApiResult.Ok).value.id)
        val create = server.requests.last()
        assertEquals(HttpMethod.Put, create.method)
        assertEquals("$BASE/v1/recipes/${input.id}", create.url.toString())
        assertEquals("*", create.headers[HttpHeaders.IfNoneMatch])
        assertNull(create.headers[HttpHeaders.IfMatch])
        assertEquals(jsonOf(fixture("recipe_input.json")), jsonOf(create.bodyText()))

        api.putRecipe(input, expectedRev = 7)
        val update = server.requests.last()
        assertEquals("\"7\"", update.headers[HttpHeaders.IfMatch])
        assertNull(update.headers[HttpHeaders.IfNoneMatch])
    }

    @Test
    fun putRecipeErrors() = runTest {
        val input = RecipeInput(id = ID_A, title = "T", language = "de")
        listOf(
            428 to ErrorCode.PreconditionRequired,
            412 to ErrorCode.PreconditionFailed,
            413 to ErrorCode.RecipeTooLarge,
            409 to ErrorCode.RecipeLimit,
            400 to ErrorCode.InvalidBody,
            400 to ErrorCode.InvalidId,
        ).forEach { (status, code) ->
            server.handler = { error(status, code.wire) }
            assertFailure(api.putRecipe(input, 3), status, code)
        }
    }

    @Test
    fun deleteRecipe() = runTest {
        server.handler = { empty(204) }
        assertEquals(ApiResult.Ok(Unit), api.deleteRecipe(ID_A))
        assertEquals("$BASE/v1/recipes/$ID_A", server.requests.single().url.toString())
        server.handler = { error(404, "not_found") }
        assertFailure(api.deleteRecipe(ID_A), 404, ErrorCode.NotFound)
    }

    @Test
    fun putImageSendsRawBytes() = runTest {
        server.handler = { json(fixture("image_upload_response.json"), HttpStatusCode.Created) }
        val bytes = byteArrayOf(-1, -40, -1, 1, 2, 3)
        val info = (api.putImage(ID_A, IMG_1, bytes) as ApiResult.Ok).value
        assertEquals(ImageInfo(IMG_1, 12345, "image/jpeg"), info)
        val request = server.requests.single()
        assertEquals("$BASE/v1/recipes/$ID_A/images/$IMG_1", request.url.toString())
        assertEquals("image/jpeg", request.body.contentType.toString())
        assertContentEquals(bytes, request.bodyBytes())
    }

    @Test
    fun putImageErrors() = runTest {
        listOf(
            404 to ErrorCode.NotFound,
            413 to ErrorCode.ImageTooLarge,
            413 to ErrorCode.StorageCap,
            409 to ErrorCode.ImageLimit,
            415 to ErrorCode.UnsupportedMediaType,
        ).forEach { (status, code) ->
            server.handler = { error(status, code.wire) }
            assertFailure(api.putImage(ID_A, IMG_1, byteArrayOf(1)), status, code)
        }
    }

    @Test
    fun imageBytes() = runTest {
        val bytes = byteArrayOf(9, 8, 7)
        server.handler = { respond(bytes, HttpStatusCode.OK, io.ktor.http.headersOf(HttpHeaders.ContentType, "image/png")) }
        assertContentEquals(bytes, (api.image(IMG_1) as ApiResult.Ok).value)
        server.handler = { error(404, "not_found") }
        assertFailure(api.image(IMG_1), 404, ErrorCode.NotFound)
    }

    @Test
    fun errorFixtureDecodesWithTheClientsErrorType() = runTest {
        server.handler = { json(fixture("error.json"), HttpStatusCode.NotFound) }
        assertEquals(ApiResult.Failure(404, ErrorCode.NotFound, "not_found"), api.deleteRecipe(ID_A))
    }

    @Test
    fun everyContractCodeMapsToItsOwnEntry() {
        ErrorCode.entries.filter { it != ErrorCode.Unknown }.forEach { assertEquals(it, ErrorCode.of(it.wire)) }
        assertEquals(ErrorCode.Unknown, ErrorCode.of("something_new"))
        assertEquals(ErrorCode.Unknown, ErrorCode.of(null))
    }

    @Test
    fun unknownCodeAndNonJsonErrorBody() = runTest {
        server.handler = { error(418, "teapot") }
        assertEquals(ApiResult.Failure(418, ErrorCode.Unknown, "teapot"), api.me())
        server.handler = { respond("<html>bad gateway</html>", HttpStatusCode.BadGateway) }
        assertEquals(ApiResult.Failure(502, ErrorCode.Unknown, "http_502"), api.me())
    }

    @Test
    fun networkFailureAndMalformedBodyAreOffline() = runTest {
        server.offline = true
        assertEquals(ApiResult.Offline, api.recipes(0))
        server.offline = false
        server.handler = { json("""{ "unexpected": true }""") }
        assertEquals(ApiResult.Offline, api.recipes(0))
    }

    @Test
    fun noTokenMeansNoAuthorizationHeader() = runTest {
        val anonymous = server.api(token = { null })
        server.handler = { error(401, "unauthenticated") }
        anonymous.me()
        assertNull(server.requests.single().headers[HttpHeaders.Authorization])
    }
}
