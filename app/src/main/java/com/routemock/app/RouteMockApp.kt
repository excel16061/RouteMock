package com.routemock.app

import android.app.Application
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import com.routemock.app.consent.ConsentDocument
import com.routemock.app.consent.ConsentLedger
import com.routemock.app.consent.ConsentOrigin
import com.routemock.app.consent.ConsentScope
import com.routemock.app.dashboard.DashboardClient
import com.routemock.app.dashboard.DashboardSync
import com.routemock.app.dashboard.PairingStore
import com.routemock.app.data.RouteRepository
import com.routemock.app.location.DeviceLocationTracker
import com.routemock.app.location.LocationObservations
import com.routemock.app.mock.SessionJournal
import com.routemock.app.mock.TestProviderSink
import com.routemock.app.playback.SimulationClock
import com.routemock.app.routing.HttpUrlConnectionPost
import com.routemock.app.routing.RequestPacer
import com.routemock.app.routing.RoutingProvider
import com.routemock.app.routing.RoutingProviderInfo
import com.routemock.app.routing.ValhallaRoutingProvider
import com.routemock.app.simulation.SimulationController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.android.log.Logger
import java.io.File

class RouteMockApp : Application() {
    val clock: SimulationClock = object : SimulationClock {
        override fun elapsedRealtimeNanos() = SystemClock.elapsedRealtimeNanos()
        override fun currentTimeMillis() = System.currentTimeMillis()
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val simulationController by lazy {
        SimulationController(
            clock = clock,
            scope = appScope,
            injectionAllowed = { consent.requiredGranted() },
        )
    }
    val sessionJournal by lazy { SessionJournal(this) }
    val routeRepository by lazy { RouteRepository(File(filesDir, "routes.json"), Dispatchers.IO) }
    val locationObservations by lazy { LocationObservations() }
    val deviceLocationTracker by lazy {
        DeviceLocationTracker(this, locationObservations, simulationController, clock)
    }

    val routingProvider: RoutingProvider by lazy {
        ValhallaRoutingProvider(
            baseUrl = BuildConfig.ROUTING_BASE_URL,
            apiKey = BuildConfig.ROUTING_API_KEY,
            info = RoutingProviderInfo(
                name = BuildConfig.ROUTING_PROVIDER_NAME,
                attribution = BuildConfig.ROUTING_ATTRIBUTION,
                allowsStoringGeometry = BuildConfig.ROUTING_ALLOWS_STORAGE,
            ),
            http = httpClient,
            pacer = RequestPacer(ROUTING_INTERVAL_MILLIS) { SystemClock.elapsedRealtime() },
        )
    }

    val consentDocuments by lazy {
        listOf(
            Triple(ConsentScope.TERMS, 2, "legal/terms.md"),
            Triple(ConsentScope.PRIVACY, 1, "legal/privacy.md"),
            Triple(ConsentScope.MOCK_LOCATION, 1, "legal/mock-consent.md"),
            Triple(ConsentScope.DASHBOARD_UPLOAD, 3, "legal/dashboard-consent.md"),
        ).map { (scope, version, path) ->
            ConsentDocument(scope, version, path, assets.open(path).bufferedReader().use { it.readText() })
        }
    }
    val consent by lazy {
        ConsentLedger(
            file = File(filesDir, "consent-receipt.json"),
            documents = consentDocuments,
            appVersion = BuildConfig.VERSION_NAME,
            nowMillis = System::currentTimeMillis,
            origin = ::consentOrigin,
        )
    }
    /**
     * A marker only `adb` can create through `run-as`, and only in a debug build, so
     * automated test runs are labeled and release builds always record the user.
     */
    private fun consentOrigin(): ConsentOrigin =
        if (BuildConfig.DEBUG && File(filesDir, "consent-test-mode").exists()) {
            ConsentOrigin.AUTOMATED_TEST
        } else {
            ConsentOrigin.USER
        }

    val pairingStore by lazy { PairingStore(this) }
    val httpClient by lazy {
        HttpUrlConnectionPost("RouteMock/${BuildConfig.VERSION_NAME} (Android; ${BuildConfig.APPLICATION_ID})")
    }
    val dashboardClient by lazy { DashboardClient(httpClient::send) }
    private val dashboardSync by lazy {
        DashboardSync(
            state = pairingStore,
            revoke = dashboardClient::revokeSelf,
            uploadReceipt = { pairing -> dashboardClient.uploadConsent(pairing, consent.exportJson()) },
        )
    }
    private var syncJob: Job? = null

    /** Keeps trying while the process lives. A later launch or new network resumes it. */
    fun requestDashboardSync() {
        if (syncJob?.isActive == true) return
        syncJob = appScope.launch {
            while (dashboardSync.hasWork && !dashboardSync.flush()) delay(SYNC_RETRY_MILLIS)
        }
    }

    private fun syncWhenNetworkReturns() {
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    syncJob?.cancel()
                    syncJob = null
                    requestDashboardSync()
                }
            },
        )
    }

    override fun onCreate() {
        super.onCreate()
        // MapLibre can log tile requests, which would reveal the area being viewed.
        Logger.setVerbosity(Logger.NONE)
        recoverInterruptedSession()
        enforceConsent()
        syncWhenNetworkReturns()
        requestDashboardSync()
    }

    /** Whatever changes consent, and from any screen, a session cannot outlive it. */
    private fun enforceConsent() {
        appScope.launch {
            consent.receipt.collect { receipt ->
                if (!consent.requiredGranted(receipt)) simulationController.stop()
            }
        }
    }

    /**
     * A journal entry at process start means the previous process died mid-session.
     * Providers are removed and the user is told. Simulation is never resumed.
     */
    private fun recoverInterruptedSession() {
        if (!sessionJournal.providersMayExist) return
        val locationManager = getSystemService(LocationManager::class.java)
        val cleaned = TestProviderSink.removeTestProviders(locationManager, sessionJournal)
        simulationController.reportInterrupted(cleaned)
    }

    private companion object {
        /** FOSSGIS allows at most one request per second, so leave a margin. */
        const val ROUTING_INTERVAL_MILLIS = 1200L
        const val SYNC_RETRY_MILLIS = 60_000L
    }
}
