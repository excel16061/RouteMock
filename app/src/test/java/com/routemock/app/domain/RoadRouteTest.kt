package com.routemock.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadRouteTest {
    // An L shape: east along the equator, then north.
    private val corner = GeoPoint(0.0, 0.01)
    private val route = RoadRoute(listOf(GeoPoint(0.0, 0.0), corner, GeoPoint(0.01, 0.01)))
    private val leg = GeoMath.distanceMeters(GeoPoint(0.0, 0.0), corner)

    @Test fun `length is the sum of its segments, not the straight line between ends`() {
        val direct = GeoMath.distanceMeters(route.start, route.destination)
        assertEquals(leg * 2, route.lengthMeters, 1e-6)
        assertTrue(route.lengthMeters > direct * 1.4)
    }

    @Test fun `position interpolates within the first segment`() {
        val point = route.positionAt(leg / 2)
        assertEquals(0.005, point.longitude, 1e-9)
        assertEquals(0.0, point.latitude, 1e-9)
    }

    @Test fun `position interpolates within a later segment`() {
        val point = route.positionAt(leg * 1.5)
        assertEquals(0.01, point.longitude, 1e-9)
        assertEquals(0.005, point.latitude, 1e-9)
    }

    @Test fun `the path passes exactly through the corner`() {
        val point = route.positionAt(leg)
        assertEquals(corner.latitude, point.latitude, 1e-9)
        assertEquals(corner.longitude, point.longitude, 1e-9)
    }

    @Test fun `position never cuts the corner`() {
        for (step in 1..99) {
            val point = route.positionAt(route.lengthMeters * step / 100)
            val onFirstLeg = kotlin.math.abs(point.latitude) < 1e-9
            val onSecondLeg = kotlin.math.abs(point.longitude - 0.01) < 1e-9
            assertTrue("step $step left the path: $point", onFirstLeg || onSecondLeg)
        }
    }

    @Test fun `ends clamp to the exact first and last points`() {
        assertEquals(route.start, route.positionAt(-10.0))
        assertEquals(route.start, route.positionAt(0.0))
        assertEquals(route.destination, route.positionAt(route.lengthMeters))
        assertEquals(route.destination, route.positionAt(route.lengthMeters * 5))
    }

    @Test fun `bearing comes from the segment being travelled`() {
        assertEquals(90.0, route.bearingAt(leg * 0.5)!!, 1e-6)
        assertEquals(0.0, route.bearingAt(leg * 1.5)!!, 1e-6)
    }

    @Test fun `bearing turns at the corner and stays defined on arrival`() {
        assertEquals(90.0, route.bearingAt(leg - 0.01)!!, 1e-6)
        assertEquals(0.0, route.bearingAt(leg + 0.01)!!, 1e-6)
        assertEquals(0.0, route.bearingAt(route.lengthMeters)!!, 1e-6)
    }

    @Test fun `duplicate points and zero-length segments are removed`() {
        val a = GeoPoint(0.0, 0.0)
        val b = GeoPoint(0.0, 0.01)
        val c = GeoPoint(0.01, 0.01)
        val noisy = RoadRoute(listOf(a, a, a, b, b, c, c))
        assertEquals(listOf(a, b, c), noisy.points)
        assertEquals(route.lengthMeters, noisy.lengthMeters, 1e-9)
    }

    @Test fun `duplicates at a corner do not disturb position or bearing`() {
        val noisy = RoadRoute(listOf(route.start, corner, corner, corner, route.destination))
        for (step in 0..20) {
            val distance = route.lengthMeters * step / 20
            assertEquals(route.positionAt(distance), noisy.positionAt(distance))
            assertEquals(route.bearingAt(distance), noisy.bearingAt(distance))
        }
    }

    @Test fun `a route of one repeated point has no length, no bearing, and no NaN`() {
        val point = GeoPoint(1.0, 2.0)
        val degenerate = RoadRoute(listOf(point, point, point))
        assertEquals(0.0, degenerate.lengthMeters, 0.0)
        assertEquals(point, degenerate.positionAt(50.0))
        assertNull(degenerate.bearingAt(0.0))
    }

    @Test fun `an empty route is refused`() {
        assertThrows(IllegalArgumentException::class.java) { RoadRoute(emptyList()) }
    }

    @Test fun `distance along the route is proportional to geometry, not to segment count`() {
        // Many short segments then one long one: half the distance is not half the points.
        val points = (0..10).map { GeoPoint(0.0, it * 0.0001) } + GeoPoint(0.0, 0.1)
        val uneven = RoadRoute(points)
        assertEquals(0.05, uneven.positionAt(uneven.lengthMeters / 2).longitude, 1e-9)
    }

    @Test fun `routes with the same geometry are equal`() {
        assertEquals(route, RoadRoute(route.points))
    }

    @Test fun `display points are capped and keep both ends`() {
        val long = RoadRoute((0..4999).map { GeoPoint(0.0, it * 0.0001) })
        val shown = long.displayPoints(limit = 100)
        assertTrue(shown.size <= 102)
        assertEquals(long.start, shown.first())
        assertEquals(long.destination, shown.last())
        assertEquals(5000, long.points.size) // Playback keeps every point.
    }
}
