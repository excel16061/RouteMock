package com.routemock.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.routemock.app.BuildConfig
import com.routemock.app.R
import com.routemock.app.domain.GeoPoint
import com.routemock.app.domain.RoadRoute
import com.routemock.app.domain.Route
import com.routemock.app.domain.RouteDraft
import com.routemock.app.domain.RouteMode
import com.routemock.app.domain.SelectionTarget
import com.routemock.app.domain.StraightLineRoute
import com.routemock.app.domain.currentRoadRoute
import com.routemock.app.domain.destinationPoint
import com.routemock.app.domain.greatCircleSamples
import com.routemock.app.domain.pinsForRouting
import com.routemock.app.domain.startPoint
import com.routemock.app.location.InjectionWindow
import com.routemock.app.location.RealLocationStatus
import com.routemock.app.location.deliveryStatus
import com.routemock.app.location.DeliveryStatus
import com.routemock.app.location.realLocationStatus
import com.routemock.app.routing.SecureEndpoint
import com.routemock.app.simulation.SessionKind
import com.routemock.app.simulation.SimulationState
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.tile.TileOperation
import kotlin.math.max

private enum class MapLoad { LOADING, READY, FAILED }

/** Camera actions, callable from the panel whether or not the map has loaded. */
class MapController {
    internal var map by mutableStateOf<MapLibreMap?>(null)

    fun center(point: GeoPoint) {
        val map = map ?: return
        val zoom = max(map.cameraPosition.zoom, CLOSE_ZOOM)
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(point.toLatLng(), zoom), CAMERA_MILLIS)
    }

    /** Fits the running route if there is one, otherwise the draft. */
    fun fitRoute(running: Route?, draft: RouteDraft) {
        val map = map ?: return
        val path = pathOf(running) ?: draftPath(draft)
        if (path == null) {
            (running?.start ?: draft.startPoint())?.let(::center)
            return
        }
        val bounds = LatLngBounds.Builder().includes(path.map { it.toLatLng() }).build()
        map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, FIT_PADDING_PX), CAMERA_MILLIS)
    }

    internal companion object {
        const val CLOSE_ZOOM = 14.0
        const val CAMERA_MILLIS = 600
        const val FIT_PADDING_PX = 120
    }
}

private fun GeoPoint.toLatLng() = LatLng(latitude, longitude)

/** The line for a route, or null if it has none. */
private fun pathOf(route: Route?): List<GeoPoint>? = when (route) {
    is RoadRoute -> route.displayPoints()
    is StraightLineRoute -> greatCircleSamples(route.start, route.destination)
    else -> null
}

/** Road mode draws only a route the user chose, never a line between the pins. */
private fun draftPath(draft: RouteDraft): List<GeoPoint>? {
    if (draft.mode == RouteMode.ROAD) return draft.currentRoadRoute()?.route?.displayPoints()
    if (draft.mode != RouteMode.STRAIGHT_LINE) return null
    val start = draft.startPoint() ?: return null
    val destination = draft.destinationPoint() ?: return null
    return if (start == destination) null else greatCircleSamples(start, destination)
}

