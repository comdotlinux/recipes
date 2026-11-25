package com.linux.recipes.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import com.linux.recipes.App
import com.linux.recipes.LocalAppGraph
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.init
import java.lang.ref.WeakReference

class MainActivity : ComponentActivity() {
    private val app get() = application as RecipesApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        FileKit.init(this)
        app.currentActivity = WeakReference(this)
        setContent {
            CompositionLocalProvider(LocalAppGraph provides app.graph) { App(app.graph) }
        }
    }

    override fun onDestroy() {
        if (app.currentActivity?.get() === this) app.currentActivity = null
        super.onDestroy()
    }
}
