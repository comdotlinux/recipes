package com.linux.recipes.data

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

class RecipeCacheTest {
    private val dir = tempDir()
    private val cache = RecipeCache(dir)

    private fun readFile(name: String) = SystemFileSystem.source(Path(dir, name)).buffered().use { it.readByteArray().decodeToString() }

    @Test
    fun emptyCacheReadsAsNothing() {
        assertEquals(emptyList(), cache.readRecipes())
        assertEquals(RecipeCache.Settings(cursor = 0), cache.readSettings())
        assertNull(cache.imagePath(IMG_1))
    }

    @Test
    fun recipesAndCursorRoundTripWithoutTemporaryFiles() {
        val recipes = listOf(recipe(ID_A, 2, imageIds = listOf(IMG_1)), recipe(ID_B, 1))
        cache.writeRecipes(recipes)
        cache.updateSettings { it.copy(cursor = 9) }
        cache.writeRecipes(recipes.take(1))

        assertEquals(recipes.take(1), RecipeCache(dir).readRecipes())
        assertEquals(9, RecipeCache(dir).readSettings().cursor)
        assertEquals(setOf("recipes.json", "settings.json"), filesBelow(dir).map { it.name }.toSet())
        assertEquals(recipes.take(1), Api.apiJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Recipe.serializer()), readFile("recipes.json")))
    }

    @Test
    fun atomicWriteReplacesALeftoverTemporaryFile() {
        // A crash between writing and renaming leaves recipes.json.tmp; the old recipes.json stays valid.
        cache.writeRecipes(listOf(recipe(ID_A, 1)))
        SystemFileSystem.sink(Path(dir, "recipes.json.tmp")).buffered().use { it.write("{half a fi".encodeToByteArray()) }
        assertEquals(listOf(recipe(ID_A, 1)), cache.readRecipes())

        cache.writeRecipes(listOf(recipe(ID_B, 2)))
        assertEquals(listOf(recipe(ID_B, 2)), cache.readRecipes())
        assertTrue(filesBelow(dir).none { it.name.endsWith(".tmp") })
    }

    @Test
    fun unreadableRecipesResetTheCursor() {
        cache.updateSettings { it.copy(cursor = 5) }
        SystemFileSystem.sink(Path(dir, "recipes.json")).buffered().use { it.write("not json".encodeToByteArray()) }
        assertEquals(emptyList(), cache.readRecipes())
        assertEquals(0, cache.readSettings().cursor)
    }

    @Test
    fun imagesAreStoredByIdAndOnlyForUuids() {
        val bytes = byteArrayOf(1, 2, 3)
        cache.writeImage(IMG_1, bytes)
        val path = cache.imagePath(IMG_1)!!
        assertEquals(Path(dir, "images", IMG_1).toString(), path)
        assertContentEquals(bytes, SystemFileSystem.source(Path(path)).buffered().use { it.readByteArray() })

        assertFailsWith<IllegalArgumentException> { cache.writeImage("../recipes.json", bytes) }
        assertNull(cache.imagePath("../recipes.json"))
        assertNull(cache.imagePath(IMG_1.uppercase()))
    }

    @Test
    fun interleavedSettingsUpdatesFromSeveralThreadsLoseNothing() = runTest {
        cache.writeRecipes(emptyList()) // so the cursor is trusted
        withContext(Dispatchers.Default) {
            // Sync (cursor) and the import repository (job id) update the same file at the same time.
            val cursorWrites = (1..100).map { launch { cache.updateSettings { it.copy(cursor = it.cursor + 1) } } }
            val jobWrites = (1..100).map { n -> launch { cache.updateSettings { it.copy(importJobId = "job-$n", importJobKind = "text") } } }
            (cursorWrites + jobWrites).joinAll()
        }
        val settings = RecipeCache(dir).readSettings()
        assertEquals(100, settings.cursor, "no cursor increment was lost")
        assertTrue(settings.importJobId!!.startsWith("job-"), "the job id survived every cursor write")
    }

    @Test
    fun wipeRemovesEveryFile() {
        cache.writeRecipes(listOf(recipe(ID_A, 1)))
        cache.updateSettings { it.copy(cursor = 3) }
        cache.writeImage(IMG_1, byteArrayOf(1))
        cache.writeImage(IMG_2, byteArrayOf(2))

        cache.wipe()

        assertEquals(emptyList(), filesBelow(dir))
        assertEquals(emptyList(), cache.readRecipes())
        assertNull(cache.imagePath(IMG_1))
        cache.wipe() // idempotent
    }
}
