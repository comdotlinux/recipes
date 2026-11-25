package com.linux.recipes.ui.share

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

/**
 * Opens the preview for the link the app was started with. A link that arrives while signed out stays in
 * [SharingServices.incoming] and opens right after sign-in.
 */
@Composable
fun IncomingShareLink(signedIn: Boolean, onOpen: (token: String) -> Unit) {
    val services = LocalSharing.current ?: return
    val token by services.incoming.collectAsState()
    LaunchedEffect(signedIn, token) {
        val pending = token ?: return@LaunchedEffect
        if (!signedIn) return@LaunchedEffect
        services.incoming.value = null
        onOpen(pending)
    }
}
