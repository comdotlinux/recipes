package com.linux.recipes.ui.imports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.linux.recipes.data.ImportJobState
import com.linux.recipes.data.ImportOutcome
import com.linux.recipes.ui.components.AppMark
import com.linux.recipes.ui.components.PageGutter
import com.linux.recipes.ui.components.RecipeIcons
import com.linux.recipes.ui.edit.PictureActions
import com.linux.recipes.ui.paywall.LocalBilling
import com.linux.recipes.ui.paywall.Paywall
import com.linux.recipes.ui.paywall.PaywallSheet
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import recipes.shared.generated.resources.action_close
import recipes.shared.generated.resources.Res
import recipes.shared.generated.resources.consent_accept
import recipes.shared.generated.resources.consent_body_control
import recipes.shared.generated.resources.consent_body_kinds
import recipes.shared.generated.resources.consent_body_models
import recipes.shared.generated.resources.consent_body_send
import recipes.shared.generated.resources.consent_not_now
import recipes.shared.generated.resources.consent_title
import recipes.shared.generated.resources.edit_picture_failed
import recipes.shared.generated.resources.edit_take_photo
import recipes.shared.generated.resources.import_action
import recipes.shared.generated.resources.import_attempt_limit
import recipes.shared.generated.resources.import_choose_gallery
import recipes.shared.generated.resources.import_dismiss
import recipes.shared.generated.resources.import_failed
import recipes.shared.generated.resources.import_first_free
import recipes.shared.generated.resources.import_free_kept
import recipes.shared.generated.resources.import_guard_blocked
import recipes.shared.generated.resources.import_invalid
import recipes.shared.generated.resources.import_invalid_url
import recipes.shared.generated.resources.import_link_label
import recipes.shared.generated.resources.import_no_recipe
import recipes.shared.generated.resources.import_offline_start
import recipes.shared.generated.resources.import_photos_free
import recipes.shared.generated.resources.import_read_with_ai
import recipes.shared.generated.resources.import_reading_photo
import recipes.shared.generated.resources.import_unreadable_body
import recipes.shared.generated.resources.import_unreadable_title
import recipes.shared.generated.resources.import_ready
import recipes.shared.generated.resources.import_rejected
import recipes.shared.generated.resources.import_review
import recipes.shared.generated.resources.import_running
import recipes.shared.generated.resources.import_sheet_body
import recipes.shared.generated.resources.import_sheet_title
import recipes.shared.generated.resources.import_try_again
import recipes.shared.generated.resources.import_type_instead
import recipes.shared.generated.resources.import_waiting_offline

// ---------- status card ----------

/**
 * The card at the top of the list. It shows a start problem, else whatever [job] says, so it
 * follows the job across screens and restarts without keeping state of its own.
 */
