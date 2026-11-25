package com.linux.recipes.ui.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linux.recipes.data.ReportReason
import com.linux.recipes.data.ShareAccept
import com.linux.recipes.data.ShareOpen
import com.linux.recipes.data.SharePreview
import com.linux.recipes.data.ShareRepository
import com.linux.recipes.ui.components.NoteBanner
import com.linux.recipes.ui.components.PageGutter
import com.linux.recipes.ui.components.RecipeIcons
import com.linux.recipes.ui.components.SectionTitle
import com.linux.recipes.ui.imports.ReportSheet
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import recipes.shared.generated.resources.Res
import recipes.shared.generated.resources.action_back
import recipes.shared.generated.resources.detail_cook
import recipes.shared.generated.resources.detail_ingredients
import recipes.shared.generated.resources.detail_prep
import recipes.shared.generated.resources.detail_serves
import recipes.shared.generated.resources.detail_steps
import recipes.shared.generated.resources.detail_tags
import recipes.shared.generated.resources.minutes_short
import recipes.shared.generated.resources.share_accept_failed
import recipes.shared.generated.resources.share_add
import recipes.shared.generated.resources.share_adding
import recipes.shared.generated.resources.share_cap_count
import recipes.shared.generated.resources.share_cap_pictures
import recipes.shared.generated.resources.share_cap_text
import recipes.shared.generated.resources.share_error_offline
import recipes.shared.generated.resources.share_gone
import recipes.shared.generated.resources.share_loading
import recipes.shared.generated.resources.share_not_found
import recipes.shared.generated.resources.share_own
import recipes.shared.generated.resources.share_preview_pictures
import recipes.shared.generated.resources.share_preview_title
import recipes.shared.generated.resources.share_preview_until
import recipes.shared.generated.resources.share_report
import recipes.shared.generated.resources.share_report_failed
import recipes.shared.generated.resources.share_reported
import recipes.shared.generated.resources.share_retry
import recipes.shared.generated.resources.share_unreadable

internal class PreviewState(private val shares: ShareRepository, private val token: String) {
    var preview by mutableStateOf<SharePreview?>(null)
        private set
    /** Why the link cannot be shown or added; null while loading or when all is well. */
    var problem by mutableStateOf<StringResource?>(null)
        private set
    /** True when the problem ends the link for good (no retry, no add). */
    var final by mutableStateOf(false)
        private set
    var working by mutableStateOf(false)
        private set
    var reported by mutableStateOf(false)
        private set

    suspend fun load() {
        problem = null
        when (val result = shares.open(token)) {
            is ShareOpen.Preview -> preview = result.preview
            ShareOpen.Gone -> fail(Res.string.share_gone)
            ShareOpen.NotFound -> fail(Res.string.share_not_found)
            ShareOpen.OwnShare -> fail(Res.string.share_own)
            ShareOpen.Offline -> problem = Res.string.share_error_offline
            is ShareOpen.Rejected -> fail(Res.string.share_not_found)
        }
    }

    /** The id of the copy once it is in the user's recipes; null with [problem] set otherwise. */
    suspend fun accept(): String? {
        working = true
        problem = null
        val result = shares.accept(token)
        working = false
        when (result) {
            is ShareAccept.Accepted -> return result.recipe.id
            ShareAccept.RecipeLimit -> problem = Res.string.share_cap_count
            ShareAccept.RecipeStorageCap -> problem = Res.string.share_cap_text
            ShareAccept.StorageCap -> problem = Res.string.share_cap_pictures
            ShareAccept.Unreadable -> fail(Res.string.share_unreadable)
            ShareAccept.Gone -> fail(Res.string.share_gone)
            ShareAccept.NotFound -> fail(Res.string.share_not_found)
            ShareAccept.OwnShare -> fail(Res.string.share_own)
            ShareAccept.Offline -> problem = Res.string.share_error_offline
            is ShareAccept.Rejected -> problem = Res.string.share_accept_failed
        }
        return null
    }

    suspend fun report(reason: ReportReason, note: String) {
        if (shares.report(token, reason, note)) {
            reported = true
            fail(Res.string.share_reported)
        } else {
            problem = Res.string.share_report_failed
        }
    }

    private fun fail(message: StringResource) {
        problem = message
        final = true
    }
}

/** A shared recipe before it is added: its text, picture count and expiry, "Add to my recipes" and "Report". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharePreviewScreen(shares: ShareRepository, token: String, onBack: () -> Unit, onAccepted: (String) -> Unit) {
    val state = remember(token) { PreviewState(shares, token) }
    val scope = rememberCoroutineScope()
    var reporting by remember { mutableStateOf(false) }
    LaunchedEffect(state) { state.load() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.share_preview_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(RecipeIcons.Back, stringResource(Res.string.action_back)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = PageGutter, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val preview = state.preview
            state.problem?.let { NoteBanner(if (state.final) RecipeIcons.Close else RecipeIcons.CloudOff, stringResource(it), error = !state.reported) }
            if (preview == null && state.problem == null) Text(stringResource(Res.string.share_loading), style = MaterialTheme.typography.bodyLarge)
            if (preview == null && state.problem != null && !state.final) {
                OutlinedButton(onClick = { scope.launch { state.load() } }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(Res.string.share_retry))
                }
            }
            if (preview != null && !state.final) {
                Button(
                    onClick = { scope.launch { state.accept()?.let(onAccepted) } },
                    enabled = !state.working,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    shape = MaterialTheme.shapes.medium,
                ) { Text(stringResource(if (state.working) Res.string.share_adding else Res.string.share_add)) }
            }
            if (preview != null) PreviewBody(preview)
            if (preview != null && !state.reported) {
                TextButton(onClick = { reporting = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(Res.string.share_report))
                }
            }
        }
    }
    if (reporting) {
        ReportSheet(
            onSend = { reason, note ->
                reporting = false
                scope.launch { state.report(reason, note) }
            },
            onDismiss = { reporting = false },
        )
    }
}

@Composable
private fun PreviewBody(preview: SharePreview) {
    val recipe = preview.recipe
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(recipe.title, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onBackground)
        if (recipe.description.isNotBlank()) {
            Text(recipe.description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        recipe.servings?.let { Fact(stringResource(Res.string.detail_serves), it.toString()) }
        recipe.prepMinutes?.let { Fact(stringResource(Res.string.detail_prep), stringResource(Res.string.minutes_short, it)) }
        recipe.cookMinutes?.let { Fact(stringResource(Res.string.detail_cook), stringResource(Res.string.minutes_short, it)) }
        Text(stringResource(Res.string.share_preview_pictures, preview.imageCount), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(Res.string.share_preview_until, preview.expiresAt.take(10)), style = MaterialTheme.typography.bodyMedium)
        if (recipe.ingredients.isNotEmpty()) {
            SectionTitle(stringResource(Res.string.detail_ingredients), Modifier.padding(top = 8.dp))
            recipe.ingredients.forEach { ingredient ->
                val amount = listOf(ingredient.quantity, ingredient.unit, ingredient.item).filter { it.isNotBlank() }.joinToString(" ")
                Text(amount, style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (recipe.steps.isNotEmpty()) {
            SectionTitle(stringResource(Res.string.detail_steps), Modifier.padding(top = 8.dp))
            recipe.steps.forEachIndexed { i, step -> Text("${i + 1}. $step", style = MaterialTheme.typography.bodyLarge) }
        }
        if (recipe.tags.isNotEmpty()) {
            SectionTitle(stringResource(Res.string.detail_tags), Modifier.padding(top = 8.dp))
            Text(recipe.tags.joinToString(" · "), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}
