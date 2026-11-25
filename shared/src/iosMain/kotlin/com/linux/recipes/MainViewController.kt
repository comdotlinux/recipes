package com.linux.recipes

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.window.ComposeUIViewController
import com.linux.recipes.platform.AppleAuthProvider
import com.linux.recipes.platform.KeychainSecureStore
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIViewController

private val graph: AppGraph by lazy {
    val supportDir = NSFileManager.defaultManager.URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask).first() as NSURL
    excludeCacheFromBackup(supportDir)
    AppGraph(
        filesDir = requireNotNull(supportDir.path),
        secureStore = KeychainSecureStore(service = NSBundle.mainBundle.bundleIdentifier ?: "com.linux.recipes"),
        authProvider = AppleAuthProvider(),
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
    CompositionLocalProvider(LocalAppGraph provides graph) { App(graph) }
}
