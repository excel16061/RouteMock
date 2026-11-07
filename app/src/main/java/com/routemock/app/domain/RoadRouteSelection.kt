package com.routemock.app.domain

import kotlinx.serialization.Serializable

/** What a route's travel time is based on. Only LIVE may ever be called live traffic. */
@Serializable
enum class TrafficBasis { ROAD_SPEED_MODEL, HISTORICAL, LIVE }

/**
 * The road route chosen for the draft, with what is needed to display it honestly.
 * It belongs to the pins it was calculated for and stops applying if they move.
 */
@Serializable
data class RoadRouteSelection(
    val encodedShape: String,
    val estimatedSeconds: Double,
    val trafficBasis: TrafficBasis,
    val providerName: String,
    val attribution: String,
    val requestedStart: GeoPoint,
    val requestedDestination: GeoPoint,
    val calculatedAtMillis: Long,
) {
    /** Null if the stored geometry is damaged or has no length. */
    val route: RoadRoute? by lazy {
        PolylineCodec.decode(encodedShape)
            .takeIf { it.isNotEmpty() }
            ?.let(::RoadRoute)
            ?.takeIf { it.lengthMeters > 0.0 }
    }

    /** How far the road's start is from the selected pin. */
    val startSnapMeters: Double?
        get() = route?.let { GeoMath.distanceMeters(requestedStart, it.start) }

    val destinationSnapMeters: Double?
        get() = route?.let { GeoMath.distanceMeters(requestedDestination, it.destination) }

    fun matches(start: GeoPoint, destination: GeoPoint): Boolean =
        GeoMath.distanceMeters(start, requestedStart) < SAME_PIN_METERS &&
            GeoMath.distanceMeters(destination, requestedDestination) < SAME_PIN_METERS

    private companion object {
        const val SAME_PIN_METERS = 1.0
    }
}
