package com.linux.recipes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.uikit.LocalUIViewController
import androidx.compose.ui.window.ComposeUIViewController
import com.linux.recipes.platform.AppleAuthProvider
import com.linux.recipes.platform.KeychainSecureStore
import com.linux.recipes.platform.RevenueCatBillingProvider
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIUserInterfaceStyle
import platform.UIKit.UIViewController
import com.linux.recipes.ui.theme.AppearanceStore
import com.linux.recipes.ui.theme.ThemeMode

private val graph: AppGraph by lazy {
    val supportDir = NSFileManager.defaultManager.URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask).first() as NSURL
    excludeCacheFromBackup(supportDir)
    AppGraph(
        filesDir = requireNotNull(supportDir.path),
        secureStore = KeychainSecureStore(service = NSBundle.mainBundle.bundleIdentifier ?: "com.linux.recipes"),
        authProvider = AppleAuthProvider(),
        billingProvider = { RevenueCatBillingProvider(AppConfig.REVENUECAT_IOS_KEY, "https://apps.apple.com/account/subscriptions") },
    )
}

/**
 * The offline cache never leaves the device through iCloud or computer backups; the server holds the data.
 * Creates `<supportDir>/recipes-data` and marks it excluded from backup. Returns false if iOS refused.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun excludeCacheFromBackup(supportDir: NSURL): Boolean {
    val cacheDir = requireNotNull(supportDir.URLByAppendingPathComponent(AppGraph.CACHE_DIR))
    NSFileManager.defaultManager.createDirectoryAtURL(cacheDir, withIntermediateDirectories = true, attributes = null, error = null)
    return cacheDir.setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)
}

fun MainViewController(): UIViewController = ComposeUIViewController {
    CompositionLocalProvider(LocalAppGraph provides graph) {
        FollowAppearance(graph.appearance)
        App(graph)
    }
}

/**
 * The status bar follows the app's mode: Compose Multiplatform 1.12 offers no status-bar API from Kotlin except the
 * deprecated `ComposeUIViewControllerDelegate`, so the window's `overrideUserInterfaceStyle` is set instead; the
 * SwiftUI host's default status bar style follows it. System mode clears the override.
 */
@Composable
private fun FollowAppearance(store: AppearanceStore) {
    val mode = store.state.collectAsState().value.mode
    val controller = LocalUIViewController.current
    LaunchedEffect(mode) {
        val style = when (mode) {
            ThemeMode.System -> UIUserInterfaceStyle.UIUserInterfaceStyleUnspecified
            ThemeMode.Light -> UIUserInterfaceStyle.UIUserInterfaceStyleLight
            ThemeMode.Dark -> UIUserInterfaceStyle.UIUserInterfaceStyleDark
        }
        // The view joins its window after the first frames.
        while (controller.view.window == null) withFrameNanos { }
        controller.view.window?.overrideUserInterfaceStyle = style
    }
}
