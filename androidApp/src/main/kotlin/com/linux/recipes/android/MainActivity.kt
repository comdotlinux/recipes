package com.linux.recipes.android

import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalConfiguration
import com.linux.recipes.App
import com.linux.recipes.LocalAppGraph
import com.linux.recipes.ui.theme.isDark
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.init
import java.lang.ref.WeakReference

class MainActivity : ComponentActivity() {
    private val app get() = application as RecipesApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before the first frame: the stored mode, so the bar icons never start in the wrong colour.
        systemBars(app.graph.appearance.state.value.isDark(resources.configuration.isNight()))
        super.onCreate(savedInstanceState)
        FileKit.init(this)
        app.currentActivity = WeakReference(this)
        setContent {
            val appearance by app.graph.appearance.state.collectAsState()
            val dark = appearance.isDark(LocalConfiguration.current.isNight())
            LaunchedEffect(dark) { systemBars(dark) }
            CompositionLocalProvider(LocalAppGraph provides app.graph) { App(app.graph) }
        }
    }

    /** Status and navigation bar icons for the app's effective mode, not the phone's; the scrims are the library defaults. */
    private fun systemBars(dark: Boolean) = enableEdgeToEdge(
        statusBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        navigationBarStyle = if (dark) SystemBarStyle.dark(DARK_SCRIM) else SystemBarStyle.light(LIGHT_SCRIM, DARK_SCRIM),
    )

    // What `isSystemInDarkTheme()` reads; androidApp has no direct compose-foundation dependency.
    private fun Configuration.isNight() = uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    override fun onDestroy() {
        if (app.currentActivity?.get() === this) app.currentActivity = null
        super.onDestroy()
    }

    private companion object {
        // The values `enableEdgeToEdge()` uses by default for three-button navigation.
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
