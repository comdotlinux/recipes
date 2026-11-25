package com.linux.recipes.data

import com.linux.recipes.ui.FakeRecipeRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.buffered

/**
 * A synthetic account streamed through export and restore without ever existing as a whole: pictures are generated
 * on demand, the exported file only counted, the file to restore generated line by line, and the server keeps only
 * byte counts. What it proves: the bytes [Backup] holds at once ([Backup.peakBufferBytes]: line text plus decoded
 * picture, or one base64 chunk) stay at the size of one picture, whatever the account size.
 */
object SyntheticBackup {
    const val PICTURE = 1_500_000

    private fun id(prefix: Int, n: Int) = "${prefix.toString(16).padStart(8, '0')}-0000-4000-8000-${n.toString(16).padStart(12, '0')}"
    fun recipeId(n: Int) = id(1, n)
    fun pictureId(n: Int) = id(2, n)

    /** [pictures] pictures of [PICTURE] bytes, five per recipe. */
    fun recipes(pictures: Int): List<Recipe> = (0 until (pictures + 4) / 5).map { r ->
        recipe(recipeId(r), r + 1L, title = "Synthetic $r", imageIds = (r * 5 until minOf(pictures, r * 5 + 5)).map(::pictureId))
    }

    /** Deterministic JPEG-looking bytes of picture [n], produced in pieces. */
    fun pictureSource(n: Int): RawSource = object : RawSource {
        var left = PICTURE.toLong()
        override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
            if (left == 0L) return -1
            val k = minOf(byteCount, left, 8192).toInt()
            val start = PICTURE - left
            sink.write(ByteArray(k) { i -> if (start + i < 3) byteArrayOf(-1, -40, -1)[(start + i).toInt()] else ((start + i + n) % 251).toByte() })
            left -= k
            return k.toLong()
        }
        override fun close() = Unit
    }

    fun picture(n: Int): ByteArray = Buffer().also { b -> pictureSource(n).buffered().use { it.transferTo(b) } }.let { b -> ByteArray(PICTURE).also { b.readAtMostTo(it, 0, PICTURE) } }

    /** The backup file of [recipes], generated one line at a time as it is read. */
    fun file(recipes: List<Recipe>): RawSource = object : RawSource {
        val pictures = recipes.flatMap { r -> r.imageIds.map { r.id to it } }
        var line = -1
        val pending = Buffer()
        override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
            if (pending.exhausted()) {
                val text = when {
                    line == -1 -> """{"format":"recipes-backup","version":1,"recipes":${recipes.size},"pictures":${pictures.size},"pictureBytes":${pictures.size.toLong() * PICTURE}}"""
                    line < recipes.size -> """{"type":"recipe","recipe":${Api.apiJson.encodeToString(RecipeInput.serializer(), recipes[line].toInput())}}"""
                    line < recipes.size + pictures.size -> pictures[line - recipes.size].let { (recipeId, pictureId) ->
                        val n = pictureId.takeLast(12).toInt(16)
                        """{"type":"picture","recipeId":"$recipeId","id":"$pictureId","mime":"image/jpeg","size":$PICTURE,"data":"${Base64.encode(picture(n))}"}"""
                    }
                    else -> return -1
                }
                line++
                pending.write((text + "\n").encodeToByteArray())
            }
            return pending.readAtMostTo(sink, byteCount)
        }
        override fun close() = Unit
    }

    /** Counts what is written and keeps nothing. */
    class CountingSink : RawSink {
        var bytes = 0L
        var newlines = 0
        private val chunk = ByteArray(65_536)

        override fun write(source: Buffer, byteCount: Long) {
            var left = byteCount
            while (left > 0) {
                val n = source.readAtMostTo(chunk, 0, minOf(left, chunk.size.toLong()).toInt())
                for (i in 0 until n) if (chunk[i] == '\n'.code.toByte()) newlines++
                left -= n
            }
            bytes += byteCount
        }
        override fun flush() = Unit
        override fun close() = Unit
    }

    /** A server that echoes recipes and counts picture bytes, without recording requests. */
    class CountingServer {
        var pictureBytes = 0L
        var pictures = 0
        var recipes = 0
        private lateinit var engine: MockEngine
        val api = Api(
            Api.httpClient(
                HttpClient(
                    MockEngine { request ->
                        // MockEngine keeps every request (with its body) in requestHistory; that list is a plain
                        // ArrayList behind a read-only type. Emptied here, so this fake does not hold 500 MB itself.
                        @Suppress("UNCHECKED_CAST")
                        (engine.requestHistory as MutableList<Any>).clear()
                        @Suppress("UNCHECKED_CAST")
                        (engine.responseHistory as MutableList<Any>).clear()
                        val path = request.url.encodedPath
                        val json = headersOf(HttpHeaders.ContentType, "application/json")
                        when {
                            path == "/v1/recipes" -> respond("""{"recipes":[],"rev":0,"more":false}""", HttpStatusCode.OK, json)
                            path.contains("/images/") -> {
                                pictureBytes += (request.body as OutgoingContent.ByteArrayContent).bytes().size
                                pictures++
                                respond("""{"id":"x","size":1,"mime":"image/jpeg"}""", HttpStatusCode.Created, json)
                            }
                            request.method == HttpMethod.Put -> {
                                val input = Api.apiJson.decodeFromString(
                                    RecipeInput.serializer(),
                                    (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString(),
                                )
                                recipes++
                                respond(recipeJson(recipe(input.id, recipes.toLong(), input.title, input.imageIds)), HttpStatusCode.OK, json)
                            }
                            else -> respond("", HttpStatusCode.NotFound)
                        }
                    }.also { engine = it },
                ),
            ),
            BASE,
        ) { "t" }
    }

    /** Exports and restores [pictures] synthetic pictures; returns the larger of the two runs' peak buffer bytes. */
    suspend fun run(pictures: Int): Long {
        val all = recipes(pictures)
        val repo = FakeRecipeRepository(all)
        val export = Backup(
            CountingServer().api, repo,
            pictureSize = { PICTURE.toLong() },
            openPicture = { pictureSource(it.takeLast(12).toInt(16)) },
        )
        val sink = CountingSink()
        assertEquals(ExportResult.Done(all.size, pictures), export.export { write -> sink.buffered().let { write(it); it.flush() }; true })
        assertEquals(1 + all.size + pictures, sink.newlines)
        assertTrue(sink.bytes > pictures.toLong() * PICTURE * 4 / 3, "exported ${sink.bytes} bytes")

        val server = CountingServer()
        val restore = Backup(server.api, FakeRecipeRepository())
        val result = SyntheticBackup.file(all).buffered().use { restore.restore(it) }
        assertEquals(RestoreResult.Done(all.size, 0, pictures, 0, emptyList(), emptyList(), 0), result)
        assertEquals(pictures.toLong() * PICTURE, server.pictureBytes)
        return maxOf(export.peakBufferBytes, restore.peakBufferBytes)
    }

    /** One picture line held as text (UTF-16) plus its decoded bytes, with room for the rest of the line. */
    const val BOUND = 4L * PICTURE * 4 / 3 + PICTURE + 64_000
}

class BackupMemoryTest {
    /** 40 pictures, 60 MB of pictures: small enough for every test run, large enough to show the bound does not grow. */
    @Test
    fun ownBuffersStayAtOnePictureForA60MbAccount() = runTest {
        val peak = SyntheticBackup.run(pictures = 40)
        assertTrue(peak <= SyntheticBackup.BOUND, "peak $peak bytes")
    }
}
