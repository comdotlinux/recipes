package com.linux.recipes.ui.edit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.ImageFormat
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberCameraPickerLauncher
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.compressImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class PictureActions(val takePhoto: () -> Unit, val choose: () -> Unit)

/**
 * Null in the app, so [rememberPictureActions] uses FileKit. UI tests provide a double: on iOS FileKit's launchers
 * need the UIViewController of a real window, which the Compose test harness does not have.
 */
val LocalPictureActions = staticCompositionLocalOf<PictureActions?> { null }

/** Quality and longest edge, tried in order until the JPEG fits [Limits.IMAGE_BYTES]. */
private val COMPRESSION_STEPS = listOf(85 to 2048, 75 to 1600, 65 to 1280, 55 to 1024)

/** JPEG bytes of at most [Limits.IMAGE_BYTES], or null when no step gets there or decoding fails. */
internal suspend fun compressForUpload(compress: suspend (quality: Int, edge: Int) -> ByteArray): ByteArray? {
    for ((quality, edge) in COMPRESSION_STEPS) {
        val bytes = runCatching { compress(quality, edge) }.getOrNull() ?: return null
        if (bytes.size <= Limits.IMAGE_BYTES) return bytes
    }
    return null
}

/**
 * Camera and gallery launchers from FileKit. Every chosen picture is compressed to JPEG before it
 * reaches the form; [onFailed] is called for a picture that cannot be decoded or made small enough.
 */
@Composable
fun rememberPictureActions(remaining: Int, onPicked: (ByteArray) -> Unit, onFailed: () -> Unit): PictureActions {
    LocalPictureActions.current?.let { return it }
    val scope = rememberCoroutineScope()
    val handle: (PlatformFile) -> Unit = { file -> scope.compress(file, onPicked, onFailed) }
    val camera = rememberCameraPickerLauncher { file -> file?.let(handle) }
    val single = rememberFilePickerLauncher(type = FileKitType.Image) { file -> file?.let(handle) }
    val multiple = rememberFilePickerLauncher(
        type = FileKitType.Image,
        mode = FileKitMode.Multiple(maxItems = remaining.coerceAtLeast(2)),
    ) { files -> files?.take(remaining)?.forEach(handle) }
    return PictureActions(
        takePhoto = { if (remaining > 0) camera.launch() },
        choose = {
            when {
                remaining == 1 -> single.launch()
                remaining > 1 -> multiple.launch()
            }
        },
    )
}

private fun CoroutineScope.compress(file: PlatformFile, onPicked: (ByteArray) -> Unit, onFailed: () -> Unit) {
    launch {
        val bytes = compressForUpload { quality, edge -> FileKit.compressImage(file, ImageFormat.JPEG, quality, edge, edge) }
        if (bytes != null) onPicked(bytes) else onFailed()
    }
}
