package com.linux.recipes

import androidx.compose.runtime.staticCompositionLocalOf
import com.linux.recipes.data.Api
import com.linux.recipes.data.Backup
import com.linux.recipes.data.DefaultShareRepository
import com.linux.recipes.data.PdfDownload
import com.linux.recipes.ui.share.SharingServices
import com.linux.recipes.data.BillingRepository
import com.linux.recipes.data.DefaultBillingRepository
import com.linux.recipes.data.ConsentRepository
import com.linux.recipes.data.DefaultConsentRepository
import com.linux.recipes.data.DefaultImportRepository
import com.linux.recipes.data.DefaultRecipeRepository
import com.linux.recipes.data.DefaultSessionRepository
import com.linux.recipes.data.DefaultTranslationRepository
import com.linux.recipes.data.TranslationRepository
import com.linux.recipes.ui.theme.AppearanceStore
import com.linux.recipes.data.ImportRepository
import com.linux.recipes.data.RecipeCache
import com.linux.recipes.data.RecipeRepository
import com.linux.recipes.data.SessionRepository
import com.linux.recipes.data.TokenStore
import com.linux.recipes.platform.AuthProvider
import com.linux.recipes.platform.BillingProvider
import com.linux.recipes.platform.RevenueCatBillingProvider
import com.linux.recipes.platform.SecureStore
import com.linux.recipes.platform.TextRecognizer
import com.linux.recipes.platform.TurnstileGate
import com.linux.recipes.platform.platformTextRecognizer
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * The app's object graph, built once per process by the platform entry point. No DI framework.
 * [filesDir] is the app's private files directory; the offline cache lives in `<filesDir>/recipes-data`.
 * [billingProvider] builds the store seam; only the debug build's provider uses the session token it is given.
 */
class AppGraph(
    filesDir: String,
    secureStore: SecureStore,
    authProvider: AuthProvider,
    httpClient: HttpClient = Api.httpClient(),
    val baseUrl: String = AppConfig.API_BASE_URL,
    val textRecognizer: TextRecognizer = platformTextRecognizer(),
    billingProvider: (sessionToken: () -> String?) -> BillingProvider = { RevenueCatBillingProvider(apiKey = "", manageFallback = null) },
) {
    val turnstile = TurnstileGate()
    private val tokens = TokenStore(
        secureStore.also { clearStaleTokenOnFirstLaunch(Path(filesDir), it) },
        // Without the marker the next start counts as a first launch and removes the token again.
        onClearFailed = { SystemFileSystem.delete(Path(filesDir, INSTALL_MARKER), mustExist = false) },
    )
    private val api = Api(httpClient, baseUrl) { tokens.token }

    private val cache = RecipeCache(Path(filesDir, CACHE_DIR))

    private val recipeRepositoryImpl: DefaultRecipeRepository = DefaultRecipeRepository(
        api = api,
        cache = cache,
        currentToken = { tokens.token },
        onUnauthorized = { session.signOut() },
    )
    val recipes: RecipeRepository = recipeRepositoryImpl

    private val consentRepositoryImpl = DefaultConsentRepository(
        api = api,
        currentToken = { tokens.token },
        onUnauthorized = { session.signOut() },
    )
    val consent: ConsentRepository = consentRepositoryImpl

    private val importRepositoryImpl = DefaultImportRepository(
        api = api,
        cache = cache,
        consent = consentRepositoryImpl,
        recognizer = textRecognizer,
        currentToken = { tokens.token },
        onTranslated = { recipeRepositoryImpl.sync() },
        onUnauthorized = { session.signOut() },
    )
    val imports: ImportRepository = importRepositoryImpl

    val translations: TranslationRepository =
        DefaultTranslationRepository(api, cache, consentRepositoryImpl, importRepositoryImpl, currentToken = { tokens.token })

    /** A device preference outside [CACHE_DIR], so sign-out keeps it. */
    val appearance = AppearanceStore(Path(filesDir, APPEARANCE_FILE))

    // M5: sharing by link, PDF and backup.
    private val shareRepositoryImpl = DefaultShareRepository(
        api = api,
        cacheDir = Path(filesDir, CACHE_DIR),
        sync = { recipes.sync() },
        currentToken = { tokens.token },
        onUnauthorized = { session.signOut() },
    )
    val sharing = SharingServices(
        shares = shareRepositoryImpl,
        pdf = PdfDownload(api, currentToken = { tokens.token }, onUnauthorized = { session.signOut() }),
        backup = Backup(api, recipes, currentToken = { tokens.token }, onUnauthorized = { session.signOut() }),
        consent = consentRepositoryImpl,
    )

    val session: SessionRepository = DefaultSessionRepository(
        api = api,
        auth = authProvider,
        turnstile = turnstile,
        tokens = tokens,
        clearLocal = {
            // The open import job first: the recipe wipe then removes the whole cache directory.
            importRepositoryImpl.clearLocal()
            consentRepositoryImpl.clearLocal()
            recipeRepositoryImpl.clearLocal()
            shareRepositoryImpl.clearLocal()
        },
    )

    val billing: BillingRepository = DefaultBillingRepository(
        api = api,
        provider = billingProvider { tokens.token },
        session = session,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        currentToken = { tokens.token },
        onUnauthorized = { session.signOut() },
    )

    companion object {
        const val CACHE_DIR = "recipes-data"
        const val INSTALL_MARKER = "installed"
        const val APPEARANCE_FILE = "appearance.json"

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
