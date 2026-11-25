package com.linux.recipes.data

import kotlin.io.encoding.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.io.RawSource
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.discardingSink
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.indexOf
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The backup file, UTF-8, one JSON object per line:
 * 1. header `{"format":"recipes-backup","version":1,"recipes":N,"pictures":M,"pictureBytes":B}`
 * 2. N lines `{"type":"recipe","recipe":<RecipeInput>}`
 * 3. M lines `{"type":"picture","recipeId":"…","id":"…","mime":"image/jpeg","size":S,"data":"<base64>"}`
 */
@Serializable
data class BackupHeader(val format: String, val version: Int, val recipes: Int, val pictures: Int, val pictureBytes: Long)

@Serializable private data class RecipeLine(val type: String, val recipe: RecipeInput)

@Serializable
private data class PictureLine(val type: String, val recipeId: String, val id: String, val mime: String, val size: Long, val data: String)

data class BackupProgress(val done: Int, val total: Int)

sealed interface ExportResult {
    data class Done(val recipes: Int, val pictures: Int) : ExportResult
    /** The picture sync could not finish: these recipes (titles) still miss pictures on the device. Nothing was written. */
    data class MissingPictures(val titles: List<String>) : ExportResult
    data object Offline : ExportResult
    /** The user closed the save dialog. */
    data object Cancelled : ExportResult
}

/** [freeBytes]: what the account's picture storage still takes, from `GET /me`. */
data class RestorePlan(val recipes: Int, val pictures: Int, val newPictureBytes: Long, val freeBytes: Long) {
    val overCap: Boolean get() = newPictureBytes > freeBytes
}

sealed interface RestoreCheck {
    data class Ready(val plan: RestorePlan) : RestoreCheck
    /** Not a backup of this app, or a version this build cannot read. */
    data object Refused : RestoreCheck
    data object Offline : RestoreCheck
}

/** A recipe or a picture that was not restored, with the recipe's title and the server's code. */
data class Skipped(val title: String, val code: String)

sealed interface RestoreResult {
    data class Done(
        val recipesWritten: Int,
        val recipesUnchanged: Int,
        val picturesWritten: Int,
        val picturesUnchanged: Int,
        val skippedRecipes: List<Skipped>,
        val skippedPictures: List<Skipped>,
        /** Lines that could not be read, plus lines the header announced that never came. */
        val malformedLines: Int,
    ) : RestoreResult
    data object Refused : RestoreResult
    /** Stopped part way (connection lost or the session ended); running it again completes it. */
    data object Interrupted : RestoreResult
}

/**
 * Export and restore of every recipe and picture, streamed line by line: at most one picture (and one line of its
 * base64 text) is in memory at a time, whatever the size of the account. Restore writes through the normal routes
 * with their preconditions and keeps the ids of the file, so a restore into the same account matches its recipes and
 * running it again changes nothing; into another account it creates them.
 */
