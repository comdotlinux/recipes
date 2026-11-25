package com.linux.recipes.data

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The server answers 411 to a body without Content-Length. This runs the real Ktor Android engine
 * (HttpURLConnection, fixed-length streaming when the length is known) against a local HTTP server.
 */
class ContentLengthTest {
    private data class Seen(val path: String, val contentLength: String?, val transferEncoding: String?, val bodySize: Int)

    private val seen = mutableListOf<Seen>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes()
            synchronized(seen) {
                seen += Seen(
                    exchange.requestURI.path,
                    exchange.requestHeaders.getFirst("Content-Length"),
                    exchange.requestHeaders.getFirst("Transfer-Encoding"),
                    body.size,
                )
            }
            val answer = """{ "error": { "code": "test", "message": "" } }""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(400, answer.size.toLong())
            exchange.responseBody.use { it.write(answer) }
        }
        start()
    }

    @After
    fun stop() = server.stop(0)

    @Test
    fun jsonImageAndPhotoImportBodiesCarryContentLength() = runBlocking {
        val api = Api(Api.httpClient(HttpClient(Android)), "http://127.0.0.1:${server.address.port}") { "v1.u.s.t" }
        api.createSession(SessionRequest("test", "id", "n", "ts", true))
        api.putRecipe(RecipeInput(id = ID_A, title = "Kartoffelsalat", language = "de"), expectedRev = null)
        api.putImage(ID_A, IMG_1, ByteArray(70_000) { it.toByte() })
        api.putConsent(1)
        api.startImport(ID_B, ImportRequest(kind = "text", text = "Kartoffelsalat\n1 kg Kartoffeln"))
        api.startPhotoImport(ID_B, ByteArray(90_000) { it.toByte() })
        api.report(Report("import_result", ID_B, "other", ""))

        assertEquals(
            listOf("/v1/session", "/v1/recipes/$ID_A", "/v1/recipes/$ID_A/images/$IMG_1", "/v1/me/consent", "/v1/imports", "/v1/imports", "/v1/reports"),
            seen.map { it.path },
        )
        seen.forEach {
            assertEquals("${it.path} Content-Length", it.bodySize.toString(), it.contentLength)
            assertNull("${it.path} must not be chunked", it.transferEncoding)
        }
        assertEquals(70_000, seen[2].bodySize)
        assertEquals(90_000, seen[5].bodySize)
    }
}
