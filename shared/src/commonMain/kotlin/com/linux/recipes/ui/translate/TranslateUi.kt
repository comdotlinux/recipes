package com.linux.recipes.ui.translate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.linux.recipes.data.DefaultImportRepository
import com.linux.recipes.data.ImportJobState
import com.linux.recipes.data.ImportOutcome
import com.linux.recipes.data.Recipe
import com.linux.recipes.data.TranslationLanguages
import com.linux.recipes.data.primaryLanguage
import com.linux.recipes.ui.components.NoteBanner
import com.linux.recipes.ui.components.PageGutter
import com.linux.recipes.ui.components.RecipeIcons
import com.linux.recipes.ui.imports.AiNotice
import com.linux.recipes.ui.imports.ReportSheet
import com.linux.recipes.ui.imports.StatusCard
import com.linux.recipes.ui.paywall.PaywallSheet
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import recipes.shared.generated.resources.Res
import recipes.shared.generated.resources.import_dismiss
import recipes.shared.generated.resources.import_try_again
import recipes.shared.generated.resources.report_failed
import recipes.shared.generated.resources.report_sent
import recipes.shared.generated.resources.translate_action
import recipes.shared.generated.resources.translate_again
import recipes.shared.generated.resources.translate_already_running
import recipes.shared.generated.resources.translate_attempt_limit
import recipes.shared.generated.resources.translate_available
import recipes.shared.generated.resources.translate_blocked
import recipes.shared.generated.resources.translate_by_ai
import recipes.shared.generated.resources.translate_failed
import recipes.shared.generated.resources.translate_invalid
import recipes.shared.generated.resources.translate_missing
import recipes.shared.generated.resources.translate_offline
import recipes.shared.generated.resources.translate_original
import recipes.shared.generated.resources.translate_quota
import recipes.shared.generated.resources.translate_rejected
import recipes.shared.generated.resources.translate_report
import recipes.shared.generated.resources.translate_running
import recipes.shared.generated.resources.translate_sheet_body
import recipes.shared.generated.resources.translate_sheet_title
import recipes.shared.generated.resources.translate_stale
import recipes.shared.generated.resources.translate_stale_note
import recipes.shared.generated.resources.translate_waiting_offline

/** The top bar action that opens the language sheet. */
@Composable
fun LanguageAction(vm: TranslateViewModel) {
    IconButton(onClick = vm::openSheet) { Icon(RecipeIcons.Language, stringResource(Res.string.translate_action)) }
}

/** The language's own name, or the tag itself for a language the app does not translate into. */
internal fun languageName(tag: String): String =
    TranslationLanguages.firstOrNull { it.code == primaryLanguage(tag) }?.name ?: tag

/**
 * Below the title: the running or failed translation of this recipe, a start problem, and for a translated view
 * the quiet "Translated by AI" line with the report action.
 */
