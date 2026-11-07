package com.routemock.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.routemock.app.R
import com.routemock.app.data.SavedRoute
import com.routemock.app.domain.RouteMode
import com.routemock.app.playback.Formatters

@Composable
fun SavedRoutesScreen(viewModel: RouteMockViewModel, onLoaded: () -> Unit) {
    val routes by viewModel.savedRoutes.collectAsStateWithLifecycle()
    val recovered by viewModel.routesRecovered.collectAsStateWithLifecycle()
    val storageFailed by viewModel.storageFailed.collectAsStateWithLifecycle()
    var renamingId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text(stringResource(R.string.section_saved), style = MaterialTheme.typography.headlineSmall) }
        if (storageFailed) {
            item {
                Notice(NoticeTone.ERROR, stringResource(R.string.storage_failed)) {
                    TextButton(onClick = viewModel::dismissStorageFailure) {
                        Text(stringResource(R.string.action_dismiss))
                    }
                }
            }
        }
        if (recovered) {
            item { Notice(NoticeTone.WARNING, stringResource(R.string.routes_recovered)) }
        }
        if (routes.isEmpty()) {
            item { Notice(NoticeTone.INFO, stringResource(R.string.no_saved_routes)) }
        }
        items(routes, key = { it.id }) { route ->
            SavedRouteCard(
                route = route,
                onLoad = {
                    viewModel.loadRoute(route)
                    onLoaded()
                },
                onRename = { renamingId = route.id },
                onDelete = { deletingId = route.id },
            )
        }
    }

    routes.firstOrNull { it.id == renamingId }?.let { route ->
        RouteNameDialog(
            title = stringResource(R.string.rename_route_title),
            initialName = route.name,
            onConfirm = {
                viewModel.renameRoute(route.id, it)
                renamingId = null
            },
            onDismiss = { renamingId = null },
        )
    }

    routes.firstOrNull { it.id == deletingId }?.let { route ->
        AlertDialog(
            onDismissRequest = { deletingId = null },
            title = { Text(stringResource(R.string.delete_route_title)) },
            text = { Text(stringResource(R.string.delete_route_body, route.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteRoute(route.id)
                    deletingId = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deletingId = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun SavedRouteCard(
    route: SavedRoute,
    onLoad: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val draft = route.draft
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 8.dp, bottom = 4.dp)) {
            Text(route.name, style = MaterialTheme.typography.titleMedium)
            Text(
                if (draft.mode == RouteMode.STATIONARY) {
                    stringResource(R.string.saved_stationary, draft.startLatitude, draft.startLongitude)
                } else if (draft.mode == RouteMode.ROAD) {
                    val road = draft.roadRoute?.route
                    if (road != null) {
                        stringResource(
                            R.string.saved_road,
                            Formatters.distance(road.lengthMeters, draft.speedUnit),
                            draft.speed,
                            draft.speedUnit.label,
                        )
                    } else {
                        stringResource(R.string.saved_road_no_geometry)
                    }
                } else {
                    stringResource(
                        R.string.saved_straight_line,
                        draft.startLatitude,
                        draft.startLongitude,
                        draft.destinationLatitude,
                        draft.destinationLongitude,
                        draft.speed,
                        draft.speedUnit.label,
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Row {
                TextButton(onClick = onLoad) { Text(stringResource(R.string.action_load)) }
                TextButton(onClick = onRename) { Text(stringResource(R.string.action_rename)) }
                TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete)) }
            }
        }
    }
}
