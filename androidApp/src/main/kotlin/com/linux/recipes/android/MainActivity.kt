package com.linux.recipes.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import com.linux.recipes.App
import com.linux.recipes.AppConfig
import com.linux.recipes.LocalAppGraph
import com.linux.recipes.data.ShareLinks
import com.linux.recipes.ui.share.LocalSharing
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
        if (savedInstanceState == null) receiveShareLink(intent)
        setContent {
            CompositionLocalProvider(LocalAppGraph provides app.graph, LocalSharing provides app.graph.sharing) { App(app.graph) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receiveShareLink(intent)
    }

    /** A share link from an App Link (token in the fragment) or from the landing page (the `shareToken` extra). */
    private fun receiveShareLink(intent: Intent?) {
        val token = ShareLinks.tokenFromIntent(
            dataUrl = intent?.dataString,
            extra = intent?.getStringExtra("shareToken"),
            host = ShareLinks.hostOf(AppConfig.API_BASE_URL),
        )
        if (token != null) app.graph.sharing.incoming.value = token
    }

    override fun onDestroy() {
        if (app.currentActivity?.get() === this) app.currentActivity = null
        super.onDestroy()
    }
}
