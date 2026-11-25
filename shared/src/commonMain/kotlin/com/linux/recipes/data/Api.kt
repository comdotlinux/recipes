package com.linux.recipes.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.ContentConvertException
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Error codes of docs/api.md. [Unknown] covers anything the contract does not list. */
enum class ErrorCode(val wire: String) {
    InvalidBody("invalid_body"),
    InvalidId("invalid_id"),
    Unauthenticated("unauthenticated"),
    NotFound("not_found"),
    RateLimited("rate_limited"),
    AgeConfirmationRequired("age_confirmation_required"),
    InvalidTurnstile("invalid_turnstile"),
    InvalidNonce("invalid_nonce"),
    InvalidToken("invalid_token"),
    AccountDeleting("account_deleting"),
    LengthRequired("length_required"),
    ProviderUnavailable("provider_unavailable"),
    CursorExpired("cursor_expired"),
    PreconditionRequired("precondition_required"),
    PreconditionFailed("precondition_failed"),
    RecipeTooLarge("recipe_too_large"),
    RecipeLimit("recipe_limit"),
    RecipeStorageCap("recipe_storage_cap"),
    ImageTooLarge("image_too_large"),
    StorageCap("storage_cap"),
    ImageLimit("image_limit"),
    UnsupportedMediaType("unsupported_media_type"),
    ConsentVersionUnknown("consent_version_unknown"),
    ConsentRequired("consent_required"),
    QuotaExhausted("quota_exhausted"),
    JobRunning("job_running"),
    AttemptLimit("attempt_limit"),
    InvalidUrl("invalid_url"),
    TextTooLarge("text_too_large"),
    Unknown("unknown"),
    ;

    companion object {
        fun of(wire: String?): ErrorCode = entries.firstOrNull { it.wire == wire } ?: Unknown
    }
}

sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>

    /** The server answered with an error status. [wire] is the code as sent. */
    data class Failure(val status: Int, val code: ErrorCode, val wire: String) : ApiResult<Nothing>

    /** No usable answer: network down, timeout, or a body that is not the contract's shape. */
    data object Offline : ApiResult<Nothing>
}

@Serializable data class NonceResponse(val nonce: String)

@Serializable
data class SessionRequest(
    val provider: String,
    val idToken: String,
    val nonce: String,
    val turnstileToken: String,
    val ageConfirmed: Boolean,
    // Optional in the contract: absent, never null (the server rejects `null` with invalid_body).
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val appleAuthCode: String? = null,
)

@Serializable data class SessionResponse(val token: String, val userId: String, val expiresAt: String)

@Serializable
data class Me(
    val userId: String,
    val state: String,
    val suspension: Suspension? = null,
    val entitlement: Entitlement,
    val quota: Quota,
    val storage: Storage,
    val consentVersion: Int? = null,
    /** The user's import job that is still pending or running, if any. */
    val openJobId: String? = null,
) {
    @Serializable data class Suspension(val reason: String)
    @Serializable data class Entitlement(val active: Boolean, val expiresAt: String? = null)
    @Serializable data class Quota(val freeImportUsed: Boolean, val monthUsed: Int, val monthLimit: Int)
    @Serializable data class Storage(val usedBytes: Long, val capBytes: Long)
}

/** One page of `GET /recipes`: while [more] is true, ask again with `since = rev`. */
@Serializable data class RecipeList(val recipes: List<Recipe>, val rev: Long, val more: Boolean = false)

@Serializable data class ImageInfo(val id: String, val size: Long, val mime: String)

@Serializable data class ConsentRequest(val version: Int)

@Serializable data class ConsentResponse(val consentVersion: Int)

/** `POST /imports` JSON body: kind `url` with [url], or kind `text` with [text]. Photos go as raw JPEG instead. */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class ImportRequest(
    val kind: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val url: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val text: String? = null,
)

/** `status`: pending, running, done, guard_block, no_recipe, invalid or failed. [draft] only when done. */
@Serializable
data class ImportJob(val id: String, val kind: String, val status: String, val createdAt: String, val draft: RecipeInput? = null)

/** `POST /reports`; [reason] is wrong_content, offensive or other. */
@Serializable data class Report(val kind: String, val jobId: String, val reason: String, val note: String)

@Serializable
private data class ErrorBody(val error: Detail) {
    @Serializable data class Detail(val code: String, val message: String = "")
}

