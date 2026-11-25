package com.linux.recipes.ui

import com.linux.recipes.data.ConsentRepository
import com.linux.recipes.data.ConsentState
import com.linux.recipes.data.ImportJobState
import com.linux.recipes.data.ImportOutcome
import com.linux.recipes.data.ImportRepository
import com.linux.recipes.data.ImportSource
import com.linux.recipes.data.ImportStart
import com.linux.recipes.data.Ingredient
import com.linux.recipes.data.Quota
import com.linux.recipes.data.ReportReason
import com.linux.recipes.data.NewImage
import com.linux.recipes.data.Recipe
import com.linux.recipes.data.RecipeInput
import com.linux.recipes.data.RecipeRepository
import com.linux.recipes.data.SaveResult
import com.linux.recipes.data.SessionRepository
import com.linux.recipes.data.SessionState
import com.linux.recipes.data.SignInResult
import com.linux.recipes.platform.TurnstileGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Answers [start] with [startResult]; [ImportStart.Started] and [ImportStart.AlreadyRunning] open a job
 * in [activeJob], as the real repository does. Tests finish the job by setting [activeJob] themselves.
 */
class FakeImportRepository(job: ImportJobState? = null) : ImportRepository {
    override val activeJob = MutableStateFlow(job)
    var startResult: ImportStart = ImportStart.Started("")
    var quotaResult: Quota? = Quota(freeImportUsed = false, monthUsed = 0, monthLimit = 30, entitled = false)
    var reportResult = true
    val starts = mutableListOf<Pair<ImportSource, String>>()
    val acknowledged = mutableListOf<String>()
    val reports = mutableListOf<Triple<String, ReportReason, String>>()
    var resumeCalls = 0

    override suspend fun start(source: ImportSource, jobId: String): ImportStart {
        starts += source to jobId
        val result = startResult
        if (result is ImportStart.Started) activeJob.value = ImportJobState(jobId, "url", outcome = null, offline = false)
        if (result == ImportStart.AlreadyRunning) activeJob.value = ImportJobState("running-job", "url", outcome = null, offline = false)
        return result
    }

    override suspend fun resume() {
        resumeCalls++
    }

    override fun acknowledge(jobId: String) {
        acknowledged += jobId
        if (activeJob.value?.jobId == jobId) activeJob.value = null
    }

    override suspend fun quota(): Quota? = quotaResult

    override suspend fun report(jobId: String, reason: ReportReason, note: String): Boolean {
        reports += Triple(jobId, reason, note)
        return reportResult
    }

    fun finish(outcome: ImportOutcome) {
        activeJob.value = activeJob.value!!.copy(outcome = outcome)
    }
}

class FakeConsentRepository(given: Boolean = true) : ConsentRepository {
    override val currentVersion = 1
    override val state = MutableStateFlow<ConsentState>(if (given) ConsentState.Given(1) else ConsentState.NotGiven)
    var online = true
    var acceptCalls = 0

    override suspend fun accept(): Boolean {
        acceptCalls++
        if (online) state.value = ConsentState.Given(currentVersion)
        return online
    }

    override suspend fun withdraw(): Boolean {
        if (online) state.value = ConsentState.NotGiven
        return online
    }
}

/** What the server returns for a finished link import. */
val importedDraft = RecipeInput(
    id = "7d3e2c1b-0a9f-4e8d-8c7b-6a5f4e3d2c1b",
    title = "Pasta e ceci",
    description = "Chickpeas and small pasta in one pot.",
    servings = 4,
    cookMinutes = 30,
    ingredients = listOf(Ingredient("400", "g", "chickpeas"), Ingredient("150", "g", "ditalini")),
    steps = listOf("Soften garlic and rosemary in oil.", "Add chickpeas, stock and pasta; simmer until thick."),
    sourceUrl = "https://example.com/pasta-e-ceci",
    sourceType = "url",
    language = "en",
)

const val USER_ID = "Qm9vay1ib3gtdGVzdC11c2VyLWlkLTAwMDAwMDAwMDA"

/** [turnstile], when set, is awaited like the real repository does: a null token means Cancelled. */
class FakeSessionRepository(signedIn: Boolean = false, val turnstile: TurnstileGate? = null) : SessionRepository {
    override val state = MutableStateFlow<SessionState>(if (signedIn) SessionState.SignedIn(USER_ID) else SessionState.SignedOut)
    var result: SignInResult = SignInResult.Success
    /** When set, signIn suspends until it completes; lets a test see the progress state. */
    var gate: CompletableDeferred<Unit>? = null
    val ageArguments = mutableListOf<Boolean>()
    var signOutCalls = 0

