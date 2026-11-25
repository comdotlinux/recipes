package com.linux.recipes.data

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.serialization.builtins.ListSerializer

/**
 * [ShareRepository] over [Api]. The links made here are kept in `<cacheDir>/shares.json`, so the cache wipe on
 * sign-out removes them with everything else. [sync] pulls the recipes after an accept, so the copy shows in the list.
 * On a 401 [onUnauthorized] runs when the token of that call is still the current one, as in the other repositories.
 */
@OptIn(ExperimentalTime::class)
class DefaultShareRepository(
    private val api: Api,
    cacheDir: Path,
    private val sync: suspend () -> Boolean,
    private val currentToken: () -> String? = { null },
    private val onUnauthorized: suspend () -> Unit = {},
    private val now: () -> Instant = { Clock.System.now() },
    private val fs: FileSystem = SystemFileSystem,
) : ShareRepository {
    private val file = Path(cacheDir, "shares.json")
    private val stored = MutableStateFlow(read())
    private val _links = MutableStateFlow(active(stored.value))
    override val links: StateFlow<List<ShareLink>> = _links.asStateFlow()

    override suspend fun create(recipeId: String): ShareCreate {
        val token = currentToken()
        return when (val result = api.createShare(recipeId)) {
            is ApiResult.Ok -> {
                val link = ShareLink(result.value.shareId, recipeId, result.value.url, result.value.expiresAt)
                store(listOf(link) + stored.value)
                ShareCreate.Created(link)
            }
            ApiResult.Offline -> ShareCreate.Offline
            is ApiResult.Failure -> when {
                result.wire == "subscription_required" -> ShareCreate.SubscriptionRequired
                result.wire == "consent_required" -> ShareCreate.ConsentRequired
                result.wire == "picture_rejected" -> ShareCreate.PictureRejected
                result.wire == "share_limit" -> ShareCreate.ShareLimit
                result.wire == "screening_unavailable" -> ShareCreate.ScreeningUnavailable
                result.status == 401 -> ShareCreate.Rejected(result.wire).also { unauthorized(token) }
                result.status == 429 || result.status >= 500 -> ShareCreate.Offline
                else -> ShareCreate.Rejected(result.wire)
            }
        }
    }

    override suspend fun revoke(shareId: String): Boolean {
        val token = currentToken()
        val result = api.deleteShare(shareId)
        if (result is ApiResult.Failure && result.status == 401) unauthorized(token)
        // 404: the share no longer exists for this user (revoked elsewhere or purged); it is gone either way.
        val gone = result is ApiResult.Ok || (result is ApiResult.Failure && result.status == 404)
        if (gone) store(stored.value.filter { it.shareId != shareId })
        return gone
    }

    override suspend fun open(token: String): ShareOpen {
        val session = currentToken()
        return when (val result = api.openShare(token)) {
            is ApiResult.Ok -> ShareOpen.Preview(result.value)
            ApiResult.Offline -> ShareOpen.Offline
            is ApiResult.Failure -> when {
                result.wire == "share_gone" -> ShareOpen.Gone
                result.wire == "own_share" -> ShareOpen.OwnShare
                result.status == 404 -> ShareOpen.NotFound
                result.status == 401 -> ShareOpen.Rejected(result.wire).also { unauthorized(session) }
                result.status == 429 || result.status >= 500 -> ShareOpen.Offline
                else -> ShareOpen.Rejected(result.wire)
            }
        }
    }

    override suspend fun accept(token: String): ShareAccept {
        val session = currentToken()
        return when (val result = api.acceptShare(token)) {
            is ApiResult.Ok -> ShareAccept.Accepted(result.value).also { sync() }
            ApiResult.Offline -> ShareAccept.Offline
            is ApiResult.Failure -> when {
                result.wire == "recipe_limit" -> ShareAccept.RecipeLimit
                result.wire == "recipe_storage_cap" -> ShareAccept.RecipeStorageCap
                result.wire == "storage_cap" -> ShareAccept.StorageCap
                result.wire == "share_unreadable" -> ShareAccept.Unreadable
                result.wire == "share_gone" -> ShareAccept.Gone
                result.wire == "own_share" -> ShareAccept.OwnShare
                result.status == 404 -> ShareAccept.NotFound
                result.status == 401 -> ShareAccept.Rejected(result.wire).also { unauthorized(session) }
                result.status == 429 || result.status >= 500 -> ShareAccept.Offline
                else -> ShareAccept.Rejected(result.wire)
            }
        }
    }

    override suspend fun report(token: String, reason: ReportReason, note: String): Boolean {
        val session = currentToken()
        val result = api.reportShare(ShareReport(shareToken = token, reason = reason.wire(), note = note))
        if (result is ApiResult.Failure && result.status == 401) unauthorized(session)
        // 410: the link is already revoked or expired, which is what the report asks for.
        return result is ApiResult.Ok || (result is ApiResult.Failure && result.wire == "share_gone")
    }

    /** Forgets the links after sign-out; the cache wipe has already removed the file. */
    fun clearLocal() {
        stored.value = emptyList()
        _links.value = emptyList()
    }

    private suspend fun unauthorized(usedToken: String?) {
        if (usedToken != null && usedToken == currentToken()) onUnauthorized()
    }

    private fun active(all: List<ShareLink>): List<ShareLink> {
        val at = now()
        return all.filter { link -> runCatching { Instant.parse(link.expiresAt) > at }.getOrDefault(false) }
    }

    private fun store(all: List<ShareLink>) {
        // Expired links are dropped from the file too, so it never grows past the links still working.
        val kept = active(all)
        stored.value = kept
        _links.value = kept
        val dir = requireNotNull(file.parent)
        fs.createDirectories(dir)
        val tmp = Path(dir, "${file.name}.tmp")
        fs.sink(tmp).buffered().use { it.write(Api.apiJson.encodeToString(SERIALIZER, kept).encodeToByteArray()) }
        fs.atomicMove(tmp, file)
    }

    private fun read(): List<ShareLink> {
        if (!fs.exists(file)) return emptyList()
        val text = fs.source(file).buffered().use { it.readByteArray().decodeToString() }
        return runCatching { Api.apiJson.decodeFromString(SERIALIZER, text) }.getOrDefault(emptyList())
    }

    private companion object {
        val SERIALIZER = ListSerializer(ShareLink.serializer())
    }
}
