package com.linux.recipes

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.linux.recipes.data.BillingRepository
import com.linux.recipes.data.ConsentRepository
import com.linux.recipes.data.ImportRepository
import com.linux.recipes.data.RecipeRepository
import com.linux.recipes.data.SessionRepository
import com.linux.recipes.platform.TurnstileGate
import com.linux.recipes.ui.RecipesNavHost
import com.linux.recipes.ui.paywall.LocalBilling
import com.linux.recipes.ui.signin.TurnstileChallenge
import com.linux.recipes.ui.theme.RecipesTheme

/** The app as the platform entry points start it. */
@Composable
fun App(graph: AppGraph, darkTheme: Boolean = isSystemInDarkTheme()) {
    val (imports, consent) = aiImport(graph)
    App(graph.session, graph.recipes, darkTheme, TurnstileChallenge(graph.turnstile, graph.baseUrl), imports, consent, graph.billing)
}

/** The AI import repositories of the running app. Tests pass fakes, or null to hide the import entry. */
private fun aiImport(graph: AppGraph): Pair<ImportRepository?, ConsentRepository?> = graph.imports to graph.consent

/**
 * The app on explicit repositories; tests pass fakes and, when sign-in needs it, a [TurnstileChallenge].
 * [billingRepository] null behaves like a device without a store.
 */
@Composable
fun App(
    sessionRepository: SessionRepository,
    recipeRepository: RecipeRepository,
    darkTheme: Boolean = isSystemInDarkTheme(),
    turnstile: TurnstileChallenge? = null,
    importRepository: ImportRepository? = null,
    consentRepository: ConsentRepository? = null,
    billingRepository: BillingRepository? = null,
) {
    RecipesTheme(darkTheme) {
        CompositionLocalProvider(LocalBilling provides billingRepository) {
            RecipesNavHost(sessionRepository, recipeRepository, turnstile, importRepository, consentRepository)
        }
    }
}
