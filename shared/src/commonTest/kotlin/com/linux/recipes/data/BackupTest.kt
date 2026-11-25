package com.linux.recipes.data

import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

/**
 * A whole account on a scripted server: recipes with revisions and preconditions, pictures per recipe with the
 * storage cap, `GET /me`. [offlineAfterWrites] cuts the connection after that many successful writes.
 */
class FakeAccount(var capBytes: Long = 52_428_800, var maxRecipes: Int = 2000) {
    val recipes = mutableMapOf<String, Recipe>()
    /** imageId to (recipeId, mime, bytes). */
    val images = mutableMapOf<String, Triple<String, String, ByteArray>>()
    var rev = 0L
    var writes = 0
    var offlineAfterWrites: Int? = null
    val server = FakeServer()

    init {
        server.handler = { request -> handle(request) }
    }

    fun api() = server.api()

    fun used() = images.values.sumOf { it.third.size.toLong() }

    private fun wrote() {
        writes++
        if (offlineAfterWrites != null && writes >= offlineAfterWrites!!) server.offline = true
    }

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.handle(request: HttpRequestData): io.ktor.client.request.HttpResponseData {
        val path = request.url.encodedPath
        val parts = path.removePrefix("/v1/").split('/')
        return when {
            path == "/v1/recipes" && request.method == HttpMethod.Get -> {
                val since = request.url.parameters["since"]?.toLong() ?: 0
                json(recipeListJson(rev, *recipes.values.filter { it.rev > since }.sortedBy { it.rev }.toTypedArray()))
            }
            path == "/v1/me" -> json(
                """{"userId":"u","state":"active","entitlement":{"active":false},"quota":{"freeImportUsed":false,"monthUsed":0,"monthLimit":30},
                    "storage":{"usedBytes":${used()},"capBytes":$capBytes}}""",
            )
            parts.size == 2 && parts[0] == "recipes" && request.method == HttpMethod.Put -> {
                val input = Api.apiJson.decodeFromString(RecipeInput.serializer(), request.bodyText())
                val current = recipes[parts[1]]
                val ifMatch = request.headers[HttpHeaders.IfMatch]
                when {
                    request.headers[HttpHeaders.IfNoneMatch] == "*" && current != null -> error(412, "precondition_failed")
                    ifMatch != null && (current == null || ifMatch != "\"${current.rev}\"") -> error(412, "precondition_failed")
                    current == null && recipes.size >= maxRecipes -> error(409, "recipe_limit")
                    else -> {
                        rev++
                        val stored = input.toServer(rev)
                        recipes[input.id] = stored
                        // Pictures of this recipe that are no longer listed are removed when it is saved.
                        images.entries.removeAll { it.value.first == input.id && it.key !in input.imageIds }
                        wrote()
                        json(recipeJson(stored))
                    }
                }
            }
            parts.size == 4 && parts[0] == "recipes" && parts[2] == "images" -> {
                val bytes = request.bodyBytes()
                when {
                    parts[1] !in recipes -> error(404, "not_found")
                    used() + bytes.size > capBytes -> error(413, "storage_cap")
                    else -> {
                        images[parts[3]] = Triple(parts[1], request.body.contentType.toString(), bytes)
                        wrote()
                        json("""{"id":"${parts[3]}","size":${bytes.size},"mime":"image/jpeg"}""", HttpStatusCode.Created)
                    }
                }
            }
            parts.size == 2 && parts[0] == "images" -> images[parts[1]]
                ?.let { respond(it.third, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, it.second)) }
                ?: error(404, "not_found")
            else -> error(404, "not_found")
        }
    }

    /** The account's content as the client would send it, for comparing two accounts. */
    fun inputs(): Map<String, RecipeInput> = recipes.mapValues { it.value.toInput() }

    fun pictureBytes(): Map<String, List<Byte>> = images.mapValues { it.value.third.toList() }
}

