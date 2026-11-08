package com.routemock.app.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.routemock.app.R
import com.routemock.app.domain.RouteValidator
import com.routemock.app.domain.SpeedUnit
import com.routemock.app.domain.formatSpeedValue
import com.routemock.app.mock.DeviceReadiness
import com.routemock.app.playback.Formatters
import com.routemock.app.playback.PlaybackSnapshot
import com.routemock.app.playback.PlaybackStatus
import com.routemock.app.simulation.SessionEnd
import com.routemock.app.simulation.SessionKind
import com.routemock.app.simulation.SimulationState

@Composable
fun SimulationScreen(viewModel: RouteMockViewModel) {
    val simulation by viewModel.simulation.collectAsStateWithLifecycle()
    val readiness by viewModel.readiness.collectAsStateWithLifecycle()
    val startBlock by viewModel.startBlock.collectAsStateWithLifecycle()
    val repeat by viewModel.repeat.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        viewModel.startInjection() // Reports the problem itself if permission was refused.
    }
    val onStartInjection = {
        if (readiness.mockAppSelected && shouldRequestNotifications(context)) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.startInjection()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.section_simulation), style = MaterialTheme.typography.headlineSmall)

        when (val state = simulation) {
            is SimulationState.Idle -> {
                state.lastEnd?.let { SessionEndNotice(it, viewModel::dismissLastEnd) }
                StartBlockNotice(startBlock, context)
                Notice(
                    NoticeTone.INFO,
                    stringResource(R.string.idle_title),
                    body = stringResource(R.string.idle_body),
                )
                Button(onClick = onStartInjection, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_start_mock))
                }
                OutlinedButton(onClick = viewModel::startPreview, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_start_preview))
                }
                RepeatRow(repeat, viewModel::setRepeat)
                SetupCard(readiness, context)
            }
            is SimulationState.Active -> {
                SessionBadge(state)
                Readouts(state)
                Controls(state, viewModel)
                RepeatRow(repeat, viewModel::setRepeat)
                if (!state.snapshot.isStationary) LiveSpeedEditor(state, viewModel::setLiveSpeed)
                if (state.kind == SessionKind.INJECTION && !readiness.locationEnabled) {
                    Notice(NoticeTone.WARNING, stringResource(R.string.setup_location_off))
                }
            }
        }
    }
}

private fun shouldRequestNotifications(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED

@Composable
private fun SessionBadge(state: SimulationState.Active) {
    when (state.kind) {
        SessionKind.PREVIEW -> Notice(
            NoticeTone.WARNING,
            stringResource(R.string.preview_title),
            body = stringResource(R.string.preview_body),
        )
        SessionKind.INJECTION -> Notice(
            NoticeTone.ACTIVE,
            stringResource(R.string.injection_title),
            body = stringResource(R.string.injection_body, state.providers.joinToString(", ")),
        )
    }
}

@Composable
private fun Readouts(state: SimulationState.Active) {
    val snapshot = state.snapshot
    val unit = state.displayUnit
    val none = stringResource(R.string.not_applicable)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(statusLabel(snapshot)), style = MaterialTheme.typography.titleMedium)
            if (!snapshot.isStationary) {
                val total = snapshot.distanceTraveledMeters + snapshot.distanceRemainingMeters
                LinearProgressIndicator(
                    progress = { if (total > 0) (snapshot.distanceTraveledMeters / total).toFloat() else 1f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Readout(R.string.latitude, Formatters.coordinate(snapshot.position.latitude))
            Readout(R.string.longitude, Formatters.coordinate(snapshot.position.longitude))
            Readout(R.string.speed, Formatters.speed(snapshot.speedMetersPerSecond, unit))
            Readout(
                R.string.distance_remaining,
                if (snapshot.isStationary) none else Formatters.distance(snapshot.distanceRemainingMeters, unit),
            )
            Readout(R.string.time_remaining, snapshot.remainingSeconds?.let(Formatters::duration) ?: none)
            if (snapshot.repeat && snapshot.completedLaps > 0) {
                Readout(R.string.laps_completed, snapshot.completedLaps.toString())
            }
        }
    }
}

private fun statusLabel(snapshot: PlaybackSnapshot): Int = when {
    snapshot.status == PlaybackStatus.PAUSED -> R.string.status_paused
    snapshot.status == PlaybackStatus.COMPLETED -> R.string.status_completed
    snapshot.isStationary -> R.string.status_stationary
    else -> R.string.status_moving
}

@Composable
private fun Readout(label: Int, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Controls(state: SimulationState.Active, viewModel: RouteMockViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        when (state.snapshot.status) {
            PlaybackStatus.RUNNING -> Button(onClick = viewModel::pause, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.action_pause))
            }
            PlaybackStatus.PAUSED -> Button(onClick = viewModel::resume, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.action_resume))
            }
            PlaybackStatus.COMPLETED -> Button(onClick = viewModel::restart, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.action_repeat_now))
            }
        }
        OutlinedButton(onClick = viewModel::stop, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.action_stop))
        }
    }
}

