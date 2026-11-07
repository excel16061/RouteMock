package com.routemock.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RouteTest {
    private val eastward = StraightLineRoute(GeoPoint(0.0, 0.0), GeoPoint(0.0, 1.0))

    @Test fun `position is measured in distance from the start`() {
        val quarter = eastward.positionAt(eastward.lengthMeters / 4)
        assertEquals(0.25, quarter.longitude, 1e-9)
    }

    @Test fun `position clamps to both ends of the route`() {
        assertEquals(eastward.start, eastward.positionAt(-5.0))
        assertEquals(eastward.destination, eastward.positionAt(eastward.lengthMeters * 2))
    }

    @Test fun `bearing follows the curve of a long route`() {
        val route = StraightLineRoute(GeoPoint(51.5, 0.0), GeoPoint(40.7, -74.0))
        val atStart = route.bearingAt(0.0)!!
        val midway = route.bearingAt(route.lengthMeters / 2)!!
        assertEquals(GeoMath.initialBearingDegrees(route.start, route.destination), atStart, 1e-9)
        assert(kotlin.math.abs(atStart - midway) > 10.0) { "Great-circle bearing should change en route" }
    }

    @Test fun `bearing stays defined on arrival`() {
        assertEquals(90.0, eastward.bearingAt(eastward.lengthMeters)!!, 1e-9)
    }

    @Test fun `a zero-length route has no bearing and produces no NaN`() {
        val point = GeoPoint(10.0, 20.0)
        val route = StraightLineRoute(point, point)
        assertEquals(0.0, route.lengthMeters, 0.0)
        assertEquals(point, route.positionAt(100.0))
        assertNull(route.bearingAt(0.0))
    }

    @Test fun `stationary route never moves`() {
        val point = GeoPoint(10.0, 20.0)
        val route = StationaryRoute(point)
        assertEquals(point, route.positionAt(1_000_000.0))
        assertNull(route.bearingAt(0.0))
        assertNotNull(route.start)
    }
}
