package com.linux.recipes

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import com.linux.recipes.data.RecipeRepository
import com.linux.recipes.data.SessionRepository
import com.linux.recipes.platform.TurnstileGate
import com.linux.recipes.ui.RecipesNavHost
import com.linux.recipes.ui.signin.TurnstileChallenge
import com.linux.recipes.ui.theme.RecipesTheme

/** The app as the platform entry points start it. */
@Composable
fun App(graph: AppGraph, darkTheme: Boolean = isSystemInDarkTheme()) {
    App(graph.session, graph.recipes, darkTheme, TurnstileChallenge(graph.turnstile, graph.baseUrl))
}

/** The app on explicit repositories; tests pass fakes and, when sign-in needs it, a [TurnstileChallenge]. */
@Composable
fun App(
    sessionRepository: SessionRepository,
    recipeRepository: RecipeRepository,
    darkTheme: Boolean = isSystemInDarkTheme(),
    turnstile: TurnstileChallenge? = null,
) {
    RecipesTheme(darkTheme) {
        RecipesNavHost(sessionRepository, recipeRepository, turnstile)
    }
}
