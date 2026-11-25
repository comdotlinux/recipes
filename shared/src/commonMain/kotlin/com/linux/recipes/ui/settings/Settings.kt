package com.linux.recipes.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.linux.recipes.data.ConsentRepository
import com.linux.recipes.data.ConsentState
import com.linux.recipes.data.BillingRepository
import com.linux.recipes.data.ImportRepository
import com.linux.recipes.data.Quota
import com.linux.recipes.ui.paywall.PaywallSheet
import com.linux.recipes.ui.paywall.messageOf
import org.jetbrains.compose.resources.StringResource
import androidx.compose.runtime.LaunchedEffect
import recipes.shared.generated.resources.pro_loading
import recipes.shared.generated.resources.pro_restore
import recipes.shared.generated.resources.pro_title
import recipes.shared.generated.resources.pro_working
import recipes.shared.generated.resources.settings_pro_active
import recipes.shared.generated.resources.settings_pro_free
import recipes.shared.generated.resources.settings_pro_manage
import recipes.shared.generated.resources.settings_pro_upgrade
import com.linux.recipes.data.SessionRepository
import com.linux.recipes.data.SessionState
import com.linux.recipes.ui.components.ConfirmDialog
import com.linux.recipes.ui.components.PageGutter
import com.linux.recipes.ui.components.RecipeIcons
import com.linux.recipes.ui.components.SectionTitle
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import recipes.shared.generated.resources.Res
import recipes.shared.generated.resources.action_back
import recipes.shared.generated.resources.action_cancel
import recipes.shared.generated.resources.settings_account
import recipes.shared.generated.resources.settings_ai
import recipes.shared.generated.resources.settings_ai_given
import recipes.shared.generated.resources.settings_ai_not_given
import recipes.shared.generated.resources.settings_ai_withdraw
import recipes.shared.generated.resources.settings_ai_withdraw_body
import recipes.shared.generated.resources.settings_ai_withdraw_confirm
import recipes.shared.generated.resources.settings_ai_withdraw_failed
import recipes.shared.generated.resources.settings_ai_withdraw_title
import recipes.shared.generated.resources.settings_copy_support_id
import recipes.shared.generated.resources.settings_imprint
import recipes.shared.generated.resources.settings_legal
import recipes.shared.generated.resources.settings_opens_browser
import recipes.shared.generated.resources.settings_privacy
import recipes.shared.generated.resources.settings_sign_out
import recipes.shared.generated.resources.settings_sign_out_body
import recipes.shared.generated.resources.settings_sign_out_title
import recipes.shared.generated.resources.settings_support_id
import recipes.shared.generated.resources.settings_support_id_copied
import recipes.shared.generated.resources.settings_support_id_hint
import recipes.shared.generated.resources.settings_terms
import recipes.shared.generated.resources.settings_title

const val LEGAL_BASE = "https://recipe.munich-tech.de"

