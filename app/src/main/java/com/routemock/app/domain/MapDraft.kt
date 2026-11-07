package com.routemock.app.domain

import java.util.Locale

/** Which draft pin a map tap or drag edits. */
enum class SelectionTarget { START, DESTINATION }

/**
 * Writes a map position into the draft's coordinate text, so the map and the
 * coordinate fields are two views of one value. This only ever produces a new
 * draft: a running session keeps the route it was started with.
 */
fun RouteDraft.withMapPoint(target: SelectionTarget, point: GeoPoint): RouteDraft {
    val latitude = formatCoordinate(point.latitude.coerceIn(GeoPoint.MIN_LATITUDE, GeoPoint.MAX_LATITUDE))
    val longitude = formatCoordinate(wrapLongitude(point.longitude))
    return when (target) {
        SelectionTarget.START -> copy(startLatitude = latitude, startLongitude = longitude)
        SelectionTarget.DESTINATION ->
            if (mode == RouteMode.STATIONARY) this
            else copy(destinationLatitude = latitude, destinationLongitude = longitude)
    }
}

/** The start pin, or null while its text is not a valid coordinate. */
fun RouteDraft.startPoint(): GeoPoint? = pointOf(startLatitude, startLongitude)

/** The destination pin. Stationary drafts have none, whatever text they kept. */
fun RouteDraft.destinationPoint(): GeoPoint? =
    if (mode == RouteMode.STATIONARY) null else pointOf(destinationLatitude, destinationLongitude)

/**
 * Evenly spaced points along the great circle for drawing, the same path playback
 * follows. Longitudes run past ±180 when crossing the antimeridian, so a line
 * joining them does not streak across the whole world.
 */
fun greatCircleSamples(start: GeoPoint, end: GeoPoint, segments: Int = 64): List<GeoPoint> {
    require(segments >= 1) { "At least one segment is needed" }
    var previousLongitude = start.longitude
    return (0..segments).map { step ->
        val point = GeoMath.interpolate(start, end, step.toDouble() / segments)
        var longitude = point.longitude
        while (longitude - previousLongitude > 180.0) longitude -= 360.0
        while (longitude - previousLongitude < -180.0) longitude += 360.0
        previousLongitude = longitude
        GeoPoint(point.latitude, longitude)
    }
}

private fun pointOf(latitude: String, longitude: String): GeoPoint? {
    if (RouteValidator.validateLatitude(latitude) != null) return null
    if (RouteValidator.validateLongitude(longitude) != null) return null
    return GeoPoint(RouteValidator.parseDecimal(latitude)!!, RouteValidator.parseDecimal(longitude)!!)
}

/** Six decimals is about 0.1 m, well below what a finger can place. */
private fun formatCoordinate(value: Double): String = "%.6f".format(Locale.US, value)

/** The map's world repeats sideways, so taps can land outside ±180. */
private fun wrapLongitude(longitude: Double): Double {
    val wrapped = (longitude + 180.0) % 360.0
    return (if (wrapped < 0.0) wrapped + 360.0 else wrapped) - 180.0
}

/** Both pins, if they are valid and different, ready to send to a routing provider. */
fun RouteDraft.pinsForRouting(): Pair<GeoPoint, GeoPoint>? {
    val start = startPoint() ?: return null
    val destination = destinationPoint() ?: return null
    return (start to destination).takeUnless {
        GeoMath.distanceMeters(start, destination) < RouteValidator.MIN_ROUTE_LENGTH_METERS
    }
}

/** The selected road route, unless the pins have moved since it was calculated. */
fun RouteDraft.currentRoadRoute(): RoadRouteSelection? {
    if (mode != RouteMode.ROAD) return null
    val selection = roadRoute ?: return null
    val (start, destination) = pinsForRouting() ?: return null
    return selection.takeIf { it.route != null && it.matches(start, destination) }
}
