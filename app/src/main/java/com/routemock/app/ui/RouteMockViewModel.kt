package com.routemock.app.ui

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.routemock.app.BuildConfig
import com.routemock.app.RouteMockApp
import com.routemock.app.consent.ConsentDocument
import com.routemock.app.consent.ConsentLedger
import com.routemock.app.consent.ConsentReceipt
import com.routemock.app.consent.ConsentScope
import com.routemock.app.dashboard.DashboardException
import com.routemock.app.dashboard.Pairing
import com.routemock.app.dashboard.RemoteCommand
import com.routemock.app.dashboard.RemoteCommandParser
import com.routemock.app.data.RouteRepository
import com.routemock.app.data.SavedRoute
import com.routemock.app.domain.GeoPoint
import com.routemock.app.domain.PolylineCodec
import com.routemock.app.domain.RoadRouteSelection
import com.routemock.app.domain.RouteDraft
import com.routemock.app.domain.RouteError
import com.routemock.app.domain.RouteMode
import com.routemock.app.domain.RouteValidator
import com.routemock.app.domain.SelectionTarget
import com.routemock.app.domain.SpeedUnit
import com.routemock.app.domain.ValidatedRoute
import com.routemock.app.domain.convertSpeedText
import com.routemock.app.domain.pinsForRouting
import com.routemock.app.domain.withMapPoint
import com.routemock.app.location.ObservedMockFix
import com.routemock.app.location.RealFix
import com.routemock.app.mock.DeviceReadiness
import com.routemock.app.routing.ProviderRoute
import com.routemock.app.routing.RouteCandidate
import com.routemock.app.routing.RouteComparer
import com.routemock.app.routing.RoutingException
import com.routemock.app.routing.RoutingFailure
import com.routemock.app.routing.RoutingProviderInfo
import com.routemock.app.routing.RoutingRequest
import com.routemock.app.service.SimulationService
import com.routemock.app.simulation.PreviewSink
import com.routemock.app.simulation.SessionKind
import com.routemock.app.simulation.SimulationConfig
import com.routemock.app.simulation.SimulationState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException

/** Why Start did nothing. Cleared by the next attempt. */
enum class StartBlock { INVALID_ROUTE, ROAD_ROUTE_NEEDED, MOCK_APP_NOT_SELECTED, NOTIFICATIONS_DISABLED }

/** A road-route calculation. Results belong to the pins they were requested for. */
sealed interface RoadRouteState {
    data object Idle : RoadRouteState
    data object Loading : RoadRouteState
    data class Failed(val failure: RoutingFailure) : RoadRouteState
    data class Ready(
        val request: RoutingRequest,
        val routes: List<ProviderRoute>,
        val distanceSearchFailed: Boolean,
    ) : RoadRouteState
}

@Serializable
data class MapCamera(val latitude: Double, val longitude: Double, val zoom: Double)

