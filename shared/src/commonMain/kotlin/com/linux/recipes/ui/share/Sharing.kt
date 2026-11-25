package com.linux.recipes.ui.share

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.linux.recipes.data.Backup
import com.linux.recipes.data.ConsentRepository
import com.linux.recipes.data.PdfDownload
import com.linux.recipes.data.ShareRepository
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.cacheDir
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.dialogs.openFileSaver
import io.github.vinceglb.filekit.dialogs.shareFile
import io.github.vinceglb.filekit.div
import io.github.vinceglb.filekit.sink
import io.github.vinceglb.filekit.source
import io.github.vinceglb.filekit.startAccessingSecurityScopedResource
import io.github.vinceglb.filekit.stopAccessingSecurityScopedResource
import io.github.vinceglb.filekit.write
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.io.RawSource
import kotlinx.io.Sink
import kotlinx.io.buffered

/**
 * What milestone M5 adds to the app: sharing by link, PDF and backup. [incoming] holds the token of a link the app
 * was opened with until the user is signed in and the preview opens. Null in [LocalSharing] hides every M5 action.
 */
class SharingServices(
    val shares: ShareRepository,
    val pdf: PdfDownload,
    val backup: Backup?,
    val consent: ConsentRepository?,
    val incoming: MutableStateFlow<String?> = MutableStateFlow(null),
)

/** Provided by the platform entry points next to `LocalAppGraph`; tests provide their own. */
val LocalSharing = staticCompositionLocalOf<SharingServices?> { null }

/** A file the user chose. [open] may be called more than once (restore checks the file, then reads it). */
class PickedFile(val open: () -> RawSource, val release: () -> Unit = {})

/** The system dialogs M5 needs. Tests provide a double: on iOS they need the view controller of a real window. */
class FileActions(
    /** Hands [text] (a link) to the system share sheet. */
    val shareText: (String) -> Unit,
    /** Shows the save dialog and lets [write] fill the chosen file; false when the user cancelled. */
    val save: suspend (suggestedName: String, extension: String, write: suspend (Sink) -> Unit) -> Boolean,
    /** Shows the open dialog; null when the user cancelled. */
    val pick: suspend () -> PickedFile?,
    /** Hands a file with these bytes to the system share sheet. */
    val shareFile: suspend (name: String, bytes: ByteArray) -> Unit,
)

val LocalFileActions = staticCompositionLocalOf<FileActions?> { null }

/** The platform's share sheet for plain text. */
@Composable
internal expect fun rememberTextShare(): (String) -> Unit

/** FileKit's save, open and share dialogs, unless a test provides [LocalFileActions]. */
@Composable
fun rememberFileActions(): FileActions {
    LocalFileActions.current?.let { return it }
    val shareText = rememberTextShare()
    return remember(shareText) {
        FileActions(
            shareText = shareText,
            save = { name, extension, write ->
                val file = FileKit.openFileSaver(suggestedName = name, defaultExtension = extension)
                if (file == null) false else {
                    // On iOS the chosen place belongs to another container: write while access is held.
                    val scoped = file.startAccessingSecurityScopedResource()
                    try {
                        file.sink(append = false).buffered().use { write(it) }
                    } finally {
                        if (scoped) file.stopAccessingSecurityScopedResource()
                    }
                    true
                }
            },
            pick = {
                FileKit.openFilePicker()?.let { file ->
                    // iOS hands out files from other apps' containers only while access is held.
                    val scoped = file.startAccessingSecurityScopedResource()
                    PickedFile(open = { file.source() }, release = { if (scoped) file.stopAccessingSecurityScopedResource() })
                }
            },
            shareFile = { name, bytes ->
                // The cache directory is covered by FileKit's own FileProvider on Android.
                val file = FileKit.cacheDir / name
                file.write(bytes)
                FileKit.shareFile(file)
            },
        )
    }
}
