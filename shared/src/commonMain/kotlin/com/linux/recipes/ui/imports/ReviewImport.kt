package com.linux.recipes.ui.imports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.linux.recipes.ui.components.PageGutter
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.linux.recipes.data.ImportRepository
import com.linux.recipes.data.ReportReason
import com.linux.recipes.ui.components.ConfirmDialog
import com.linux.recipes.ui.components.NoteBanner
import com.linux.recipes.ui.components.RecipeIcons
import com.linux.recipes.ui.edit.RecipeEditScreen
import com.linux.recipes.ui.edit.RecipeEditViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import recipes.shared.generated.resources.Res
import recipes.shared.generated.resources.action_cancel
import recipes.shared.generated.resources.edit_discard_keep
import recipes.shared.generated.resources.import_local_note
import recipes.shared.generated.resources.import_tidy_up
import recipes.shared.generated.resources.import_tidy_up_body
import recipes.shared.generated.resources.import_tidy_up_confirm
import recipes.shared.generated.resources.import_tidy_up_title
import recipes.shared.generated.resources.report_action
import recipes.shared.generated.resources.report_failed
import recipes.shared.generated.resources.report_note
import recipes.shared.generated.resources.report_reason_offensive
import recipes.shared.generated.resources.report_reason_other
import recipes.shared.generated.resources.report_reason_wrong
import recipes.shared.generated.resources.report_send
import recipes.shared.generated.resources.report_sent
import recipes.shared.generated.resources.report_title
import recipes.shared.generated.resources.review_hint

/** The normal editor on an imported draft, with a way to report a bad result. Saving creates the recipe. */
@Composable
fun ReviewImportScreen(
    vm: RecipeEditViewModel,
    imports: ImportRepository,
    jobId: String,
    onSaved: (String) -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var reporting by rememberSaveable { mutableStateOf(false) }
    // null: not reported yet; then whether the server took the report.
    var reported by rememberSaveable { mutableStateOf<Boolean?>(null) }

    RecipeEditScreen(vm, onSaved, onClose, header = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(Res.string.review_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (reported) {
                true -> NoteBanner(RecipeIcons.Check, stringResource(Res.string.report_sent), Modifier.padding(top = 8.dp))
                else -> {
                    if (reported == false) {
                        NoteBanner(RecipeIcons.CloudOff, stringResource(Res.string.report_failed), Modifier.padding(top = 8.dp), error = true)
                    }
                    TextButton(onClick = { reporting = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(Res.string.report_action))
                    }
                }
            }
        }
    })

    if (reporting) {
        ReportSheet(
            onSend = { reason, note ->
                reporting = false
                scope.launch { reported = imports.report(jobId, reason, note.trim()) }
            },
            onDismiss = { reporting = false },
        )
    }
}

/**
 * The normal editor on the draft of a photo read on the phone. Saving creates the recipe; nothing was sent, so
 * there is nothing to report. [onTidyUp] runs after the user confirmed leaving this draft for an AI import.
 */
@Composable
fun ReviewLocalScreen(
    vm: RecipeEditViewModel,
    onTidyUp: () -> Unit,
    onSaved: (String) -> Unit,
    onClose: () -> Unit,
) {
    var confirmTidyUp by rememberSaveable { mutableStateOf(false) }
    RecipeEditScreen(vm, onSaved, onClose, header = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(Res.string.review_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(
                onClick = { confirmTidyUp = true },
                modifier = Modifier.heightIn(min = 48.dp),
                shape = MaterialTheme.shapes.medium,
            ) { Text(stringResource(Res.string.import_tidy_up)) }
            Text(
                stringResource(Res.string.import_local_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    })
    if (confirmTidyUp) {
        ConfirmDialog(
            title = stringResource(Res.string.import_tidy_up_title),
            body = stringResource(Res.string.import_tidy_up_body),
            confirm = stringResource(Res.string.import_tidy_up_confirm),
            dismiss = stringResource(Res.string.edit_discard_keep),
            onConfirm = {
                confirmTidyUp = false
                onTidyUp()
            },
            onDismiss = { confirmTidyUp = false },
        )
    }
}

private val REASONS = listOf(
    ReportReason.WrongContent to Res.string.report_reason_wrong,
    ReportReason.Offensive to Res.string.report_reason_offensive,
    ReportReason.Other to Res.string.report_reason_other,
)

/** Docs/api.md, "Reports": the note is optional, at most 1,000 characters. */
private const val NOTE_MAX = 1000

/** A sheet rather than an alert dialog: it holds a text field, which wants the room and the keyboard. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReportSheet(onSend: (ReportReason, String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        ReportContent(onSend, onDismiss)
    }
}

@Composable
internal fun ReportContent(onSend: (ReportReason, String) -> Unit, onDismiss: () -> Unit) {
    var reason by rememberSaveable { mutableStateOf<ReportReason?>(null) }
    var note by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = PageGutter, end = PageGutter, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(Res.string.report_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Column(Modifier.selectableGroup()) {
            REASONS.forEach { (value, label) ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected = reason == value, role = Role.RadioButton) { reason = value },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = reason == value, onClick = null)
                    Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
        OutlinedTextField(
            value = note,
            onValueChange = { note = it.take(NOTE_MAX) },
            label = { Text(stringResource(Res.string.report_note)) },
            minLines = 2,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { reason?.let { onSend(it, note) } },
            enabled = reason != null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            shape = MaterialTheme.shapes.medium,
        ) { Text(stringResource(Res.string.report_send)) }
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(Res.string.action_cancel))
        }
    }
}
