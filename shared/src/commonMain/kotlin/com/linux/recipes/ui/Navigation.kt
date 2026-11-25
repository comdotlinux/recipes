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
import com.linux.recipes.data.RecipeRepository
import com.linux.recipes.data.SessionRepository
import com.linux.recipes.data.SessionState
import com.linux.recipes.ui.detail.RecipeDetailScreen
import com.linux.recipes.ui.detail.RecipeDetailViewModel
import com.linux.recipes.ui.edit.RecipeEditScreen
import com.linux.recipes.ui.edit.RecipeEditViewModel
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

/**
 * The whole app below the theme. Signed out, only the sign-in screen exists; signing in or out
 * replaces the back stack, so no screen of the other state stays reachable with back.
 */
@Composable
fun RecipesNavHost(
    sessionRepository: SessionRepository,
    recipeRepository: RecipeRepository,
    turnstile: TurnstileChallenge? = null,
    nav: NavHostController = rememberNavController(),
) {
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
            SettingsScreen(viewModel { SettingsViewModel(sessionRepository) }, onBack = { nav.popBackStack() })
        }
    }
}
