package com.linux.recipes.ui.backup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.linux.recipes.data.Backup
import com.linux.recipes.data.ExportResult
import com.linux.recipes.data.RestoreCheck
import com.linux.recipes.data.RestoreResult
import com.linux.recipes.ui.components.ConfirmDialog
import com.linux.recipes.ui.components.SectionTitle
import com.linux.recipes.ui.share.FileActions
import com.linux.recipes.ui.share.LocalSharing
import com.linux.recipes.ui.share.PickedFile
import com.linux.recipes.ui.share.rememberFileActions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.io.buffered
import org.jetbrains.compose.resources.stringResource
import recipes.shared.generated.resources.Res
import recipes.shared.generated.resources.action_cancel
import recipes.shared.generated.resources.backup_body
import recipes.shared.generated.resources.backup_cancelled
import recipes.shared.generated.resources.backup_export
import recipes.shared.generated.resources.backup_exported
import recipes.shared.generated.resources.backup_failed
import recipes.shared.generated.resources.backup_interrupted
import recipes.shared.generated.resources.backup_malformed
import recipes.shared.generated.resources.backup_missing
import recipes.shared.generated.resources.backup_offline
import recipes.shared.generated.resources.backup_over_cap_body
import recipes.shared.generated.resources.backup_over_cap_confirm
import recipes.shared.generated.resources.backup_over_cap_title
import recipes.shared.generated.resources.backup_progress
import recipes.shared.generated.resources.backup_refused
import recipes.shared.generated.resources.backup_restore
import recipes.shared.generated.resources.backup_restored
import recipes.shared.generated.resources.backup_skipped_item
import recipes.shared.generated.resources.backup_skipped_pictures
import recipes.shared.generated.resources.backup_skipped_recipes
import recipes.shared.generated.resources.backup_title
import recipes.shared.generated.resources.backup_working

/** What the last export or restore came to, as the section shows it. */
internal sealed interface BackupOutcome {
    data class Exported(val recipes: Int, val pictures: Int) : BackupOutcome
    data class Missing(val titles: List<String>) : BackupOutcome
    data class Restored(val result: RestoreResult.Done) : BackupOutcome
    data object Offline : BackupOutcome
    data object Refused : BackupOutcome
    data object Interrupted : BackupOutcome
    data object Cancelled : BackupOutcome
    data object Failed : BackupOutcome
}

internal class BackupState(private val backup: Backup, private val files: FileActions, private val scope: CoroutineScope) {
    var outcome by mutableStateOf<BackupOutcome?>(null)
        private set
    var running by mutableStateOf(false)
        private set
    /** A restore waiting for the user to accept the storage warning. */
    var warning by mutableStateOf<PickedFile?>(null)
        private set
    private var job: Job? = null
    private var restoring = false

    fun export() = run(restore = false) {
        when (val result = backup.export { write -> files.save(FILE_NAME, FILE_EXTENSION, write) }) {
            is ExportResult.Done -> BackupOutcome.Exported(result.recipes, result.pictures)
            is ExportResult.MissingPictures -> BackupOutcome.Missing(result.titles)
            ExportResult.Offline -> BackupOutcome.Offline
            ExportResult.Cancelled -> null
        }
    }

    fun restore() = run(restore = true) {
        val file = files.pick() ?: return@run null
        val check = try {
            file.open().buffered().use { backup.check(it) }
        } catch (e: Exception) {
            file.release()
            throw e
        }
        when (check) {
            RestoreCheck.Refused -> BackupOutcome.Refused.also { file.release() }
            RestoreCheck.Offline -> BackupOutcome.Offline.also { file.release() }
            is RestoreCheck.Ready -> if (check.plan.overCap) {
                warning = file
                null
            } else {
                restoreFrom(file)
            }
        }
    }

    /** After "Restore anyway" on the storage warning. */
    fun confirmWarning() {
        val file = warning ?: return
        warning = null
        run(restore = true) { restoreFrom(file) }
    }

    fun dismissWarning() {
        warning?.release()
        warning = null
    }

    fun cancel() {
        job?.cancel()
    }

    private suspend fun restoreFrom(file: PickedFile): BackupOutcome = try {
        when (val result = file.open().buffered().use { backup.restore(it) }) {
            is RestoreResult.Done -> BackupOutcome.Restored(result)
            RestoreResult.Refused -> BackupOutcome.Refused
            RestoreResult.Interrupted -> BackupOutcome.Interrupted
        }
    } finally {
        file.release()
    }

