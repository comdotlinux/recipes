package com.linux.recipes.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.text.intl.Locale
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.linux.recipes.data.ConsentRepository
import com.linux.recipes.data.ImportOutcome
import com.linux.recipes.data.ImportRepository
import com.linux.recipes.data.RecipeRepository
import com.linux.recipes.data.SessionRepository
import com.linux.recipes.data.SessionState
import com.linux.recipes.ui.detail.RecipeDetailScreen
import com.linux.recipes.ui.detail.RecipeDetailViewModel
import com.linux.recipes.ui.edit.RecipeEditScreen
import com.linux.recipes.ui.edit.RecipeEditViewModel
import com.linux.recipes.ui.imports.ImportViewModel
import com.linux.recipes.ui.imports.ReviewImportScreen
import com.linux.recipes.ui.imports.ReviewLocalScreen
import com.linux.recipes.ui.list.RecipeListScreen
import com.linux.recipes.ui.list.RecipeListViewModel
import com.linux.recipes.ui.settings.SettingsScreen
import com.linux.recipes.ui.settings.SettingsViewModel
import com.linux.recipes.ui.signin.SignInScreen
import com.linux.recipes.ui.signin.SignInViewModel
import com.linux.recipes.ui.signin.TurnstileChallenge
import kotlinx.serialization.Serializable

@Serializable data object SignInRoute
@Serializable data object ListRoute
@Serializable data class DetailRoute(val id: String)
/** `id == null` creates a new recipe. */
@Serializable data class EditRoute(val id: String? = null)
@Serializable data object SettingsRoute
/** The editor on the draft of the finished import [jobId]; saving creates a new recipe. */
@Serializable data class ReviewImportRoute(val jobId: String)
/** The editor on the draft of a photo read on the phone, held by the list's [ImportViewModel]; saving creates a new recipe. */
@Serializable data object ReviewLocalRoute

/**
 * The whole app below the theme. [imports] and [consent] are the AI import; without both, no import entry is shown. Signed out, only the sign-in screen exists; signing in or out
 * replaces the back stack, so no screen of the other state stays reachable with back.
 */
@Composable
fun RecipesNavHost(
    sessionRepository: SessionRepository,
    recipeRepository: RecipeRepository,
    turnstile: TurnstileChallenge? = null,
    imports: ImportRepository? = null,
    consent: ConsentRepository? = null,
    nav: NavHostController = rememberNavController(),
) {
    val ai = if (imports != null && consent != null) imports to consent else null
    val session by sessionRepository.state.collectAsState()
    val signedIn = session is SessionState.SignedIn
    LaunchedEffect(signedIn) {
        // Null before the graph is set; the start destination already matches the state then.
        val onSignIn = nav.currentDestination?.hasRoute<SignInRoute>() ?: return@LaunchedEffect
        if (onSignIn != signedIn) return@LaunchedEffect
        nav.navigate(if (signedIn) ListRoute else SignInRoute) { popUpTo(nav.graph.id) { inclusive = true } }
    }

    // The graph is built once; later session changes navigate instead of swapping the start.
    val start: Any = remember { if (signedIn) ListRoute else SignInRoute }
    NavHost(nav, startDestination = start) {
        composable<SignInRoute> {
            SignInScreen(viewModel { SignInViewModel(sessionRepository) }, turnstile)
        }
        composable<ListRoute> {
            RecipeListScreen(
                vm = viewModel { RecipeListViewModel(recipeRepository) },
                onOpen = { nav.navigate(DetailRoute(it)) },
                onAdd = { nav.navigate(EditRoute()) },
                onSettings = { nav.navigate(SettingsRoute) },
                imports = ai?.let { (i, c) -> viewModel { ImportViewModel(i, c) } },
                onReview = { nav.navigate(ReviewImportRoute(it)) },
                onReviewLocal = { nav.navigate(ReviewLocalRoute) },
            )
        }
        composable<ReviewLocalRoute> { entry ->
            // The list's view model holds the draft and runs "Tidy up with AI", so its status card shows on the list.
            val list = remember(entry) { nav.getBackStackEntry<ListRoute>() }
            val importVm = ai?.let { (i, c) -> viewModel(list) { ImportViewModel(i, c) } }
            val draft = remember { importVm?.localDraft?.draft }
            if (importVm == null || draft == null) {
                LaunchedEffect(Unit) { nav.popBackStack() }
                return@composable
            }
            val language = Locale.current.toLanguageTag()
            ReviewLocalScreen(
                vm = viewModel { RecipeEditViewModel(recipeRepository, null, language, draft) },
                onTidyUp = {
                    importVm.tidyUp()
                    nav.popBackStack()
                },
                onSaved = { savedId ->
                    importVm.closeLocalDraft()
                    nav.navigate(DetailRoute(savedId)) { popUpTo<ReviewLocalRoute> { inclusive = true } }
                },
                onClose = {
                    importVm.closeLocalDraft()
                    nav.popBackStack()
                },
            )
        }
        composable<ReviewImportRoute> { entry ->
            val jobId = entry.toRoute<ReviewImportRoute>().jobId
            val repository = ai?.first
            // Read once: acknowledging after the save clears activeJob, the open editor keeps its draft.
            val draft = remember(jobId) {
                (repository?.activeJob?.value?.takeIf { it.jobId == jobId }?.outcome as? ImportOutcome.Done)?.draft
            }
            if (repository == null || draft == null) {
                // The job was acknowledged elsewhere or has expired: nothing to review.
                LaunchedEffect(jobId) { nav.popBackStack() }
                return@composable
            }
            val language = Locale.current.toLanguageTag()
            ReviewImportScreen(
                vm = viewModel { RecipeEditViewModel(recipeRepository, null, language, draft) },
                imports = repository,
                jobId = jobId,
                onSaved = { savedId ->
                    repository.acknowledge(jobId)
                    nav.navigate(DetailRoute(savedId)) { popUpTo<ReviewImportRoute> { inclusive = true } }
                },
                onClose = {
                    repository.acknowledge(jobId)
                    nav.popBackStack()
                },
            )
        }
        composable<DetailRoute> { entry ->
            val id = entry.toRoute<DetailRoute>().id
            RecipeDetailScreen(
                vm = viewModel { RecipeDetailViewModel(recipeRepository, id) },
                onBack = { nav.popBackStack() },
                onEdit = { nav.navigate(EditRoute(id)) },
            )
        }
        composable<EditRoute> { entry ->
            val id = entry.toRoute<EditRoute>().id
            val language = Locale.current.toLanguageTag()
            RecipeEditScreen(
                vm = viewModel { RecipeEditViewModel(recipeRepository, id, language) },
                onSaved = { savedId ->
                    if (id == null) {
                        nav.navigate(DetailRoute(savedId)) { popUpTo<EditRoute> { inclusive = true } }
                    } else {
                        nav.popBackStack()
                    }
                },
                onClose = { nav.popBackStack() },
            )
        }
        composable<SettingsRoute> {
            SettingsScreen(viewModel { SettingsViewModel(sessionRepository, consent) }, onBack = { nav.popBackStack() })
        }
    }
}
