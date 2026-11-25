package com.linux.recipes.data

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** The M5 share routes, the repository's result for every documented code, and the strict link parser. */
@OptIn(ExperimentalTime::class)
class ShareRepositoryTest {
    private val server = FakeServer()
    private val dir = tempDir()
    private var token: String? = "v1.user.secret.tag"
    private var unauthorized = 0
    private var syncs = 0
    private var now = Instant.parse("2026-11-25T10:00:00Z")

    private fun repository() = DefaultShareRepository(
        api = server.api { token },
        cacheDir = dir,
        sync = { true.also { syncs++ } },
        currentToken = { token },
        onUnauthorized = { unauthorized++ },
        now = { now },
    )

    private val created = Api.apiJson.decodeFromString(ShareCreated.serializer(), fixture("share_created.json"))

    @Test
    fun wireFixturesRoundTrip() {
        assertEquals("3f2a9c4d5e6b7a8f9c0d1e2f3a4b5c6d", created.shareId)
        val preview = Api.apiJson.decodeFromString(SharePreview.serializer(), fixture("share_preview.json"))
        assertEquals(SharedRecipe("Kartoffelsalat", "", 4, 20, 25, listOf(Ingredient("1", "kg", "Kartoffeln")), listOf("Kartoffeln kochen."), listOf("Beilage"), "de"), preview.recipe)
        assertEquals(1, preview.imageCount)
        val body = Api.apiJson.decodeFromString(ShareTokenBody.serializer(), fixture("share_token_body.json"))
        assertEquals(jsonOf(fixture("share_token_body.json")), jsonOf(Api.apiJson.encodeToString(ShareTokenBody.serializer(), body)))
        val report = Api.apiJson.decodeFromString(ShareReport.serializer(), fixture("report_share.json"))
        assertEquals(jsonOf(fixture("report_share.json")), jsonOf(Api.apiJson.encodeToString(ShareReport.serializer(), report)))
        assertEquals("shared", Api.apiJson.decodeFromString(Recipe.serializer(), fixture("share_accept_response.json")).sourceType)
    }

    @Test
    fun createStoresTheLinkAndKeepsItAcrossRestarts() = runTest {
        server.handler = { json(fixture("share_created.json"), HttpStatusCode.Created) }
        val result = repository().create(ID_A)
        val link = ShareLink(created.shareId, ID_A, created.url, created.expiresAt)
        assertEquals(ShareCreate.Created(link), result)
        val sent = server.requests.single()
        assertEquals(HttpMethod.Post, sent.method)
        assertEquals("$BASE/v1/recipes/$ID_A/shares", sent.url.toString())
        assertEquals("Bearer v1.user.secret.tag", sent.headers[HttpHeaders.Authorization])
        assertEquals(listOf(link), repository().links.value)
        // Expired links are not listed.
        now = Instant.parse("2026-12-26T00:00:00Z")
        assertEquals(emptyList(), repository().links.value)
    }

    @Test
    fun createMapsEveryDocumentedCode() = runTest {
        listOf(
            Triple(402, "subscription_required", ShareCreate.SubscriptionRequired),
            Triple(403, "consent_required", ShareCreate.ConsentRequired),
            Triple(422, "picture_rejected", ShareCreate.PictureRejected),
            Triple(429, "share_limit", ShareCreate.ShareLimit),
            Triple(503, "screening_unavailable", ShareCreate.ScreeningUnavailable),
            Triple(429, "rate_limited", ShareCreate.Offline),
            Triple(500, "internal", ShareCreate.Offline),
            Triple(404, "not_found", ShareCreate.Rejected("not_found")),
            Triple(400, "invalid_id", ShareCreate.Rejected("invalid_id")),
        ).forEach { (status, code, expected) ->
            server.handler = { error(status, code) }
            assertEquals(expected, repository().create(ID_A), code)
        }
        server.offline = true
        assertEquals(ShareCreate.Offline, repository().create(ID_A))
        assertEquals(0, unauthorized)
        assertEquals(emptyList(), repository().links.value)
    }

    @Test
    fun a401EndsTheSessionOnlyForTheCurrentToken() = runTest {
        server.handler = { error(401, "unauthenticated") }
        assertEquals(ShareCreate.Rejected("unauthenticated"), repository().create(ID_A))
        assertEquals(ShareOpen.Rejected("unauthenticated"), repository().open(TOKEN))
        assertEquals(ShareAccept.Rejected("unauthenticated"), repository().accept(TOKEN))
        assertFalse(repository().report(TOKEN, ReportReason.Other, ""))
        assertFalse(repository().revoke(created.shareId))
        assertEquals(5, unauthorized)
    }