@Composable
internal fun ImportStatus(vm: ImportViewModel, job: ImportJobState?, onReview: (String) -> Unit, onTypeIn: () -> Unit) {
    val problem = vm.problem
    val outcome = job?.outcome
    if (outcome == ImportOutcome.Failed) LaunchedEffect(job?.jobId) { vm.loadQuota() }
    val typeIn: @Composable RowScope.() -> Unit = { TextButton(onClick = onTypeIn) { Text(stringResource(Res.string.import_type_instead)) } }
    when {
        problem != null -> {
            val dismiss: @Composable RowScope.() -> Unit = { TextButton(onClick = vm::dismissProblem) { Text(stringResource(Res.string.import_dismiss)) } }
            when (problem) {
                StartProblem.Offline -> StatusCard(RecipeIcons.CloudOff, stringResource(Res.string.import_offline_start)) {
                    TextButton(onClick = vm::retry) { Text(stringResource(Res.string.import_try_again)) }
                    dismiss()
                }
                StartProblem.Picture -> StatusCard(RecipeIcons.Gallery, stringResource(Res.string.edit_picture_failed)) { dismiss() }
                else -> StatusCard(RecipeIcons.Close, stringResource(problem.message())) {
                    typeIn()
                    dismiss()
                }
            }
        }
        vm.reading -> StatusCard(icon = null, message = stringResource(Res.string.import_reading_photo))
        job == null -> if (vm.starting) RunningCard()
        outcome == null -> if (job.offline) StatusCard(RecipeIcons.CloudOff, stringResource(Res.string.import_waiting_offline)) else RunningCard()
        outcome is ImportOutcome.Done -> StatusCard(
            RecipeIcons.Check,
            stringResource(Res.string.import_ready),
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer,
            emphasis = true,
        ) {
            Button(onClick = { onReview(job.jobId) }, modifier = Modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.medium) {
                Text(stringResource(Res.string.import_review))
            }
        }
        else -> {
            val freeKept = outcome == ImportOutcome.Failed && vm.quota?.freeImportUsed == false
            StatusCard(
                RecipeIcons.Close,
                stringResource(outcome.message()),
                detail = if (freeKept) stringResource(Res.string.import_free_kept) else null,
            ) {
                TextButton(onClick = {
                    vm.acknowledge(job.jobId)
                    onTypeIn()
                }) { Text(stringResource(Res.string.import_type_instead)) }
                TextButton(onClick = { vm.acknowledge(job.jobId) }) { Text(stringResource(Res.string.import_dismiss)) }
            }
        }
    }
}

private fun StartProblem.message(): StringResource = when (this) {
    StartProblem.Offline -> Res.string.import_offline_start
    StartProblem.AttemptLimit -> Res.string.import_attempt_limit
    StartProblem.InvalidUrl -> Res.string.import_invalid_url
    StartProblem.Rejected -> Res.string.import_rejected
    StartProblem.Picture -> Res.string.edit_picture_failed
}

private fun ImportOutcome.message(): StringResource = when (this) {
    ImportOutcome.GuardBlocked -> Res.string.import_guard_blocked
    ImportOutcome.NoRecipe -> Res.string.import_no_recipe
    ImportOutcome.Invalid -> Res.string.import_invalid
    ImportOutcome.Failed, is ImportOutcome.Done -> Res.string.import_failed
}

@Composable
private fun RunningCard() = StatusCard(icon = null, message = stringResource(Res.string.import_running))

