package com.routemock.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.routemock.app.R
import com.routemock.app.domain.RoadRoute
import com.routemock.app.domain.RouteDraft
import com.routemock.app.domain.SpeedUnit
import com.routemock.app.domain.TrafficBasis
import com.routemock.app.domain.currentRoadRoute
import com.routemock.app.domain.pinsForRouting
import com.routemock.app.playback.Formatters
import com.routemock.app.routing.LongerAlternative
import com.routemock.app.routing.NoLongerRouteReason
import com.routemock.app.routing.RouteCandidate
import com.routemock.app.routing.RouteComparer
import com.routemock.app.routing.RouteComparison
import com.routemock.app.routing.RoutingFailure
import com.routemock.app.routing.TrafficStatus
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun RoadRoutePanel(draft: RouteDraft, viewModel: RouteMockViewModel) {
    val state by viewModel.roadRoutes.collectAsStateWithLifecycle()
    val maxExtra by viewModel.maxExtraPercent.collectAsStateWithLifecycle()
    val info = viewModel.routingInfo
    val pins = draft.pinsForRouting()

    Text(stringResource(R.string.road_privacy, info.name), style = MaterialTheme.typography.bodySmall)
    Button(
        onClick = viewModel::calculateRoadRoutes,
        enabled = pins != null && state != RoadRouteState.Loading,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            stringResource(
                if (state == RoadRouteState.Loading) R.string.road_calculating else R.string.action_calculate_routes,
            ),
        )
    }
    if (pins == null) {
        Text(stringResource(R.string.road_needs_pins), style = MaterialTheme.typography.bodySmall)
    }

    when (val current = state) {
        RoadRouteState.Idle, RoadRouteState.Loading -> Unit
        is RoadRouteState.Failed -> Notice(
            NoticeTone.ERROR,
            stringResource(failureMessage(current.failure)),
            body = stringResource(R.string.road_no_fallback),
        )
        is RoadRouteState.Ready -> {
            
            val applies = pins != null && pins.first == current.request.start && pins.second == current.request.destination
            if (applies) {
                ComparisonResults(current, maxExtra, draft, viewModel)
            } else {
                Notice(NoticeTone.WARNING, stringResource(R.string.road_routes_outdated))
            }
        }
    }

    SelectedRouteDetails(draft)
    TrafficLine(TrafficStatus.NotConfigured)
    Attribution(info.attribution)
}