@Composable
fun TranslationHeader(vm: TranslateViewModel, recipe: Recipe) {
    val job by vm.activeJob.collectAsState()
    LaunchedEffect(vm) { vm.followOpenJob() }
    val translation = vm.shownTranslation(recipe)
    var reporting by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        job?.takeIf { it.kind == DefaultImportRepository.TRANSLATION && it.recipeId == recipe.id }?.let {
            TranslationJobCard(it, onDismiss = { vm.acknowledge(it.jobId) })
        }
        vm.problem?.let { problem ->
            StatusCard(if (problem == TranslateProblem.Offline) RecipeIcons.CloudOff else RecipeIcons.Close, stringResource(problem.message())) {
                if (problem == TranslateProblem.Offline) TextButton(onClick = vm::retry) { Text(stringResource(Res.string.import_try_again)) }
                TextButton(onClick = vm::dismissProblem) { Text(stringResource(Res.string.import_dismiss)) }
            }
        }
        if (vm.starting) StatusCard(icon = null, message = stringResource(Res.string.translate_running))
        if (translation != null) {
            Text(
                stringResource(Res.string.translate_by_ai, languageName(recipe.language)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (translation.stale) {
                Text(
                    stringResource(Res.string.translate_stale_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val jobId = remember(translation.language) { vm.jobIdFor(translation.language) }
            when (vm.reported) {
                true -> NoteBanner(RecipeIcons.Check, stringResource(Res.string.report_sent))
                else -> {
                    if (vm.reported == false) NoteBanner(RecipeIcons.CloudOff, stringResource(Res.string.report_failed), error = true)
                    // A translation made on another device, or forgotten since: there is no job id to report.
                    if (jobId != null) {
                        TextButton(onClick = { reporting = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(Res.string.translate_report))
                        }
                    }
                }
            }
            if (reporting && jobId != null) {
                ReportSheet(
                    onSend = { reason, note ->
                        reporting = false
                        vm.report(jobId, reason, note)
                    },
                    onDismiss = { reporting = false },
                )
            }
        }
    }
}

/** The open translation job: running, waiting for the network, or ended without a result. Done jobs are never shown. */
@Composable
internal fun TranslationJobCard(job: ImportJobState, onDismiss: () -> Unit) {
    val outcome = job.outcome
    when {
        outcome == null -> StatusCard(
            icon = if (job.offline) RecipeIcons.CloudOff else null,
            message = stringResource(if (job.offline) Res.string.translate_waiting_offline else Res.string.translate_running),
        )
        // The repository acknowledges a finished translation itself; this only covers a race with that.
        outcome == ImportOutcome.Translated -> Unit
        else -> StatusCard(RecipeIcons.Close, stringResource(outcome.translationMessage())) {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.import_dismiss)) }
        }
    }
}

private fun ImportOutcome.translationMessage(): StringResource = when (this) {
    ImportOutcome.GuardBlocked -> Res.string.translate_blocked
    ImportOutcome.Invalid, ImportOutcome.NoRecipe -> Res.string.translate_invalid
    else -> Res.string.translate_failed
}

private fun TranslateProblem.message(): StringResource = when (this) {
    TranslateProblem.QuotaExhausted -> Res.string.translate_quota
    TranslateProblem.AttemptLimit -> Res.string.translate_attempt_limit
    TranslateProblem.AlreadyRunning -> Res.string.translate_already_running
    TranslateProblem.Offline -> Res.string.translate_offline
    TranslateProblem.Rejected -> Res.string.translate_rejected
}

/** The sheet, the AI notice and the paywall of the detail screen; each opens in its own window. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslationOverlays(vm: TranslateViewModel, recipe: Recipe) {
    if (vm.sheetOpen) {
        ModalBottomSheet(
            onDismissRequest = vm::closeSheet,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            LanguageSheetContent(recipe, vm.shownTranslation(recipe)?.language, { vm.choose(recipe, it) }, vm::translate)
        }
    }
    if (vm.consentFor != null) AiNotice(onAccept = vm::acceptConsent, onNotNow = vm::declineConsent)
    if (vm.paywallFor != null) PaywallSheet(onClose = vm::closePaywall, onSubscribed = vm::subscribed)
}

/**
 * The ten languages, with the recipe's own marked as the original (listed first when it is not one of the ten),
 * existing translations as available or out of date, and the one shown now selected. [shown] null is the original.
 */
@Composable
internal fun LanguageSheetContent(recipe: Recipe, shown: String?, onChoose: (String) -> Unit, onTranslateAgain: (String) -> Unit) {
    val original = primaryLanguage(recipe.language)
    val codes = TranslationLanguages.map { it.code }
    val rows = (if (original in codes) emptyList() else listOf(original)) + codes
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = PageGutter, end = PageGutter, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(Res.string.translate_sheet_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(Res.string.translate_sheet_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(Modifier.selectableGroup()) {
            rows.forEach { code ->
                val translation = recipe.translations.firstOrNull { it.language == code }
                val selected = (shown ?: original) == code
                val status = when {
                    code == original -> Res.string.translate_original
                    translation == null -> Res.string.translate_missing
                    translation.stale -> Res.string.translate_stale
                    else -> Res.string.translate_available
                }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .selectable(selected = selected, role = Role.RadioButton) { onChoose(code) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(languageName(code), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(status),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (translation?.stale == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (translation?.stale == true && code != original) {
                        TextButton(onClick = { onTranslateAgain(code) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(Res.string.translate_again))
                        }
                    }
                    if (selected) Icon(RecipeIcons.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}
