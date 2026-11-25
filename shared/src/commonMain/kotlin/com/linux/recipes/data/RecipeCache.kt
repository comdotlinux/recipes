package com.linux.recipes.data

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * Offline copy of the user's data under [root] (a directory inside the app's files directory):
 * `recipes.json`, `settings.json` (sync cursor, open import job) and `images/<imageId>`.
 * Every file is written to a temporary name first and then renamed, so a crash never leaves half a file.
 */
class RecipeCache(private val root: Path, private val fs: FileSystem = SystemFileSystem) {
    /**
     * [importJobId] and [importJobKind]: the open job (import or translation), kept so it survives an app restart.
     * [translationJobs]: the translation jobs this device started or followed, for reports. [displayLanguages]: the
     * language each recipe is shown in, by recipe id, when it is not the original. All of it is account data.
     */
    @Serializable
    data class Settings(
        val cursor: Long = 0,
        val importJobId: String? = null,
        val importJobKind: String? = null,
        val translationJobs: List<TranslationJobRef> = emptyList(),
        val displayLanguages: Map<String, String> = emptyMap(),
    )

    private val recipesFile = Path(root, "recipes.json")
    private val settingsFile = Path(root, "settings.json")
    private val imagesDir = Path(root, "images")

    /** Cached recipes; an unreadable file counts as empty. */
    fun readRecipes(): List<Recipe> = read(recipesFile, ListSerializer(Recipe.serializer())) ?: emptyList()

    fun writeRecipes(recipes: List<Recipe>) =
        writeAtomic(recipesFile, json.encodeToString(ListSerializer(Recipe.serializer()), recipes).encodeToByteArray())

    /** The cursor is only trusted when the recipes it belongs to can be read; otherwise sync starts from 0. */
    fun readSettings(): Settings {
        val stored = read(settingsFile, Settings.serializer()) ?: Settings()
        return if (read(recipesFile, ListSerializer(Recipe.serializer())) == null) stored.copy(cursor = 0) else stored
    }

    /**
     * The only way to change the settings: read, change, write under one lock, so the recipe sync (cursor) and the
     * import repository (job) never overwrite each other's field, from whatever thread they run. Not suspending,
     * because `ImportRepository.acknowledge` is not.
     */
    @OptIn(ExperimentalAtomicApi::class)
    fun updateSettings(change: (Settings) -> Settings) {
        // shortcut: a spin lock, held for one small file write; common code has no blocking lock without adding
        // atomicfu as a direct dependency. Upgrade to a real lock if updates ever get slow or frequent.
        while (!settingsLock.compareAndSet(expectedValue = false, newValue = true)) Unit
        try {
            writeAtomic(settingsFile, json.encodeToString(Settings.serializer(), change(readSettings())).encodeToByteArray())
        } finally {
            settingsLock.store(false)
        }
    }

    @OptIn(ExperimentalAtomicApi::class)
    private val settingsLock = AtomicBoolean(false)

    fun imagePath(imageId: String): String? = imageFile(imageId)?.takeIf { fs.exists(it) }?.toString()

    fun writeImage(imageId: String, bytes: ByteArray) {
        writeAtomic(requireNotNull(imageFile(imageId)) { "invalid image id" }, bytes)
    }

    /** Removes cached picture files whose id is not in [keep] (pictures no cached recipe lists any more). */
    fun removeImagesExcept(keep: Set<String>) {
        if (!fs.exists(imagesDir)) return
        fs.list(imagesDir).filter { it.name !in keep }.forEach { fs.delete(it, mustExist = false) }
    }

    /** Removes every cached file: recipes, settings, pictures and leftover temporary files. */
    fun wipe() {
        if (fs.exists(imagesDir)) {
            fs.list(imagesDir).forEach { fs.delete(it, mustExist = false) }
            fs.delete(imagesDir, mustExist = false)
        }
        if (fs.exists(root)) fs.list(root).forEach { fs.delete(it, mustExist = false) }
    }

    // Image ids come from the server and become file names, so only lowercase UUIDs are accepted.
    private fun imageFile(imageId: String): Path? = if (UUID.matches(imageId)) Path(imagesDir, imageId) else null

    private fun <T> read(path: Path, serializer: kotlinx.serialization.KSerializer<T>): T? {
        if (!fs.exists(path)) return null
        val text = fs.source(path).buffered().use { it.readByteArray().decodeToString() }
        return runCatching { json.decodeFromString(serializer, text) }.getOrNull()
    }

    private fun writeAtomic(target: Path, bytes: ByteArray) {
        val dir = requireNotNull(target.parent)
        fs.createDirectories(dir)
        val tmp = Path(dir, "${target.name}.tmp")
        fs.sink(tmp).buffered().use { it.write(bytes) }
        fs.atomicMove(tmp, target)
    }

    private companion object {
        val json = Api.apiJson
        val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    }
}
