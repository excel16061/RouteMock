package com.routemock.app.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import com.routemock.app.domain.GeoPoint
import com.routemock.app.mock.DeviceReadiness
import com.routemock.app.playback.SimulationClock
import com.routemock.app.simulation.SessionKind
import com.routemock.app.simulation.SimulationController
import com.routemock.app.simulation.SimulationState

/**
 * Reads what Android reports and hands it to [LocationObservations]. It runs only
 * while the app is visible. Coordinates are kept in memory and must never be logged.
 */
class DeviceLocationTracker(
    private val context: Context,
    private val observations: LocationObservations,
    private val controller: SimulationController,
    private val clock: SimulationClock,
) {
    private val locationManager = context.getSystemService(LocationManager::class.java)
    private val listener = LocationListener(::report)
    private var running = false

    // Each call first confirms the permission, which the linter cannot see through.
    @SuppressLint("MissingPermission")
    fun start() {
        if (running || !DeviceReadiness.hasLocationPermission(context)) return
        running = true
        captureLastKnown()
        for (provider in PROVIDERS) {
            try {
                locationManager.requestLocationUpdates(provider, UPDATE_INTERVAL_MILLIS, 0f, listener, Looper.getMainLooper())
            } catch (_: IllegalArgumentException) {
                // This device does not have the provider.
            } catch (_: SecurityException) {
                // Permission was withdrawn between the check and this call.
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        locationManager.removeUpdates(listener)
    }

    /** Takes what Android already has. Cached fixes go through the same classification. */
    @SuppressLint("MissingPermission")
    fun captureLastKnown() {
        if (!DeviceReadiness.hasLocationPermission(context)) return
        for (provider in PROVIDERS) {
            try {
                locationManager.getLastKnownLocation(provider)?.let(::report)
            } catch (_: IllegalArgumentException) {
                // This device does not have the provider.
            } catch (_: SecurityException) {
                // Permission was withdrawn between the check and this call.
            }
        }
    }

    private fun report(location: Location) {
        observations.onFix(location.toRawFix(), clock.elapsedRealtimeNanos(), currentInjection())
    }

    private fun currentInjection(): InjectionWindow? {
        val state = controller.state.value as? SimulationState.Active ?: return null
        if (state.kind != SessionKind.INJECTION) return null
        return InjectionWindow(state.startedAtElapsedNanos, state.snapshot.position)
    }

    private companion object {
        const val UPDATE_INTERVAL_MILLIS = 1000L
        val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
    }
}

private fun Location.toRawFix() = RawLocationFix(
    position = GeoPoint(latitude, longitude),
    accuracyMeters = if (hasAccuracy()) accuracy else null,
    timeMillis = time,
    elapsedRealtimeNanos = elapsedRealtimeNanos,
    isMock = isMockFix(),
)

@Suppress("DEPRECATION") // The replacement, isMock, needs API 31.
private fun Location.isMockFix(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) isMock else isFromMockProvider
