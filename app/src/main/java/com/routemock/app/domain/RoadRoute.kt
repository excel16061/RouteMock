package com.routemock.app.domain

/**
 * A path along road geometry. Position is found from cumulative segment distance,
 * so playback follows exactly these points, corners included.
 */
class RoadRoute(points: List<GeoPoint>) : Route {
    /** The geometry without zero-length segments, which have no direction. */
    val points: List<GeoPoint> = withoutRepeats(points)

    /** Distance from the start to each point. */
    private val cumulative = DoubleArray(this.points.size).also { distances ->
        for (index in 1 until this.points.size) {
            distances[index] = distances[index - 1] +
                GeoMath.distanceMeters(this.points[index - 1], this.points[index])
        }
    }

    override val start: GeoPoint = this.points.first()
    val destination: GeoPoint = this.points.last()
    override val lengthMeters: Double = cumulative.last()

    override fun positionAt(distanceMeters: Double): GeoPoint {
        if (points.size < 2 || distanceMeters <= 0.0) return start
        if (distanceMeters >= lengthMeters) return destination
        val segment = segmentAt(distanceMeters)
        val segmentLength = cumulative[segment + 1] - cumulative[segment]
        val fraction = (distanceMeters - cumulative[segment]) / segmentLength
        return GeoMath.interpolate(points[segment], points[segment + 1], fraction)
    }

    /** The direction of the segment being travelled. Corners turn at once, as paths do. */
    override fun bearingAt(distanceMeters: Double): Double? {
        if (points.size < 2) return null
        val segment = segmentAt(distanceMeters.coerceIn(0.0, lengthMeters))
        return GeoMath.initialBearingDegrees(points[segment], points[segment + 1])
    }

    /**
     * At most [limit] points for drawing. Redrawing a long route's full geometry on
     * every camera frame would stall the map. Playback always uses [points].
     */
    fun displayPoints(limit: Int = 400): List<GeoPoint> {
        if (points.size <= limit) return points
        val stride = (points.size + limit - 1) / limit
        return points.filterIndexed { index, _ -> index % stride == 0 } + destination
    }

    /** The last segment starting at or before [distance], found by binary search. */
    private fun segmentAt(distance: Double): Int {
        var low = 0
        var high = points.size - 2
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (cumulative[middle] <= distance) low = middle else high = middle - 1
        }
        return low
    }

    override fun equals(other: Any?) = other is RoadRoute && other.points == points
    override fun hashCode() = points.hashCode()
    override fun toString() = "RoadRoute(${points.size} points, $lengthMeters m)"

    private companion object {
        const val SAME_POINT_METERS = 0.01

        fun withoutRepeats(points: List<GeoPoint>): List<GeoPoint> {
            require(points.isNotEmpty()) { "A road route needs at least one point" }
            val kept = mutableListOf(points.first())
            for (point in points.drop(1)) {
                if (GeoMath.distanceMeters(kept.last(), point) > SAME_POINT_METERS) kept += point
            }
            return kept
        }
    }
}
