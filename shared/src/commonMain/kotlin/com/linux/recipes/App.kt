package com.linux.recipes

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import com.linux.recipes.data.ConsentRepository
import com.linux.recipes.data.ImportRepository
import com.linux.recipes.data.RecipeRepository
import com.linux.recipes.data.SessionRepository
import com.linux.recipes.platform.TurnstileGate
import com.linux.recipes.ui.RecipesNavHost
import com.linux.recipes.ui.signin.TurnstileChallenge
import com.linux.recipes.ui.theme.RecipesTheme

/** The app as the platform entry points start it. */
@Composable
fun App(graph: AppGraph, darkTheme: Boolean = isSystemInDarkTheme()) {
    val (imports, consent) = aiImport(graph)
    App(graph.session, graph.recipes, darkTheme, TurnstileChallenge(graph.turnstile, graph.baseUrl), imports, consent)
}

/**
 * Wiring seam for the M2 data layer, which adds the import and consent repositories to [AppGraph] on
 * its own branch. When both branches are merged, this body becomes `graph.<imports> to graph.<consent>`.
 * Until then the real app passes no repositories, and the list, settings and editor show no AI import.
 */
@Suppress("UNUSED_PARAMETER")
private fun aiImport(graph: AppGraph): Pair<ImportRepository?, ConsentRepository?> = null to null

/** The app on explicit repositories; tests pass fakes and, when sign-in needs it, a [TurnstileChallenge]. */
@Composable
fun App(
    sessionRepository: SessionRepository,
    recipeRepository: RecipeRepository,
    darkTheme: Boolean = isSystemInDarkTheme(),
    turnstile: TurnstileChallenge? = null,
    importRepository: ImportRepository? = null,
    consentRepository: ConsentRepository? = null,
) {
    RecipesTheme(darkTheme) {
        RecipesNavHost(sessionRepository, recipeRepository, turnstile, importRepository, consentRepository)
    }
}
