package com.linux.recipes.data

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/** The M2 routes of docs/api.md against the contract fixtures. */
class ImportApiTest {
    private val server = FakeServer()
    private val api = server.api()
    private val jobId = "4c0e9a1b-7d2f-4e3a-9b8c-1f2e3d4c5b6a"

    private fun assertFailure(result: ApiResult<*>, status: Int, code: ErrorCode) {
        assertIs<ApiResult.Failure>(result)
        assertEquals(status, result.status)
        assertEquals(code, result.code)
        assertEquals(code.wire, result.wire)
    }

    @Test
    fun requestFixturesRoundTrip() {
        listOf("import_request_url.json", "import_request_text.json").forEach { name ->
            val request = Api.apiJson.decodeFromString(ImportRequest.serializer(), fixture(name))
            assertEquals(jsonOf(fixture(name)), jsonOf(Api.apiJson.encodeToString(ImportRequest.serializer(), request)), name)
        }
        assertEquals(ConsentRequest(1), Api.apiJson.decodeFromString(ConsentRequest.serializer(), fixture("consent_request.json")))
        val report = Api.apiJson.decodeFromString(Report.serializer(), fixture("report.json"))
        assertEquals(Report("import_result", jobId, "wrong_content", ""), report)
        assertEquals(jsonOf(fixture("report.json")), jsonOf(Api.apiJson.encodeToString(Report.serializer(), report)))
    }

    @Test
    fun jobFixturesDecode() {
        val pending = Api.apiJson.decodeFromString(ImportJob.serializer(), fixture("import_job_pending.json"))
        assertEquals(ImportJob(jobId, "text", "pending", "2026-11-24T18:00:00Z", null), pending)
        val done = Api.apiJson.decodeFromString(ImportJob.serializer(), fixture("import_job_done.json"))
        assertEquals("done", done.status)
        assertEquals("Kartoffelsalat", done.draft?.title)
        assertEquals(listOf(Ingredient("1", "kg", "Kartoffeln")), done.draft?.ingredients)
        assertEquals("url", done.draft?.sourceType)
        assertEquals(emptyList(), done.draft?.imageIds)
    }

    @Test
    fun startImportSendsTheIdempotencyKeyAndTheContractBody() = runTest {
        server.handler = { json(fixture("import_job_pending.json"), HttpStatusCode.Accepted) }
        val request = Api.apiJson.decodeFromString(ImportRequest.serializer(), fixture("import_request_url.json"))
        val job = (api.startImport(jobId, request) as ApiResult.Ok).value
        assertEquals("pending", job.status)
        val sent = server.requests.single()
        assertEquals(HttpMethod.Post, sent.method)
        assertEquals("$BASE/v1/imports", sent.url.toString())
        assertEquals(jobId, sent.headers["Idempotency-Key"])
        assertEquals("Bearer v1.user.secret.tag", sent.headers[HttpHeaders.Authorization])
        assertEquals("application/json", sent.body.contentType.toString())
        assertEquals(jsonOf(fixture("import_request_url.json")), jsonOf(sent.bodyText()))
    }

    @Test
    fun photoImportSendsRawJpeg() = runTest {
        server.handler = { json(fixture("import_job_pending.json"), HttpStatusCode.Accepted) }
        val jpeg = byteArrayOf(-1, -40, -1, 1, 2, 3)
        assertIs<ApiResult.Ok<ImportJob>>(api.startPhotoImport(jobId, jpeg))
        val sent = server.requests.single()
        assertEquals("$BASE/v1/imports", sent.url.toString())
        assertEquals(jobId, sent.headers["Idempotency-Key"])
        assertEquals("image/jpeg", sent.body.contentType.toString())
        assertContentEquals(jpeg, sent.bodyBytes())
    }

    @Test
    fun startImportErrors() = runTest {
        listOf(
            400 to ErrorCode.InvalidBody,
            400 to ErrorCode.InvalidId,
            400 to ErrorCode.InvalidUrl,
            402 to ErrorCode.QuotaExhausted,
            403 to ErrorCode.ConsentRequired,
            409 to ErrorCode.JobRunning,
            411 to ErrorCode.LengthRequired,
            413 to ErrorCode.TextTooLarge,
            413 to ErrorCode.ImageTooLarge,
            415 to ErrorCode.UnsupportedMediaType,
            429 to ErrorCode.AttemptLimit,
            429 to ErrorCode.RateLimited,
        ).forEach { (status, code) ->
            server.handler = { error(status, code.wire) }
            assertFailure(api.startImport(jobId, ImportRequest("text", text = "x")), status, code)
            assertFailure(api.startPhotoImport(jobId, byteArrayOf(1)), status, code)
        }
    }

    @Test
    fun jobAndItsNotFound() = runTest {
        server.handler = { json(fixture("import_job_done.json")) }
        assertEquals("done", (api.job(jobId) as ApiResult.Ok).value.status)
        assertEquals("$BASE/v1/jobs/$jobId", server.requests.single().url.toString())
        server.handler = { error(404, "not_found") }
        assertFailure(api.job(jobId), 404, ErrorCode.NotFound)
    }

    @Test
    fun consentRoutes() = runTest {
        server.handler = { json("""{ "consentVersion": 1 }""") }
        assertEquals(ApiResult.Ok(ConsentResponse(1)), api.putConsent(1))
        val put = server.requests.single()
        assertEquals(HttpMethod.Put, put.method)
        assertEquals("$BASE/v1/me/consent", put.url.toString())
        assertEquals(jsonOf(fixture("consent_request.json")), jsonOf(put.bodyText()))

        server.handler = { error(400, "consent_version_unknown") }
        assertFailure(api.putConsent(2), 400, ErrorCode.ConsentVersionUnknown)

        server.handler = { empty() }
        assertEquals(ApiResult.Ok(Unit), api.deleteConsent())
        assertEquals(HttpMethod.Delete, server.requests.last().method)
        assertEquals("$BASE/v1/me/consent", server.requests.last().url.toString())
    }

    @Test
    fun reportRoute() = runTest {
        server.handler = { empty() }
        val report = Api.apiJson.decodeFromString(Report.serializer(), fixture("report.json"))
        assertEquals(ApiResult.Ok(Unit), api.report(report))
        assertEquals("$BASE/v1/reports", server.requests.single().url.toString())
        assertEquals(jsonOf(fixture("report.json")), jsonOf(server.requests.single().bodyText()))
        server.handler = { error(404, "not_found") }
        assertFailure(api.report(report), 404, ErrorCode.NotFound)
    }

    @Test
    fun meCarriesConsentAndQuota() = runTest {
        server.handler = { json(fixture("me.json")) }
        val me = (api.me() as ApiResult.Ok).value
        assertNull(me.consentVersion)
        assertEquals(Me.Quota(freeImportUsed = false, monthUsed = 0, monthLimit = 30), me.quota)
    }
}
