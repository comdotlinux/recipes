package com.linux.recipes.data

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.client.engine.mock.respond
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

class RecipeRepositoryTest {
    private val server = FakeServer()
    private val dir = tempDir()
    private var unauthorizedCalls = 0
    private var token: String? = "v1.user.secret.tag"
    private fun repository() = DefaultRecipeRepository(server.api { token }, RecipeCache(dir), currentToken = { token }) { unauthorizedCalls++ }

    private val imageBytes = mapOf(IMG_1 to byteArrayOf(1, 1), IMG_2 to byteArrayOf(2, 2))

    /** Serves `GET /recipes` from [lists] by `since`, pictures from [imageBytes]. */
    private fun serve(lists: Map<Long, String>, cursorExpiredFor: Set<Long> = emptySet()) {
        server.handler = { request ->
            val path = request.url.encodedPath
            when {
                path == "/v1/recipes" -> {
                    val since = request.url.parameters["since"]!!.toLong()
                    if (since in cursorExpiredFor) error(410, "cursor_expired") else json(lists.getValue(since))
                }
                path.startsWith("/v1/images/") -> imageBytes[path.substringAfterLast('/')]
                    ?.let { respond(it, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/jpeg")) }
                    ?: error(404, "not_found")
                else -> error(404, "not_found")
            }
        }
    }

    @Test
    fun firstSyncAppliesTheContractListAndSkipsDeletionMarkers() = runTest {
        serve(mapOf(0L to fixture("recipes_list.json")))
        val repo = repository()
        assertTrue(repo.sync())
        assertEquals(listOf("Kartoffelsalat"), repo.recipes.value.map { it.title })
        assertEquals(8, RecipeCache(dir).readSettings().cursor)
        assertEquals(listOf("/v1/recipes?since=0"), server.paths())
    }

    @Test
    fun deltaSyncAppliesChangesAndDeletionMarkersFromTheCursor() = runTest {
        serve(
            mapOf(
                0L to recipeListJson(2, recipe(ID_A, 1), recipe(ID_B, 2)),
                2L to recipeListJson(4, recipe(ID_A, 3, title = "changed"), Recipe(id = ID_B, rev = 4, deleted = true, updatedAt = "x"), recipe(ID_C, 4)),
            ),
        )
        val repo = repository()
        repo.sync()
        assertTrue(repo.sync())
        assertEquals(listOf(ID_C to 4L, ID_A to 3L), repo.recipes.value.map { it.id to it.rev }, "newest change first")
        assertEquals("changed", repo.recipes.value.last().title)
        assertEquals(listOf("/v1/recipes?since=0", "/v1/recipes?since=2"), server.paths())
        assertEquals(4, RecipeCache(dir).readSettings().cursor)
    }

    @Test
    fun cursorExpiredPullsEverythingAndReplacesTheCache() = runTest {
        serve(
            mapOf(
                0L to recipeListJson(5, recipe(ID_A, 1), recipe(ID_B, 2)),
            ),
        )
        val repo = repository()
        repo.sync()
        // Later the server purged markers; ID_B was deleted long ago and the full list no longer contains it.
        serve(mapOf(0L to recipeListJson(90, recipe(ID_A, 1), recipe(ID_C, 90))), cursorExpiredFor = setOf(5L))
        assertTrue(repo.sync())
        assertEquals(listOf(ID_C, ID_A), repo.recipes.value.map { it.id })
        assertEquals(listOf(ID_C, ID_A), RecipeCache(dir).readRecipes().map { it.id })
        assertEquals(90, RecipeCache(dir).readSettings().cursor)
        assertEquals(listOf("/v1/recipes?since=0", "/v1/recipes?since=5", "/v1/recipes?since=0"), server.paths())
    }

    @Test
    fun syncDownloadsOnlyMissingPictures() = runTest {
        val unknownImage = "99999999-9999-4999-8999-999999999999"
        serve(mapOf(0L to recipeListJson(2, recipe(ID_A, 1, imageIds = listOf(IMG_1, unknownImage)), recipe(ID_B, 2, imageIds = listOf(IMG_2)))))
        val repo = repository()
        RecipeCache(dir).writeImage(IMG_2, byteArrayOf(7))

        assertTrue(repo.sync())

        assertContentEquals(imageBytes[IMG_1], read(repo.imagePath(IMG_1)!!))
        assertContentEquals(byteArrayOf(7), read(repo.imagePath(IMG_2)!!), "cached picture is not downloaded again")
        assertNull(repo.imagePath(unknownImage), "a 404 picture is skipped")
        assertEquals(listOf("/v1/recipes?since=0", "/v1/images/$IMG_1", "/v1/images/$unknownImage"), server.paths())
    }

    @Test
    fun ac9OfflineServesCachedRecipesAndPictures() = runTest {
        serve(mapOf(0L to recipeListJson(1, recipe(ID_A, 1, imageIds = listOf(IMG_1)))))
        repository().sync()

        server.offline = true
        val afterRestart = repository()
        assertFalse(afterRestart.sync())
        assertEquals(listOf(ID_A), afterRestart.recipes.value.map { it.id })
        assertContentEquals(imageBytes[IMG_1], read(assertNotNull(afterRestart.imagePath(IMG_1))))
    }

    @Test
    fun pictureDownloadInterruptedReportsUnreachable() = runTest {
        server.handler = { request ->
            if (request.url.encodedPath == "/v1/recipes") json(recipeListJson(1, recipe(ID_A, 1, imageIds = listOf(IMG_1)))) else throw kotlinx.io.IOException("drop")
        }
        val repo = repository()
        assertFalse(repo.sync())
        assertEquals(listOf(ID_A), repo.recipes.value.map { it.id }, "recipes are kept even when pictures fail")
    }

    @Test
    fun unauthorizedSyncEndsTheSession() = runTest {
        server.handler = { error(401, "unauthenticated") }
        val repo = repository()
        assertFalse(repo.sync())
        assertEquals(1, unauthorizedCalls)
    }

    @Test
    fun unauthorizedPictureDownloadEndsTheSession() = runTest {
        server.handler = { request ->
            if (request.url.encodedPath == "/v1/recipes") json(recipeListJson(1, recipe(ID_A, 1, imageIds = listOf(IMG_1)))) else error(401, "unauthenticated")
        }
        assertFalse(repository().sync())
        assertEquals(1, unauthorizedCalls)
    }

    @Test
    fun createAndUpdateUseThePreconditionHeaders() = runTest {
        val input = RecipeInput(id = ID_A, title = "Neu", language = "de")
        server.handler = { request ->
            val rev = if (request.headers[HttpHeaders.IfMatch] == null) 1L else 2L
            json(recipeJson(recipe(ID_A, rev, title = "Neu")))
        }
        val repo = repository()
        val created = repo.save(input, expectedRev = null, newImages = emptyList())
        assertEquals(1, (created as SaveResult.Saved).recipe.rev)
        assertEquals("*", server.requests[0].headers[HttpHeaders.IfNoneMatch])

        val updated = repo.save(input, expectedRev = 1, newImages = emptyList())
        assertEquals(2, (updated as SaveResult.Saved).recipe.rev)
        assertEquals("\"1\"", server.requests[1].headers[HttpHeaders.IfMatch])
        assertEquals(listOf(ID_A to 2L), repo.recipes.value.map { it.id to it.rev })
        assertEquals(listOf(ID_A to 2L), RecipeCache(dir).readRecipes().map { it.id to it.rev })
    }

    @Test
    fun conflictOfflineAndRejected() = runTest {
        val input = RecipeInput(id = ID_A, title = "T", language = "de")
        val repo = repository()
        server.handler = { error(412, "precondition_failed") }
        assertEquals(SaveResult.Conflict, repo.save(input, 4, emptyList()))
        server.handler = { error(413, "recipe_too_large") }
        assertEquals(SaveResult.Rejected("recipe_too_large"), repo.save(input, 4, emptyList()))
        server.handler = { error(401, "unauthenticated") }
        assertEquals(SaveResult.Rejected("unauthenticated"), repo.save(input, 4, emptyList()))
        assertEquals(1, unauthorizedCalls)
        server.offline = true
        assertEquals(SaveResult.Offline, repo.save(input, 4, emptyList()))
        assertEquals(emptyList(), repo.recipes.value)
    }

    @Test
    fun updateUploadsNewPicturesBeforeTheRecipe() = runTest {
        server.handler = { request ->
            if (request.url.encodedPath.contains("/images/")) {
                json(fixture("image_upload_response.json"), HttpStatusCode.Created)
            } else {
                json(recipeJson(recipe(ID_A, 6, imageIds = listOf(IMG_1))))
            }
        }
        val repo = repository()
        val input = RecipeInput(id = ID_A, title = "T", language = "de", imageIds = listOf(IMG_1))
        val result = repo.save(input, expectedRev = 5, newImages = listOf(NewImage(IMG_1, byteArrayOf(4, 4))))

        assertTrue(result is SaveResult.Saved)
        assertEquals(listOf("/v1/recipes/$ID_A/images/$IMG_1", "/v1/recipes/$ID_A"), server.paths())
        assertContentEquals(byteArrayOf(4, 4), read(repo.imagePath(IMG_1)!!), "the uploaded picture is cached for offline use")
    }

    @Test
    fun createWithPicturesCreatesFirstThenUploadsThenUpdates() = runTest {
        server.handler = { request ->
            when {
                request.url.encodedPath.contains("/images/") -> json(fixture("image_upload_response.json"), HttpStatusCode.Created)
                request.headers[HttpHeaders.IfNoneMatch] == "*" -> json(recipeJson(recipe(ID_A, 1)))
                else -> json(recipeJson(recipe(ID_A, 2, imageIds = listOf(IMG_1))))
            }
        }
        val repo = repository()
        val input = RecipeInput(id = ID_A, title = "T", language = "de", imageIds = listOf(IMG_1))
        val result = repo.save(input, expectedRev = null, newImages = listOf(NewImage(IMG_1, byteArrayOf(1))))

        assertEquals(listOf(IMG_1), (result as SaveResult.Saved).recipe.imageIds)
        assertEquals(listOf(HttpMethod.Put, HttpMethod.Put, HttpMethod.Put), server.requests.map { it.method })
        assertEquals(listOf("/v1/recipes/$ID_A", "/v1/recipes/$ID_A/images/$IMG_1", "/v1/recipes/$ID_A"), server.paths())
        assertEquals(emptyList(), Api.apiJson.decodeFromString(RecipeInput.serializer(), server.requests[0].bodyText()).imageIds)
        assertEquals("\"1\"", server.requests[2].headers[HttpHeaders.IfMatch])
    }

    @Test
    fun failedPictureUploadStopsTheSave() = runTest {
        server.handler = { error(409, "image_limit") }
        val repo = repository()
        val input = RecipeInput(id = ID_A, title = "T", language = "de", imageIds = listOf(IMG_1))
        assertEquals(SaveResult.Rejected("image_limit"), repo.save(input, 3, listOf(NewImage(IMG_1, byteArrayOf(1)))))
        assertEquals(1, server.requests.size)

        server.handler = { error(412, "precondition_failed") }
        assertEquals(SaveResult.Conflict, repo.save(input, null, listOf(NewImage(IMG_1, byteArrayOf(1)))), "the create step conflicts")
    }

    @Test
    fun retryAfterAFailedUploadDoesNotCreateTwice() = runTest {
        val input = RecipeInput(id = ID_A, title = "T", language = "de", imageIds = listOf(IMG_1))
        val picture = listOf(NewImage(IMG_1, byteArrayOf(1)))
        var uploadsFail = true
        server.handler = { request ->
            when {
                request.url.encodedPath.contains("/images/") ->
                    if (uploadsFail) throw kotlinx.io.IOException("drop") else json(fixture("image_upload_response.json"), HttpStatusCode.Created)
                request.headers[HttpHeaders.IfNoneMatch] == "*" -> json(recipeJson(recipe(ID_A, 1)))
                else -> json(recipeJson(recipe(ID_A, 2, imageIds = listOf(IMG_1))))
            }
        }
        val repo = repository()
        assertEquals(SaveResult.Offline, repo.save(input, expectedRev = null, newImages = picture))
        assertEquals(listOf(ID_A to 1L), RecipeCache(dir).readRecipes().map { it.id to it.rev }, "the cache holds what the server accepted")

        uploadsFail = false
        server.requests.clear()
        val retried = repo.save(input, expectedRev = null, newImages = picture)

        assertEquals(2, (retried as SaveResult.Saved).recipe.rev)
        assertEquals(listOf("/v1/recipes/$ID_A/images/$IMG_1", "/v1/recipes/$ID_A"), server.paths())
        assertTrue(server.requests.none { it.headers[HttpHeaders.IfNoneMatch] == "*" }, "no second create")
        assertEquals("\"1\"", server.requests.last().headers[HttpHeaders.IfMatch])
    }

    @Test
    fun updateOfAnUnknownIdIsRejectedAsNotFound() = runTest {
        server.handler = { error(404, "not_found") }
        assertEquals(SaveResult.Rejected("not_found"), repository().save(RecipeInput(id = ID_C, title = "T", language = "de"), 3, emptyList()))
    }

    @Test
    fun deleteRemovesFromTheCache() = runTest {
        serve(mapOf(0L to recipeListJson(2, recipe(ID_A, 1), recipe(ID_B, 2))))
        val repo = repository()
        repo.sync()

        server.handler = { empty(204) }
        assertTrue(repo.delete(ID_A))
        server.handler = { error(404, "not_found") }
        assertTrue(repo.delete(ID_B), "already gone on the server")
        assertEquals(emptyList(), RecipeCache(dir).readRecipes())

        serve(mapOf(2L to recipeListJson(2)))
        repo.sync()
        server.offline = true
        assertFalse(repo.delete(ID_C))
        server.offline = false
        server.handler = { error(401, "unauthenticated") }
        assertFalse(repo.delete(ID_C))
        assertEquals(1, unauthorizedCalls)
    }

    @Test
    fun clearLocalWipesFilesAndState() = runTest {
        serve(mapOf(0L to recipeListJson(1, recipe(ID_A, 1, imageIds = listOf(IMG_1)))))
        val repo = repository()
        repo.sync()
        repo.clearLocal()
        assertEquals(emptyList(), repo.recipes.value)
        assertEquals(emptyList(), filesBelow(dir))
    }

    @Test
    fun pagesAreFetchedInOrderAndAppliedTogether() = runTest {
        serve(
            mapOf(
                0L to recipeListJson(2, recipe(ID_A, 1), recipe(ID_B, 2), more = true),
                2L to recipeListJson(3, Recipe(id = ID_A, rev = 3, deleted = true, updatedAt = "x"), more = false),
            ),
        )
        val repo = repository()
        assertTrue(repo.sync())
        assertEquals(listOf("/v1/recipes?since=0", "/v1/recipes?since=2"), server.paths())
        assertEquals(listOf(ID_B), repo.recipes.value.map { it.id }, "page two's deletion marker applies after page one")
        assertEquals(3, RecipeCache(dir).readSettings().cursor)
    }

    @Test
    fun failureOnPageTwoChangesNothing() = runTest {
        serve(mapOf(0L to recipeListJson(1, recipe(ID_A, 1))))
        val repo = repository()
        repo.sync()
        server.handler = { request ->
            if (request.url.parameters["since"] == "1") json(recipeListJson(5, recipe(ID_B, 5), more = true)) else throw kotlinx.io.IOException("drop")
        }
        assertFalse(repo.sync())
        assertEquals(listOf(ID_A), repo.recipes.value.map { it.id })
        assertEquals(listOf(ID_A), RecipeCache(dir).readRecipes().map { it.id })
        assertEquals(1, RecipeCache(dir).readSettings().cursor)
    }

    @Test
    fun cursorExpiredThenPagedFullPullReplacesTheCacheAtTheEnd() = runTest {
        serve(mapOf(0L to recipeListJson(4, recipe(ID_A, 4))))
        val repo = repository()
        repo.sync()
        serve(
            mapOf(
                0L to recipeListJson(7, recipe(ID_B, 7), more = true),
                7L to recipeListJson(9, recipe(ID_C, 9), more = false),
            ),
            cursorExpiredFor = setOf(4L),
        )
        assertTrue(repo.sync())
        assertEquals(listOf(ID_C, ID_B), repo.recipes.value.map { it.id }, "ID_A is gone: the full pull replaced the cache")
        assertEquals(9, RecipeCache(dir).readSettings().cursor)
        assertEquals(listOf("/v1/recipes?since=0", "/v1/recipes?since=4", "/v1/recipes?since=0", "/v1/recipes?since=7"), server.paths())
    }

    @Test
    fun aPageThatDoesNotAdvanceTheCursorStopsTheSync() = runTest {
        server.handler = { json(recipeListJson(0, recipe(ID_A, 1), more = true)) }
        assertFalse(repository().sync())
        assertEquals(1, server.requests.size)
    }

    @Test
    fun createWhoseResponseWasLostIsRetriedAsAnUpdate() = runTest {
        val input = RecipeInput(id = ID_A, title = "Mine", language = "de")
        server.handler = { request ->
            when {
                request.url.encodedPath == "/v1/recipes" -> json(recipeListJson(4, recipe(ID_A, 4, title = "Mine")))
                request.headers[HttpHeaders.IfNoneMatch] == "*" -> error(412, "precondition_failed")
                else -> json(recipeJson(recipe(ID_A, 5, title = "Mine")))
            }
        }
        val result = repository().save(input, expectedRev = null, newImages = emptyList())
        assertEquals(5, (result as SaveResult.Saved).recipe.rev)
        assertEquals("\"4\"", server.requests.last().headers[HttpHeaders.IfMatch])
    }

    @Test
    fun a401ForAnOldTokenDoesNotEndTheNewSession() = runTest {
        val repo = repository()
        server.handler = {
            token = "v1.newer.session.tag" // the user signed in again while this call was in flight
            error(401, "unauthenticated")
        }
        assertFalse(repo.sync())
        assertEquals(0, unauthorizedCalls)
    }

    @Test
    fun pictureDownloadsDoNotHoldTheLockAndAreDroppedAfterAWipe() = runTest {
        lateinit var repo: DefaultRecipeRepository
        server.handler = { request ->
            if (request.url.encodedPath == "/v1/recipes") {
                json(recipeListJson(1, recipe(ID_A, 1, imageIds = listOf(IMG_1))))
            } else {
                repo.clearLocal() // sign-out during the download; would deadlock if the download held the lock
                respond(byteArrayOf(1), HttpStatusCode.OK)
            }
        }
        repo = repository()
        repo.sync()
        assertEquals(emptyList(), filesBelow(dir), "the late picture is not written after the wipe")
    }

    @Test
    fun picturesNoRecipeListsAreRemovedAfterSyncAndDelete() = runTest {
        RecipeCache(dir).writeImage(IMG_2, byteArrayOf(9)) // left over from an earlier version of a recipe
        serve(mapOf(0L to recipeListJson(2, recipe(ID_A, 1, imageIds = listOf(IMG_1)), recipe(ID_B, 2))))
        val repo = repository()
        repo.sync()
        assertNull(repo.imagePath(IMG_2), "orphan removed by sync")
        assertNotNull(repo.imagePath(IMG_1))

        server.handler = { empty(204) }
        repo.delete(ID_A)
        assertNull(repo.imagePath(IMG_1), "picture of the deleted recipe removed")
    }

    @Test
    fun duplicatePictureIdsAreSentOnce() = runTest {
        server.handler = { request ->
            if (request.url.encodedPath.contains("/images/")) json(fixture("image_upload_response.json"), HttpStatusCode.Created) else json(recipeJson(recipe(ID_A, 2)))
        }
        val input = RecipeInput(id = ID_A, title = "T", language = "de", imageIds = listOf(IMG_1, IMG_1, IMG_2))
        repository().save(input, 1, listOf(NewImage(IMG_1, byteArrayOf(1)), NewImage(IMG_1, byteArrayOf(1))))
        assertEquals(1, server.requests.count { it.url.encodedPath.contains("/images/") })
        val sent = Api.apiJson.decodeFromString(RecipeInput.serializer(), server.requests.last().bodyText())
        assertEquals(listOf(IMG_1, IMG_2), sent.imageIds)
    }

    @Test
    fun serverErrorsMeanRetryLaterNotRejected() = runTest {
        val repo = repository()
        for (status in listOf(500, 502, 503)) {
            server.handler = { respond("upstream", HttpStatusCode.fromValue(status)) }
            assertFalse(repo.sync(), "sync $status")
            assertEquals(SaveResult.Offline, repo.save(RecipeInput(id = ID_A, title = "T", language = "de"), 1, emptyList()), "save $status")
            assertFalse(repo.delete(ID_A), "delete $status")
        }
        assertEquals(0, unauthorizedCalls)
    }

    @Test
    fun cursorExpiredInTheMiddleOfAFullPullStartsOverOnce() = runTest {
        var expiredOnce = false
        server.handler = { request ->
            when (request.url.parameters["since"]) {
                "0" -> if (expiredOnce) json(recipeListJson(9, recipe(ID_C, 9))) else json(recipeListJson(3, recipe(ID_A, 3), more = true))
                else -> { expiredOnce = true; error(410, "cursor_expired") } // markers purged between pages
            }
        }
        val repo = repository()
        assertTrue(repo.sync())
        assertEquals(listOf(ID_C), repo.recipes.value.map { it.id })
        assertEquals(listOf("/v1/recipes?since=0", "/v1/recipes?since=3", "/v1/recipes?since=0"), server.paths())

        server.handler = { error(410, "cursor_expired") }
        assertFalse(repository().sync(), "a second 410 in the same sync gives up")
    }

    @Test
    fun rateLimitedPictureDownloadMeansRetryLater() = runTest {
        server.handler = { request ->
            if (request.url.encodedPath == "/v1/recipes") json(recipeListJson(1, recipe(ID_A, 1, imageIds = listOf(IMG_1)))) else error(429, "rate_limited")
        }
        assertFalse(repository().sync())
    }

    @Test
    fun rateLimitedSaveMeansRetryLaterAndStorageCapIsRejected() = runTest {
        val input = RecipeInput(id = ID_A, title = "T", language = "de")
        server.handler = { error(429, "rate_limited") }
        assertEquals(SaveResult.Offline, repository().save(input, 1, emptyList()))
        server.handler = { error(413, "recipe_storage_cap") }
        assertEquals(SaveResult.Rejected("recipe_storage_cap"), repository().save(input, 1, emptyList()))
        assertEquals(ErrorCode.RecipeStorageCap, ErrorCode.of("recipe_storage_cap"))
    }

    private fun read(path: String) = SystemFileSystem.source(Path(path)).buffered().use { it.readByteArray() }
}