    override suspend fun signIn(ageConfirmed: Boolean): SignInResult {
        ageArguments += ageConfirmed
        gate?.await()
        if (turnstile != null && turnstile.awaitToken() == null) return SignInResult.Cancelled
        if (result == SignInResult.Success) state.value = SessionState.SignedIn(USER_ID)
        return result
    }

    override suspend fun signOut() {
        signOutCalls++
        state.value = SessionState.SignedOut
    }
}

class FakeRecipeRepository(initial: List<Recipe> = emptyList()) : RecipeRepository {
    override val recipes = MutableStateFlow(initial)
    var online = true
    var syncCalls = 0
    /** A result to return instead of saving; null saves into [recipes]. */
    var saveResult: SaveResult? = null
    val saved = mutableListOf<Triple<RecipeInput, Long?, List<NewImage>>>()
    var deleteOk = true
    val images = mutableMapOf<String, String>()
    /** Applied on the next sync, as if another device had changed the data. */
    var onNextSync: (() -> Unit)? = null

    /** When set, sync suspends until it completes; lets a test see the first-load state. */
    var syncGate: CompletableDeferred<Unit>? = null

    override suspend fun sync(): Boolean {
        syncCalls++
        syncGate?.await()
        onNextSync?.invoke()
        onNextSync = null
        return online
    }

    override suspend fun save(recipe: RecipeInput, expectedRev: Long?, newImages: List<NewImage>): SaveResult {
        saved += Triple(recipe, expectedRev, newImages)
        saveResult?.let { return it }
        val stored = recipe.toRecipe(rev = (recipes.value.maxOfOrNull { it.rev } ?: 0) + 1)
        recipes.value = listOf(stored) + recipes.value.filterNot { it.id == recipe.id }
        return SaveResult.Saved(stored)
    }

    override suspend fun delete(id: String): Boolean {
        if (deleteOk) recipes.value = recipes.value.filterNot { it.id == id }
        return deleteOk
    }

    override fun imagePath(imageId: String): String? = images[imageId]
}

fun RecipeInput.toRecipe(rev: Long) = Recipe(
    id = id, rev = rev, updatedAt = "2026-11-24T18:00:00Z", title = title, description = description,
    servings = servings, prepMinutes = prepMinutes, cookMinutes = cookMinutes, ingredients = ingredients,
    steps = steps, tags = tags, sourceUrl = sourceUrl, sourceType = sourceType, language = language, imageIds = imageIds,
)

val potatoSalad = Recipe(
    id = "0b9f6c1e-3a52-4d0f-9a43-2f1d0c6b7e11",
    rev = 7,
    updatedAt = "2026-11-24T18:00:00Z",
    title = "Kartoffelsalat",
    description = "The Swabian way: warm potatoes, broth and vinegar, no mayonnaise.",
    servings = 4,
    prepMinutes = 20,
    cookMinutes = 25,
    ingredients = listOf(
        Ingredient("1", "kg", "waxy potatoes"),
        Ingredient("1", "", "red onion"),
        Ingredient("250", "ml", "hot beef broth", group = "Dressing"),
        Ingredient("4", "tbsp", "white wine vinegar", group = "Dressing"),
        Ingredient("1", "tsp", "mustard", group = "Dressing"),
    ),
    steps = listOf(
        "Boil the potatoes in their skins until just tender, about 25 minutes.",
        "Peel while warm and slice thinly into a bowl with the chopped onion.",
        "Pour over the hot broth with vinegar and mustard, toss gently and rest for 30 minutes.",
    ),
    tags = listOf("Side", "Summer"),
    sourceUrl = "https://example.com/kartoffelsalat",
    language = "de",
    imageIds = listOf("img-1"),
)

val shakshuka = Recipe(
    id = "4f1d2a77-8a0c-4e5e-9f0e-1b2c3d4e5f60",
    rev = 9,
    updatedAt = "2026-11-24T19:00:00Z",
    title = "Shakshuka",
    servings = 2,
    prepMinutes = 10,
    cookMinutes = 20,
    ingredients = listOf(Ingredient("4", "", "eggs"), Ingredient("1", "can", "tomatoes")),
    steps = listOf("Simmer the sauce.", "Crack in the eggs and cover."),
    tags = listOf("Breakfast", "Vegetarian"),
    language = "en",
)

val lemonCake = Recipe(
    id = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d",
    rev = 3,
    updatedAt = "2026-11-23T10:00:00Z",
    title = "Lemon drizzle cake",
    servings = 12,
    prepMinutes = 15,
    cookMinutes = 45,
    tags = listOf("Baking"),
    language = "en",
)

/** Only a title, as a quick first save leaves it. */
val titleOnly = Recipe(id = "1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f", rev = 2, updatedAt = "2026-11-24T20:00:00Z", title = "Grandma's goulash", language = "en")
