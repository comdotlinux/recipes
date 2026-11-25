package com.linux.recipes.ui

import com.linux.recipes.data.ReportReason
import com.linux.recipes.data.ShareAccept
import com.linux.recipes.data.ShareCreate
import com.linux.recipes.data.ShareLink
import com.linux.recipes.data.ShareOpen
import com.linux.recipes.data.SharePreview
import com.linux.recipes.data.ShareRepository
import com.linux.recipes.data.SharedRecipe
import com.linux.recipes.ui.share.FileActions
import com.linux.recipes.ui.share.PickedFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

/** The system dialogs as a recording double: shared texts and files, saved files, and the file the open dialog returns. */
class FakeFileActions(var picked: ByteArray? = null) {
    val sharedTexts = mutableListOf<String>()
    val sharedFiles = mutableListOf<Pair<String, ByteArray>>()
    val saved = mutableListOf<Pair<String, ByteArray>>()
    var cancelSave = false
    var releases = 0

    val actions = FileActions(
        shareText = { sharedTexts += it },
        save = { name, extension, write ->
            if (cancelSave) false else {
                val buffer = Buffer()
                write(buffer)
                saved += "$name.$extension" to buffer.readByteArray()
                true
            }
        },
        pick = { picked?.let { bytes -> PickedFile(open = { Buffer().apply { write(bytes) } }, release = { releases++ }) } },
        shareFile = { name, bytes -> sharedFiles += name to bytes },
    )
}

/**
 * Answers from [createResults] in order (the last one repeats), [openResult], [acceptResult] and [reportResult].
 * An accept adds the copy to [recipes], as the real repository's sync does.
 */
class FakeShareRepository(private val recipes: FakeRecipeRepository? = null) : ShareRepository {
    override val links = MutableStateFlow<List<ShareLink>>(emptyList())
    var createResults = mutableListOf<ShareCreate>(ShareCreate.Created(testLink))
    var openResult: ShareOpen = ShareOpen.Preview(testPreview)
    var acceptResult: ShareAccept = ShareAccept.Accepted(sharedCopy)
    var reportResult = true
    var revokeResult = true
    val created = mutableListOf<String>()
    val reports = mutableListOf<Triple<String, ReportReason, String>>()
    val accepted = mutableListOf<String>()

    override suspend fun create(recipeId: String): ShareCreate {
        created += recipeId
        val result = if (createResults.size > 1) createResults.removeAt(0) else createResults.single()
        if (result is ShareCreate.Created) links.value = listOf(result.link) + links.value
        return result
    }

    override suspend fun revoke(shareId: String): Boolean {
        if (revokeResult) links.value = links.value.filterNot { it.shareId == shareId }
        return revokeResult
    }

    override suspend fun open(token: String): ShareOpen = openResult

    override suspend fun accept(token: String): ShareAccept {
        accepted += token
        val result = acceptResult
        if (result is ShareAccept.Accepted) recipes?.let { it.recipes.value = listOf(result.recipe) + it.recipes.value }
        return result
    }

    override suspend fun report(token: String, reason: ReportReason, note: String): Boolean {
        reports += Triple(token, reason, note)
        return reportResult
    }
}

val SHARE_TOKEN = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_".repeat(3).take(107)

val testLink = ShareLink(
    shareId = "3f2a9c4d5e6b7a8f9c0d1e2f3a4b5c6d",
    recipeId = "0b9f6c1e-3a52-4d0f-9a43-2f1d0c6b7e11",
    url = "https://recipe.munich-tech.de/s#$SHARE_TOKEN",
    expiresAt = "2026-12-25T10:00:00Z",
)

val testPreview = SharePreview(
    recipe = SharedRecipe(
        title = "Apfelstrudel",
        description = "Thin dough, lots of apples.",
        servings = 6,
        prepMinutes = 40,
        cookMinutes = 45,
        ingredients = potatoSalad.ingredients.take(2),
        steps = listOf("Stretch the dough very thin.", "Fill, roll and bake."),
        tags = listOf("Baking"),
        language = "de",
    ),
    imageCount = 2,
    expiresAt = "2026-12-25T10:00:00Z",
)

val sharedCopy = potatoSalad.copy(
    id = "5b8e1f0a-2c3d-4e5f-a6b7-c8d9e0f1a2b3",
    rev = 12,
    title = "Apfelstrudel",
    sourceType = "shared",
    sourceUrl = null,
    imageIds = emptyList(),
)
