package com.routemock.app.ui

import android.Manifest
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.routemock.app.R
import com.routemock.app.domain.RoadRoute
import com.routemock.app.domain.RouteDraft
import com.routemock.app.domain.RouteMode
import com.routemock.app.domain.RouteValidator
import com.routemock.app.domain.SelectionTarget
import com.routemock.app.domain.StraightLineRoute
import com.routemock.app.location.DeliveryStatus
import com.routemock.app.location.InjectionWindow
import com.routemock.app.location.RealFix
import com.routemock.app.location.RealLocationStatus
import com.routemock.app.location.deliveryStatus
import com.routemock.app.location.realLocationStatus
import com.routemock.app.playback.Formatters
import com.routemock.app.playback.PlaybackStatus
import com.routemock.app.simulation.SessionKind
import com.routemock.app.simulation.SimulationState
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

@Composable
fun MapScreen(viewModel: RouteMockViewModel) {
    val controller = remember { MapController() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth > maxHeight) {
            Row(Modifier.fillMaxSize()) {
                MapPane(viewModel, controller, Modifier.weight(1f).fillMaxHeight())
                MapPanel(viewModel, controller, Modifier.width(320.dp).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                MapPane(viewModel, controller, Modifier.weight(1f).fillMaxWidth())
                MapPanel(viewModel, controller, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

/** Every control here works from app state, so none of it depends on tiles loading. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MapPanel(viewModel: RouteMockViewModel, controller: MapController, modifier: Modifier) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val target by viewModel.selectionTarget.collectAsStateWithLifecycle()
    val simulation by viewModel.simulation.collectAsStateWithLifecycle()
    val follow by viewModel.followSimulation.collectAsStateWithLifecycle()
    val realFix by viewModel.realFix.collectAsStateWithLifecycle()
    val active = simulation as? SimulationState.Active
    val stationary = draft.mode == RouteMode.STATIONARY

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            RouteMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = draft.mode == mode,
                    onClick = { viewModel.setMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, RouteMode.entries.size),
                    label = {
                        Text(
                            stringResource(modeLabel(mode)),
                        )
                    },
                )
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectionButton(
                label = stringResource(if (stationary) R.string.map_select_stationary else R.string.map_select_start),
                selected = target == SelectionTarget.START,
                onClick = {
                    viewModel.selectTarget(SelectionTarget.START.takeUnless { target == it })
                },
            )
            if (!stationary) {
                SelectionButton(
                    label = stringResource(R.string.map_select_destination),
                    selected = target == SelectionTarget.DESTINATION,
                    onClick = {
                        viewModel.selectTarget(SelectionTarget.DESTINATION.takeUnless { target == it })
                    },
                )
            }
        }
        Text(
            stringResource(
                when {
                    target == null -> R.string.map_hint_none
                    target == SelectionTarget.DESTINATION -> R.string.map_hint_destination
                    stationary -> R.string.map_hint_stationary
                    else -> R.string.map_hint_start
                },
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        if (active != null) {
            Notice(NoticeTone.INFO, stringResource(R.string.map_draft_only))
        }

        RouteSummary(draft)
        if (draft.mode == RouteMode.ROAD) RoadRoutePanel(draft, viewModel)
        CoordinateEditor(draft, viewModel)
        LocationStatus(viewModel, active, realFix)

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { realFix?.let { controller.center(it.position) } },
                enabled = realFix != null,
            ) { Text(stringResource(R.string.action_center_real)) }
            OutlinedButton(
                onClick = { active?.let { controller.center(it.snapshot.position) } },
                enabled = active != null,
            ) { Text(stringResource(R.string.action_center_simulated)) }
            OutlinedButton(onClick = { controller.fitRoute(active?.route, draft) }) {
                Text(stringResource(R.string.action_fit_route))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.follow_simulation), style = MaterialTheme.typography.bodyLarge)
            Switch(checked = follow, onCheckedChange = viewModel::setFollowSimulation)
        }

        if (active != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                when (active.snapshot.status) {
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
    }
}

@Composable
private fun SelectionButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(label) }
    } else {
        FilledTonalButton(onClick = onClick) { Text(label) }
    }
}

@Composable
private fun RouteSummary(draft: RouteDraft) {
    val validation = RouteValidator.validate(draft)
    val result = validation.result
    val route = result?.route
    when {
        draft.mode == RouteMode.STATIONARY -> Text(
            stringResource(R.string.map_stationary_summary),
            style = MaterialTheme.typography.bodyMedium,
        )
        route is RoadRoute -> Column {
            Text(stringResource(R.string.road_route_selected), style = MaterialTheme.typography.titleSmall)
            SummaryRow(R.string.map_summary_distance, Formatters.distance(route.lengthMeters, draft.speedUnit))
            SummaryRow(R.string.map_summary_speed, Formatters.speed(result.speedMetersPerSecond, draft.speedUnit))
            SummaryRow(
                R.string.map_summary_duration_at_speed,
                Formatters.duration(route.lengthMeters / result.speedMetersPerSecond),
            )
        }
        draft.mode == RouteMode.ROAD -> Unit // The road panel explains what is needed.
        route is StraightLineRoute -> Column {
            Text(stringResource(R.string.straight_line_label), style = MaterialTheme.typography.titleSmall)
            SummaryRow(R.string.map_summary_distance, Formatters.distance(route.lengthMeters, draft.speedUnit))
            SummaryRow(R.string.map_summary_speed, Formatters.speed(result.speedMetersPerSecond, draft.speedUnit))
            SummaryRow(
                R.string.map_summary_duration,
                Formatters.duration(route.lengthMeters / result.speedMetersPerSecond),
            )
        }
        else -> Text(stringResource(R.string.map_summary_incomplete), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SummaryRow(label: Int, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CoordinateEditor(draft: RouteDraft, viewModel: RouteMockViewModel) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val validation = RouteValidator.validate(draft)
    TextButton(onClick = { expanded = !expanded }) {
        Text(stringResource(if (expanded) R.string.map_hide_coordinates else R.string.map_edit_coordinates))
    }
    if (!expanded) return
    val stationary = draft.mode == RouteMode.STATIONARY
    Text(
        stringResource(if (stationary) R.string.stationary_point else R.string.starting_point),
        style = MaterialTheme.typography.titleSmall,
    )
    CoordinateField(
        label = stringResource(R.string.latitude),
        value = draft.startLatitude,
        error = validation.startLatitudeError.visible(false),
        onValueChange = { text -> viewModel.updateDraft { it.copy(startLatitude = text) } },
    )
    CoordinateField(
        label = stringResource(R.string.longitude),
        value = draft.startLongitude,
        error = validation.startLongitudeError.visible(false),
        onValueChange = { text -> viewModel.updateDraft { it.copy(startLongitude = text) } },
    )
    if (!stationary) {
        Text(stringResource(R.string.destination), style = MaterialTheme.typography.titleSmall)
        CoordinateField(
            label = stringResource(R.string.latitude),
            value = draft.destinationLatitude,
            error = validation.destinationLatitudeError.visible(false),
            onValueChange = { text -> viewModel.updateDraft { it.copy(destinationLatitude = text) } },
        )
        CoordinateField(
            label = stringResource(R.string.longitude),
            value = draft.destinationLongitude,
            error = validation.destinationLongitudeError.visible(false),
            onValueChange = { text -> viewModel.updateDraft { it.copy(destinationLongitude = text) } },
        )
    }
}

@Composable
private fun LocationStatus(viewModel: RouteMockViewModel, active: SimulationState.Active?, realFix: RealFix?) {
    val readiness by viewModel.readiness.collectAsStateWithLifecycle()
    val refused by viewModel.locationRefused.collectAsStateWithLifecycle()
    val observed by viewModel.observedMock.collectAsStateWithLifecycle()
    val now by rememberElapsedNanos(viewModel)
    val context = LocalContext.current
    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result -> viewModel.onLocationPermissionResult(result.values.any { it }) }

    when (val status = realLocationStatus(realFix, now)) {
        RealLocationStatus.Unavailable -> Text(
            stringResource(R.string.real_unavailable),
            style = MaterialTheme.typography.titleSmall,
        )
        is RealLocationStatus.Live -> RealFixText(R.string.marker_real_live, status.fix, status.ageSeconds)
        is RealLocationStatus.Last -> RealFixText(R.string.marker_real_last, status.fix, status.ageSeconds)
    }

    if (!readiness.locationPermissionGranted) {
        Text(
            stringResource(if (refused) R.string.real_permission_denied else R.string.real_permission_needed),
            style = MaterialTheme.typography.bodySmall,
        )
        val activity = context as? android.app.Activity
        val canAsk = !refused || activity?.let {
            ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.ACCESS_FINE_LOCATION)
        } == true
        if (canAsk) {
            TextButton(onClick = {
                permissions.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                )
            }) { Text(stringResource(R.string.action_allow_location)) }
        } else {
            TextButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            }) { Text(stringResource(R.string.action_open_app_settings)) }
        }
    }

    if (active == null) return
    val position = active.snapshot.position
    val latitude = Formatters.coordinate(position.latitude)
    val longitude = Formatters.coordinate(position.longitude)
    if (active.kind == SessionKind.PREVIEW) {
        Text(stringResource(R.string.simulated_preview_status, latitude, longitude), style = MaterialTheme.typography.bodySmall)
        return
    }
    Text(stringResource(R.string.simulated_status, latitude, longitude), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.real_while_mocking), style = MaterialTheme.typography.bodySmall)

    val injection = InjectionWindow(active.startedAtElapsedNanos, position)
    val delivery = deliveryStatus(injection, observed, readiness.locationPermissionGranted, now)
    Text(
        when (delivery) {
            DeliveryStatus.NotApplicable -> ""
            DeliveryStatus.Unobservable -> stringResource(R.string.delivery_unobservable)
            DeliveryStatus.Waiting -> stringResource(R.string.delivery_waiting)
            is DeliveryStatus.Observed -> stringResource(
                R.string.delivery_observed,
                Formatters.age(delivery.ageSeconds),
                Formatters.distance(delivery.offsetMeters, active.displayUnit),
            )
            is DeliveryStatus.Stale -> stringResource(R.string.delivery_stale, Formatters.age(delivery.ageSeconds))
        },
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun RealFixText(label: Int, fix: RealFix, ageSeconds: Long) {
    val name = stringResource(label)
    val age = Formatters.age(ageSeconds)
    val time = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(fix.timeMillis))
    val accuracy = fix.accuracyMeters
    Text(
        if (accuracy != null) {
            stringResource(R.string.real_status, name, age, time, "±%.0f m".format(java.util.Locale.US, accuracy))
        } else {
            stringResource(R.string.real_status_no_accuracy, name, age, time)
        },
        style = MaterialTheme.typography.titleSmall,
    )
}

/** Ticks once a second so ages stay accurate even when no new fix arrives. */
@Composable
internal fun rememberElapsedNanos(viewModel: RouteMockViewModel) = produceState(viewModel.clock.elapsedRealtimeNanos()) {
    while (true) {
        value = viewModel.clock.elapsedRealtimeNanos()
        delay(1000)
    }
}

/** True while a network that can reach the internet exists. */
@Composable
internal fun rememberIsOnline(): Boolean {
    val context = LocalContext.current
    val manager = remember { context.getSystemService(ConnectivityManager::class.java) }
    fun currentlyOnline() = manager.getNetworkCapabilities(manager.activeNetwork)
        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    var online by remember { mutableStateOf(currentlyOnline()) }
    DisposableEffect(manager) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                online = true
            }

            override fun onLost(network: Network) {
                online = currentlyOnline()
            }
        }
        manager.registerDefaultNetworkCallback(callback)
        onDispose { manager.unregisterNetworkCallback(callback) }
    }
    return online
}
