package com.linux.recipes.ui.signin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.window.Dialog
import com.linux.recipes.platform.TurnstileGate
import com.linux.recipes.platform.TurnstileView
import recipes.shared.generated.resources.action_cancel
import recipes.shared.generated.resources.signin_check_body
import recipes.shared.generated.resources.signin_check_title
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.linux.recipes.data.SessionRepository
import com.linux.recipes.data.SignInResult
import com.linux.recipes.data.SignInResult.Failed.Reason
import com.linux.recipes.ui.components.AppMark
import com.linux.recipes.ui.components.NoteBanner
import com.linux.recipes.ui.components.PageGutter
import com.linux.recipes.ui.components.RecipeIcons
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import recipes.shared.generated.resources.Res
import recipes.shared.generated.resources.app_name
import recipes.shared.generated.resources.signin_age_confirmation
import recipes.shared.generated.resources.signin_age_hint
import recipes.shared.generated.resources.signin_button
import recipes.shared.generated.resources.signin_cancelled
import recipes.shared.generated.resources.signin_failed_account_deleting
import recipes.shared.generated.resources.signin_failed_age
import recipes.shared.generated.resources.signin_failed_network
import recipes.shared.generated.resources.signin_failed_rate_limited
import recipes.shared.generated.resources.signin_failed_rejected
import recipes.shared.generated.resources.signin_progress
import recipes.shared.generated.resources.signin_tagline

sealed interface SignInStatus {
    data object Idle : SignInStatus
    data object Working : SignInStatus
    data class Finished(val result: SignInResult) : SignInStatus
}

class SignInViewModel(private val session: SessionRepository) : ViewModel() {
    var ageConfirmed by mutableStateOf(false)
    var status by mutableStateOf<SignInStatus>(SignInStatus.Idle)
        private set

    fun signIn() {
        if (status == SignInStatus.Working) return
        status = SignInStatus.Working
        viewModelScope.launch { status = SignInStatus.Finished(session.signIn(ageConfirmed)) }
    }
}

internal fun SignInResult.message(): StringResource? = when (this) {
    SignInResult.Success -> null
    SignInResult.Cancelled -> Res.string.signin_cancelled
    is SignInResult.Failed -> when (reason) {
        Reason.Network -> Res.string.signin_failed_network
        Reason.Rejected -> Res.string.signin_failed_rejected
        Reason.AgeNotConfirmed -> Res.string.signin_failed_age
        Reason.AccountDeleting -> Res.string.signin_failed_account_deleting
        Reason.RateLimited -> Res.string.signin_failed_rate_limited
    }
}

/** The Turnstile gate of the sign-in flow and the base URL its page is served from. */
class TurnstileChallenge(val gate: TurnstileGate, val baseUrl: String)

/**
 * Shown while the session repository waits for a Turnstile token. Closing it delivers null, which
 * makes sign-in return Cancelled.
 */
@Composable
private fun TurnstileDialog(challenge: TurnstileChallenge) {
    val pending by challenge.gate.pending.collectAsState()
    if (!pending) return
    Dialog(onDismissRequest = { challenge.gate.deliver(null) }) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(Res.string.signin_check_title), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(Res.string.signin_check_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TurnstileView(
                    baseUrl = challenge.baseUrl,
                    onToken = { challenge.gate.deliver(it) },
                    // The page itself could not load: sign-in ends as Failed(Rejected), not a blank dialog.
                    onError = { challenge.gate.fail() },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                )
                TextButton(
                    onClick = { challenge.gate.deliver(null) },
                    modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp),
                ) {
                    Text(stringResource(Res.string.action_cancel))
                }
            }
        }
    }
}

@Composable
fun SignInScreen(vm: SignInViewModel, turnstile: TurnstileChallenge? = null) {
    turnstile?.let { TurnstileDialog(it) }
    val working = vm.status == SignInStatus.Working
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = PageGutter + 8.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AppMark(size = 96.dp)
                Spacer(Modifier.size(28.dp))
                Text(
                    stringResource(Res.string.app_name),
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    stringResource(Res.string.signin_tagline),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.size(48.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .toggleable(
                            value = vm.ageConfirmed,
                            enabled = !working,
                            role = Role.Checkbox,
                            onValueChange = { vm.ageConfirmed = it },
                        )
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(checked = vm.ageConfirmed, onCheckedChange = null, enabled = !working)
                    Text(stringResource(Res.string.signin_age_confirmation), style = MaterialTheme.typography.bodyLarge)
                }
                Spacer(Modifier.size(12.dp))
                Button(
                    onClick = vm::signIn,
                    enabled = vm.ageConfirmed && !working,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(stringResource(Res.string.signin_button), style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.size(20.dp))
                Box(Modifier.fillMaxWidth().heightIn(min = 72.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                    when (val s = vm.status) {
                        SignInStatus.Working -> Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(12.dp))
                            Text(stringResource(Res.string.signin_progress), style = MaterialTheme.typography.bodyMedium)
                        }
                        is SignInStatus.Finished -> s.result.message()?.let {
                            NoteBanner(
                                icon = if (s.result is SignInResult.Failed) RecipeIcons.Close else RecipeIcons.Back,
                                text = stringResource(it),
                                error = s.result is SignInResult.Failed,
                            )
                        }
                        SignInStatus.Idle -> if (!vm.ageConfirmed) {
                            Text(
                                stringResource(Res.string.signin_age_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}
