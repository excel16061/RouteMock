package com.routemock.app.domain

/**
 * What the playback engine follows. Road-following routes can be added later as
 * another implementation without changing the engine.
 */
sealed interface Route {
    val start: GeoPoint
    val lengthMeters: Double

    /** Position after travelling [distanceMeters] from the start, clamped to the route. */
    fun positionAt(distanceMeters: Double): GeoPoint

    /** Direction of travel at [distanceMeters], or null if the route has no direction. */
    fun bearingAt(distanceMeters: Double): Double?
}

/** A single fixed coordinate, supplied until the user stops. */
data class StationaryRoute(val point: GeoPoint) : Route {
    override val start: GeoPoint get() = point
    override val lengthMeters: Double get() = 0.0
    override fun positionAt(distanceMeters: Double): GeoPoint = point
    override fun bearingAt(distanceMeters: Double): Double? = null
}

/** The direct great-circle path between two points. These are not driving directions. */
data class StraightLineRoute(
    override val start: GeoPoint,
    val destination: GeoPoint,
) : Route {
    override val lengthMeters: Double = GeoMath.distanceMeters(start, destination)

    override fun positionAt(distanceMeters: Double): GeoPoint {
        if (lengthMeters == 0.0) return start
        return GeoMath.interpolate(start, destination, distanceMeters / lengthMeters)
    }

    override fun bearingAt(distanceMeters: Double): Double? {
        if (lengthMeters == 0.0) return null
        val remaining = lengthMeters - distanceMeters
        if (remaining > END_OF_ROUTE_METERS) {
            return GeoMath.initialBearingDegrees(positionAt(distanceMeters), destination)
        }
        // A bearing from the destination to itself is undefined, so arrive on the
        // reverse of the bearing looking back along the route.
        return GeoMath.normalizeDegrees(
            GeoMath.initialBearingDegrees(destination, start) + 180.0,
        )
    }

    private companion object {
        const val END_OF_ROUTE_METERS = 0.01
    }
}