private fun RecipeInput.toServer(rev: Long) = Recipe(
    id = id, rev = rev, updatedAt = "2026-11-25T10:00:00Z", title = title, description = description, servings = servings,
    prepMinutes = prepMinutes, cookMinutes = cookMinutes, ingredients = ingredients, steps = steps, tags = tags,
    sourceUrl = sourceUrl, sourceType = sourceType, language = language, imageIds = imageIds,
)

class BackupTest {
    private val jpeg = byteArrayOf(-1, -40, -1, 0x10) + ByteArray(5000) { (it % 251).toByte() }
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) + ByteArray(3001) { 7 }

    /** A source account with three recipes, two of them with pictures, each picture also synced to the device. */
    private fun sourceAccount(): FakeAccount = FakeAccount().apply {
        val a = recipe(ID_A, 1, title = "Kartoffelsalat", imageIds = listOf(IMG_1, IMG_2)).copy(
            description = "Warm, with broth.", servings = 4, ingredients = listOf(Ingredient("1", "kg", "Kartoffeln", "Basis")),
            steps = listOf("Kochen.", "Schneiden."), tags = listOf("Beilage"), sourceUrl = "https://example.com/k", sourceType = "url",
        )
        recipes[ID_A] = a
        recipes[ID_B] = recipe(ID_B, 2, title = "Strudel ✓ «quoted» \"x\"\nline")
        recipes[ID_C] = recipe(ID_C, 3, title = "Ceviche", imageIds = listOf(IMG_3))
        rev = 3
        images[IMG_1] = Triple(ID_A, "image/jpeg", jpeg)
        images[IMG_2] = Triple(ID_A, "image/png", png)
        images[IMG_3] = Triple(ID_C, "image/jpeg", jpeg.reversedArray().also { it[0] = -1; it[1] = -40; it[2] = -1 })
    }

    private fun backupOf(account: FakeAccount): Backup {
        val repo = DefaultRecipeRepository(account.api(), RecipeCache(tempDir()), currentToken = { "t" }) {}
        return Backup(account.api(), repo, currentToken = { "t" })
    }

    private suspend fun export(account: FakeAccount): ByteArray {
        val buffer = Buffer()
        val result = backupOf(account).export { write -> write(buffer); true }
        assertEquals(ExportResult.Done(account.recipes.size, account.images.size), result)
        return buffer.readByteArray()
    }

    private suspend fun restore(account: FakeAccount, file: ByteArray): RestoreResult =
        backupOf(account).restore(Buffer().apply { write(file) })

    private fun lines(file: ByteArray) = file.decodeToString().trimEnd('\n').split('\n')

    @Test
    fun exportWritesAHeaderThenOneLinePerRecipeAndPicture() = runTest {
        val source = sourceAccount()
        val lines = lines(export(source))
        assertEquals(1 + 3 + 3, lines.size)
        assertEquals(
            jsonOf("""{"format":"recipes-backup","version":1,"recipes":3,"pictures":3,"pictureBytes":${jpeg.size * 2 + png.size}}"""),
            jsonOf(lines[0]),
        )
        assertTrue(lines.subList(1, 4).all { it.startsWith("{\"type\":\"recipe\"") })
        assertTrue(lines.subList(4, 7).all { it.startsWith("{\"type\":\"picture\"") })
        assertTrue(lines.any { "\"mime\":\"image/png\"" in it })
    }

    @Test
    fun roundTripIntoAnEmptyAccountIsByteIdentical() = runTest {
        val source = sourceAccount()
        val file = export(source)
        val target = FakeAccount()
        val result = restore(target, file)
        assertEquals(RestoreResult.Done(3, 0, 3, 0, emptyList(), emptyList(), 0), result)
        assertEquals(source.inputs(), target.inputs())
        assertEquals(source.pictureBytes(), target.pictureBytes())
        assertEquals("image/png", target.images.getValue(IMG_2).second)
        // The picture order of each recipe is the file's.
        assertEquals(listOf(IMG_1, IMG_2), target.recipes.getValue(ID_A).imageIds)
    }

    @Test
    fun restoringTwiceChangesNothing() = runTest {
        val file = export(sourceAccount())
        val target = FakeAccount()
        restore(target, file)
        val writes = target.writes
        val before = target.inputs()
        val again = restore(target, file)
        assertEquals(RestoreResult.Done(0, 3, 0, 3, emptyList(), emptyList(), 0), again)
        assertEquals(writes, target.writes)
        assertEquals(before, target.inputs())
    }

    @Test
    fun restoringIntoTheSourceAccountChangesNothing() = runTest {
        val source = sourceAccount()
        val file = export(source)
        val result = restore(source, file)
        assertEquals(RestoreResult.Done(0, 3, 0, 3, emptyList(), emptyList(), 0), result)
        assertEquals(0, source.writes)
    }

    @Test
    fun restoreBringsBackEditedAndDeletedContentOfTheSameAccount() = runTest {
        val source = sourceAccount()
        val file = export(source)
        val original = source.inputs()
        val pictures = source.pictureBytes()
        source.rev++
        source.recipes[ID_B] = source.recipes.getValue(ID_B).copy(title = "Changed", rev = source.rev)
        source.rev++
        source.recipes[ID_A] = source.recipes.getValue(ID_A).copy(imageIds = listOf(IMG_1), rev = source.rev)
        source.images.remove(IMG_2)
        val result = restore(source, file)
        assertIs<RestoreResult.Done>(result)
        assertEquals(original, source.inputs())
        assertEquals(pictures, source.pictureBytes())
    }

    @Test
    fun anInterruptedRestoreCompletesWhenRunAgain() = runTest {
        val source = sourceAccount()
        val file = export(source)
        // A clean restore makes 3 recipe writes and 3 uploads with 3 attaching updates: cut after each of them.
        for (cut in 1 until 9) {
            val target = FakeAccount().apply { offlineAfterWrites = cut }
            assertEquals(RestoreResult.Interrupted, restore(target, file), "cut after $cut")
            target.server.offline = false
            target.offlineAfterWrites = null
            assertIs<RestoreResult.Done>(restore(target, file), "rerun after $cut")
            assertEquals(source.inputs(), target.inputs(), "cut after $cut")
            assertEquals(source.pictureBytes(), target.pictureBytes(), "cut after $cut")
        }
    }

    @Test
    fun aMalformedHeaderIsRefused() = runTest {
        val target = FakeAccount()
        listOf(
            "",
            "garbage\n",
            """{"format":"other","version":1,"recipes":0,"pictures":0,"pictureBytes":0}""" + "\n",
            """{"format":"recipes-backup","version":2,"recipes":0,"pictures":0,"pictureBytes":0}""" + "\n",
            """{"format":"recipes-backup","version":1,"recipes":-1,"pictures":0,"pictureBytes":0}""" + "\n",
            "x".repeat(2_300_000),
        ).forEach { file ->
            assertEquals(RestoreResult.Refused, restore(target, file.encodeToByteArray()))
            assertEquals(RestoreCheck.Refused, backupOf(target).check(Buffer().apply { write(file.encodeToByteArray()) }))
        }
        assertEquals(0, target.writes)
    }

    @Test
    fun malformedLinesAreSkippedAndCounted() = runTest {
        val source = sourceAccount()
        val lines = lines(export(source)).toMutableList()
        val badPicture = lines[4].replace("\"data\":\"", "\"data\":\"!!")
        val wrongSize = lines[5].replace(Regex("\"size\":\\d+"), "\"size\":1")
        val unknownRecipe = lines[6].replace(ID_A, "99999999-2222-4333-8444-555555555555")
        val edited = listOf(lines[0]) + lines.subList(1, 4) + listOf(
            "not json",
            """{"type":"recipe","recipe":{"id":"NOT-A-UUID","title":"x","language":"de"}}""",
            """{"type":"something"}""",
            lines[1], // the same recipe twice
            "y".repeat(2_300_000),
            badPicture,
            wrongSize,
            unknownRecipe,
        )
        val file = edited.joinToString("\n", postfix = "\n").encodeToByteArray()
        val target = FakeAccount()
        val result = restore(target, file)
        assertIs<RestoreResult.Done>(result)
        assertEquals(3, result.recipesWritten)
        assertEquals(0, result.picturesWritten)
        // Eight bad lines; the header announced 6 items and 11 lines came, so nothing counts as missing.
        assertEquals(8, result.malformedLines)
        assertEquals(source.inputs().mapValues { it.value.copy(imageIds = emptyList()) }, target.inputs())
    }

    @Test
    fun aTruncatedFileCountsTheMissingLines() = runTest {
        val lines = lines(export(sourceAccount()))
        val file = lines.take(5).joinToString("\n").encodeToByteArray() // no final newline either
        val result = restore(FakeAccount(), file)
        assertIs<RestoreResult.Done>(result)
        assertEquals(1, result.picturesWritten)
        assertEquals(2, result.malformedLines)
    }

    @Test
    fun exportStopsWithAListWhenPicturesAreMissingLocally() = runTest {
        val source = sourceAccount()
        source.server.handler.let { original ->
            source.server.handler = { request -> if (request.url.encodedPath == "/v1/images/$IMG_3") error(503, "busy") else original(this, request) }
        }
        var opened = false
        val result = backupOf(source).export { _ -> true.also { opened = true } }
        assertEquals(ExportResult.MissingPictures(listOf("Ceviche")), result)
        assertTrue(!opened, "the save dialog must not open")
    }

    /** What the real save dialog does: a writer that throws leaves no file behind. */
    private class Dialog {
        val discarded = mutableListOf<Int>()
        val save: suspend (suspend (kotlinx.io.Sink) -> Unit) -> Boolean = { write ->
            val buffer = Buffer()
            try {
                write(buffer)
            } catch (e: Throwable) {
                discarded += buffer.size.toInt()
                throw e
            }
            true
        }
    }

    @Test
    fun aPictureGoneDuringTheExportRemovesTheUnfinishedFile() = runTest {
        val source = sourceAccount()
        val repo = DefaultRecipeRepository(source.api(), RecipeCache(tempDir()), currentToken = { "t" }) {}
        val backup = Backup(source.api(), repo, openPicture = { id ->
            if (id == IMG_2) null else repo.imagePath(id)?.let { kotlinx.io.files.SystemFileSystem.source(kotlinx.io.files.Path(it)) }
        })
        val dialog = Dialog()
        assertEquals(ExportResult.MissingPictures(listOf("Kartoffelsalat")), backup.export(dialog.save))
        assertEquals(1, dialog.discarded.size)
    }

    @Test
    fun cancellingTheExportRemovesTheUnfinishedFile() = runTest {
        val source = sourceAccount()
        val repo = DefaultRecipeRepository(source.api(), RecipeCache(tempDir()), currentToken = { "t" }) {}
        lateinit var job: kotlinx.coroutines.Job
        val backup = Backup(source.api(), repo, openPicture = { id ->
            job.cancel()
            repo.imagePath(id)?.let { kotlinx.io.files.SystemFileSystem.source(kotlinx.io.files.Path(it)) }
        })
        val dialog = Dialog()
        var result: ExportResult? = null
        job = launch { result = backup.export(dialog.save) }
        job.join()
        assertEquals(null, result)
        assertEquals(1, dialog.discarded.size)
        assertEquals(null, backup.progress.value)
    }

    @Test
    fun exportOfflineAndCancelled() = runTest {
        val source = sourceAccount()
        assertEquals(ExportResult.Cancelled, backupOf(source).export { false })
        source.server.offline = true
        assertEquals(ExportResult.Offline, backupOf(source).export { true })
    }

    @Test
    fun overCapWarnsThenRestoresAllTextAndAsManyPicturesAsFit() = runTest {
        val source = sourceAccount()
        val file = export(source)
        val target = FakeAccount(capBytes = jpeg.size.toLong() + 10)
        val check = backupOf(target).check(Buffer().apply { write(file) })
        assertIs<RestoreCheck.Ready>(check)
        assertEquals(RestorePlan(3, 3, (jpeg.size * 2 + png.size).toLong(), jpeg.size.toLong() + 10), check.plan)
        assertTrue(check.plan.overCap)
        val result = restore(target, file)
        assertIs<RestoreResult.Done>(result)
        assertEquals(3, result.recipesWritten)
        assertEquals(1, result.picturesWritten)
        // The cache lists newest first: Ceviche's picture fits, both of Kartoffelsalat do not.
        assertEquals(listOf(Skipped("Kartoffelsalat", "storage_cap"), Skipped("Kartoffelsalat", "storage_cap")), result.skippedPictures)
        assertEquals(source.inputs().keys, target.inputs().keys)
        assertEquals(source.inputs().mapValues { it.value.title }, target.inputs().mapValues { it.value.title })
    }

    @Test
    fun checkOfTheSameAccountCountsNoNewBytes() = runTest {
        val source = sourceAccount()
        val file = export(source)
        val check = backupOf(source).check(Buffer().apply { write(file) })
        assertIs<RestoreCheck.Ready>(check)
        assertEquals(0, check.plan.newPictureBytes)
        assertTrue(!check.plan.overCap)
        source.server.offline = true
        assertEquals(RestoreCheck.Offline, backupOf(source).check(Buffer().apply { write(file) }))
    }

    @Test
    fun aRecipeOverTheLimitIsListedAndItsPicturesWithIt() = runTest {
        val file = export(sourceAccount())
        val target = FakeAccount(maxRecipes = 1)
        val result = restore(target, file)
        assertIs<RestoreResult.Done>(result)
        assertEquals(1, result.recipesWritten)
        assertEquals(listOf(Skipped("Strudel ✓ «quoted» \"x\"\nline", "recipe_limit"), Skipped("Kartoffelsalat", "recipe_limit")), result.skippedRecipes)
        assertEquals(listOf(Skipped("Kartoffelsalat", "recipe_limit"), Skipped("Kartoffelsalat", "recipe_limit")), result.skippedPictures)
    }

    @Test
    fun a401StopsTheRestoreAndEndsTheSession() = runTest {
        val file = export(sourceAccount())
        val target = FakeAccount()
        var ended = 0
        val repo = DefaultRecipeRepository(target.api(), RecipeCache(tempDir()), currentToken = { "t" }) {}
        val backup = Backup(target.api(), repo, currentToken = { "t" }, onUnauthorized = { ended++ })
        repo.sync()
        target.server.handler.let { original ->
            target.server.handler = { request -> if (request.method == HttpMethod.Put) error(401, "unauthenticated") else original(this, request) }
        }
        assertEquals(RestoreResult.Interrupted, backup.restore(Buffer().apply { write(file) }))
        assertEquals(1, ended)
    }

    @Test
    fun progressCountsItemsAndClearsAtTheEndAndPicturesAreBase64() = runTest {
        val source = sourceAccount()
        val backup = backupOf(source)
        val buffer = Buffer()
        var last: BackupProgress? = null
        backup.export { write ->
            write(buffer)
            last = backup.progress.value
            true
        }
        assertEquals(BackupProgress(6, 6), last)
        assertEquals(null, backup.progress.value)
        val line = lines(buffer.readByteArray()).first { "\"id\":\"$IMG_2\"" in it }
        assertContentEquals(png, Base64.decode(line.substringAfter("\"data\":\"").substringBefore('"')))
    }

    @Test
    fun mimeIsSniffedFromMagicBytes() {
        val webp = "RIFF0000WEBP".encodeToByteArray()
        assertEquals("image/webp", Backup.mimeOf(webp, webp.size))
        assertEquals("image/png", Backup.mimeOf(png, png.size))
        assertEquals("image/jpeg", Backup.mimeOf(jpeg, jpeg.size))
        assertEquals("image/jpeg", Backup.mimeOf(byteArrayOf(1, 2), 2))
    }
}

const val IMG_3 = "cccccccc-dddd-4eee-8fff-000000000000"
