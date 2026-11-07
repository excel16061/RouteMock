package com.routemock.app.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Spherical geodesy on a mean-radius Earth. Against the WGS84 ellipsoid the
 * error is at most about 0.5%, which is acceptable for a simulated path.
 */
object GeoMath {
    const val EARTH_RADIUS_METERS = 6_371_008.8

    /** Great-circle distance using the haversine formula. */
    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double =
        EARTH_RADIUS_METERS * centralAngle(a, b)

    /** Initial great-circle bearing from [from] to [to], in degrees within [0, 360). */
    fun initialBearingDegrees(from: GeoPoint, to: GeoPoint): Double {
        val lat1 = from.latitude.toRadians()
        val lat2 = to.latitude.toRadians()
        val deltaLon = (to.longitude - from.longitude).toRadians()
        val y = sin(deltaLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLon)
        return normalizeDegrees(atan2(y, x).toDegrees())
    }

    /**
     * The point a [fraction] of the way along the great circle from [a] to [b].
     * Equal fractions cover equal geographic distance, unlike a linear blend of
     * latitude and longitude. Crossing the antimeridian needs no special case.
     */
    fun interpolate(a: GeoPoint, b: GeoPoint, fraction: Double): GeoPoint {
        if (fraction <= 0.0) return a
        if (fraction >= 1.0) return b
        val angle = centralAngle(a, b)
        val sinAngle = sin(angle)
        if (abs(sinAngle) < 1e-12) return a

        val weightA = sin((1 - fraction) * angle) / sinAngle
        val weightB = sin(fraction * angle) / sinAngle
        val latA = a.latitude.toRadians()
        val lonA = a.longitude.toRadians()
        val latB = b.latitude.toRadians()
        val lonB = b.longitude.toRadians()
        val x = weightA * cos(latA) * cos(lonA) + weightB * cos(latB) * cos(lonB)
        val y = weightA * cos(latA) * sin(lonA) + weightB * cos(latB) * sin(lonB)
        val z = weightA * sin(latA) + weightB * sin(latB)
        return GeoPoint(
            latitude = atan2(z, sqrt(x * x + y * y)).toDegrees(),
            longitude = atan2(y, x).toDegrees(),
        )
    }

    fun normalizeDegrees(degrees: Double): Double {
        val remainder = degrees % 360.0
        val normalized = if (remainder < 0.0) remainder + 360.0 else remainder
        // A tiny negative remainder rounds up to exactly 360 when added.
        return if (normalized >= 360.0) 0.0 else normalized
    }

    private fun centralAngle(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = a.latitude.toRadians()
        val lat2 = b.latitude.toRadians()
        val sinLat = sin((lat2 - lat1) / 2)
        val sinLon = sin((b.longitude - a.longitude).toRadians() / 2)
        val h = sinLat * sinLat + cos(lat1) * cos(lat2) * sinLon * sinLon
        return 2 * asin(min(1.0, sqrt(h)))
    }

    private fun Double.toRadians() = this * PI / 180.0
    private fun Double.toDegrees() = this * 180.0 / PI
}