@Composable
private fun RepeatRow(repeat: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.repeat_title), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.repeat_body), style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = repeat, onCheckedChange = onChange)
    }
}

@Composable
private fun LiveSpeedEditor(state: SimulationState.Active, onApply: (Double) -> Unit) {
    val unit: SpeedUnit = state.displayUnit
    var text by rememberSaveable {
        mutableStateOf(formatSpeedValue(unit.fromMetersPerSecond(state.snapshot.selectedSpeedMetersPerSecond)))
    }
    val error = RouteValidator.validateSpeed(text, unit)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(R.string.speed_in_unit, unit.label)) },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { { Text(stringResource(messageFor(it))) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(
            onClick = { RouteValidator.parseDecimal(text)?.let { onApply(unit.toMetersPerSecond(it)) } },
            enabled = error == null,
        ) { Text(stringResource(R.string.action_apply)) }
    }
}

@Composable
private fun SessionEndNotice(end: SessionEnd, onDismiss: () -> Unit) {
    val leftover = stringResource(R.string.end_cleanup_incomplete)
    val restore = stringResource(R.string.end_restore_explanation)
    val (tone, title, body) = when (end) {
        is SessionEnd.Stopped -> when (end.kind) {
            SessionKind.PREVIEW -> Triple(NoticeTone.INFO, stringResource(R.string.end_preview_stopped), null)
            SessionKind.INJECTION -> Triple(
                if (end.cleanupComplete) NoticeTone.INFO else NoticeTone.ERROR,
                stringResource(R.string.end_stopped),
                if (end.cleanupComplete) restore else leftover,
            )
        }
        is SessionEnd.NotAuthorized -> Triple(
            NoticeTone.ERROR,
            stringResource(R.string.end_not_authorized),
            if (end.cleanupComplete) stringResource(R.string.setup_instructions) else leftover,
        )
        is SessionEnd.Failed -> Triple(
            NoticeTone.ERROR,
            stringResource(R.string.end_failed, end.detail),
            if (end.cleanupComplete) restore else leftover,
        )
        is SessionEnd.Interrupted -> Triple(
            NoticeTone.WARNING,
            stringResource(R.string.end_interrupted),
            if (end.cleanupComplete) stringResource(R.string.end_interrupted_cleaned) else leftover,
        )
    }
    Notice(tone, title, body = body) {
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_dismiss)) }
    }
}

@Composable
private fun StartBlockNotice(block: StartBlock?, context: Context) {
    when (block) {
        StartBlock.INVALID_ROUTE -> Notice(NoticeTone.ERROR, stringResource(R.string.block_invalid_route))
        StartBlock.ROAD_ROUTE_NEEDED -> Notice(NoticeTone.ERROR, stringResource(R.string.block_road_route))
        StartBlock.MOCK_APP_NOT_SELECTED -> Notice(
            NoticeTone.ERROR,
            stringResource(R.string.block_mock_not_selected),
            body = stringResource(R.string.setup_instructions),
        ) {
            TextButton(onClick = { openDeveloperOptions(context) }) {
                Text(stringResource(R.string.action_open_developer_options))
            }
        }
        StartBlock.NOTIFICATIONS_DISABLED -> Notice(
            NoticeTone.ERROR,
            stringResource(R.string.block_notifications),
        ) {
            TextButton(onClick = { openNotificationSettings(context) }) {
                Text(stringResource(R.string.action_open_notification_settings))
            }
        }
        null -> Unit
    }
}

@Composable
private fun SetupCard(readiness: DeviceReadiness, context: Context) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.titleMedium)
            SetupItem(R.string.setup_developer_options, readiness.developerOptionsEnabled)
            SetupItem(R.string.setup_mock_app, readiness.mockAppSelected)
            SetupItem(R.string.setup_notifications, readiness.notificationsEnabled)
            SetupItem(R.string.setup_location, readiness.locationEnabled)
            Text(stringResource(R.string.setup_instructions), style = MaterialTheme.typography.bodySmall)
            if (!readiness.mockAppSelected) {
                TextButton(onClick = { openDeveloperOptions(context) }) {
                    Text(stringResource(R.string.action_open_developer_options))
                }
            }
        }
    }
}

@Composable
private fun SetupItem(label: Int, ready: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(if (ready) R.string.setup_ready else R.string.setup_not_ready),
            style = MaterialTheme.typography.bodyMedium,
            color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
}

/** Only opens the screen. The choice stays with the user. */
private fun openDeveloperOptions(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
    } catch (_: ActivityNotFoundException) {
        // Developer options are still hidden, so go where they get enabled.
        context.startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
    }
}

private fun openNotificationSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
    )
}
