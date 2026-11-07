package com.routemock.app.playback

import com.routemock.app.domain.GeoPoint

/** Everything needed to build one platform `Location`, free of Android types. */
data class LocationFix(
    val position: GeoPoint,
    val timeMillis: Long,
    val elapsedRealtimeNanos: Long,
    val accuracyMeters: Float,
    val speedMetersPerSecond: Float,
    val speedAccuracyMetersPerSecond: Float,
    val bearingDegrees: Float?,
    val bearingAccuracyDegrees: Float?,
)

object LocationFixFactory {
    const val HORIZONTAL_ACCURACY_METERS = 5f
    const val SPEED_ACCURACY_METERS_PER_SECOND = 0.5f
    const val BEARING_ACCURACY_DEGREES = 5f

    fun create(snapshot: PlaybackSnapshot, clock: SimulationClock): LocationFix {
        val bearing = snapshot.bearingDegrees?.toFloat()
        return LocationFix(
            position = snapshot.position,
            timeMillis = clock.currentTimeMillis(),
            elapsedRealtimeNanos = clock.elapsedRealtimeNanos(),
            accuracyMeters = HORIZONTAL_ACCURACY_METERS,
            speedMetersPerSecond = snapshot.speedMetersPerSecond.toFloat(),
            speedAccuracyMetersPerSecond = SPEED_ACCURACY_METERS_PER_SECOND,
            bearingDegrees = bearing,
            bearingAccuracyDegrees = if (bearing != null) BEARING_ACCURACY_DEGREES else null,
        )
    }
}
