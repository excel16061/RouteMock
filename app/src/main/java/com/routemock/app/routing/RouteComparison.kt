package com.routemock.app.routing

import com.routemock.app.domain.GeoMath
import com.routemock.app.domain.GeoPoint
import com.routemock.app.domain.RoadRoute
import com.routemock.app.domain.TrafficBasis
import kotlin.math.max

/** A distinct route the user can choose. Distance is the geometry playback follows. */
data class RouteCandidate(
    val route: RoadRoute,
    val estimatedSeconds: Double,
    val trafficBasis: TrafficBasis,
) {
    val distanceMeters: Double get() = route.lengthMeters
}

enum class NoLongerRouteReason {
    NO_OTHER_ROUTES,
    ALL_OVER_LIMIT,
    TOO_SIMILAR,
    ALL_HAVE_LOOPS,
}

sealed interface LongerAlternative {
    data class Found(val candidate: RouteCandidate, val extraPercent: Double) : LongerAlternative
    data class None(val reason: NoLongerRouteReason) : LongerAlternative
}

/**
 * Comparison of the routes a provider happened to return. "Shortest" and "fastest"
 * mean only among these, never across the whole road network.
 */
data class RouteComparison(
    val candidates: List<RouteCandidate>,
    val shortest: RouteCandidate,
    val fastest: RouteCandidate,
    val longer: LongerAlternative,
) {
    fun extraPercent(candidate: RouteCandidate): Double =
        (candidate.distanceMeters / shortest.distanceMeters - 1.0) * 100.0
}

object RouteComparer {
    const val DEFAULT_MAX_EXTRA_PERCENT = 50

    /** Above this share of road in common, two routes are the same choice. */
    private const val SAME_ROUTE_OVERLAP = 0.95

    /** Above this, a route is too like the shortest to be worth offering as different. */
    private const val SIMILAR_ROUTE_OVERLAP = 0.90

    /** Longer by less than this is not meaningfully a longer route. */
    private const val MIN_EXTRA_PERCENT = 0.5
    private const val SAMPLES = 160
    private const val NEAR_METERS = 30.0

    /** Returns null if nothing usable came back. */
    fun compare(routes: List<ProviderRoute>, maxExtraPercent: Int): RouteComparison? {
        val candidates = distinctCandidates(routes)
        if (candidates.isEmpty()) return null
        val shortest = candidates.minBy { it.distanceMeters }
        val fastest = candidates.minBy { it.estimatedSeconds }
        return RouteComparison(
            candidates = candidates,
            shortest = shortest,
            fastest = fastest,
            longer = chooseLonger(candidates, shortest, fastest, maxExtraPercent),
        )
    }

    private fun distinctCandidates(routes: List<ProviderRoute>): List<RouteCandidate> {
        val kept = mutableListOf<RouteCandidate>()
        for (provided in routes) {
            val route = RoadRoute(provided.points)
            if (route.lengthMeters <= 0.0) continue
            val candidate = RouteCandidate(route, provided.estimatedSeconds, provided.trafficBasis)
            // The same road found twice is one choice, whichever search found it.
            val duplicate = kept.any {
                overlap(route, it.route) >= SAME_ROUTE_OVERLAP && overlap(it.route, route) >= SAME_ROUTE_OVERLAP
            }
            if (!duplicate) kept += candidate
        }
        return kept
    }

    private fun chooseLonger(
        candidates: List<RouteCandidate>,
        shortest: RouteCandidate,
        fastest: RouteCandidate,
        maxExtraPercent: Int,
    ): LongerAlternative {
        fun extra(candidate: RouteCandidate) = (candidate.distanceMeters / shortest.distanceMeters - 1.0) * 100.0

        val longer = candidates.filter { extra(it) >= MIN_EXTRA_PERCENT }
        if (longer.isEmpty()) return LongerAlternative.None(NoLongerRouteReason.NO_OTHER_ROUTES)
        val withinLimit = longer.filter { extra(it) <= maxExtraPercent }
        if (withinLimit.isEmpty()) return LongerAlternative.None(NoLongerRouteReason.ALL_OVER_LIMIT)
        val withoutLoops = withinLimit.filterNot { hasRepeatedLoop(it.route) }
        if (withoutLoops.isEmpty()) return LongerAlternative.None(NoLongerRouteReason.ALL_HAVE_LOOPS)
        val distinct = withoutLoops.filter { overlap(it.route, shortest.route) < SIMILAR_ROUTE_OVERLAP }
        if (distinct.isEmpty()) return LongerAlternative.None(NoLongerRouteReason.TOO_SIMILAR)

        // Most different road first, and a third option rather than repeating Fastest.
        val chosen = distinct.minWith(
            compareBy<RouteCandidate> { it === fastest }.thenBy { overlap(it.route, shortest.route) },
        )
        return LongerAlternative.Found(chosen, extra(chosen))
    }

    /** The share of [route] that runs along [other]. */
    fun overlap(route: RoadRoute, other: RoadRoute): Double {
        val samples = samples(route)
        val otherSamples = samples(other)
        val near = max(NEAR_METERS, other.lengthMeters / SAMPLES)
        val shared = samples.count { point ->
            otherSamples.any { GeoMath.distanceMeters(point, it) <= near }
        }
        return shared.toDouble() / samples.size
    }

    /** True if the route comes back to a place it already left, such as a detour loop. */
    fun hasRepeatedLoop(route: RoadRoute): Boolean {
        val samples = samples(route)
        val spacing = route.lengthMeters / SAMPLES
        val near = max(NEAR_METERS, spacing)
        // Samples this close along the path are near each other simply by being adjacent.
        val minimumGap = max(3, (near * 4 / spacing).toInt())
        for (later in samples.indices) {
            for (earlier in 0..later - minimumGap) {
                if (GeoMath.distanceMeters(samples[earlier], samples[later]) <= near) return true
            }
        }
        return false
    }

    private fun samples(route: RoadRoute): List<GeoPoint> =
        (0..SAMPLES).map { route.positionAt(route.lengthMeters * it / SAMPLES) }
}
