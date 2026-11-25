package com.linux.recipes

import androidx.compose.runtime.staticCompositionLocalOf
import com.linux.recipes.data.Api
import com.linux.recipes.data.DefaultRecipeRepository
import com.linux.recipes.data.DefaultSessionRepository
import com.linux.recipes.data.RecipeCache
import com.linux.recipes.data.RecipeRepository
import com.linux.recipes.data.SessionRepository
import com.linux.recipes.data.TokenStore
import com.linux.recipes.platform.AuthProvider
import com.linux.recipes.platform.SecureStore
import com.linux.recipes.platform.TurnstileGate
import io.ktor.client.HttpClient
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * The app's object graph, built once per process by the platform entry point. No DI framework.
 * [filesDir] is the app's private files directory; the offline cache lives in `<filesDir>/recipes-data`.
 */
class AppGraph(
    filesDir: String,
    secureStore: SecureStore,
    authProvider: AuthProvider,
    httpClient: HttpClient = Api.httpClient(),
    val baseUrl: String = AppConfig.API_BASE_URL,
) {
    val turnstile = TurnstileGate()
    private val tokens = TokenStore(
        secureStore.also { clearStaleTokenOnFirstLaunch(Path(filesDir), it) },
        // Without the marker the next start counts as a first launch and removes the token again.
        onClearFailed = { SystemFileSystem.delete(Path(filesDir, INSTALL_MARKER), mustExist = false) },
    )
    private val api = Api(httpClient, baseUrl) { tokens.token }

    private val recipeRepositoryImpl: DefaultRecipeRepository = DefaultRecipeRepository(
        api = api,
        cache = RecipeCache(Path(filesDir, CACHE_DIR)),
        currentToken = { tokens.token },
        onUnauthorized = { session.signOut() },
    )
    val recipes: RecipeRepository = recipeRepositoryImpl

    val session: SessionRepository = DefaultSessionRepository(
        api = api,
        auth = authProvider,
        turnstile = turnstile,
        tokens = tokens,
        clearLocal = { recipeRepositoryImpl.clearLocal() },
    )

    companion object {
        const val CACHE_DIR = "recipes-data"
        const val INSTALL_MARKER = "installed"

        /**
         * A reinstall starts signed out. iOS Keychain items survive deleting the app, the app's files do not:
         * when the marker file is missing, this is the first launch after installation, so the stored token is
         * removed before anything reads it, and the marker is written. On Android both live in app storage.
         * A sign-out whose removal failed deletes the marker, so this retries it. The marker is written only once
         * the removal worked; while the store is unavailable it reads nothing either, so the start is signed out.
         */
        fun clearStaleTokenOnFirstLaunch(filesDir: Path, secureStore: SecureStore, fs: FileSystem = SystemFileSystem) {
            val marker = Path(filesDir, INSTALL_MARKER)
            if (fs.exists(marker)) return
            if (!(secureStore.remove(TokenStore.KEY) and secureStore.remove(TokenStore.USER_ID_KEY))) return
            fs.createDirectories(filesDir)
            fs.sink(marker).buffered().use { it.write(ByteArray(0)) }
        }
    }
}

/** Provided by `MainActivity` and `MainViewController` around `App()`. */
val LocalAppGraph = staticCompositionLocalOf<AppGraph> { error("AppGraph is not provided") }
