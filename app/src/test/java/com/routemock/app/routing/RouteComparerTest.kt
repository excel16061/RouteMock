package com.routemock.app.routing

import com.routemock.app.domain.GeoPoint
import com.routemock.app.domain.RoadRoute
import com.routemock.app.domain.TrafficBasis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic routes built by hand. None of this data came from a provider. */
class RouteComparerTest {
    private fun route(seconds: Double, vararg points: Pair<Double, Double>) = ProviderRoute(
        points.map { GeoPoint(it.first, it.second) },
        seconds,
        TrafficBasis.ROAD_SPEED_MODEL,
    )

    // All run from (0,0) to (0,0.1); a higher midpoint makes a longer, separate road.
    private val direct = route(1000.0, 0.0 to 0.0, 0.0 to 0.1)
    private fun detour(height: Double, seconds: Double) = route(seconds, 0.0 to 0.0, height to 0.05, 0.0 to 0.1)

    @Test fun `shortest and fastest can be different routes`() {
        val faster = detour(0.02, 800.0)
        val result = RouteComparer.compare(listOf(direct, faster), 50)!!
        assertEquals(RoadRoute(direct.points), result.shortest.route)
        assertEquals(RoadRoute(faster.points), result.fastest.route)
    }

    @Test fun `one route can be both shortest and fastest`() {
        val result = RouteComparer.compare(listOf(direct), 50)!!
        assertSame(result.shortest, result.fastest)
    }

    @Test fun `no usable routes gives no comparison`() {
        assertNull(RouteComparer.compare(emptyList(), 50))
        assertNull(RouteComparer.compare(listOf(route(10.0, 1.0 to 1.0, 1.0 to 1.0)), 50))
    }

    @Test fun `extra distance is relative to the shortest returned`() {
        val longer = detour(0.02, 2000.0)
        val result = RouteComparer.compare(listOf(direct, longer), 50)!!
        val found = result.longer as LongerAlternative.Found
        val expected = (found.candidate.distanceMeters / result.shortest.distanceMeters - 1) * 100
        assertEquals(expected, found.extraPercent, 1e-9)
        assertEquals(0.0, result.extraPercent(result.shortest), 1e-9)
    }

    @Test fun `the same road returned twice counts once`() {
        val copy = route(1200.0, 0.0 to 0.0, 0.0 to 0.05, 0.0 to 0.1)
        val result = RouteComparer.compare(listOf(direct, copy), 50)!!
        assertEquals(1, result.candidates.size)
    }

    @Test fun `only one route means no longer alternative, honestly`() {
        val result = RouteComparer.compare(listOf(direct), 50)!!
        assertEquals(LongerAlternative.None(NoLongerRouteReason.NO_OTHER_ROUTES), result.longer)
    }

    @Test fun `a route over the limit is not offered`() {
        val tooLong = detour(0.05, 3000.0) // About 41% longer.
        val result = RouteComparer.compare(listOf(direct, tooLong), maxExtraPercent = 20)!!
        assertEquals(LongerAlternative.None(NoLongerRouteReason.ALL_OVER_LIMIT), result.longer)
    }

    @Test fun `raising the limit brings that route back`() {
        val tooLong = detour(0.05, 3000.0)
        val result = RouteComparer.compare(listOf(direct, tooLong), maxExtraPercent = 50)!!
        assertTrue(result.longer is LongerAlternative.Found)
    }

    @Test fun `the default limit is 50 percent`() {
        assertEquals(50, RouteComparer.DEFAULT_MAX_EXTRA_PERCENT)
    }

    @Test fun `a longer route sharing nearly all its road is too similar`() {
        // Follows the direct road apart from one kink: distinct, but barely a choice.
        val kinked = route(
            1500.0,
            0.0 to 0.0, 0.0 to 0.084, 0.004 to 0.087, 0.0 to 0.09, 0.0 to 0.1,
        )
        val result = RouteComparer.compare(listOf(direct, kinked), 50)!!
        assertEquals(2, result.candidates.size)
        assertEquals(LongerAlternative.None(NoLongerRouteReason.TOO_SIMILAR), result.longer)
    }

    @Test fun `a route that repeats part of its path is not offered`() {
        val looped = route(
            2000.0,
            0.0 to 0.0, 0.02 to 0.05, 0.02 to 0.07, 0.04 to 0.07, 0.04 to 0.05, 0.02 to 0.05, 0.0 to 0.1,
        )
        val result = RouteComparer.compare(listOf(direct, looped), 200)!!
        assertEquals(LongerAlternative.None(NoLongerRouteReason.ALL_HAVE_LOOPS), result.longer)
    }

    @Test fun `loop detection does not flag ordinary winding roads`() {
        val winding = RoadRoute((0..40).map { GeoPoint(if (it % 2 == 0) 0.0 else 0.002, it * 0.0025) })
        assertFalse(RouteComparer.hasRepeatedLoop(winding))
        assertFalse(RouteComparer.hasRepeatedLoop(RoadRoute(direct.points)))
    }

    @Test fun `going out and back along the same road counts as a loop`() {
        val outAndBack = RoadRoute(listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.05), GeoPoint(0.0, 0.01)))
        assertTrue(RouteComparer.hasRepeatedLoop(outAndBack))
    }

    @Test fun `the longer alternative avoids repeating the fastest when it can`() {
        val fastestAndLonger = detour(0.02, 500.0)
        val thirdOption = detour(-0.02, 2000.0)
        val result = RouteComparer.compare(listOf(direct, fastestAndLonger, thirdOption), 50)!!
        val found = result.longer as LongerAlternative.Found
        assertEquals(RoadRoute(thirdOption.points), found.candidate.route)
    }

    @Test fun `the fastest can stand as the longer alternative if nothing else qualifies`() {
        val fastest = detour(0.02, 500.0)
        val result = RouteComparer.compare(listOf(direct, fastest), 50)!!
        assertEquals(result.fastest, (result.longer as LongerAlternative.Found).candidate)
    }

    @Test fun `overlap is one with itself and low for separate roads`() {
        val a = RoadRoute(direct.points)
        val b = RoadRoute(detour(0.02, 1.0).points)
        assertEquals(1.0, RouteComparer.overlap(a, a), 0.0)
        assertTrue(RouteComparer.overlap(b, a) < 0.2)
    }

    @Test fun `the traffic basis is carried through unchanged`() {
        val result = RouteComparer.compare(listOf(direct), 50)!!
        assertEquals(TrafficBasis.ROAD_SPEED_MODEL, result.shortest.trafficBasis)
    }
}