    @Test
    fun revokeRemovesTheLinkOn204And404() = runTest {
        server.handler = { json(fixture("share_created.json"), HttpStatusCode.Created) }
        val repo = repository()
        repo.create(ID_A)
        server.handler = { error(500, "internal") }
        assertFalse(repo.revoke(created.shareId))
        assertEquals(1, repo.links.value.size)
        server.handler = { empty() }
        assertTrue(repo.revoke(created.shareId))
        assertEquals(emptyList(), repo.links.value)
        assertEquals("$BASE/v1/shares/${created.shareId}", server.requests.last().url.toString())
        assertEquals(HttpMethod.Delete, server.requests.last().method)
        server.handler = { error(404, "not_found") }
        assertTrue(repo.revoke(created.shareId))
    }

    @Test
    fun openSendsTheTokenOnlyInTheBody() = runTest {
        server.handler = { json(fixture("share_preview.json")) }
        val result = repository().open(TOKEN)
        assertIs<ShareOpen.Preview>(result)
        assertEquals("Kartoffelsalat", result.preview.recipe.title)
        val sent = server.requests.single()
        assertEquals("$BASE/v1/shares/open", sent.url.toString())
        assertFalse(TOKEN in sent.url.toString())
        assertEquals(jsonOf("""{"shareToken":"$TOKEN"}"""), jsonOf(sent.bodyText()))
    }

    @Test
    fun openMapsEveryDocumentedCode() = runTest {
        listOf(
            Triple(410, "share_gone", ShareOpen.Gone),
            Triple(409, "own_share", ShareOpen.OwnShare),
            Triple(404, "not_found", ShareOpen.NotFound),
            Triple(400, "invalid_body", ShareOpen.Rejected("invalid_body")),
            Triple(411, "length_required", ShareOpen.Rejected("length_required")),
            Triple(503, "internal", ShareOpen.Offline),
            Triple(429, "rate_limited", ShareOpen.Offline),
        ).forEach { (status, code, expected) ->
            server.handler = { error(status, code) }
            assertEquals(expected, repository().open(TOKEN), code)
        }
        server.offline = true
        assertEquals(ShareOpen.Offline, repository().open(TOKEN))
    }

    @Test
    fun acceptReturnsTheCopyAndSyncs() = runTest {
        server.handler = { json(fixture("share_accept_response.json")) }
        val result = repository().accept(TOKEN)
        assertIs<ShareAccept.Accepted>(result)
        assertEquals("5b8e1f0a-2c3d-4e5f-a6b7-c8d9e0f1a2b3", result.recipe.id)
        assertEquals(1, syncs)
        val sent = server.requests.single()
        assertEquals("$BASE/v1/shares/accept", sent.url.toString())
        assertEquals(jsonOf(fixture("share_token_body.json").replace(FIXTURE_TOKEN, TOKEN)), jsonOf(sent.bodyText()))
    }

    @Test
    fun acceptMapsEveryDocumentedCode() = runTest {
        listOf(
            Triple(409, "recipe_limit", ShareAccept.RecipeLimit),
            Triple(413, "recipe_storage_cap", ShareAccept.RecipeStorageCap),
            Triple(413, "storage_cap", ShareAccept.StorageCap),
            Triple(422, "share_unreadable", ShareAccept.Unreadable),
            Triple(410, "share_gone", ShareAccept.Gone),
            Triple(409, "own_share", ShareAccept.OwnShare),
            Triple(404, "not_found", ShareAccept.NotFound),
            Triple(400, "invalid_body", ShareAccept.Rejected("invalid_body")),
            Triple(502, "internal", ShareAccept.Offline),
        ).forEach { (status, code, expected) ->
            server.handler = { error(status, code) }
            assertEquals(expected, repository().accept(TOKEN), code)
        }
        server.offline = true
        assertEquals(ShareAccept.Offline, repository().accept(TOKEN))
        assertEquals(0, syncs)
    }

    @Test
    fun reportSendsTheShareBodyAndTreatsGoneAsDone() = runTest {
        server.handler = { empty() }
        assertTrue(repository().report(TOKEN, ReportReason.Offensive, "spam"))
        val sent = server.requests.single()
        assertEquals("$BASE/v1/reports", sent.url.toString())
        assertEquals(jsonOf("""{"kind":"share","shareToken":"$TOKEN","reason":"offensive","note":"spam"}"""), jsonOf(sent.bodyText()))
        server.handler = { error(410, "share_gone") }
        assertTrue(repository().report(TOKEN, ReportReason.WrongContent, ""))
        server.handler = { error(404, "not_found") }
        assertFalse(repository().report(TOKEN, ReportReason.Other, ""))
        server.offline = true
        assertFalse(repository().report(TOKEN, ReportReason.Other, ""))
    }

    @Test
    fun clearLocalForgetsTheLinks() = runTest {
        server.handler = { json(fixture("share_created.json"), HttpStatusCode.Created) }
        val repo = repository()
        repo.create(ID_A)
        repo.clearLocal()
        assertEquals(emptyList(), repo.links.value)
    }

