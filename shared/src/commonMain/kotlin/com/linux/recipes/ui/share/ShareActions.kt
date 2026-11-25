package com.linux.recipes.ui.share

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.linux.recipes.data.PdfResult
import com.linux.recipes.data.Recipe
import com.linux.recipes.data.ShareCreate
import com.linux.recipes.data.ShareLink
import com.linux.recipes.ui.components.ConfirmDialog
import com.linux.recipes.ui.components.PageGutter
import com.linux.recipes.ui.components.RecipeIcons
import com.linux.recipes.ui.imports.AiNotice
import com.linux.recipes.ui.paywall.PaywallSheet
import com.linux.recipes.ui.settings.plainTextClip
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import recipes.shared.generated.resources.Res
import recipes.shared.generated.resources.action_cancel
import recipes.shared.generated.resources.action_close
import recipes.shared.generated.resources.pdf_action
import recipes.shared.generated.resources.pdf_failed
import recipes.shared.generated.resources.pdf_limit
import recipes.shared.generated.resources.pdf_ready_body
import recipes.shared.generated.resources.pdf_ready_title
import recipes.shared.generated.resources.pdf_save
import recipes.shared.generated.resources.pdf_saved
import recipes.shared.generated.resources.pdf_share
import recipes.shared.generated.resources.pdf_unavailable
import recipes.shared.generated.resources.pdf_working
import recipes.shared.generated.resources.share_action
import recipes.shared.generated.resources.share_body
import recipes.shared.generated.resources.share_copied
import recipes.shared.generated.resources.share_copy
import recipes.shared.generated.resources.share_create
import recipes.shared.generated.resources.share_error_limit
import recipes.shared.generated.resources.share_error_offline
import recipes.shared.generated.resources.share_error_other
import recipes.shared.generated.resources.share_error_picture
import recipes.shared.generated.resources.share_error_screening
import recipes.shared.generated.resources.share_link_until
import recipes.shared.generated.resources.share_links
import recipes.shared.generated.resources.share_other_device
import recipes.shared.generated.resources.share_recipients
import recipes.shared.generated.resources.share_revoke
import recipes.shared.generated.resources.share_revoke_body
import recipes.shared.generated.resources.share_revoke_failed
import recipes.shared.generated.resources.share_revoke_title
import recipes.shared.generated.resources.share_send
import recipes.shared.generated.resources.share_title
import recipes.shared.generated.resources.share_working

/** Share and PDF on the detail screen's top bar; nothing without [LocalSharing]. */
@Composable
fun ShareActions(recipe: Recipe) {
    val services = LocalSharing.current ?: return
    val files = rememberFileActions()
    var sheet by remember { mutableStateOf(false) }
    val pdf = remember(services) { PdfState(services) }
    val scope = rememberCoroutineScope()
    IconButton(onClick = { sheet = true }) { Icon(RecipeIcons.Link, stringResource(Res.string.share_action)) }
    IconButton(onClick = { scope.launch { pdf.download(recipe.id) } }, enabled = !pdf.working) {
        Icon(RecipeIcons.Import, stringResource(Res.string.pdf_action))
    }
    if (sheet) ShareSheet(services, files, recipe, onClose = { sheet = false })
    PdfDialogs(pdf, files)
}

