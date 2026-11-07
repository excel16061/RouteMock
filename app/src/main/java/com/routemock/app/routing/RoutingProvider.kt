package com.routemock.app.routing

import com.routemock.app.domain.GeoPoint
import com.routemock.app.domain.TrafficBasis

data class RoutingProviderInfo(
    val name: String,
    /** Required by the service and shown beside any route it produced. */
    val attribution: String,
    /** Whether the terms of this provider allow keeping geometry in saved routes. */
    val allowsStoringGeometry: Boolean,
)

data class RoutingRequest(val start: GeoPoint, val destination: GeoPoint)

/** One route exactly as the provider described it. */
data class ProviderRoute(
    val points: List<GeoPoint>,
    val estimatedSeconds: Double,
    val trafficBasis: TrafficBasis,
)

data class RoutingOutcome(
    val routes: List<ProviderRoute>,
    /** The distance-only search failed, so "shortest" covers fewer routes than usual. */
    val distanceSearchFailed: Boolean = false,
)

enum class RoutingFailure {
    /** No road close enough to a pin. */
    NO_ROAD_NEAR_PIN,

    /** Both pins are near roads, but no drivable path joins them. */
    UNREACHABLE,
    TOO_FAR,
    RATE_LIMITED,
    NETWORK,
    TIMEOUT,

    /** The TLS handshake or certificate check failed. */
    SECURE_CONNECTION,

    /** The service tried to redirect the request, which is never followed. */
    REDIRECT_REFUSED,

    /** The configured address is not an acceptable HTTPS address. Nothing was sent. */
    INSECURE_ENDPOINT,
    SERVICE_ERROR,
    BAD_RESPONSE,
}

/** Deliberately carries no URL or coordinates, so it is safe to display or log. */
class RoutingException(val failure: RoutingFailure) : Exception(failure.name)

/** Implement this to replace the routing service. Nothing else depends on Valhalla. */
interface RoutingProvider {
    val info: RoutingProviderInfo

    /** Throws [RoutingException] if no route at all could be obtained. */
    suspend fun findRoutes(request: RoutingRequest): RoutingOutcome
}
