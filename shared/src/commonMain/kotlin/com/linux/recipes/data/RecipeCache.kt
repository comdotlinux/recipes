package com.linux.recipes.data

import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * Offline copy of the user's data under [root] (a directory inside the app's files directory):
 * `recipes.json`, `settings.json` (sync cursor) and `images/<imageId>`.
 * Every file is written to a temporary name first and then renamed, so a crash never leaves half a file.
 */
class RecipeCache(private val root: Path, private val fs: FileSystem = SystemFileSystem) {
    @Serializable
    data class Settings(val cursor: Long = 0)

    private val recipesFile = Path(root, "recipes.json")
    private val settingsFile = Path(root, "settings.json")
    private val imagesDir = Path(root, "images")

    /** Cached recipes; an unreadable file counts as empty. */
    fun readRecipes(): List<Recipe> = read(recipesFile, ListSerializer(Recipe.serializer())) ?: emptyList()

    fun writeRecipes(recipes: List<Recipe>) =
        writeAtomic(recipesFile, json.encodeToString(ListSerializer(Recipe.serializer()), recipes).encodeToByteArray())

    /** The cursor is only trusted when the recipes it belongs to can be read; otherwise sync starts from 0. */
    fun readSettings(): Settings =
        if (read(recipesFile, ListSerializer(Recipe.serializer())) == null) Settings() else read(settingsFile, Settings.serializer()) ?: Settings()

    fun writeSettings(settings: Settings) =
        writeAtomic(settingsFile, json.encodeToString(Settings.serializer(), settings).encodeToByteArray())

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