/** [consent] null hides the "AI imports" section; [imports] or [billing] null hides "Recipes Pro". */
class SettingsViewModel(
    private val session: SessionRepository,
    private val consent: ConsentRepository? = null,
    private val imports: ImportRepository? = null,
    val billing: BillingRepository? = null,
) : ViewModel() {
    val state = session.state
    val proShown = imports != null && billing != null

    /** From `GET /me`; null while loading or offline. */
    var quota by mutableStateOf<Quota?>(null)
        private set
    var restoring by mutableStateOf(false)
        private set
    var restoreMessage by mutableStateOf<StringResource?>(null)
        private set

    fun loadQuota() {
        val repository = imports ?: return
        viewModelScope.launch { quota = repository.quota() }
    }

    fun restore() {
        val repository = billing ?: return
        restoring = true
        restoreMessage = null
        viewModelScope.launch {
            restoreMessage = messageOf(repository.restore(), restore = true)
            restoring = false
            loadQuota()
        }
    }

    val consentState = consent?.state
    val consentVersion = consent?.currentVersion
    var withdrawFailed by mutableStateOf(false)
        private set

    fun withdrawConsent() {
        val repository = consent ?: return
        withdrawFailed = false
        viewModelScope.launch { withdrawFailed = !repository.withdraw() }
    }

    var signingOut by mutableStateOf(false)
        private set

    fun signOut() {
        if (signingOut) return
        signingOut = true
        viewModelScope.launch {
            try {
                session.signOut()
            } finally {
                signingOut = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsState()
    val userId = (state as? SessionState.SignedIn)?.userId.orEmpty()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val uriHandler = LocalUriHandler.current
    val copied = stringResource(Res.string.settings_support_id_copied)
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    var confirmWithdraw by rememberSaveable { mutableStateOf(false) }
    var showPaywall by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(RecipeIcons.Back, stringResource(Res.string.action_back)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = PageGutter, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionTitle(stringResource(Res.string.settings_account))
            Column(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    stringResource(Res.string.settings_support_id),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(userId, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        scope.launch {
                            clipboard.setClipEntry(plainTextClip(userId))
                            snackbar.showSnackbar(copied)
                        }
                    }) {
                        Icon(RecipeIcons.Copy, stringResource(Res.string.settings_copy_support_id))
                    }
                }
                Text(
                    stringResource(Res.string.settings_support_id_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
            OutlinedButton(
                onClick = { confirmSignOut = true },
                enabled = !vm.signingOut,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(RecipeIcons.SignOut, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                Text(
                    stringResource(Res.string.settings_sign_out),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (vm.proShown) {
                LaunchedEffect(vm) { vm.loadQuota() }
                ProSection(
                    quota = vm.quota,
                    restoring = vm.restoring,
                    restoreMessage = vm.restoreMessage,
                    onOpen = {
                        if (vm.quota?.entitled == true) vm.billing?.manageUrl()?.let(uriHandler::openUri) else showPaywall = true
                    },
                    onRestore = vm::restore,
                )
            }
            vm.consentState?.let { consentFlow ->
                val consent by consentFlow.collectAsState()
                AiImportsSection(
                    given = consent == ConsentState.Given(vm.consentVersion ?: 0),
                    failed = vm.withdrawFailed,
                    onWithdraw = { confirmWithdraw = true },
                )
            }
            SectionTitle(stringResource(Res.string.settings_legal), Modifier.padding(top = 16.dp))
            Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                val links = listOf(
                    Res.string.settings_privacy to "$LEGAL_BASE/privacy",
                    Res.string.settings_imprint to "$LEGAL_BASE/imprint",
                    Res.string.settings_terms to "$LEGAL_BASE/terms",
                )
                links.forEachIndexed { i, (label, url) ->
                    if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button) { uriHandler.openUri(url) }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(Res.string.settings_opens_browser),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(RecipeIcons.External, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }

    if (showPaywall) {
        PaywallSheet(
            onClose = { showPaywall = false },
            onSubscribed = {
                showPaywall = false
                vm.loadQuota()
            },
            billing = vm.billing,
        )
    }
    if (confirmWithdraw) {
        ConfirmDialog(
            title = stringResource(Res.string.settings_ai_withdraw_title),
            body = stringResource(Res.string.settings_ai_withdraw_body),
            confirm = stringResource(Res.string.settings_ai_withdraw_confirm),
            dismiss = stringResource(Res.string.action_cancel),
            destructive = true,
            onConfirm = {
                confirmWithdraw = false
                vm.withdrawConsent()
            },
            onDismiss = { confirmWithdraw = false },
        )
    }
    if (confirmSignOut) {
        ConfirmDialog(
            title = stringResource(Res.string.settings_sign_out_title),
            body = stringResource(Res.string.settings_sign_out_body),
            confirm = stringResource(Res.string.settings_sign_out),
            dismiss = stringResource(Res.string.action_cancel),
            destructive = true,
            onConfirm = {
                confirmSignOut = false
                vm.signOut()
            },
            onDismiss = { confirmSignOut = false },
        )
    }
}

/** Free, or Pro until the date the server reports; the row opens the paywall or the store's subscription page. */
@Composable
private fun ProSection(quota: Quota?, restoring: Boolean, restoreMessage: StringResource?, onOpen: () -> Unit, onRestore: () -> Unit) {
    val entitled = quota?.entitled == true
    SectionTitle(stringResource(Res.string.pro_title), Modifier.padding(top = 16.dp))
    Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(enabled = quota != null, role = Role.Button, onClick = onOpen)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        quota == null -> stringResource(Res.string.pro_loading)
                        // The date part of the ISO 8601 end; readable in every locale.
                        entitled -> stringResource(Res.string.settings_pro_active, quota.entitledUntil.orEmpty().take(10))
                        else -> stringResource(Res.string.settings_pro_free)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (quota != null) {
                    Text(
                        stringResource(if (entitled) Res.string.settings_pro_manage else Res.string.settings_pro_upgrade),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (entitled) Icon(RecipeIcons.External, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (restoreMessage != null) {
                Text(stringResource(restoreMessage), style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedButton(onClick = onRestore, enabled = !restoring, modifier = Modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.medium) {
                Text(stringResource(if (restoring) Res.string.pro_working else Res.string.pro_restore))
            }
        }
    }
}

@Composable
private fun AiImportsSection(given: Boolean, failed: Boolean, onWithdraw: () -> Unit) {
    SectionTitle(stringResource(Res.string.settings_ai), Modifier.padding(top = 16.dp))
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(if (given) Res.string.settings_ai_given else Res.string.settings_ai_not_given),
            style = MaterialTheme.typography.bodyLarge,
        )
        if (failed) {
            Text(
                stringResource(Res.string.settings_ai_withdraw_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (given) {
            OutlinedButton(onClick = onWithdraw, modifier = Modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.medium) {
                Text(stringResource(Res.string.settings_ai_withdraw))
            }
        }
    }
}
