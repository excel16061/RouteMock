package com.routemock.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadDraftTest {
    private val pinStart = GeoPoint(0.0, 0.0)
    private val pinEnd = GeoPoint(0.01, 0.01)
    private val road = listOf(GeoPoint(0.0001, 0.0), GeoPoint(0.0001, 0.01), GeoPoint(0.0101, 0.01))

    private fun selection(shape: String = PolylineCodec.encode(road)) = RoadRouteSelection(
        encodedShape = shape,
        estimatedSeconds = 100.0,
        trafficBasis = TrafficBasis.ROAD_SPEED_MODEL,
        providerName = "Test",
        attribution = "Attribution",
        requestedStart = pinStart,
        requestedDestination = pinEnd,
        calculatedAtMillis = 1L,
    )

    private val draft = RouteDraft(
        mode = RouteMode.ROAD,
        startLatitude = "0",
        startLongitude = "0",
        destinationLatitude = "0.01",
        destinationLongitude = "0.01",
        speed = "36",
        roadRoute = selection(),
    )

    @Test fun `a selected route validates as that exact geometry`() {
        val result = RouteValidator.validate(draft).result!!
        assertEquals(RoadRoute(road), result.route)
        assertEquals(10.0, result.speedMetersPerSecond, 1e-9)
    }

    @Test fun `road mode with no route is refused, not turned into a straight line`() {
        val validation = RouteValidator.validate(draft.copy(roadRoute = null))
        assertNull(validation.result)
        assertEquals(RouteError.ROAD_ROUTE_MISSING, validation.routeError)
    }

    @Test fun `moving a pin invalidates the route rather than following old geometry`() {
        val moved = draft.withMapPoint(SelectionTarget.START, GeoPoint(0.005, 0.0))
        val validation = RouteValidator.validate(moved)
        assertNull(validation.result)
        assertEquals(RouteError.ROAD_ROUTE_OUTDATED, validation.routeError)
        assertNull(moved.currentRoadRoute())
    }

    @Test fun `putting the pin back makes the route apply again`() {
        val moved = draft.withMapPoint(SelectionTarget.START, GeoPoint(0.005, 0.0))
        val restored = moved.withMapPoint(SelectionTarget.START, pinStart)
        assertNotNull(restored.currentRoadRoute())
    }

    @Test fun `damaged stored geometry counts as no route`() {
        val validation = RouteValidator.validate(draft.copy(roadRoute = selection(shape = "!")))
        assertNull(validation.result)
        assertEquals(RouteError.ROAD_ROUTE_MISSING, validation.routeError)
    }

    @Test fun `geometry with no length counts as no route`() {
        val point = GeoPoint(1.0, 1.0)
        val validation = RouteValidator.validate(
            draft.copy(roadRoute = selection(shape = PolylineCodec.encode(listOf(point, point)))),
        )
        assertEquals(RouteError.ROAD_ROUTE_MISSING, validation.routeError)
    }

    @Test fun `an invalid speed still blocks a road route`() {
        assertNull(RouteValidator.validate(draft.copy(speed = "0")).result)
    }

    @Test fun `straight line stays available as its own explicit mode`() {
        val result = RouteValidator.validate(draft.copy(mode = RouteMode.STRAIGHT_LINE)).result!!
        assertTrue(result.route is StraightLineRoute)
    }

    @Test fun `a stored road route is ignored outside road mode`() {
        assertNull(draft.copy(mode = RouteMode.STRAIGHT_LINE).currentRoadRoute())
    }

    @Test fun `the difference between pin and road is measured at each end`() {
        val selected = selection()
        assertEquals(11.1, selected.startSnapMeters!!, 0.1)
        assertEquals(11.1, selected.destinationSnapMeters!!, 0.1)
    }

    @Test fun `identical pins cannot be sent for routing`() {
        assertNull(draft.copy(destinationLatitude = "0", destinationLongitude = "0").pinsForRouting())
        assertEquals(pinStart to pinEnd, draft.pinsForRouting())
    }

    @Test fun `stationary mode has nothing to route`() {
        assertNull(draft.copy(mode = RouteMode.STATIONARY).pinsForRouting())
    }
}
