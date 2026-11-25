package com.linux.recipes.data

import com.linux.recipes.contract.contractFixtures
import com.linux.recipes.platform.AuthProvider
import com.linux.recipes.platform.AuthResult
import com.linux.recipes.platform.SecureStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlin.random.Random
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

const val BASE = "https://api.test"

fun fixture(name: String): String = contractFixtures.getValue(name)

fun jsonOf(text: String): JsonElement = Json.parseToJsonElement(text)

/** A scripted server: [handler] answers each request, every request is recorded in [requests]. */
class FakeServer {
    val requests = mutableListOf<HttpRequestData>()
    var offline = false
    var handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { respond("", HttpStatusCode.NotFound) }

    val client: HttpClient = Api.httpClient(
        HttpClient(
            MockEngine { request ->
                requests += request
                if (offline) throw IOException("network down")
                handler(request)
            },
        ),
    )

    fun api(token: () -> String? = { "v1.user.secret.tag" }) = Api(client, BASE, token)

    fun paths() = requests.map { it.url.encodedPath + (it.url.encodedQuery.takeIf { q -> q.isNotEmpty() }?.let { q -> "?$q" } ?: "") }
}

fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
    respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

fun MockRequestHandleScope.error(status: Int, code: String) =
    json("""{ "error": { "code": "$code", "message": "test" } }""", HttpStatusCode.fromValue(status))

fun MockRequestHandleScope.empty(status: Int = 204) = respond(ByteArray(0), HttpStatusCode.fromValue(status))

fun HttpRequestData.bodyText(): String = when (val content = body) {
    is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
    else -> error("unexpected body ${content::class}")
}

fun HttpRequestData.bodyBytes(): ByteArray = (body as OutgoingContent.ByteArrayContent).bytes()

class MemorySecureStore : SecureStore {
    val values = mutableMapOf<String, String>()

    /** False acts like a Keychain the OS refuses (device locked since boot): nothing is read, written or removed. */
    var available = true
    override fun get(key: String) = if (available) values[key] else null
    override fun put(key: String, value: String) = available.also { if (it) values[key] = value }
    override fun remove(key: String) = available.also { if (it) values.remove(key) }
}

class FakeAuthProvider(override val provider: String = "apple", var result: AuthResult = AuthResult.Success("id-token", "auth-code")) :
    AuthProvider {
    val nonces = mutableListOf<String>()
    override suspend fun signIn(nonce: String): AuthResult {
        nonces += nonce
        return result
    }
}

fun tempDir(): Path = Path(SystemTemporaryDirectory, "recipes-test-${Random.nextLong().toULong().toString(16)}")
    .also { SystemFileSystem.createDirectories(it) }

/** Every file below [dir], recursively. */
fun filesBelow(dir: Path): List<Path> {
    if (!SystemFileSystem.exists(dir)) return emptyList()
    return SystemFileSystem.list(dir).flatMap { child ->
        if (SystemFileSystem.metadataOrNull(child)?.isDirectory == true) filesBelow(child) else listOf(child)
    }
}

fun recipe(id: String, rev: Long, title: String = "R$rev", imageIds: List<String> = emptyList()) =
    Recipe(id = id, rev = rev, updatedAt = "2026-11-24T18:00:00Z", title = title, language = "de", imageIds = imageIds)

fun recipeListJson(rev: Long, vararg items: Recipe, more: Boolean = false): String =
    Api.apiJson.encodeToString(RecipeList.serializer(), RecipeList(items.toList(), rev, more))

fun recipeJson(recipe: Recipe): String = Api.apiJson.encodeToString(Recipe.serializer(), recipe)

const val ID_A = "0b9f6c1e-3a52-4d0f-9a43-2f1d0c6b7e11"
const val ID_B = "5d3c2b1a-0f9e-4d8c-8b7a-6e5f4d3c2b1a"
const val ID_C = "11111111-2222-4333-8444-555555555555"
const val IMG_1 = "7f1e2d3c-4b5a-4968-8776-655443322110"
const val IMG_2 = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