@Composable
private fun ComparisonResults(
    state: RoadRouteState.Ready,
    maxExtra: Int,
    draft: RouteDraft,
    viewModel: RouteMockViewModel,
) {
    val comparison = remember(state.routes, maxExtra) { RouteComparer.compare(state.routes, maxExtra) }
    if (comparison == null) {
        Notice(
            NoticeTone.ERROR,
            stringResource(R.string.road_failed_response),
            body = stringResource(R.string.road_no_fallback),
        )
        return
    }
    if (state.distanceSearchFailed) Notice(NoticeTone.WARNING, stringResource(R.string.road_partial))

    Text(stringResource(R.string.road_guarantee), style = MaterialTheme.typography.bodySmall)
    val longer = (comparison.longer as? LongerAlternative.Found)?.candidate
    comparison.candidates
        .sortedBy { it.distanceMeters }
        .forEach { candidate ->
            CandidateCard(
                label = candidateLabel(candidate, comparison, longer),
                candidate = candidate,
                comparison = comparison,
                unit = draft.speedUnit,
                selected = draft.roadRoute?.route == candidate.route,
                onSelect = { viewModel.selectRoadRoute(state.request, candidate) },
            )
        }

    Text(stringResource(R.string.road_max_extra, maxExtra), style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = maxExtra.toFloat(),
        onValueChange = { viewModel.setMaxExtraPercent(it.roundToInt()) },
        valueRange = 5f..200f,
        steps = 38,
    )
    (comparison.longer as? LongerAlternative.None)?.let { none ->
        Text(
            when (none.reason) {
                NoLongerRouteReason.NO_OTHER_ROUTES -> stringResource(R.string.road_no_longer_other)
                NoLongerRouteReason.ALL_OVER_LIMIT -> stringResource(R.string.road_no_longer_limit, maxExtra)
                NoLongerRouteReason.TOO_SIMILAR -> stringResource(R.string.road_no_longer_similar)
                NoLongerRouteReason.ALL_HAVE_LOOPS -> stringResource(R.string.road_no_longer_loops)
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun candidateLabel(candidate: RouteCandidate, comparison: RouteComparison, longer: RouteCandidate?): Int = when {
    candidate === comparison.shortest && candidate === comparison.fastest -> R.string.road_shortest_fastest
    candidate === comparison.shortest -> R.string.road_shortest
    candidate === comparison.fastest -> R.string.road_fastest
    candidate === longer -> R.string.road_longer
    else -> R.string.road_other
}

@Composable
private fun CandidateCard(
    label: Int,
    candidate: RouteCandidate,
    comparison: RouteComparison,
    unit: SpeedUnit,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val extra = comparison.extraPercent(candidate)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(label), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(
                    R.string.road_candidate_stats,
                    Formatters.distance(candidate.distanceMeters, unit),
                    Formatters.duration(candidate.estimatedSeconds),
                    if (candidate === comparison.shortest) {
                        stringResource(R.string.road_extra_none)
                    } else {
                        stringResource(R.string.road_extra, "%.1f".format(Locale.US, extra))
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(basisLabel(candidate.trafficBasis)),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                if (selected) {
                    Button(onClick = onSelect) { Text(stringResource(R.string.road_selected)) }
                } else {
                    OutlinedButton(onClick = onSelect) { Text(stringResource(R.string.road_select)) }
                }
            }
        }
    }
}

@Composable
private fun SelectedRouteDetails(draft: RouteDraft) {
    val selection = draft.currentRoadRoute() ?: return
    val road: RoadRoute = selection.route ?: return
    val unit = draft.speedUnit
    selection.startSnapMeters?.let {
        Text(
            stringResource(R.string.road_snap_start, Formatters.distance(it, unit)),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    selection.destinationSnapMeters?.let {
        Text(
            stringResource(R.string.road_snap_end, Formatters.distance(it, unit)),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (road.lengthMeters > 0) {
        Text(stringResource(R.string.road_starts_explicitly), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.road_speed_note), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TrafficLine(status: TrafficStatus) {
    fun time(millis: Long) = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))
    Text(
        when (status) {
            TrafficStatus.NotConfigured -> stringResource(R.string.traffic_not_configured)
            is TrafficStatus.Live -> stringResource(R.string.traffic_live, status.attribution, time(status.updatedAtMillis))
            is TrafficStatus.Stale -> stringResource(R.string.traffic_stale, status.attribution, time(status.updatedAtMillis))
            is TrafficStatus.Unavailable -> stringResource(R.string.traffic_unavailable, status.attribution)
        },
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** FOSSGIS requires the attribution and a way to report map errors. */
@Composable
private fun Attribution(text: String) {
    val report = stringResource(R.string.road_report_error)
    Text(
        buildAnnotatedString {
            append(text)
            append(" · ")
            withLink(LinkAnnotation.Url("https://www.openstreetmap.org/fixthemap")) { append(report) }
        },
        style = MaterialTheme.typography.labelSmall,
    )
}

private fun basisLabel(basis: TrafficBasis): Int = when (basis) {
    TrafficBasis.ROAD_SPEED_MODEL -> R.string.road_basis_model
    TrafficBasis.HISTORICAL -> R.string.road_basis_historical
    TrafficBasis.LIVE -> R.string.road_basis_live
}

private fun failureMessage(failure: RoutingFailure): Int = when (failure) {
    RoutingFailure.NO_ROAD_NEAR_PIN -> R.string.road_failed_no_road
    RoutingFailure.UNREACHABLE -> R.string.road_failed_unreachable
    RoutingFailure.TOO_FAR -> R.string.road_failed_too_far
    RoutingFailure.RATE_LIMITED -> R.string.road_failed_rate_limited
    RoutingFailure.NETWORK -> R.string.road_failed_network
    RoutingFailure.TIMEOUT -> R.string.road_failed_timeout
    RoutingFailure.SECURE_CONNECTION -> R.string.road_failed_tls
    RoutingFailure.REDIRECT_REFUSED -> R.string.road_failed_redirect
    RoutingFailure.INSECURE_ENDPOINT -> R.string.road_failed_endpoint
    RoutingFailure.SERVICE_ERROR -> R.string.road_failed_service
    RoutingFailure.BAD_RESPONSE -> R.string.road_failed_response
}