/** One function per M1 and M2 route of docs/api.md. Never throws except for cancellation. */
class Api(
    private val http: HttpClient,
    private val baseUrl: String,
    private val token: () -> String?,
) {
    suspend fun nonce(): ApiResult<NonceResponse> = call { post(url("/session/nonce")) }

    suspend fun createSession(request: SessionRequest): ApiResult<SessionResponse> = call {
        post(url("/session")) { json(request) }
    }

    suspend fun deleteSession(): ApiResult<Unit> = call { delete(url("/session")) { auth() } }

    suspend fun me(): ApiResult<Me> = call { get(url("/me")) { auth() } }

    suspend fun recipes(since: Long): ApiResult<RecipeList> = call {
        get(url("/recipes")) {
            auth()
            parameter("since", since)
        }
    }

    /** Creates with `If-None-Match: *` when [expectedRev] is null, otherwise updates with `If-Match`. */
    suspend fun putRecipe(input: RecipeInput, expectedRev: Long?): ApiResult<Recipe> = call {
        put(url("/recipes/${input.id}")) {
            auth()
            if (expectedRev == null) header(HttpHeaders.IfNoneMatch, "*") else header(HttpHeaders.IfMatch, "\"$expectedRev\"")
            json(input)
        }
    }

    suspend fun deleteRecipe(id: String): ApiResult<Unit> = call { delete(url("/recipes/$id")) { auth() } }

    suspend fun putImage(recipeId: String, imageId: String, bytes: ByteArray, mime: String = "image/jpeg"): ApiResult<ImageInfo> =
        call {
            put(url("/recipes/$recipeId/images/$imageId")) {
                auth()
                contentType(ContentType.parse(mime))
                setBody(bytes)
            }
        }

    suspend fun image(imageId: String): ApiResult<ByteArray> = call { get(url("/images/$imageId")) { auth() } }

    suspend fun putConsent(version: Int): ApiResult<ConsentResponse> = call {
        put(url("/me/consent")) {
            auth()
            json(ConsentRequest(version))
        }
    }

    suspend fun deleteConsent(): ApiResult<Unit> = call { delete(url("/me/consent")) { auth() } }

    /** [jobId] is the Idempotency-Key: the same key returns the same job and charges nothing more. */
    suspend fun startImport(jobId: String, request: ImportRequest): ApiResult<ImportJob> = call {
        post(url("/imports")) {
            auth()
            header(IDEMPOTENCY_KEY, jobId)
            json(request)
        }
    }

    /** Kind `photo`: the raw JPEG as the body. */
    suspend fun startPhotoImport(jobId: String, jpeg: ByteArray): ApiResult<ImportJob> = call {
        post(url("/imports")) {
            auth()
            header(IDEMPOTENCY_KEY, jobId)
            contentType(ContentType.Image.JPEG)
            setBody(jpeg)
        }
    }

    suspend fun job(jobId: String): ApiResult<ImportJob> = call { get(url("/jobs/$jobId")) { auth() } }

    suspend fun report(report: Report): ApiResult<Unit> = call {
        post(url("/reports")) {
            auth()
            json(report)
        }
    }

    private fun url(path: String) = "$baseUrl/v1$path"

    private fun HttpRequestBuilder.auth() {
        token()?.let { bearerAuth(it) }
    }

    private fun HttpRequestBuilder.json(body: Any) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend inline fun <reified T> call(request: HttpClient.() -> HttpResponse): ApiResult<T> = try {
        val response = http.request()
        if (response.status.isSuccess()) {
            @Suppress("UNCHECKED_CAST")
            ApiResult.Ok(
                when (T::class) {
                    Unit::class -> Unit as T
                    ByteArray::class -> response.bodyAsBytes() as T
                    else -> response.body<T>()
                },
            )
        } else {
            val wire = runCatching { apiJson.decodeFromString<ErrorBody>(response.bodyAsText()).error.code }.getOrNull()
            ApiResult.Failure(response.status.value, ErrorCode.of(wire), wire ?: "http_${response.status.value}")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: IOException) {
        ApiResult.Offline
    } catch (_: SerializationException) {
        ApiResult.Offline
    } catch (_: ContentConvertException) {
        ApiResult.Offline
    }

    companion object {
        const val IDEMPOTENCY_KEY = "Idempotency-Key"

        /** Sends every field, nulls included, so request bodies match the contract exactly. */
        val apiJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = true
        }

        fun httpClient(base: HttpClient = HttpClient()): HttpClient = base.config {
            expectSuccess = false
            install(ContentNegotiation) { json(apiJson) }
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                requestTimeoutMillis = 60_000
            }
        }
    }
}