/** A tonal card: a leading icon (a spinner when [icon] is null), the message, an optional detail line and actions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusCard(
    icon: ImageVector?,
    message: String,
    detail: String? = null,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: Color = MaterialTheme.colorScheme.onSurface,
    emphasis: Boolean = false,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(container)
            .semantics(mergeDescendants = false) { liveRegion = LiveRegionMode.Polite }
            .padding(start = 16.dp, end = 12.dp, top = 16.dp, bottom = if (actions == null) 16.dp else 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon == null) {
            CircularProgressIndicator(Modifier.padding(top = 2.dp).size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(icon, contentDescription = null, tint = if (emphasis) content else MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(message, style = if (emphasis) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge, color = content)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (actions != null) {
                FlowRow(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) { actions() }
            }
        }
    }
}

// ---------- the import sheet ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImportSheet(vm: ImportViewModel, pictures: PictureActions) {
    ModalBottomSheet(
        onDismissRequest = vm::closeSheet,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        ImportSheetContent(
            link = vm.link,
            onLink = { vm.link = it },
            firstFree = vm.quota?.freeImportUsed == false,
            onImport = vm::importLink,
            onTakePhoto = pictures.takePhoto,
            onChoose = pictures.choose,
        )
    }
}

@Composable
internal fun ImportSheetContent(
    link: String,
    onLink: (String) -> Unit,
    firstFree: Boolean,
    onImport: () -> Unit,
    onTakePhoto: () -> Unit,
    onChoose: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = PageGutter, end = PageGutter, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(Res.string.import_sheet_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(Res.string.import_sheet_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(
            Modifier.clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val color = MaterialTheme.colorScheme.onSecondaryContainer
            Text(stringResource(Res.string.import_photos_free), style = MaterialTheme.typography.labelLarge, color = color)
            if (firstFree) Text(stringResource(Res.string.import_first_free), style = MaterialTheme.typography.labelLarge, color = color)
        }
        OutlinedTextField(
            value = link,
            onValueChange = onLink,
            label = { Text(stringResource(Res.string.import_link_label)) },
            leadingIcon = { Icon(RecipeIcons.Link, contentDescription = null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onImport() }),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = onImport,
            enabled = link.isNotBlank(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            shape = MaterialTheme.shapes.medium,
        ) {
            Icon(RecipeIcons.Import, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(Res.string.import_action))
        }
        SourceButton(RecipeIcons.Camera, stringResource(Res.string.edit_take_photo), onTakePhoto)
        SourceButton(RecipeIcons.Gallery, stringResource(Res.string.import_choose_gallery), onChoose)
    }
}

@Composable
private fun SourceButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = MaterialTheme.shapes.medium) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(8.dp))
        Text(label)
    }
}

// ---------- a photo the phone could not read ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UnreadableSheet(onReadWithAi: () -> Unit, onTypeIn: () -> Unit, onClose: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        UnreadableSheetContent(onReadWithAi, onTypeIn, onClose)
    }
}

@Composable
internal fun UnreadableSheetContent(onReadWithAi: () -> Unit, onTypeIn: () -> Unit, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = PageGutter, end = PageGutter, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(Res.string.import_unreadable_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(Res.string.import_unreadable_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(4.dp))
        Button(onClick = onReadWithAi, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = MaterialTheme.shapes.medium) {
            Text(stringResource(Res.string.import_read_with_ai))
        }
        SourceButton(RecipeIcons.Edit, stringResource(Res.string.import_type_instead), onTypeIn)
        TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(Res.string.action_close))
        }
    }
}

// ---------- the AI notice ----------

/** Full height, before anything is sent; back counts as "Not now". */
@Composable
internal fun AiNotice(onAccept: () -> Unit, onNotNow: () -> Unit) {
    Dialog(onDismissRequest = onNotNow, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        AiNoticeContent(onAccept, onNotNow)
    }
}

@Composable
internal fun AiNoticeContent(onAccept: () -> Unit, onNotNow: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = PageGutter, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AppMark(size = 72.dp)
                Text(
                    stringResource(Res.string.consent_title),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(top = 8.dp).semantics { heading() },
                )
                Paragraph(Res.string.consent_body_send)
                Text(
                    stringResource(Res.string.consent_body_kinds),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(16.dp),
                )
                Paragraph(Res.string.consent_body_models)
                Paragraph(Res.string.consent_body_control)
            }
            Column(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = PageGutter, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Button(onClick = onAccept, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = MaterialTheme.shapes.medium) {
                    Text(stringResource(Res.string.consent_accept))
                }
                TextButton(onClick = onNotNow, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(Res.string.consent_not_now))
                }
            }
        }
    }
}

@Composable
private fun Paragraph(text: StringResource) {
    Text(stringResource(text), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
}

// ---------- the paywall ----------

/**
 * The one entry point for "out of imports": the Recipes Pro paywall of `ui/paywall`. [onSubscribed] runs
 * once Recipes Pro is active, so the import that hit the limit can continue.
 */
@Composable
fun ImportPaywall(onTypeIn: () -> Unit, onClose: () -> Unit, onSubscribed: () -> Unit = onClose) =
    PaywallSheet(onClose = onClose, onSubscribed = onSubscribed, onTypeIn = onTypeIn)

@Composable
internal fun ImportPaywallContent(onTypeIn: () -> Unit, onClose: () -> Unit) =
    Paywall(LocalBilling.current, onTypeIn, onClose, onSubscribed = onClose)