    @Test
    fun anUnreadableLinksFileCountsAsEmpty() {
        SystemFileSystem.sink(Path(dir, "shares.json")).buffered().use { it.write("not json".encodeToByteArray()) }
        assertEquals(emptyList(), repository().links.value)
    }

    @Test
    fun pdfDownloadsBytesAndMapsItsCodes() = runTest {
        val pdf = "%PDF-1.7".encodeToByteArray()
        server.handler = { respond(pdf, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/pdf")) }
        val download = PdfDownload(server.api { token }, { token }) { unauthorized++ }
        val ready = download.download(ID_A)
        assertIs<PdfResult.Ready>(ready)
        assertContentEquals(pdf, ready.bytes)
        assertEquals("$BASE/v1/recipes/$ID_A/pdf", server.requests.single().url.toString())
        assertEquals("Bearer v1.user.secret.tag", server.requests.single().headers[HttpHeaders.Authorization])
        listOf(
            Triple(429, "pdf_limit", PdfResult.Limit),
            Triple(503, "pdf_unavailable", PdfResult.Unavailable),
            Triple(429, "rate_limited", PdfResult.Offline),
            Triple(404, "not_found", PdfResult.Rejected("not_found")),
            Triple(400, "invalid_id", PdfResult.Rejected("invalid_id")),
        ).forEach { (status, code, expected) ->
            server.handler = { error(status, code) }
            assertEquals(expected, download.download(ID_A), code)
        }
        server.handler = { error(401, "unauthenticated") }
        assertEquals(PdfResult.Rejected("unauthenticated"), download.download(ID_A))
        assertEquals(1, unauthorized)
        server.offline = true
        assertEquals(PdfResult.Offline, download.download(ID_A))
    }

    // ---------- the link parser ----------

    @Test
    fun parserAcceptsOnlyTheExactLink() {
        assertEquals(TOKEN, ShareLinks.tokenFromUrl("https://$HOST/s#$TOKEN", HOST))
        assertEquals(TOKEN, ShareLinks.tokenFromUrl("HTTPS://Recipe.Munich-Tech.DE/s#$TOKEN", HOST))
        val hostile = listOf(
            null,
            "",
            "https://$HOST/s",
            "https://$HOST/s#",
            "http://$HOST/s#$TOKEN",
            "https://evil.example/s#$TOKEN",
            "https://$HOST.evil.example/s#$TOKEN",
            "https://evil$HOST/s#$TOKEN",
            "https://recipe-munich-tech.de/s#$TOKEN",
            "https://$HOST@evil.example/s#$TOKEN",
            "https://user@$HOST/s#$TOKEN",
            "https://$HOST:8443/s#$TOKEN",
            "https://$HOST/s/$TOKEN",
            "https://$HOST/s?t=$TOKEN",
            "https://$HOST/s?x=1#$TOKEN",
            "https://$HOST/$TOKEN",
            "https://$HOST/S#$TOKEN",
            "https://$HOST/s/#$TOKEN",
            "https://$HOST//s#$TOKEN",
            "https://$HOST/x/s#$TOKEN",
            "https://$HOST/s#${TOKEN.dropLast(1)}",
            "https://$HOST/s#${TOKEN}A",
            "https://$HOST/s#$TOKEN#$TOKEN",
            "https://$HOST/s##$TOKEN",
            "https://$HOST/s#${TOKEN.dropLast(1)}=",
            "https://$HOST/s#${TOKEN.dropLast(3)}%41A",
            "https://$HOST/s#${TOKEN.dropLast(1)}+",
            "https://$HOST/s#${TOKEN.dropLast(1)}/",
            "https://$HOST/s# $TOKEN",
            "javascript:alert(1)//https://$HOST/s#$TOKEN",
            "intent://$HOST/s#$TOKEN",
        )
        hostile.forEach { assertNull(ShareLinks.tokenFromUrl(it, HOST), "accepted: $it") }
    }

    @Test
    fun intentTokenFromFragmentOrExtra() {
        assertEquals(TOKEN, ShareLinks.tokenFromIntent("https://$HOST/s#$TOKEN", null, HOST))
        assertEquals(TOKEN, ShareLinks.tokenFromIntent("https://$HOST/s", TOKEN, HOST))
        assertEquals(TOKEN, ShareLinks.tokenFromIntent(null, TOKEN, HOST))
        assertNull(ShareLinks.tokenFromIntent(null, "short", HOST))
        assertNull(ShareLinks.tokenFromIntent("https://evil.example/s#$TOKEN", null, HOST))
        assertNull(ShareLinks.tokenFromIntent(null, null, HOST))
        assertEquals("recipe.munich-tech.de", ShareLinks.hostOf("https://recipe.munich-tech.de"))
    }

    companion object {
        const val HOST = "recipe.munich-tech.de"
        /** 107 base64url characters, as the server issues them. */
        val TOKEN = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_".repeat(3).take(107)
        const val FIXTURE_TOKEN = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_AbCdEfGhIjKlMnOpQrStUvWxYz012"
    }
}