/** UI-side state. Playback lives in the process-wide controller, not here. */
class RouteMockViewModel(
    application: Application,
    private val savedState: SavedStateHandle,
) : AndroidViewModel(application) {
    private val app = getApplication<RouteMockApp>()
    private val controller = app.simulationController
    private val repository = app.routeRepository

    // Kept in the saved-state bundle, so form text also survives process death.
    private val mutableDraft = MutableStateFlow(
        savedState.get<String>(KEY_DRAFT)?.let { Json.decodeFromString<RouteDraft>(it) } ?: RouteDraft(mode = RouteMode.ROAD),
    )
    private val mutableRepeat = MutableStateFlow(savedState[KEY_REPEAT] ?: false)
    private val mutableShowAllErrors = MutableStateFlow(savedState[KEY_SHOW_ERRORS] ?: false)
    private val mutableStartBlock = MutableStateFlow<StartBlock?>(null)
    private val mutableReadiness = MutableStateFlow(DeviceReadiness.read(app))
    private val mutableStorageFailed = MutableStateFlow(false)
    private val mutableSelectionTarget = MutableStateFlow<SelectionTarget?>(
        savedState.get<String>(KEY_SELECTION)?.let(SelectionTarget::valueOf),
    )
    private val mutableFollow = MutableStateFlow(savedState[KEY_FOLLOW] ?: false)
    private val mutableLocationRefused = MutableStateFlow(savedState[KEY_LOCATION_REFUSED] ?: false)
    private val mutableRoadRoutes = MutableStateFlow<RoadRouteState>(RoadRouteState.Idle)
    private var roadRouteJob: Job? = null
    private var roadRouteRequest: RoutingRequest? = null
    private val mutableMaxExtraPercent =
        MutableStateFlow(savedState[KEY_MAX_EXTRA] ?: RouteComparer.DEFAULT_MAX_EXTRA_PERCENT)

    val draft: StateFlow<RouteDraft> = mutableDraft.asStateFlow()
    val repeat: StateFlow<Boolean> = mutableRepeat.asStateFlow()
    val showAllErrors: StateFlow<Boolean> = mutableShowAllErrors.asStateFlow()
    val startBlock: StateFlow<StartBlock?> = mutableStartBlock.asStateFlow()
    val readiness: StateFlow<DeviceReadiness> = mutableReadiness.asStateFlow()
    val storageFailed: StateFlow<Boolean> = mutableStorageFailed.asStateFlow()
    val simulation: StateFlow<SimulationState> = controller.state
    val savedRoutes: StateFlow<List<SavedRoute>> = repository.routes
    val routesRecovered: StateFlow<Boolean> = repository.recoveredFromCorruption
    val selectionTarget: StateFlow<SelectionTarget?> = mutableSelectionTarget.asStateFlow()
    val followSimulation: StateFlow<Boolean> = mutableFollow.asStateFlow()
    val locationRefused: StateFlow<Boolean> = mutableLocationRefused.asStateFlow()
    val realFix: StateFlow<RealFix?> = app.locationObservations.realFix
    val observedMock: StateFlow<ObservedMockFix?> = app.locationObservations.observedMock
    val clock get() = app.clock
    val consentReceipt: StateFlow<ConsentReceipt> = app.consent.receipt
    val consentDocuments: List<ConsentDocument> get() = app.consentDocuments
    private val mutablePairing = MutableStateFlow(app.pairingStore.load())
    private val mutableRemoteEnabled = MutableStateFlow(app.pairingStore.remoteControlEnabled)
    private val mutablePendingRemote = MutableStateFlow<RemoteCommand?>(null)
    private val mutableDashboardStatus = MutableStateFlow<Int?>(null)
    private val seenNonces = mutableSetOf<String>()
    private var pollJob: Job? = null
    private var sessionStartedAt: Long? = null
    private var sessionSpeedKmh: Double? = null
    val pairing: StateFlow<Pairing?> = mutablePairing.asStateFlow()
    val remoteEnabled: StateFlow<Boolean> = mutableRemoteEnabled.asStateFlow()
    val pendingRemote: StateFlow<RemoteCommand?> = mutablePendingRemote.asStateFlow()
    val dashboardStatus: StateFlow<Int?> = mutableDashboardStatus.asStateFlow()
    val roadRoutes: StateFlow<RoadRouteState> = mutableRoadRoutes.asStateFlow()
    val maxExtraPercent: StateFlow<Int> = mutableMaxExtraPercent.asStateFlow()
    val routingInfo: RoutingProviderInfo get() = app.routingProvider.info

    /** The last camera, restored when the map is rebuilt by rotation or navigation. */
    var mapCamera: MapCamera?
        get() = savedState.get<String>(KEY_CAMERA)?.let { Json.decodeFromString<MapCamera>(it) }
        set(value) {
            savedState[KEY_CAMERA] = value?.let { Json.encodeToString(it) }
        }

    init {
        storage { repository.load() }
        viewModelScope.launch { controller.state.collect(::summarizeSessions) }
    }

    fun isGranted(scope: ConsentScope, receipt: ConsentReceipt) = app.consent.isGranted(scope, receipt)
    fun requiredConsentGranted(receipt: ConsentReceipt) = app.consent.requiredGranted(receipt)
    fun exportConsentReceipt(): String = app.consent.exportJson()

    fun acceptConsent(scopes: Set<ConsentScope>) {
        app.consent.accept(scopes)
        uploadReceipt()
    }

    /** Withdrawal takes effect at once: a running simulation or connection stops now. */
    fun withdrawConsent(scopes: Set<ConsentScope>) {
        val awaitingApproval = mutablePendingRemote.value
        mutablePendingRemote.value = null
        val pairing = mutablePairing.value
        app.consent.withdraw(scopes)
        if (scopes.any { it in ConsentLedger.REQUIRED }) controller.stop()
        if (awaitingApproval != null && pairing != null) {
            viewModelScope.launch {
                quietly { app.dashboardClient.reportStatus(pairing, awaitingApproval, "rejected", "consent withdrawn") }
            }
        }
        if (ConsentScope.DASHBOARD_UPLOAD in scopes) disconnectDashboard() else uploadReceipt()
    }

    fun anyConsentGranted(receipt: ConsentReceipt) = ConsentScope.entries.any { app.consent.isGranted(it, receipt) }

    /** Marked first, so a receipt that cannot be sent now is sent after reconnecting. */
    private fun uploadReceipt() {
        mutablePairing.value ?: return
        if (!app.consent.isGranted(ConsentScope.DASHBOARD_UPLOAD)) return
        app.pairingStore.receiptUploadPending = true
        app.requestDashboardSync()
    }

    fun pairDashboard(url: String, code: String) {
        if (!app.consent.isGranted(ConsentScope.DASHBOARD_UPLOAD)) return
        viewModelScope.launch {
            quietly {
                val pairing = app.dashboardClient.pair(
                    url.trim(),
                    code.trim(),
                    Build.MODEL,
                    Build.VERSION.RELEASE,
                    app.consent.exportJson(),
                    BuildConfig.DEBUG,
                )
                app.pairingStore.save(pairing)
                mutablePairing.value = pairing
                uploadReceipt()
                startPolling() // Disconnecting ended the previous loop.
            }
        }
    }

    /**
     * The phone stops at once, with or without a connection. The revocation is saved before
     * anything is sent, then delivered now or, if that fails, when the network returns.
     */
    fun disconnectDashboard() {
        pollJob?.cancel()
        mutablePairing.value?.let(app.pairingStore::addPendingRevocation)
        app.pairingStore.clear()
        app.requestDashboardSync()
        mutablePairing.value = null
        mutableRemoteEnabled.value = false
        mutablePendingRemote.value = null
    }

    fun setRemoteEnabled(enabled: Boolean) {
        app.pairingStore.remoteControlEnabled = enabled
        mutableRemoteEnabled.value = enabled
    }

    /** Runs only while the app is visible. The phone asks; nothing can call the phone. */
    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (true) {
                pollOnce()
                delay(POLL_INTERVAL_MILLIS)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
    }

    private suspend fun pollOnce() {
        val pairing = mutablePairing.value ?: return
        if (!mutableRemoteEnabled.value || !app.consent.isGranted(ConsentScope.DASHBOARD_UPLOAD)) return
        if (!app.consent.requiredGranted()) return
        if (mutablePendingRemote.value != null) return
        quietly {
            for (item in app.dashboardClient.fetchCommands(pairing)) {
                val command = RemoteCommandParser.parse(item, app.clock.currentTimeMillis(), seenNonces) ?: continue
                seenNonces += command.nonce
                mutablePendingRemote.value = command
                break
            }
        }
    }

    /** A person on this phone decides each request. Nothing starts without this. */
    fun answerRemoteStart(approved: Boolean) {
        val command = mutablePendingRemote.value ?: return
        val pairing = mutablePairing.value
        mutablePendingRemote.value = null
        val expired = app.clock.currentTimeMillis() >= command.expiresAtMillis
        viewModelScope.launch {
            if (pairing == null) return@launch
            quietly {
                if (!approved || expired) {
                    app.dashboardClient.reportStatus(pairing, command, "rejected", if (expired) "expired on device" else "")
                    return@quietly
                }
                app.dashboardClient.reportStatus(pairing, command, "accepted")
                setDraft(command.draft)
                startInjection()
                // Reports what happened, not what was asked for.
                val running = withTimeoutOrNull(START_TIMEOUT_MILLIS) {
                    controller.state.first { it is SimulationState.Active && it.kind == SessionKind.INJECTION }
                } != null
                app.dashboardClient.reportStatus(
                    pairing,
                    command,
                    if (running) "executed" else "failed",
                    if (running) "" else mutableStartBlock.value?.name ?: "did not start",
                )
            }
        }
    }

    /** Failures never affect local simulation. Only a status number is kept. */
    private suspend fun quietly(action: suspend () -> Unit) {
        try {
            action()
            mutableDashboardStatus.value = null
        } catch (e: DashboardException) {
            mutableDashboardStatus.value = e.status
        } catch (_: IOException) {
            mutableDashboardStatus.value = -1
        } catch (_: NullPointerException) {
            mutableDashboardStatus.value = -1
        }
    }

    private fun summarizeSessions(state: SimulationState) {
        when (state) {
            is SimulationState.Active -> if (state.kind == SessionKind.INJECTION && sessionStartedAt == null) {
                sessionStartedAt = state.startedAtElapsedNanos
                sessionSpeedKmh = state.snapshot.selectedSpeedMetersPerSecond * 3.6
            }
            is SimulationState.Idle -> {
                val started = sessionStartedAt ?: return
                sessionStartedAt = null
                val pairing = mutablePairing.value ?: return
                if (!app.consent.isGranted(ConsentScope.DASHBOARD_UPLOAD)) return
                val seconds = (app.clock.elapsedRealtimeNanos() - started) / 1e9
                viewModelScope.launch {
                    quietly {
                        app.dashboardClient.uploadSessionSummary(pairing, BuildConfig.VERSION_NAME, sessionSpeedKmh, seconds)
                    }
                }
            }
        }
    }

    fun refreshReadiness() {
        val current = DeviceReadiness.read(app)
        mutableReadiness.value = current
        val resolved = when (mutableStartBlock.value) {
            StartBlock.MOCK_APP_NOT_SELECTED -> current.mockAppSelected
            StartBlock.NOTIFICATIONS_DISABLED -> current.notificationsEnabled
            else -> false
        }
        if (resolved) mutableStartBlock.value = null
    }

    fun updateDraft(transform: (RouteDraft) -> RouteDraft) {
        setDraft(transform(mutableDraft.value))
        if (mutableStartBlock.value in setOf(StartBlock.INVALID_ROUTE, StartBlock.ROAD_ROUTE_NEEDED)) {
            mutableStartBlock.value = null
        }
    }

    fun setSpeedUnit(unit: SpeedUnit) = updateDraft {
        it.copy(speed = convertSpeedText(it.speed, it.speedUnit, unit), speedUnit = unit)
    }

    /** Draft only: map selection never reaches the controller. */
    fun selectTarget(target: SelectionTarget?) {
        mutableSelectionTarget.value = target
        savedState[KEY_SELECTION] = target?.name
    }

    fun placePin(point: GeoPoint) {
        val target = mutableSelectionTarget.value ?: return
        movePin(target, point)
    }

    fun movePin(target: SelectionTarget, point: GeoPoint) = updateDraft { it.withMapPoint(target, point) }

    fun setMode(mode: RouteMode) {
        if (mode == RouteMode.STATIONARY && mutableSelectionTarget.value == SelectionTarget.DESTINATION) {
            selectTarget(SelectionTarget.START)
        }
        updateDraft { it.copy(mode = mode) }
    }

    fun setFollowSimulation(enabled: Boolean) {
        mutableFollow.value = enabled
        savedState[KEY_FOLLOW] = enabled
    }

    fun onLocationPermissionResult(granted: Boolean) {
        mutableLocationRefused.value = !granted
        savedState[KEY_LOCATION_REFUSED] = !granted
        refreshReadiness()
    }

    /** Only ever runs because the user asked: this sends the pins to the provider. */
    fun calculateRoadRoutes() {
        val (start, destination) = mutableDraft.value.pinsForRouting() ?: return
        if (mutableRoadRoutes.value == RoadRouteState.Loading) return
        val request = RoutingRequest(start, destination)
        roadRouteRequest = request
        mutableRoadRoutes.value = RoadRouteState.Loading
        roadRouteJob = viewModelScope.launch {
            mutableRoadRoutes.value = try {
                val outcome = app.routingProvider.findRoutes(request)
                RoadRouteState.Ready(request, outcome.routes, outcome.distanceSearchFailed)
            } catch (e: RoutingException) {
                RoadRouteState.Failed(e.failure)
            }
        }
    }

    /** Changes the draft only. A running session keeps its own route. */
    fun selectRoadRoute(request: RoutingRequest, candidate: RouteCandidate) {
        val info = routingInfo
        updateDraft {
            it.copy(
                roadRoute = RoadRouteSelection(
                    encodedShape = PolylineCodec.encode(candidate.route.points),
                    estimatedSeconds = candidate.estimatedSeconds,
                    trafficBasis = candidate.trafficBasis,
                    providerName = info.name,
                    attribution = info.attribution,
                    requestedStart = request.start,
                    requestedDestination = request.destination,
                    calculatedAtMillis = app.clock.currentTimeMillis(),
                ),
            )
        }
    }

    fun setMaxExtraPercent(percent: Int) {
        mutableMaxExtraPercent.value = percent
        savedState[KEY_MAX_EXTRA] = percent
    }

    fun startPreview() {
        val config = validConfig() ?: return
        mutableStartBlock.value = null
        controller.start(config, PreviewSink, SessionKind.PREVIEW)
    }

    fun startInjection() {
        val config = validConfig() ?: return
        refreshReadiness()
        val readiness = mutableReadiness.value
        mutableStartBlock.value = when {
            !readiness.mockAppSelected -> StartBlock.MOCK_APP_NOT_SELECTED
            !readiness.notificationsEnabled -> StartBlock.NOTIFICATIONS_DISABLED
            else -> null
        }
        if (mutableStartBlock.value == null) {
            // The last chance to see a real fix: test providers replace the real ones.
            app.deviceLocationTracker.captureLastKnown()
            SimulationService.startInjection(app, app, config)
        }
    }

    fun pause() = controller.pause()
    fun resume() = controller.resume()
    fun stop() = controller.stop()
    fun restart() = controller.restart()
    fun dismissLastEnd() = controller.dismissLastEnd()
    fun setLiveSpeed(metersPerSecond: Double) = controller.setSpeed(metersPerSecond)

    fun setRepeat(enabled: Boolean) {
        mutableRepeat.value = enabled
        savedState[KEY_REPEAT] = enabled
        controller.setRepeat(enabled)
    }

    /** Returns false if the form is invalid, in which case nothing is saved. */
    fun canSaveDraft(): Boolean {
        if (RouteValidator.validate(mutableDraft.value).result != null) return true
        revealErrors()
        return false
    }

    fun saveDraft(name: String) = storage {
        val draft = mutableDraft.value
        // Without permission to store geometry, only the pins are kept.
        val storable = if (routingInfo.allowsStoringGeometry) draft else draft.copy(roadRoute = null)
        repository.add(name, storable)
    }
    fun renameRoute(id: String, name: String) = storage { repository.rename(id, name) }
    fun deleteRoute(id: String) = storage { repository.delete(id) }
    fun loadRoute(route: SavedRoute) = setDraft(route.draft)
    fun dismissStorageFailure() { mutableStorageFailed.value = false }

    private fun validConfig(): SimulationConfig? {
        val draft = mutableDraft.value
        val validation = RouteValidator.validate(draft)
        val validated: ValidatedRoute? = validation.result
        if (validated == null) {
            revealErrors()
            val roadRouteMissing = validation.routeError == RouteError.ROAD_ROUTE_MISSING ||
                validation.routeError == RouteError.ROAD_ROUTE_OUTDATED
            mutableStartBlock.value = if (roadRouteMissing) StartBlock.ROAD_ROUTE_NEEDED else StartBlock.INVALID_ROUTE
            return null
        }
        return SimulationConfig(
            route = validated.route,
            speedMetersPerSecond = validated.speedMetersPerSecond,
            repeat = mutableRepeat.value,
            displayUnit = draft.speedUnit,
        )
    }

    private fun revealErrors() {
        mutableShowAllErrors.value = true
        savedState[KEY_SHOW_ERRORS] = true
    }

    /** A request for pins that have since moved is no longer wanted. */
    private fun cancelObsoleteRequest(draft: RouteDraft) {
        val request = roadRouteRequest ?: return
        if (mutableRoadRoutes.value != RoadRouteState.Loading) return
        if (draft.pinsForRouting() != (request.start to request.destination)) {
            roadRouteJob?.cancel()
            mutableRoadRoutes.value = RoadRouteState.Idle
        }
    }

    private fun setDraft(draft: RouteDraft) {
        cancelObsoleteRequest(draft)
        mutableDraft.value = draft
        // A very long route would overflow Android's saved-state limit and crash. The
        // ViewModel still holds it through rotation; only process death loses it.
        val oversized = (draft.roadRoute?.encodedShape?.length ?: 0) > MAX_SAVED_STATE_SHAPE
        savedState[KEY_DRAFT] = Json.encodeToString(if (oversized) draft.copy(roadRoute = null) else draft)
    }

    private fun storage(action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                action()
            } catch (_: IOException) {
                mutableStorageFailed.value = true
            }
        }
    }

    private companion object {
        const val POLL_INTERVAL_MILLIS = 15_000L
        const val START_TIMEOUT_MILLIS = 8_000L
        const val KEY_DRAFT = "draft"
        const val KEY_REPEAT = "repeat"
        const val KEY_SHOW_ERRORS = "show_errors"
        const val KEY_SELECTION = "selection_target"
        const val KEY_FOLLOW = "follow_simulation"
        const val KEY_CAMERA = "map_camera"
        const val KEY_LOCATION_REFUSED = "location_refused"
        const val KEY_MAX_EXTRA = "max_extra_percent"
        const val MAX_SAVED_STATE_SHAPE = 100_000
    }
}
