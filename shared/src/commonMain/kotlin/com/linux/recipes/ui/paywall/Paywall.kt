package com.linux.recipes.ui.paywall

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.linux.recipes.AppConfig
import com.linux.recipes.data.BillingRepository
import com.linux.recipes.data.Plan
import com.linux.recipes.data.PlanPeriod
import com.linux.recipes.data.PurchaseResult
import com.linux.recipes.ui.components.PageGutter
import com.linux.recipes.ui.components.RecipeIcons
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import recipes.shared.generated.resources.Res
import recipes.shared.generated.resources.action_close
import recipes.shared.generated.resources.import_paywall_body
import recipes.shared.generated.resources.import_paywall_title
import recipes.shared.generated.resources.import_type_instead
import recipes.shared.generated.resources.pro_cancelled
import recipes.shared.generated.resources.pro_failed
import recipes.shared.generated.resources.pro_feature_imports
import recipes.shared.generated.resources.pro_feature_sharing
import recipes.shared.generated.resources.pro_feature_storage
import recipes.shared.generated.resources.pro_loading
import recipes.shared.generated.resources.pro_pending
import recipes.shared.generated.resources.pro_plan_month
import recipes.shared.generated.resources.pro_plan_month_price
import recipes.shared.generated.resources.pro_plan_year
import recipes.shared.generated.resources.pro_plan_year_price
import recipes.shared.generated.resources.pro_renewal
import recipes.shared.generated.resources.pro_restore
import recipes.shared.generated.resources.pro_restore_none
import recipes.shared.generated.resources.pro_restored
import recipes.shared.generated.resources.pro_store_unavailable
import recipes.shared.generated.resources.pro_subscribe
import recipes.shared.generated.resources.pro_title
import recipes.shared.generated.resources.pro_working
import recipes.shared.generated.resources.settings_privacy
import recipes.shared.generated.resources.settings_terms

/** The store of the running app; null (tests, previews) behaves like a device without a store. */
val LocalBilling = staticCompositionLocalOf<BillingRepository?> { null }

/** What the user sees after a purchase or restore; never a raw code. */
fun messageOf(result: PurchaseResult, restore: Boolean): StringResource = when (result) {
    PurchaseResult.Success -> Res.string.pro_restored
    PurchaseResult.Cancelled -> Res.string.pro_cancelled
    PurchaseResult.Pending -> Res.string.pro_pending
    PurchaseResult.StoreUnavailable -> Res.string.pro_store_unavailable
    PurchaseResult.Failed -> if (restore) Res.string.pro_restore_none else Res.string.pro_failed
}

/** The paywall's store calls; it lives as long as the sheet. */
class PaywallState(private val billing: BillingRepository?) {
    /** Null while loading; empty when the store is unavailable. */
    var plans by mutableStateOf<List<Plan>?>(null)
        private set
    var selected by mutableStateOf<String?>(null)
    var working by mutableStateOf(false)
        private set
    var message by mutableStateOf<StringResource?>(null)
        private set

    suspend fun load() {
        val loaded = billing?.plans().orEmpty()
        plans = loaded
        if (selected == null) selected = loaded.firstOrNull()?.id
    }

    /** True when Recipes Pro is now active. */
    suspend fun subscribe(): Boolean {
        val planId = selected ?: return false
        return run(restore = false) { it.purchase(planId) }
    }

    suspend fun restore(): Boolean = run(restore = true) { it.restore() }

    private suspend fun run(restore: Boolean, call: suspend (BillingRepository) -> PurchaseResult): Boolean {
        val repository = billing ?: return false
        working = true
        message = null
        val result = try {
            call(repository)
        } finally {
            working = false
        }
        message = messageOf(result, restore)
        return result == PurchaseResult.Success
    }
}