    private fun run(restore: Boolean, work: suspend () -> BackupOutcome?) {
        if (running) return
        running = true
        restoring = restore
        outcome = null
        job = scope.launch {
            try {
                outcome = work()
            } catch (e: CancellationException) {
                // A cancelled restore resumes when run again; a cancelled export leaves an incomplete file.
                outcome = if (restoring) BackupOutcome.Interrupted else BackupOutcome.Cancelled
                throw e
            } catch (_: Exception) {
                outcome = BackupOutcome.Failed
            } finally {
                running = false
            }
        }
    }

    companion object {
        const val FILE_NAME = "recipes-backup"
        const val FILE_EXTENSION = "jsonl"
    }
}

/** "Backup" in Settings: export and restore with progress, cancel and a summary. Nothing without a backup service. */
@Composable
fun BackupSection() {
    val backup = LocalSharing.current?.backup ?: return
    val files = rememberFileActions()
    val scope = rememberCoroutineScope()
    val state = remember(backup, files) { BackupState(backup, files, scope) }
    BackupContent(state, backup)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BackupContent(state: BackupState, backup: Backup) {
    val progress by backup.progress.collectAsState()
    SectionTitle(stringResource(Res.string.backup_title), Modifier.padding(top = 16.dp))
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(Res.string.backup_body), style = MaterialTheme.typography.bodyLarge)
        if (state.running) {
            val current = progress
            Text(
                if (current == null) stringResource(Res.string.backup_working) else stringResource(Res.string.backup_progress, current.done, current.total),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (current != null && current.total > 0) {
                LinearProgressIndicator(progress = { current.done.toFloat() / current.total }, modifier = Modifier.fillMaxWidth())
            }
            TextButton(onClick = state::cancel, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(Res.string.action_cancel)) }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = state::export, modifier = Modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.medium) {
                    Text(stringResource(Res.string.backup_export))
                }
                OutlinedButton(onClick = state::restore, modifier = Modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.medium) {
                    Text(stringResource(Res.string.backup_restore))
                }
            }
        }
        state.outcome?.let { Outcome(it) }
    }
    if (state.warning != null) {
        ConfirmDialog(
            title = stringResource(Res.string.backup_over_cap_title),
            body = stringResource(Res.string.backup_over_cap_body),
            confirm = stringResource(Res.string.backup_over_cap_confirm),
            dismiss = stringResource(Res.string.action_cancel),
            onConfirm = state::confirmWarning,
            onDismiss = state::dismissWarning,
        )
    }
}

@Composable
private fun Outcome(outcome: BackupOutcome) {
    val style = MaterialTheme.typography.bodyMedium
    when (outcome) {
        is BackupOutcome.Exported -> Text(stringResource(Res.string.backup_exported, outcome.recipes, outcome.pictures), style = style)
        is BackupOutcome.Missing -> {
            Text(stringResource(Res.string.backup_missing), style = style, color = MaterialTheme.colorScheme.error)
            outcome.titles.forEach { Text(it, style = style) }
        }
        is BackupOutcome.Restored -> {
            val r = outcome.result
            Text(stringResource(Res.string.backup_restored, r.recipesWritten, r.picturesWritten, r.recipesUnchanged + r.picturesUnchanged), style = style)
            if (r.skippedRecipes.isNotEmpty()) {
                Text(stringResource(Res.string.backup_skipped_recipes), style = style, color = MaterialTheme.colorScheme.error)
                r.skippedRecipes.forEach { Text(stringResource(Res.string.backup_skipped_item, it.title, it.code), style = style) }
            }
            if (r.skippedPictures.isNotEmpty()) {
                Text(stringResource(Res.string.backup_skipped_pictures), style = style, color = MaterialTheme.colorScheme.error)
                r.skippedPictures.forEach { Text(stringResource(Res.string.backup_skipped_item, it.title, it.code), style = style) }
            }
            if (r.malformedLines > 0) Text(stringResource(Res.string.backup_malformed, r.malformedLines), style = style)
        }
        BackupOutcome.Offline -> Text(stringResource(Res.string.backup_offline), style = style, color = MaterialTheme.colorScheme.error)
        BackupOutcome.Refused -> Text(stringResource(Res.string.backup_refused), style = style, color = MaterialTheme.colorScheme.error)
        BackupOutcome.Interrupted -> Text(stringResource(Res.string.backup_interrupted), style = style, color = MaterialTheme.colorScheme.error)
        BackupOutcome.Cancelled -> Text(stringResource(Res.string.backup_cancelled), style = style)
        BackupOutcome.Failed -> Text(stringResource(Res.string.backup_failed), style = style, color = MaterialTheme.colorScheme.error)
    }
}