@Composable
fun MapPane(viewModel: RouteMockViewModel, controller: MapController, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var load by remember { mutableStateOf(MapLoad.LOADING) }
    var tilesFailed by remember { mutableStateOf(false) }
    var cameraRevision by remember { mutableIntStateOf(0) }
    val online = rememberIsOnline()

    val mapView = remember {
        MapLibre.getInstance(context)
        // Attribution is drawn below as always-visible text rather than behind a button.
        val options = MapLibreMapOptions.createFromAttributes(context)
            .textureMode(true)
            .attributionEnabled(false)
            .logoEnabled(false)
        MapView(context, options).apply { onCreate(null) }
    }

    DisposableEffect(lifecycle, mapView) {
        var started = false
        var resumed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> { mapView.onStart(); started = true }
                Lifecycle.Event.ON_RESUME -> { mapView.onResume(); resumed = true }
                Lifecycle.Event.ON_PAUSE -> { mapView.onPause(); resumed = false }
                Lifecycle.Event.ON_STOP -> { mapView.onStop(); started = false }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (resumed) mapView.onPause()
            if (started) mapView.onStop()
            controller.map = null
            mapView.onDestroy()
        }
    }

    DisposableEffect(mapView) {
        mapView.addOnDidFinishLoadingStyleListener { load = MapLoad.READY }
        mapView.addOnDidFailLoadingMapListener { load = MapLoad.FAILED }
        mapView.addOnTileActionListener { operation, _, _, _, _, _, _ ->
            if (operation == TileOperation.Error) tilesFailed = true
        }
        mapView.getMapAsync { map ->
            map.uiSettings.isRotateGesturesEnabled = false
            map.uiSettings.isTiltGesturesEnabled = false
            val camera = viewModel.mapCamera
            map.moveCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(camera?.latitude ?: 20.0, camera?.longitude ?: 0.0))
                        .zoom(camera?.zoom ?: 1.0)
                        .build(),
                ),
            )
            map.addOnCameraMoveListener { cameraRevision++ }
            map.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    viewModel.setFollowSimulation(false)
                }
            }
            map.addOnCameraIdleListener {
                cameraRevision++
                val position = map.cameraPosition
                position.target?.let {
                    viewModel.mapCamera = MapCamera(it.latitude, it.longitude, position.zoom)
                }
            }
            map.addOnMapClickListener { latLng ->
                viewModel.placePin(GeoPoint(latLng.latitude, latLng.longitude))
                viewModel.selectionTarget.value != null
            }
            loadStyle(map) { load = MapLoad.FAILED }
            controller.map = map
        }
        onDispose { }
    }

    Box(modifier.clipToBounds()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        controller.map?.let { map -> MapOverlay(viewModel, map, cameraRevision) }
        MapStateBanner(
            load = load,
            online = online,
            tilesFailed = tilesFailed,
            onRetry = {
                load = MapLoad.LOADING
                tilesFailed = false
                controller.map?.let { map -> loadStyle(map) { load = MapLoad.FAILED } }
            },
            modifier = Modifier.align(Alignment.TopCenter),
        )
        Attribution(Modifier.align(Alignment.BottomStart))
    }
}

/** Refuses an address that is not secure, rather than passing it to the map. */
private fun loadStyle(map: MapLibreMap, onRefused: () -> Unit) {
    if (SecureEndpoint.isSecure(BuildConfig.MAP_STYLE_URL)) map.setStyle(BuildConfig.MAP_STYLE_URL) else onRefused()
}

@Composable
private fun MapStateBanner(
    load: MapLoad,
    online: Boolean,
    tilesFailed: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier,
) {
    val canRetry = online && (load == MapLoad.FAILED || tilesFailed)
    val message = when {
        !online -> R.string.map_offline
        load == MapLoad.FAILED -> R.string.map_failed
        tilesFailed -> R.string.map_tiles_failed
        load == MapLoad.LOADING -> R.string.map_loading
        else -> return
    }
    Notice(
        tone = if (load == MapLoad.LOADING && online) NoticeTone.INFO else NoticeTone.WARNING,
        title = stringResource(message),
        modifier = modifier.padding(8.dp),
    ) {
        if (canRetry) {
            TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        }
    }
}

