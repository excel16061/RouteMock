package com.routemock.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.routemock.app.R
import com.routemock.app.domain.RoadRoute
import com.routemock.app.domain.RouteError
import com.routemock.app.domain.RouteMode
import com.routemock.app.domain.RouteValidator
import com.routemock.app.domain.SpeedUnit
import com.routemock.app.domain.StraightLineRoute
import com.routemock.app.playback.Formatters
import com.routemock.app.simulation.SimulationState

@Composable
fun RouteScreen(viewModel: RouteMockViewModel, onGoToSimulation: () -> Unit) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val showAll by viewModel.showAllErrors.collectAsStateWithLifecycle()
    val simulation by viewModel.simulation.collectAsStateWithLifecycle()
    var naming by rememberSaveable { mutableStateOf(false) }
    val validation = RouteValidator.validate(draft)
    val stationary = draft.mode == RouteMode.STATIONARY

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.section_route), style = MaterialTheme.typography.headlineSmall)

        if (simulation is SimulationState.Active) {
            Notice(NoticeTone.INFO, stringResource(R.string.route_edit_while_running))
        }

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

        if (draft.mode == RouteMode.ROAD) {
            Notice(
                NoticeTone.INFO,
                stringResource(R.string.road_mode_title),
                body = stringResource(R.string.road_mode_body),
            )
        }
        if (draft.mode == RouteMode.STRAIGHT_LINE) {
            Notice(
                NoticeTone.WARNING,
                stringResource(R.string.straight_line_label),
                body = stringResource(R.string.straight_line_explanation),
            )
        }

        Text(
            stringResource(if (stationary) R.string.stationary_point else R.string.starting_point),
            style = MaterialTheme.typography.titleMedium,
        )
        CoordinateField(
            label = stringResource(R.string.latitude),
            value = draft.startLatitude,
            error = validation.startLatitudeError.visible(showAll),
            onValueChange = { text -> viewModel.updateDraft { it.copy(startLatitude = text) } },
        )
        CoordinateField(
            label = stringResource(R.string.longitude),
            value = draft.startLongitude,
            error = validation.startLongitudeError.visible(showAll),
            onValueChange = { text -> viewModel.updateDraft { it.copy(startLongitude = text) } },
        )

        if (!stationary) {
            Text(stringResource(R.string.destination), style = MaterialTheme.typography.titleMedium)
            CoordinateField(
                label = stringResource(R.string.latitude),
                value = draft.destinationLatitude,
                error = validation.destinationLatitudeError.visible(showAll),
                onValueChange = { text -> viewModel.updateDraft { it.copy(destinationLatitude = text) } },
            )
            CoordinateField(
                label = stringResource(R.string.longitude),
                value = draft.destinationLongitude,
                error = validation.destinationLongitudeError.visible(showAll),
                onValueChange = { text -> viewModel.updateDraft { it.copy(destinationLongitude = text) } },
            )

            Text(stringResource(R.string.speed), style = MaterialTheme.typography.titleMedium)
            val speedError = validation.speedError.visible(showAll)
            OutlinedTextField(
                value = draft.speed,
                onValueChange = { text -> viewModel.updateDraft { it.copy(speed = text) } },
                label = { Text(stringResource(R.string.speed_in_unit, draft.speedUnit.label)) },
                singleLine = true,
                isError = speedError != null,
                supportingText = speedError?.let { { Text(stringResource(messageFor(it))) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SpeedUnit.entries.forEachIndexed { index, unit ->
                    SegmentedButton(
                        selected = draft.speedUnit == unit,
                        onClick = { viewModel.setSpeedUnit(unit) },
                        shape = SegmentedButtonDefaults.itemShape(index, SpeedUnit.entries.size),
                        label = { Text(unit.label) },
                    )
                }
            }
        }

        when (validation.routeError) {
            RouteError.IDENTICAL_ENDPOINTS -> Notice(
                NoticeTone.ERROR,
                stringResource(R.string.error_identical_title),
                body = stringResource(R.string.error_identical_body),
            )
            RouteError.ANTIPODAL_ENDPOINTS -> Notice(
                NoticeTone.ERROR,
                stringResource(R.string.error_antipodal_title),
                body = stringResource(R.string.error_antipodal_body),
            )
            RouteError.ROAD_ROUTE_MISSING -> Notice(NoticeTone.WARNING, stringResource(R.string.road_route_missing))
            RouteError.ROAD_ROUTE_OUTDATED -> Notice(NoticeTone.WARNING, stringResource(R.string.road_route_outdated))
            null -> Unit
        }

        validation.result?.let { result ->
            val route = result.route
            if (route is RoadRoute) {
                Notice(
                    NoticeTone.INFO,
                    stringResource(
                        R.string.road_route_summary,
                        Formatters.distance(route.lengthMeters, draft.speedUnit),
                        Formatters.duration(route.lengthMeters / result.speedMetersPerSecond),
                    ),
                )
            }
            if (route is StraightLineRoute) {
                Notice(
                    NoticeTone.INFO,
                    stringResource(
                        R.string.route_summary,
                        Formatters.distance(route.lengthMeters, draft.speedUnit),
                        Formatters.duration(route.lengthMeters / result.speedMetersPerSecond),
                    ),
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { if (viewModel.canSaveDraft()) naming = true },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.action_save_route)) }
            Button(onClick = onGoToSimulation, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.action_go_to_simulation))
            }
        }
    }

    if (naming) {
        RouteNameDialog(
            title = stringResource(R.string.save_route_title),
            initialName = "",
            onConfirm = {
                viewModel.saveDraft(it)
                naming = false
            },
            onDismiss = { naming = false },
        )
    }
}

internal fun modeLabel(mode: RouteMode): Int = when (mode) {
    RouteMode.STATIONARY -> R.string.mode_stationary
    RouteMode.ROAD -> R.string.mode_road
    RouteMode.STRAIGHT_LINE -> R.string.mode_straight_line
}