/** The share sheet: create a link (with the paywall and the AI notice when the server asks), the active links. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShareSheet(services: SharingServices, files: FileActions, recipe: Recipe, onClose: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        ShareContent(services, files, recipe, onClose)
    }
}

internal class ShareState(private val services: SharingServices) {
    var working by mutableStateOf(false)
        private set
    var message by mutableStateOf<StringResource?>(null)
        private set
    var paywall by mutableStateOf(false)
    var consent by mutableStateOf(false)
    var revokeFailed by mutableStateOf(false)
        private set

    /** The new link, or null; a refusal sets [message], [paywall] or [consent]. */
    suspend fun create(recipeId: String): ShareLink? {
        working = true
        message = null
        val result = services.shares.create(recipeId)
        working = false
        when (result) {
            is ShareCreate.Created -> return result.link
            ShareCreate.SubscriptionRequired -> paywall = true
            ShareCreate.ConsentRequired -> consent = true
            ShareCreate.PictureRejected -> message = Res.string.share_error_picture
            ShareCreate.ShareLimit -> message = Res.string.share_error_limit
            ShareCreate.ScreeningUnavailable -> message = Res.string.share_error_screening
            ShareCreate.Offline -> message = Res.string.share_error_offline
            is ShareCreate.Rejected -> message = Res.string.share_error_other
        }
        return null
    }

    /** After "Accept" on the AI notice: records consent, then tries again. */
    suspend fun acceptConsent(recipeId: String): ShareLink? {
        consent = false
        if (services.consent?.accept() != true) {
            message = Res.string.share_error_offline
            return null
        }
        return create(recipeId)
    }

    suspend fun revoke(shareId: String) {
        revokeFailed = !services.shares.revoke(shareId)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ShareContent(services: SharingServices, files: FileActions, recipe: Recipe, onClose: () -> Unit) {
    val state = remember(services) { ShareState(services) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val all by services.shares.links.collectAsState()
    val links = all.filter { it.recipeId == recipe.id }
    var copied by remember { mutableStateOf(false) }
    var confirmRevoke by remember { mutableStateOf<ShareLink?>(null) }
    val created: (ShareLink?) -> Unit = { link -> link?.url?.let(files.shareText) }
    LaunchedEffect(services) { services.shares.refresh() }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = PageGutter, end = PageGutter, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(Res.string.share_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text(stringResource(Res.string.share_body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.message?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = { scope.launch { created(state.create(recipe.id)) } },
            enabled = !state.working,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            shape = MaterialTheme.shapes.medium,
        ) { Text(stringResource(if (state.working) Res.string.share_working else Res.string.share_create)) }
        if (links.isNotEmpty()) {
            Text(
                stringResource(Res.string.share_links),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp).semantics { heading() },
            )
            if (copied) Text(stringResource(Res.string.share_copied), style = MaterialTheme.typography.bodyMedium)
            if (state.revokeFailed) {
                Text(stringResource(Res.string.share_revoke_failed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            links.forEach { link ->
                Column(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // The date part of the ISO 8601 end; readable in every locale.
                    Text(stringResource(Res.string.share_link_until, link.expiresAt.take(10)), style = MaterialTheme.typography.bodyLarge)
                    link.recipients?.let { Text(stringResource(Res.string.share_recipients, it), style = MaterialTheme.typography.bodyMedium) }
                    // The link's address exists only on the device that made it; elsewhere only Revoke.
                    if (link.url == null) Text(stringResource(Res.string.share_other_device), style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        link.url?.let { url ->
                            OutlinedButton(onClick = { files.shareText(url) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(Res.string.share_send))
                            }
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        clipboard.setClipEntry(plainTextClip(url))
                                        copied = true
                                    }
                                },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { Text(stringResource(Res.string.share_copy)) }
                        }
                        TextButton(onClick = { confirmRevoke = link }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(Res.string.share_revoke), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
        TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(Res.string.action_close)) }
    }

    confirmRevoke?.let { link ->
        ConfirmDialog(
            title = stringResource(Res.string.share_revoke_title),
            body = stringResource(Res.string.share_revoke_body),
            confirm = stringResource(Res.string.share_revoke),
            dismiss = stringResource(Res.string.action_cancel),
            destructive = true,
            onConfirm = {
                confirmRevoke = null
                scope.launch { state.revoke(link.shareId) }
            },
            onDismiss = { confirmRevoke = null },
        )
    }
    if (state.paywall) {
        PaywallSheet(
            onClose = { state.paywall = false },
            onSubscribed = {
                state.paywall = false
                scope.launch { created(state.create(recipe.id)) }
            },
        )
    }
    if (state.consent) {
        AiNotice(onAccept = { scope.launch { created(state.acceptConsent(recipe.id)) } }, onNotNow = { state.consent = false })
    }
}

internal class PdfState(private val services: SharingServices) {
    var working by mutableStateOf(false)
        private set
    var ready by mutableStateOf<ByteArray?>(null)
    var message by mutableStateOf<StringResource?>(null)

    suspend fun download(recipeId: String) {
        if (working) return
        working = true
        when (val result = services.pdf.download(recipeId)) {
            is PdfResult.Ready -> ready = result.bytes
            PdfResult.Limit -> message = Res.string.pdf_limit
            PdfResult.Unavailable -> message = Res.string.pdf_unavailable
            PdfResult.Offline -> message = Res.string.share_error_offline
            is PdfResult.Rejected -> message = Res.string.pdf_failed
        }
        working = false
    }
}

@Composable
private fun PdfDialogs(pdf: PdfState, files: FileActions) {
    val scope = rememberCoroutineScope()
    if (pdf.working) {
        AlertDialog(
            onDismissRequest = {},
            text = { Text(stringResource(Res.string.pdf_working), style = MaterialTheme.typography.bodyLarge) },
            confirmButton = {},
        )
    }
    pdf.ready?.let { bytes ->
        AlertDialog(
            onDismissRequest = { pdf.ready = null },
            title = { Text(stringResource(Res.string.pdf_ready_title)) },
            text = { Text(stringResource(Res.string.pdf_ready_body), style = MaterialTheme.typography.bodyLarge) },
            confirmButton = {
                TextButton(onClick = {
                    pdf.ready = null
                    scope.launch {
                        val saved = runCatching { files.save(PDF_NAME, "pdf") { it.write(bytes) } }.getOrDefault(false)
                        pdf.message = if (saved) Res.string.pdf_saved else null
                    }
                }) { Text(stringResource(Res.string.pdf_save)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    pdf.ready = null
                    scope.launch { runCatching { files.shareFile("$PDF_NAME.pdf", bytes) } }
                }) { Text(stringResource(Res.string.pdf_share)) }
            },
        )
    }
    pdf.message?.let { message ->
        AlertDialog(
            onDismissRequest = { pdf.message = null },
            text = { Text(stringResource(message), style = MaterialTheme.typography.bodyLarge) },
            confirmButton = { TextButton(onClick = { pdf.message = null }) { Text(stringResource(Res.string.action_close)) } },
        )
    }
}

private const val PDF_NAME = "recipe"