/** Kept on screen at all times, as the data and tile licences require. */
@Composable
private fun Attribution(modifier: Modifier) {
    val text = buildAnnotatedString {
        if (BuildConfig.MAP_ATTRIBUTION.isNotEmpty()) {
            append(BuildConfig.MAP_ATTRIBUTION)
        } else {
            withLink(LinkAnnotation.Url("https://openfreemap.org/")) { append("OpenFreeMap") }
            append(" ")
            withLink(LinkAnnotation.Url("https://www.openmaptiles.org/")) { append("© OpenMapTiles") }
            append(" Data from ")
            withLink(LinkAnnotation.Url("https://www.openstreetmap.org/copyright")) { append("OpenStreetMap") }
        }
    }
    Text(
        text,
        color = Color.Black,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier
            .background(Color.White.copy(alpha = 0.85f))
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

private val StartColor = Color(0xFF1B8A3A)
private val DestinationColor = Color(0xFFC62828)
private val StationaryColor = Color(0xFF6A1B9A)
private val RealColor = Color(0xFF1565C0)
private val SimulatedColor = Color(0xFFEF6C00)
private val DeliveredColor = Color(0xFF00838F)
private val AlternativeColor = Color(0xFF9E9E9E)

private val DiamondShape: Shape = GenericShape { size, _ ->
    moveTo(size.width / 2, 0f)
    lineTo(size.width, size.height / 2)
    lineTo(size.width / 2, size.height)
    lineTo(0f, size.height / 2)
    close()
}
private val TriangleShape: Shape = GenericShape { size, _ ->
    moveTo(size.width / 2, 0f)
    lineTo(size.width, size.height)
    lineTo(0f, size.height)
    close()
}

/**
 * Pins, labels, and lines are composed over the map from its projection rather than
 * added to its style. They need no tiles, so selection works with the map offline.
 */
@Composable
private fun MapOverlay(viewModel: RouteMockViewModel, map: MapLibreMap, cameraRevision: Int) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val simulation by viewModel.simulation.collectAsStateWithLifecycle()
    val realFix by viewModel.realFix.collectAsStateWithLifecycle()
    val observed by viewModel.observedMock.collectAsStateWithLifecycle()
    val roadRoutes by viewModel.roadRoutes.collectAsStateWithLifecycle()
    val readiness by viewModel.readiness.collectAsStateWithLifecycle()
    val follow by viewModel.followSimulation.collectAsStateWithLifecycle()
    val now by rememberElapsedNanos(viewModel)
    val active = simulation as? SimulationState.Active

    LaunchedEffect(follow, active?.snapshot?.position) {
        val position = active?.snapshot?.position
        if (follow && position != null) {
            map.easeCamera(CameraUpdateFactory.newLatLng(position.toLatLng()), FOLLOW_MILLIS)
        }
    }

    // Read so every camera change re-projects what is drawn below.
    val projection = cameraRevision.let { map.projection }
    fun screen(point: GeoPoint): Offset =
        projection.toScreenLocation(point.toLatLng()).let { Offset(it.x, it.y) }

    val start = draft.startPoint()
    val destination = draft.destinationPoint()
    val draftLine = draftPath(draft)?.map(::screen)
    val runningLine = pathOf(active?.route)?.map(::screen)
    val selection = draft.currentRoadRoute()
    val selectedRoad = selection?.route
    val returnedRoads = remember(roadRoutes) {
        (roadRoutes as? RoadRouteState.Ready)?.routes.orEmpty().map { RoadRoute(it.points) }
    }
    val pinsMatchResults = (roadRoutes as? RoadRouteState.Ready)?.request?.let {
        draft.mode == RouteMode.ROAD && draft.pinsForRouting() == (it.start to it.destination)
    } == true
    val otherRoads = if (pinsMatchResults) {
        returnedRoads.filter { it != selectedRoad }.map { road -> road.displayPoints().map(::screen) }
    } else {
        emptyList()
    }
    val status = realLocationStatus(realFix, now)
    val shownFix = when (status) {
        is RealLocationStatus.Live -> status.fix
        is RealLocationStatus.Last -> status.fix
        RealLocationStatus.Unavailable -> null
    }
    val accuracyPixels = shownFix?.accuracyMeters?.let {
        (it / projection.getMetersPerPixelAtLatitude(shownFix.position.latitude)).toFloat()
    }

    Canvas(Modifier.fillMaxSize()) {
        otherRoads.forEach { drawPolyline(it, AlternativeColor, 3.dp.toPx(), null) }
        draftLine?.let {
            val dashed = if (draft.mode == RouteMode.ROAD) null else PathEffect.dashPathEffect(floatArrayOf(18f, 12f))
            drawPolyline(it, StartColor, 4.dp.toPx(), dashed)
        }
        if (selection != null && selectedRoad != null && start != null && destination != null) {
            val connector = PathEffect.dashPathEffect(floatArrayOf(6f, 8f))
            drawLine(DestinationColor, screen(start), screen(selectedRoad.start), 2.dp.toPx(), pathEffect = connector)
            drawLine(
                DestinationColor,
                screen(destination),
                screen(selectedRoad.destination),
                2.dp.toPx(),
                pathEffect = connector,
            )
        }
        runningLine?.let { drawPolyline(it, SimulatedColor, 4.dp.toPx(), null) }
        if (shownFix != null && accuracyPixels != null) {
            val center = screen(shownFix.position)
            drawCircle(RealColor.copy(alpha = 0.15f), accuracyPixels, center)
            drawCircle(RealColor.copy(alpha = 0.5f), accuracyPixels, center, style = Stroke(1.dp.toPx()))
        }
    }

    draftLine?.let { line ->
        val label = stringResource(
            if (draft.mode == RouteMode.ROAD) R.string.route_line_road_label else R.string.route_line_label,
        )
        MapLabel(line[line.size / 2], label)
    }
    runningLine?.let { line ->
        val label = stringResource(
            if (active?.route is RoadRoute) R.string.route_line_road_active_label else R.string.route_line_active_label,
        )
        MapLabel(line[line.size / 3], label)
    }

    if (shownFix != null) {
        MapMarker(
            at = screen(shownFix.position),
            label = stringResource(
                if (status is RealLocationStatus.Live) R.string.marker_real_live else R.string.marker_real_last,
            ),
            symbol = "R",
            shape = CircleShape,
            color = RealColor,
        )
    }

    start?.let { point ->
        val stationary = draft.mode == RouteMode.STATIONARY
        MapMarker(
            at = screen(point),
            label = stringResource(if (stationary) R.string.pin_stationary else R.string.pin_start),
            symbol = if (stationary) "P" else "S",
            shape = if (stationary) RoundedCornerShape(50, 50, 50, 0) else RoundedCornerShape(50, 50, 0, 50),
            color = if (stationary) StationaryColor else StartColor,
            onDrag = { offset -> dragPin(viewModel, map, SelectionTarget.START, offset) },
        )
    }
    destination?.let { point ->
        MapMarker(
            at = screen(point),
            label = stringResource(R.string.pin_destination),
            symbol = "D",
            shape = RoundedCornerShape(4.dp),
            color = DestinationColor,
            onDrag = { offset -> dragPin(viewModel, map, SelectionTarget.DESTINATION, offset) },
        )
    }

    if (selectedRoad != null) {
        MapMarker(
            at = screen(selectedRoad.start),
            label = stringResource(R.string.pin_road_start),
            symbol = "s",
            shape = CircleShape,
            color = StartColor.copy(alpha = 0.7f),
        )
        MapMarker(
            at = screen(selectedRoad.destination),
            label = stringResource(R.string.pin_road_end),
            symbol = "d",
            shape = CircleShape,
            color = DestinationColor.copy(alpha = 0.7f),
        )
    }

    if (active != null) {
        MapMarker(
            at = screen(active.snapshot.position),
            label = stringResource(R.string.marker_simulated),
            symbol = "M",
            shape = DiamondShape,
            color = SimulatedColor,
        )
        if (active.kind == SessionKind.INJECTION) {
            val injection = InjectionWindow(active.startedAtElapsedNanos, active.snapshot.position)
            val delivery = deliveryStatus(injection, observed, readiness.locationPermissionGranted, now)
            if (delivery is DeliveryStatus.Observed) {
                MapMarker(
                    at = screen(delivery.fix.position),
                    label = stringResource(R.string.marker_delivered),
                    symbol = "A",
                    shape = TriangleShape,
                    color = DeliveredColor,
                )
            }
        }
    }
}

private fun dragPin(viewModel: RouteMockViewModel, map: MapLibreMap, target: SelectionTarget, offset: Offset) {
    val latLng = map.projection.fromScreenLocation(android.graphics.PointF(offset.x, offset.y))
    viewModel.movePin(target, GeoPoint(latLng.latitude, latLng.longitude))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPolyline(
    points: List<Offset>,
    color: Color,
    width: Float,
    effect: PathEffect?,
) {
    for (index in 1 until points.size) {
        drawLine(color, points[index - 1], points[index], width, StrokeCap.Round, effect)
    }
}

/** Places content with its symbol, not its label, centered on the map position. */
private fun Modifier.anchoredAt(at: Offset, anchorFromTopPx: Int) = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    layout(placeable.width, placeable.height) {
        placeable.place(at.x.toInt() - placeable.width / 2, at.y.toInt() - anchorFromTopPx)
    }
}

@Composable
private fun MapMarker(
    at: Offset,
    label: String,
    symbol: String,
    shape: Shape,
    color: Color,
    onDrag: ((Offset) -> Unit)? = null,
) {
    val anchor = with(LocalDensity.current) { (MARKER_TOUCH_SIZE / 2).roundToPx() }
    val position by rememberUpdatedState(at)
    val drag by rememberUpdatedState(onDrag)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.anchoredAt(at, anchor),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(MARKER_TOUCH_SIZE)
                .then(
                    if (onDrag == null) {
                        Modifier
                    } else {
                        // Measured from where the drag began, so rounding the pin's
                        // stored coordinates cannot feed back into the finger position.
                        Modifier.pointerInput(Unit) {
                            var origin = Offset.Zero
                            var moved = Offset.Zero
                            detectDragGestures(
                                onDragStart = {
                                    origin = position
                                    moved = Offset.Zero
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    moved += amount
                                    drag?.invoke(origin + moved)
                                },
                            )
                        }
                    },
                ),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(MARKER_SIZE)
                    .background(color, shape),
            ) {
                Text(symbol, color = Color.White, style = MaterialTheme.typography.labelMedium)
            }
        }
        MapLabelText(label)
    }
}

@Composable
private fun MapLabel(at: Offset, text: String) {
    Box(Modifier.anchoredAt(at, 0)) { MapLabelText(text) }
}

@Composable
private fun MapLabelText(text: String) {
    Text(
        text,
        color = Color.Black,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .background(Color.White.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp)
            .semantics { contentDescription = text },
    )
}

private val MARKER_SIZE = 28.dp
private val MARKER_TOUCH_SIZE = 48.dp
private const val FOLLOW_MILLIS = 900
