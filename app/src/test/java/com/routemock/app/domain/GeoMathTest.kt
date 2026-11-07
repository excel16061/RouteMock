package com.routemock.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class GeoMathTest {
    private val oneDegreeMeters = 2 * PI * GeoMath.EARTH_RADIUS_METERS / 360

    @Test fun `identical points are zero meters apart`() {
        val point = GeoPoint(37.7749, -122.4194)
        assertEquals(0.0, GeoMath.distanceMeters(point, point), 0.0)
    }

    @Test fun `one degree along the equator matches the circumference`() {
        assertEquals(oneDegreeMeters, GeoMath.distanceMeters(GeoPoint(0.0, 0.0), GeoPoint(0.0, 1.0)), 1e-6)
    }

    @Test fun `one degree of longitude shrinks with latitude`() {
        val distance = GeoMath.distanceMeters(GeoPoint(60.0, 0.0), GeoPoint(60.0, 1.0))
        assertEquals(oneDegreeMeters / 2, distance, 10.0) // cos(60) = 0.5
    }

    @Test fun `London to Paris matches the published great-circle distance`() {
        val distance = GeoMath.distanceMeters(GeoPoint(51.5074, -0.1278), GeoPoint(48.8566, 2.3522))
        assertEquals(343_500.0, distance, 1_000.0)
    }

    @Test fun `distance takes the short way across the antimeridian`() {
        val distance = GeoMath.distanceMeters(GeoPoint(0.0, 179.5), GeoPoint(0.0, -179.5))
        assertEquals(oneDegreeMeters, distance, 1e-6)
    }

    @Test fun `distance is symmetric`() {
        val a = GeoPoint(10.0, 20.0)
        val b = GeoPoint(-30.0, 140.0)
        assertEquals(GeoMath.distanceMeters(a, b), GeoMath.distanceMeters(b, a), 1e-6)
    }

    @Test fun `cardinal bearings`() {
        val origin = GeoPoint(0.0, 0.0)
        assertEquals(0.0, GeoMath.initialBearingDegrees(origin, GeoPoint(1.0, 0.0)), 1e-9)
        assertEquals(90.0, GeoMath.initialBearingDegrees(origin, GeoPoint(0.0, 1.0)), 1e-9)
        assertEquals(180.0, GeoMath.initialBearingDegrees(origin, GeoPoint(-1.0, 0.0)), 1e-9)
        assertEquals(270.0, GeoMath.initialBearingDegrees(origin, GeoPoint(0.0, -1.0)), 1e-9)
    }

    @Test fun `bearing across the antimeridian points east`() {
        assertEquals(90.0, GeoMath.initialBearingDegrees(GeoPoint(0.0, 179.5), GeoPoint(0.0, -179.5)), 1e-9)
    }

    @Test fun `interpolation returns the exact endpoints`() {
        val a = GeoPoint(12.5, 45.25)
        val b = GeoPoint(-3.75, 100.5)
        assertEquals(a, GeoMath.interpolate(a, b, 0.0))
        assertEquals(b, GeoMath.interpolate(a, b, 1.0))
    }

    @Test fun `interpolation follows the great circle rather than blending degrees`() {
        // Going between these points passes over the North Pole. A linear blend of
        // degrees would wrongly give latitude 60, longitude 0.
        val midpoint = GeoMath.interpolate(GeoPoint(60.0, -90.0), GeoPoint(60.0, 90.0), 0.5)
        assertEquals(90.0, midpoint.latitude, 1e-6)
    }

    @Test fun `equal fractions cover equal geographic distance`() {
        val a = GeoPoint(64.1, -21.9)
        val b = GeoPoint(35.7, 139.7)
        val total = GeoMath.distanceMeters(a, b)
        var previous = a
        for (step in 1..10) {
            val next = GeoMath.interpolate(a, b, step / 10.0)
            assertEquals(total / 10, GeoMath.distanceMeters(previous, next), 0.01)
            previous = next
        }
    }

    @Test fun `interpolation crosses the antimeridian without travelling the long way`() {
        val midpoint = GeoMath.interpolate(GeoPoint(0.0, 179.0), GeoPoint(0.0, -179.0), 0.5)
        assertEquals(180.0, abs(midpoint.longitude), 1e-9)
        assertEquals(0.0, midpoint.latitude, 1e-9)
    }

    @Test fun `interpolating a point with itself yields that point`() {
        val point = GeoPoint(1.0, 2.0)
        assertEquals(point, GeoMath.interpolate(point, point, 0.5))
    }

    @Test fun `normalized bearings stay within a full circle`() {
        assertEquals(350.0, GeoMath.normalizeDegrees(-10.0), 1e-9)
        assertEquals(10.0, GeoMath.normalizeDegrees(370.0), 1e-9)
        assertEquals(0.0, GeoMath.normalizeDegrees(360.0), 1e-9)
        assertTrue(GeoMath.normalizeDegrees(-1e-15) < 360.0)
    }
}
