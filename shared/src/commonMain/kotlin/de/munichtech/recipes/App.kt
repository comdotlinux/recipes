package de.munichtech.recipes

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import de.munichtech.recipes.ui.theme.RecipesTheme

const val APP_NAME = "Recipes"

@Composable
fun App(darkTheme: Boolean = isSystemInDarkTheme()) {
    RecipesTheme(darkTheme) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(contentAlignment = Alignment.Center) {
                Text(APP_NAME, style = MaterialTheme.typography.headlineMedium)
            }
        }
    }
}
