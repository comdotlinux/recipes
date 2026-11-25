package com.linux.recipes.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [RecipeRepository] over [Api] and [RecipeCache]. One mutex serialises cache writes (sync, save, delete,
 * wipe), so a sync that finishes after sign-out cannot write the old user's data back. Picture downloads run
 * outside the lock and are dropped when the cache was wiped in the meantime.
 *
 * On a 401, [onUnauthorized] runs (outside the lock), but only when the token used for that call, taken from
 * [currentToken] before the call, is still the current one: a slow call with an old token never ends a newer
 * session. A 5xx answer counts as "server not reachable right now", never as a rejection.
 */
class DefaultRecipeRepository(
    private val api: Api,
    private val cache: RecipeCache,
    private val currentToken: () -> String? = { null },
    private val onUnauthorized: suspend () -> Unit,
) : RecipeRepository {
    private val mutex = Mutex()
    private var generation = 0
    private val _recipes = MutableStateFlow(sorted(cache.readRecipes()))
    override val recipes: StateFlow<List<Recipe>> = _recipes.asStateFlow()

    override suspend fun sync(): Boolean {
        val token = currentToken()
        return when (val pull = mutex.withLock { pullLocked() }) {
            Pull.Unreachable -> false
            Pull.Unauthorized -> false.also { unauthorized(token) }
            is Pull.Done -> downloadImages(pull, token)
        }
    }

    override suspend fun save(recipe: RecipeInput, expectedRev: Long?, newImages: List<NewImage>): SaveResult {
        val token = currentToken()
        // The server rejects duplicate picture ids with invalid_body.
        val input = recipe.copy(imageIds = recipe.imageIds.distinct())
        val images = newImages.distinctBy { it.id }
        var unauthorized = false
        val result = mutex.withLock {
            // A create that got past the create step earlier is in the cache with the server's revision.
            var rev = expectedRev ?: _recipes.value.firstOrNull { it.id == input.id }?.rev
            val newIds = images.map { it.id }.toSet()
            // A picture can only be uploaded to an existing recipe: a create with pictures creates without them first.
            if (rev == null && images.isNotEmpty()) {
                when (val created = putLocked(input.copy(imageIds = input.imageIds - newIds), null)) {
                    is ApiResult.Ok -> {
                        upsert(created.value)
                        rev = created.value.rev
                    }
                    else -> return@withLock saveFailure(created).also { unauthorized = created.isUnauthorized() }
                }
            }
            for (image in images) {
                when (val uploaded = api.putImage(input.id, image.id, image.bytes)) {
                    is ApiResult.Ok -> cache.writeImage(image.id, image.bytes)
                    else -> return@withLock saveFailure(uploaded).also { unauthorized = uploaded.isUnauthorized() }
                }
            }
            when (val saved = putLocked(input, rev)) {
                is ApiResult.Ok -> SaveResult.Saved(saved.value).also { upsert(saved.value) }
                else -> saveFailure(saved).also { unauthorized = saved.isUnauthorized() }
            }
        }
        if (unauthorized) unauthorized(token)
        return result
    }

    override suspend fun delete(id: String): Boolean {
        val token = currentToken()
        var unauthorized = false
        val done = mutex.withLock {
            val result = api.deleteRecipe(id)
            unauthorized = result.isUnauthorized()
            // 404: never existed or the deletion marker was purged after 90 days; either way it is gone.
            val gone = result is ApiResult.Ok || (result is ApiResult.Failure && result.status == 404)
            if (gone) {
                store(_recipes.value.filter { it.id != id })
                cache.removeImagesExcept(referencedImages())
            }
            gone
        }
        if (unauthorized) unauthorized(token)
        return done
    }

    override fun imagePath(imageId: String): String? = cache.imagePath(imageId)

    /** Wipes every cached file and empties [recipes]; used on sign-out and before a new sign-in. */
    suspend fun clearLocal() = mutex.withLock {
        generation++
        cache.wipe()
        _recipes.value = emptyList()
    }

    private sealed interface Pull {
        data class Done(val missingImages: List<String>, val generation: Int) : Pull
        data object Unreachable : Pull
        data object Unauthorized : Pull
    }

    /** Fetches every page from the cursor (from 0 after 410), then applies them in one step. Caller holds the lock. */
    private suspend fun pullLocked(): Pull {
        var since = cache.readSettings().cursor
        var full = since == 0L
        var restarted = false
        val items = mutableListOf<Recipe>()
        while (true) {
            when (val page = api.recipes(since)) {
                ApiResult.Offline -> return Pull.Unreachable
                is ApiResult.Failure -> when {
                    // 410 also mid full pull (markers purged between pages): start over from 0, at most once.
                    page.code == ErrorCode.CursorExpired && !restarted -> {
                        restarted = true
                        full = true
                        since = 0
                        items.clear()
                    }
                    page.status == 401 -> return Pull.Unauthorized
                    else -> return Pull.Unreachable
                }
                is ApiResult.Ok -> {
                    items += page.value.recipes
                    // A page that claims more without advancing the cursor would loop forever.
                    if (page.value.more && page.value.rev <= since) return Pull.Unreachable
                    since = page.value.rev
                    if (!page.value.more) break
                }
            }
        }
        val byId = if (full) mutableMapOf() else _recipes.value.associateBy { it.id }.toMutableMap()
        for (item in items) {
            if (item.deleted) byId.remove(item.id) else byId[item.id] = item
        }
        // Recipes before the cursor: a crash in between only repeats this pull.
        store(byId.values)
        cache.writeSettings(RecipeCache.Settings(cursor = since))
        val referenced = referencedImages()
        cache.removeImagesExcept(referenced)
        return Pull.Done(referenced.filter { cache.imagePath(it) == null }, generation)
    }

    private suspend fun downloadImages(pull: Pull.Done, token: String?): Boolean {
        for (imageId in pull.missingImages) {
            when (val result = api.image(imageId)) {
                is ApiResult.Ok -> mutex.withLock {
                    // Dropped when the cache was wiped (sign-out) or the recipe lost the picture meanwhile.
                    if (generation == pull.generation && imageId in referencedImages()) cache.writeImage(imageId, result.value)
                }
                ApiResult.Offline -> return false
                is ApiResult.Failure -> when (result.status) {
                    // 404: deleted on the server in the meantime; the next sync drops the reference.
                    404 -> Unit
                    401 -> return false.also { unauthorized(token) }
                    // 429, 5xx and the rest: try again on the next sync.
                    else -> return false
                }
            }
        }
        return true
    }

    /**
     * PUT with the create/update precondition. A create answered with 412 may be our own earlier create whose
     * response was lost: sync once, and if the id is there now, retry once as an update of that revision.
     */
    private suspend fun putLocked(input: RecipeInput, rev: Long?): ApiResult<Recipe> {
        val first = api.putRecipe(input, rev)
        if (rev != null || first !is ApiResult.Failure || first.status != 412) return first
        if (pullLocked() !is Pull.Done) return first
        val existing = _recipes.value.firstOrNull { it.id == input.id }?.rev ?: return first
        return api.putRecipe(input, existing)
    }

    private suspend fun unauthorized(usedToken: String?) {
        if (usedToken != null && usedToken == currentToken()) onUnauthorized()
    }

    private fun referencedImages(): Set<String> = _recipes.value.flatMap { it.imageIds }.toSet()

    private fun upsert(recipe: Recipe) = store(_recipes.value.filter { it.id != recipe.id } + recipe)

    private fun store(recipes: Collection<Recipe>) {
        val list = sorted(recipes)
        cache.writeRecipes(list)
        _recipes.value = list
    }

    private fun saveFailure(result: ApiResult<*>): SaveResult = when {
        result !is ApiResult.Failure -> SaveResult.Offline
        result.status == 412 -> SaveResult.Conflict
        result.status >= 500 || result.status == 429 -> SaveResult.Offline
        else -> SaveResult.Rejected(result.wire)
    }

    private fun ApiResult<*>.isUnauthorized() = this is ApiResult.Failure && status == 401

    private companion object {
        // rev grows with every change of this user, so it orders by "newest change first".
        fun sorted(recipes: Collection<Recipe>) = recipes.filter { !it.deleted }.sortedByDescending { it.rev }
    }
}
