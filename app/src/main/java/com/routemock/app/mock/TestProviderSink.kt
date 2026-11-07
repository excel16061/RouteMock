package com.routemock.app.mock

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import com.routemock.app.playback.LocationFix
import com.routemock.app.simulation.LocationSink
import com.routemock.app.simulation.MockNotAuthorizedException

/**
 * Injects fixes through Android's test-provider API. Locations delivered this way
 * are flagged as mock by the platform, and RouteMock does nothing to change that.
 */
class TestProviderSink(context: Context, private val journal: SessionJournal) : LocationSink {
    private val locationManager = context.getSystemService(LocationManager::class.java)
    private val activeProviders = mutableListOf<String>()
    private var needsCleanup = false

    override fun open(): List<String> {
        needsCleanup = journal.providersMayExist // Leftovers from a killed session.
        // Written first: if the process dies during setup, the next launch cleans up.
        journal.providersMayExist = true
        for (provider in candidateProviders()) {
            try {
                register(provider)
                activeProviders += provider
            } catch (e: SecurityException) {
                throw MockNotAuthorizedException(e)
            } catch (e: IllegalArgumentException) {
                // Only GPS is essential. A device may refuse the others.
                if (provider == LocationManager.GPS_PROVIDER) throw e
            }
        }
        return activeProviders.toList()
    }

    override fun publish(fix: LocationFix) {
        try {
            for (provider in activeProviders) {
                locationManager.setTestProviderLocation(provider, fix.toLocation(provider))
            }
        } catch (e: SecurityException) {
            throw MockNotAuthorizedException(e)
        }
    }

    override fun close(): Boolean {
        activeProviders.clear()
        if (!needsCleanup) {
            // Nothing was added, typically because authorization is missing. Removal
            // would be refused for the same reason and falsely report a leftover.
            journal.providersMayExist = false
            return true
        }
        return removeTestProviders(locationManager, journal)
    }

    // These constants are inlined at compile time, so they are safe below API 31.
    @SuppressLint("InlinedApi")
    private fun register(provider: String) {
        // A provider left behind by a killed session must not block this one.
        removeIfPresent(locationManager, provider)
        locationManager.addTestProvider(
            provider,
            /* requiresNetwork = */ provider == LocationManager.NETWORK_PROVIDER,
            /* requiresSatellite = */ provider == LocationManager.GPS_PROVIDER,
            /* requiresCell = */ false,
            /* hasMonetaryCost = */ false,
            /* supportsAltitude = */ false,
            /* supportsSpeed = */ true,
            /* supportsBearing = */ true,
            ProviderProperties.POWER_USAGE_LOW,
            ProviderProperties.ACCURACY_FINE,
        )
        needsCleanup = true
        locationManager.setTestProviderEnabled(provider, true)
    }

    companion object {
        /** Removes every provider RouteMock may have added. Returns true if none remain. */
        fun removeTestProviders(locationManager: LocationManager, journal: SessionJournal): Boolean {
            val removed = candidateProviders().map { removeIfPresent(locationManager, it) }.all { it }
            if (removed) journal.providersMayExist = false
            return removed
        }

        private fun candidateProviders(): List<String> = buildList {
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
        }

        /** Returns false only if a provider may still exist and could not be removed. */
        private fun removeIfPresent(locationManager: LocationManager, provider: String): Boolean = try {
            locationManager.removeTestProvider(provider)
            true
        } catch (_: IllegalArgumentException) {
            true // It was not a test provider, so there is nothing to remove.
        } catch (_: SecurityException) {
            false // Authorization was withdrawn, so the platform refused the removal.
        }
    }
}

private fun LocationFix.toLocation(provider: String): Location = Location(provider).also { location ->
    location.latitude = position.latitude
    location.longitude = position.longitude
    location.time = timeMillis
    location.elapsedRealtimeNanos = elapsedRealtimeNanos
    location.accuracy = accuracyMeters
    location.speed = speedMetersPerSecond
    location.speedAccuracyMetersPerSecond = speedAccuracyMetersPerSecond
    bearingDegrees?.let { location.bearing = it }
    bearingAccuracyDegrees?.let { location.bearingAccuracyDegrees = it }
}