/** The paywall as a bottom sheet. [onTypeIn] is set when an import led here; it adds the import's headline and "Type it in instead". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaywallSheet(
    onClose: () -> Unit,
    onSubscribed: () -> Unit,
    onTypeIn: (() -> Unit)? = null,
    billing: BillingRepository? = LocalBilling.current,
) {
    // The sheet's own window re-provides the platform's handler on Android; keep the app's.
    val uriHandler = LocalUriHandler.current
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        CompositionLocalProvider(LocalUriHandler provides uriHandler) { Paywall(billing, onTypeIn, onClose, onSubscribed) }
    }
}

/** Loads the plans and runs purchase and restore; [onSubscribed] runs once Recipes Pro is active. */
@Composable
fun Paywall(billing: BillingRepository?, onTypeIn: (() -> Unit)?, onClose: () -> Unit, onSubscribed: () -> Unit) {
    val state = remember(billing) { PaywallState(billing) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state) { state.load() }
    PaywallContent(
        plans = state.plans,
        selected = state.selected,
        onSelect = { state.selected = it },
        working = state.working,
        message = state.message,
        onSubscribe = { scope.launch { if (state.subscribe()) onSubscribed() } },
        onRestore = { scope.launch { if (state.restore()) onSubscribed() } },
        onTypeIn = onTypeIn,
        onClose = onClose,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PaywallContent(
    plans: List<Plan>?,
    selected: String?,
    onSelect: (String) -> Unit,
    working: Boolean,
    message: StringResource?,
    onSubscribe: () -> Unit,
    onRestore: () -> Unit,
    onTypeIn: (() -> Unit)?,
    onClose: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = PageGutter, end = PageGutter, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (onTypeIn != null) {
            Text(
                stringResource(Res.string.import_paywall_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(Res.string.import_paywall_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            stringResource(Res.string.pro_title),
            style = if (onTypeIn != null) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = if (onTypeIn != null) 8.dp else 0.dp).semantics { heading() },
        )
        listOf(Res.string.pro_feature_imports, Res.string.pro_feature_storage, Res.string.pro_feature_sharing).forEach { feature ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(RecipeIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text(stringResource(feature), style = MaterialTheme.typography.bodyLarge)
            }
        }
        when {
            plans == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(Res.string.pro_loading), style = MaterialTheme.typography.bodyLarge)
            }
            plans.isEmpty() -> Note(Res.string.pro_store_unavailable)
            else -> Column(Modifier.selectableGroup().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                plans.forEach { plan -> PlanRow(plan, plan.id == selected, enabled = !working) { onSelect(plan.id) } }
            }
        }
        if (message != null) Note(message)
        Button(
            onClick = onSubscribe,
            enabled = !working && !plans.isNullOrEmpty() && selected != null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            shape = MaterialTheme.shapes.medium,
        ) {
            if (working) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(Res.string.pro_working), modifier = Modifier.padding(start = 8.dp))
            } else {
                Text(stringResource(Res.string.pro_subscribe))
            }
        }
        Text(
            stringResource(Res.string.pro_renewal),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onRestore, enabled = !working, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(Res.string.pro_restore))
            }
            TextButton(onClick = { uriHandler.openUri(AppConfig.TERMS_URL) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(Res.string.settings_terms))
            }
            TextButton(onClick = { uriHandler.openUri(AppConfig.PRIVACY_URL) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(Res.string.settings_privacy))
            }
        }
        if (onTypeIn != null) {
            OutlinedButton(onClick = onTypeIn, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = MaterialTheme.shapes.medium) {
                Icon(RecipeIcons.Edit, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(stringResource(Res.string.import_type_instead), modifier = Modifier.padding(start = 8.dp))
            }
        }
        TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(Res.string.action_close))
        }
    }
}

/** One plan: its period and the store's price for it, nothing computed from prices. */
@Composable
private fun PlanRow(plan: Plan, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val (label, price) = when (plan.period) {
        PlanPeriod.Month -> Res.string.pro_plan_month to Res.string.pro_plan_month_price
        PlanPeriod.Year -> Res.string.pro_plan_year to Res.string.pro_plan_year_price
    }
    val outline = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(MaterialTheme.shapes.medium)
            .border(BorderStroke(if (selected) 2.dp else 1.dp, outline), MaterialTheme.shapes.medium)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(stringResource(label), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(price, plan.price), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Note(text: StringResource) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .semantics { liveRegion = LiveRegionMode.Polite }.padding(12.dp),
    )
}
