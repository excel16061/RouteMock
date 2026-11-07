package com.routemock.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MapDraftTest {
    private val draft = RouteDraft(
        mode = RouteMode.STRAIGHT_LINE,
        startLatitude = "10",
        startLongitude = "20",
        destinationLatitude = "11",
        destinationLongitude = "21",
        speed = "42",
        speedUnit = SpeedUnit.MPH,
    )

    @Test fun `a start pin rewrites only the start coordinates`() {
        val edited = draft.withMapPoint(SelectionTarget.START, GeoPoint(1.5, -2.5))
        assertEquals("1.500000", edited.startLatitude)
        assertEquals("-2.500000", edited.startLongitude)
        assertEquals(draft.copy(startLatitude = edited.startLatitude, startLongitude = edited.startLongitude), edited)
    }

    @Test fun `a destination pin rewrites only the destination coordinates`() {
        val edited = draft.withMapPoint(SelectionTarget.DESTINATION, GeoPoint(1.5, -2.5))
        assertEquals("1.500000", edited.destinationLatitude)
        assertEquals("10", edited.startLatitude)
        assertEquals("42", edited.speed)
    }

    @Test fun `map and coordinate fields read back the same point`() {
        val edited = draft.withMapPoint(SelectionTarget.START, GeoPoint(12.345678, -98.765432))
        assertEquals(GeoPoint(12.345678, -98.765432), edited.startPoint())
    }

    @Test fun `text typed into the fields moves the pin`() {
        assertEquals(GeoPoint(45.0, 90.0), draft.copy(startLatitude = "45", startLongitude = "90").startPoint())
    }

    @Test fun `a pin placed on a repeated world wraps into range`() {
        val edited = draft.withMapPoint(SelectionTarget.START, GeoPoint(0.0, 190.0))
        assertEquals("-170.000000", edited.startLongitude)
        assertNull(RouteValidator.validateLongitude(edited.startLongitude))
    }

    @Test fun `map output always passes validation`() {
        listOf(-720.0, -181.0, -180.0, 0.0, 180.0, 181.0, 540.0).forEach { longitude ->
            val edited = draft.withMapPoint(SelectionTarget.START, GeoPoint(95.0, longitude))
            assertNull(RouteValidator.validateLongitude(edited.startLongitude))
            assertNull(RouteValidator.validateLatitude(edited.startLatitude))
        }
    }

    @Test fun `stationary selection uses the start coordinates and validates`() {
        val stationary = draft.copy(mode = RouteMode.STATIONARY)
            .withMapPoint(SelectionTarget.START, GeoPoint(3.0, 4.0))
        assertTrue(RouteValidator.validate(stationary).result!!.route is StationaryRoute)
        assertEquals(GeoPoint(3.0, 4.0), stationary.startPoint())
    }

    @Test fun `a stationary draft has no destination to edit or draw`() {
        val stationary = draft.copy(mode = RouteMode.STATIONARY)
        assertEquals(stationary, stationary.withMapPoint(SelectionTarget.DESTINATION, GeoPoint(3.0, 4.0)))
        assertNull(stationary.destinationPoint())
    }

    @Test fun `incomplete text has no pin`() {
        assertNull(draft.copy(startLatitude = "").startPoint())
        assertNull(draft.copy(startLongitude = "abc").startPoint())
        assertNull(draft.copy(destinationLatitude = "95").destinationPoint())
        assertNotNull(draft.destinationPoint())
    }

    @Test fun `the drawn line is the path playback follows`() {
        val route = StraightLineRoute(GeoPoint(51.5, 0.0), GeoPoint(40.7, -74.0))
        val samples = greatCircleSamples(route.start, route.destination, segments = 8)
        samples.forEachIndexed { index, sample ->
            val playback = route.positionAt(route.lengthMeters * index / 8)
            assertEquals(playback.latitude, sample.latitude, 1e-9)
            assertEquals(playback.longitude, sample.longitude, 1e-9)
        }
    }

    @Test fun `the drawn line stays continuous across the antimeridian`() {
        val samples = greatCircleSamples(GeoPoint(0.0, 170.0), GeoPoint(0.0, -170.0), segments = 20)
        samples.zipWithNext().forEach { (a, b) ->
            assertTrue(abs(a.longitude - b.longitude) < 2.0)
        }
        assertEquals(190.0, samples.last().longitude, 1e-6)
    }
}
