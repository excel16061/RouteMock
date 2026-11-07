package com.routemock.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.routemock.app.R
import com.routemock.app.consent.ConsentDocument
import com.routemock.app.consent.ConsentLedger
import com.routemock.app.consent.ConsentScope

private fun scopeLabel(scope: ConsentScope): Int = when (scope) {
    ConsentScope.TERMS -> R.string.consent_terms
    ConsentScope.PRIVACY -> R.string.consent_privacy
    ConsentScope.MOCK_LOCATION -> R.string.consent_mock
    ConsentScope.DASHBOARD_UPLOAD -> R.string.consent_dashboard
}

@Composable
private fun DocumentDialog(document: ConsentDocument, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(document.text) } },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) } },
    )
}

/** Every box starts unchecked, and the optional one is separate from the required ones. */
@Composable
fun OnboardingScreen(viewModel: RouteMockViewModel) {
    var terms by rememberSaveable { mutableStateOf(false) }
    var privacy by rememberSaveable { mutableStateOf(false) }
    var mock by rememberSaveable { mutableStateOf(false) }
    var dashboard by rememberSaveable { mutableStateOf(false) }
    var reading by rememberSaveable { mutableStateOf<ConsentScope?>(null) }
    val receipt by viewModel.consentReceipt.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.onboarding_intro), style = MaterialTheme.typography.bodyMedium)
            ConsentRow(ConsentScope.TERMS, terms, { terms = it }, { reading = ConsentScope.TERMS })
            ConsentRow(ConsentScope.PRIVACY, privacy, { privacy = it }, { reading = ConsentScope.PRIVACY })
            ConsentRow(ConsentScope.MOCK_LOCATION, mock, { mock = it }, { reading = ConsentScope.MOCK_LOCATION })
            ConsentRow(ConsentScope.DASHBOARD_UPLOAD, dashboard, { dashboard = it }, { reading = ConsentScope.DASHBOARD_UPLOAD })
            Button(
                onClick = {
                    viewModel.acceptConsent(
                        ConsentLedger.REQUIRED + if (dashboard) setOf(ConsentScope.DASHBOARD_UPLOAD) else emptySet(),
                    )
                },
                enabled = terms && privacy && mock,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.action_continue)) }

            // Losing one consent must not trap the others, or the record of them.
            if (viewModel.anyConsentGranted(receipt)) {
                Text(stringResource(R.string.onboarding_remaining), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(
                    onClick = { viewModel.withdrawConsent(ConsentScope.entries.toSet()) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.action_withdraw_all)) }
            }
            if (receipt.events.isNotEmpty()) {
                OutlinedButton(
                    onClick = { shareReceipt(context, viewModel) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.action_export_receipt)) }
            }
        }
    }
    reading?.let { scope ->
        DocumentDialog(viewModel.consentDocuments.first { it.scope == scope }) { reading = null }
    }
}

private fun shareReceipt(context: Context, viewModel: RouteMockViewModel) {
    val share = Intent(Intent.ACTION_SEND).setType("application/json")
        .putExtra(Intent.EXTRA_TEXT, viewModel.exportConsentReceipt())
    context.startActivity(Intent.createChooser(share, null))
}

@Composable
private fun ConsentRow(scope: ConsentScope, checked: Boolean, onChange: (Boolean) -> Unit, onRead: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(stringResource(scopeLabel(scope)), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onRead) { Text(stringResource(R.string.action_read)) }
    }
}

@Composable
fun MoreScreen(viewModel: RouteMockViewModel) {
    val receipt by viewModel.consentReceipt.collectAsStateWithLifecycle()
    val pairing by viewModel.pairing.collectAsStateWithLifecycle()
    val remote by viewModel.remoteEnabled.collectAsStateWithLifecycle()
    val status by viewModel.dashboardStatus.collectAsStateWithLifecycle()
    var reading by rememberSaveable { mutableStateOf<ConsentScope?>(null) }
    var address by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val dashboardAllowed = viewModel.isGranted(ConsentScope.DASHBOARD_UPLOAD, receipt)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.consent_title), style = MaterialTheme.typography.headlineSmall)
        ConsentScope.entries.forEach { scope ->
            val granted = viewModel.isGranted(scope, receipt)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(scopeLabel(scope)), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(if (granted) R.string.consent_granted else R.string.consent_not_granted),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row {
                        TextButton(onClick = { reading = scope }) { Text(stringResource(R.string.action_read)) }
                        if (granted) {
                            TextButton(onClick = { viewModel.withdrawConsent(setOf(scope)) }) {
                                Text(stringResource(R.string.action_withdraw))
                            }
                        } else if (scope == ConsentScope.DASHBOARD_UPLOAD) {
                            TextButton(onClick = { viewModel.acceptConsent(setOf(scope)) }) {
                                Text(stringResource(R.string.action_allow))
                            }
                        }
                    }
                }
            }
        }
        Text(stringResource(R.string.consent_withdraw_note), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { viewModel.withdrawConsent(ConsentScope.entries.toSet()) }) {
            Text(stringResource(R.string.action_withdraw_all))
        }
        OutlinedButton(onClick = { shareReceipt(context, viewModel) }) {
            Text(stringResource(R.string.action_export_receipt))
        }

        Text(stringResource(R.string.dashboard_title), style = MaterialTheme.typography.headlineSmall)
        val connected = pairing
        when {
            connected != null -> {
                Text(stringResource(R.string.dashboard_connected, connected.label))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.dashboard_remote), Modifier.weight(1f))
                    Switch(checked = remote, onCheckedChange = viewModel::setRemoteEnabled)
                }
                Text(stringResource(R.string.dashboard_remote_note), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = viewModel::disconnectDashboard) {
                    Text(stringResource(R.string.action_disconnect))
                }
            }
            !dashboardAllowed -> Text(stringResource(R.string.dashboard_needs_consent))
            else -> {
                Text(stringResource(R.string.dashboard_off), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text(stringResource(R.string.dashboard_address)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text(stringResource(R.string.dashboard_code)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { viewModel.pairDashboard(address, code) }) {
                    Text(stringResource(R.string.action_pair))
                }
            }
        }
        status?.let { Text(stringResource(R.string.dashboard_error, it), style = MaterialTheme.typography.bodySmall) }
    }
    reading?.let { scope ->
        DocumentDialog(viewModel.consentDocuments.first { it.scope == scope }) { reading = null }
    }
}

/** The only way a remote request can start anything. */
@Composable
fun RemoteStartDialog(viewModel: RouteMockViewModel) {
    val command by viewModel.pendingRemote.collectAsStateWithLifecycle()
    val pending = command ?: return
    AlertDialog(
        onDismissRequest = { },
        title = { Text(stringResource(R.string.remote_title)) },
        text = {
            Text(
                stringResource(
                    R.string.remote_body,
                    pending.draftVersion,
                    stringResource(modeLabel(pending.draft.mode)),
                    pending.draft.startLatitude,
                    pending.draft.startLongitude,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = { viewModel.answerRemoteStart(true) }) { Text(stringResource(R.string.action_approve)) }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.answerRemoteStart(false) }) { Text(stringResource(R.string.action_reject)) }
        },
    )
}
