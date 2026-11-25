package com.linux.recipes.data

import kotlinx.coroutines.flow.StateFlow

// Signatures fixed by docs/api.md, "Module boundaries / Client". Change only together with that section.

sealed interface SessionState {
    data object SignedOut : SessionState
    data class SignedIn(val userId: String) : SessionState
}

sealed interface SignInResult {
    data object Success : SignInResult
    data object Cancelled : SignInResult
    data class Failed(val reason: Reason) : SignInResult {
        enum class Reason { Network, Rejected, AgeNotConfirmed, AccountDeleting, RateLimited }
    }
}

interface SessionRepository {
    val state: StateFlow<SessionState>
    /** Runs the platform sign-in, the Turnstile check and the server call. */
    suspend fun signIn(ageConfirmed: Boolean): SignInResult
    /** Revokes the session on the server when reachable and always wipes local data. */
    suspend fun signOut()
}

sealed interface SaveResult {
    data class Saved(val recipe: Recipe) : SaveResult
    data object Conflict : SaveResult          // 412: someone else changed it
    data object Offline : SaveResult
    data class Rejected(val code: String) : SaveResult
}

interface RecipeRepository {
    /** Cached recipes without deletion markers, newest change first. Works offline. */
    val recipes: StateFlow<List<Recipe>>
    /** Pulls changes and missing pictures. False when the server could not be reached. */
    suspend fun sync(): Boolean
    /** Creates when `expectedRev` is null, otherwise updates that revision. Uploads new pictures first. */
    suspend fun save(recipe: RecipeInput, expectedRev: Long?, newImages: List<NewImage>): SaveResult
    suspend fun delete(id: String): Boolean
    /** Absolute path of a cached picture, or null when it is not on the device yet. */
    fun imagePath(imageId: String): String?
}

/** A picture chosen by the user, already compressed to JPEG of at most 1.5 MB. */
class NewImage(val id: String, val bytes: ByteArray)