class Backup(
    private val api: Api,
    private val recipes: RecipeRepository,
    private val currentToken: () -> String? = { null },
    private val onUnauthorized: suspend () -> Unit = {},
    /** Size of a picture on the device, or null when it is not there. */
    private val pictureSize: (String) -> Long? = { id -> recipes.imagePath(id)?.let { SystemFileSystem.metadataOrNull(Path(it))?.size } },
    private val openPicture: (String) -> RawSource? = { id -> recipes.imagePath(id)?.let { SystemFileSystem.source(Path(it)) } },
) {
    private val _progress = MutableStateFlow<BackupProgress?>(null)
    /** Items done of the total while an export or restore runs; null otherwise. */
    val progress: StateFlow<BackupProgress?> = _progress.asStateFlow()

    /** The largest amount of line and picture data this class held at once; tests check it stays flat. */
    internal var peakBufferBytes = 0L
        private set

    /**
     * Completes the picture sync and checks every picture is on the device; only then [save] opens the save dialog and
     * runs the writer on the chosen file (false when the user cancelled). The writer does not close the sink.
     */
    suspend fun export(save: suspend (write: suspend (Sink) -> Unit) -> Boolean): ExportResult {
        if (!recipes.sync()) return ExportResult.Offline
        val all = recipes.recipes.value
        val missing = all.filter { r -> r.imageIds.any { pictureSize(it) == null } }
        if (missing.isNotEmpty()) return ExportResult.MissingPictures(missing.map { it.title })
        val pictures = all.flatMap { r -> r.imageIds.distinct().map { id -> Triple(r.id, id, pictureSize(id) ?: 0L) } }
        val header = BackupHeader(FORMAT, VERSION, all.size, pictures.size, pictures.sumOf { it.third })
        var result: ExportResult = ExportResult.Done(all.size, pictures.size)
        val saved = try {
            save { sink -> result = write(sink, header, all, pictures) }
        } finally {
            _progress.value = null
        }
        return if (saved) result else ExportResult.Cancelled
    }

    private fun write(sink: Sink, header: BackupHeader, all: List<Recipe>, pictures: List<Triple<String, String, Long>>): ExportResult {
        val total = all.size + pictures.size
        _progress.value = BackupProgress(0, total)
        sink.writeString(json.encodeToString(BackupHeader.serializer(), header) + "\n")
        all.forEachIndexed { i, r ->
            sink.writeString(json.encodeToString(RecipeLine.serializer(), RecipeLine("recipe", r.toInput())) + "\n")
            _progress.value = BackupProgress(i + 1, total)
        }
        pictures.forEachIndexed { i, (recipeId, id, size) ->
            // Removed from the device after the check (a sync dropped it): stop rather than write a broken file.
            val source = openPicture(id)?.buffered() ?: return ExportResult.MissingPictures(all.filter { it.id == recipeId }.map { it.title })
            source.use { writePicture(sink, recipeId, id, size, it) }
            _progress.value = BackupProgress(all.size + i + 1, total)
        }
        sink.flush()
        return ExportResult.Done(all.size, pictures.size)
    }

    /** Reads the header and counts the picture bytes the account does not hold yet, for the warning before a restore. */
    suspend fun check(source: Source): RestoreCheck {
        val header = readHeader(source) ?: return RestoreCheck.Refused
        if (!recipes.sync()) return RestoreCheck.Offline
        val held = recipes.recipes.value.associate { it.id to it.imageIds.toSet() }
        var newBytes = 0L
        while (true) {
            val line = readLine(source) ?: break
            val picture = (line as? Line.Text)?.let { decodeLine(it.text) } as? PictureLine ?: continue
            if (picture.id !in held[picture.recipeId].orEmpty()) newBytes += picture.size
        }
        val me = (api.me() as? ApiResult.Ok)?.value ?: return RestoreCheck.Offline
        val free = (me.storage.capBytes - me.storage.usedBytes).coerceAtLeast(0)
        return RestoreCheck.Ready(RestorePlan(header.recipes, header.pictures, newBytes, free))
    }

    suspend fun restore(source: Source): RestoreResult {
        val header = readHeader(source) ?: return RestoreResult.Refused
        if (!recipes.sync()) return RestoreResult.Interrupted
        val token = currentToken()
        val server = recipes.recipes.value.associateBy { it.id }.toMutableMap()
        val fileImages = mutableMapOf<String, List<String>>()
        val titles = mutableMapOf<String, String>()
        val recipeProblems = mutableMapOf<String, String>()
        val skippedRecipes = mutableListOf<Skipped>()
        val skippedPictures = mutableListOf<Skipped>()
        var recipesWritten = 0
        var recipesUnchanged = 0
        var picturesWritten = 0
        var picturesUnchanged = 0
        var malformed = 0
        var seen = 0
        val total = header.recipes + header.pictures

        /** Null when written; the server's code when refused. Throws [Stop] when the restore cannot go on. */
        suspend fun put(input: RecipeInput, rev: Long?): String? = when (val result = api.putRecipe(input, rev)) {
            is ApiResult.Ok -> null.also { server[input.id] = result.value }
            else -> refusal(result, token)
        }

        try {
            _progress.value = BackupProgress(0, total)
            while (true) {
                val line = readLine(source) ?: break
                seen++
                when (val item = (line as? Line.Text)?.let { decodeLine(it.text) }) {
                    is RecipeLine -> {
                        val input = item.recipe.copy(imageIds = item.recipe.imageIds.distinct())
                        if (!UUID.matches(input.id) || input.id in titles) {
                            malformed++
                            continue
                        }
                        titles[input.id] = input.title
                        fileImages[input.id] = input.imageIds
                        val current = server[input.id]
                        // Pictures are attached once uploaded; until then the recipe lists only those the server holds.
                        val desired = input.copy(imageIds = input.imageIds.filter { it in current?.imageIds.orEmpty() })
                        if (current != null && current.toInput() == desired) {
                            recipesUnchanged++
                        } else {
                            val code = put(desired, current?.rev)
                            if (code == null) recipesWritten++ else {
                                recipeProblems[input.id] = code
                                skippedRecipes += Skipped(input.title, code)
                            }
                        }
                    }
                    is PictureLine -> {
                        val order = fileImages[item.recipeId]
                        val bytes = runCatching { Base64.decode(item.data) }.getOrNull()
                        track(line, bytes?.size ?: 0)
                        if (order == null || item.id !in order || bytes == null || bytes.size.toLong() != item.size || item.mime !in MIMES) {
                            malformed++
                            continue
                        }
                        val title = titles.getValue(item.recipeId)
                        val current = server[item.recipeId]
                        when {
                            current == null -> skippedPictures += Skipped(title, recipeProblems[item.recipeId] ?: "recipe_missing")
                            item.id in current.imageIds -> picturesUnchanged++
                            else -> when (val uploaded = api.putImage(item.recipeId, item.id, bytes, item.mime)) {
                                is ApiResult.Ok -> {
                                    val attach = current.toInput().copy(imageIds = order.filter { it in current.imageIds || it == item.id })
                                    val code = put(attach, current.rev)
                                    if (code == null) picturesWritten++ else skippedPictures += Skipped(title, code)
                                }
                                else -> skippedPictures += Skipped(title, refusal(uploaded, token))
                            }
                        }
                    }
                    else -> malformed++
                }
                _progress.value = BackupProgress(seen.coerceAtMost(total), total)
            }
        } catch (_: Stop) {
            return RestoreResult.Interrupted
        } finally {
            _progress.value = null
        }
        recipes.sync()
        malformed += (total - seen).coerceAtLeast(0)
        return RestoreResult.Done(
            recipesWritten, recipesUnchanged, picturesWritten, picturesUnchanged, skippedRecipes, skippedPictures, malformed,
        )
    }

    private class Stop : Exception()

    /** The code of a refusal worth skipping, or [Stop] for anything that stops the whole restore. */
    private suspend fun refusal(result: ApiResult<*>, token: String?): String {
        if (result !is ApiResult.Failure || result.status == 429 || result.status >= 500) throw Stop()
        if (result.status == 401) {
            if (token != null && token == currentToken()) onUnauthorized()
            throw Stop()
        }
        return result.wire
    }

    private fun writePicture(sink: Sink, recipeId: String, id: String, size: Long, source: Source) {
        val chunk = ByteArray(CHUNK)
        var first = true
        while (true) {
            val n = fill(source, chunk)
            if (first) {
                sink.writeString("""{"type":"picture","recipeId":"$recipeId","id":"$id","mime":"${mimeOf(chunk, n)}","size":$size,"data":"""")
                first = false
            }
            if (n == 0) break
            val text = Base64.encode(chunk, 0, n)
            peakBufferBytes = maxOf(peakBufferBytes, (CHUNK + text.length * 2).toLong())
            sink.writeString(text)
            if (n < CHUNK) break
        }
        sink.writeString("\"}\n")
    }

    /** Fills [into] from [source] (a multiple of 3 bytes, so base64 never pads mid-picture); fewer only at the end. */
    private fun fill(source: Source, into: ByteArray): Int {
        var n = 0
        while (n < into.size) {
            val read = source.readAtMostTo(into, n, into.size)
            if (read <= 0) break
            n += read
        }
        return n
    }

    private sealed interface Line {
        class Text(val text: String) : Line
        data object TooLong : Line
    }

    /** The next line without its newline, or null at the end. A line over [MAX_LINE] bytes is skipped unread. */
    private fun readLine(source: Source): Line? {
        if (source.exhausted()) return null
        val newline = source.indexOf(NEWLINE, 0, MAX_LINE + 1)
        if (newline >= 0) {
            val text = source.readString(newline)
            source.skip(1)
            return Line.Text(text)
        }
        // No newline within the limit: the last line, or one too long to hold.
        if (!source.request(MAX_LINE + 1)) return Line.Text(source.readString())
        while (true) {
            val end = source.indexOf(NEWLINE, 0, CHUNK.toLong())
            if (end >= 0) {
                source.skip(end + 1)
                break
            }
            if (!source.request(CHUNK.toLong())) {
                source.transferTo(discardingSink())
                break
            }
            source.skip(CHUNK.toLong())
        }
        return Line.TooLong
    }

    private fun readHeader(source: Source): BackupHeader? {
        val line = readLine(source) as? Line.Text ?: return null
        val header = runCatching { json.decodeFromString(BackupHeader.serializer(), line.text) }.getOrNull() ?: return null
        val sane = header.format == FORMAT && header.version == VERSION && header.recipes >= 0 && header.pictures >= 0
        return header.takeIf { sane }
    }

    private fun decodeLine(text: String): Any? = runCatching {
        val element = json.parseToJsonElement(text) as JsonObject
        when (element["type"]?.jsonPrimitive?.contentOrNull) {
            "recipe" -> json.decodeFromJsonElement(RecipeLine.serializer(), element)
            "picture" -> json.decodeFromJsonElement(PictureLine.serializer(), element)
            else -> null
        }
    }.getOrNull()

    private fun track(line: Line, decoded: Int) {
        val held = ((line as? Line.Text)?.text?.length ?: 0) * 2L + decoded
        peakBufferBytes = maxOf(peakBufferBytes, held)
    }

    companion object {
        const val FORMAT = "recipes-backup"
        const val VERSION = 1

        /** A picture is at most 1,572,864 bytes, 2,097,152 in base64; the rest of its line is short. */
        const val MAX_LINE = 2_200_000L
        private const val CHUNK = 3 * 16_384
        private const val NEWLINE = '\n'.code.toByte()
        private val MIMES = setOf("image/jpeg", "image/png", "image/webp")
        private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        private val json = Api.apiJson

        /** The type by its magic bytes; JPEG when unknown (the app itself only ever stores JPEG). */
        internal fun mimeOf(bytes: ByteArray, n: Int): String = when {
            n >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "image/jpeg"
            n >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() -> "image/png"
            n >= 12 && bytes.decodeToString(0, 4) == "RIFF" && bytes.decodeToString(8, 12) == "WEBP" -> "image/webp"
            else -> "image/jpeg"
        }
    }
}
