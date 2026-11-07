package com.routemock.app.routing

import com.routemock.app.domain.GeoPoint
import com.routemock.app.domain.TrafficBasis
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The only test that touches the network, against the live public server. Skipped
 * unless ROUTEMOCK_LIVE_ROUTING=1, so normal runs stay deterministic and do not load a
 * volunteer service. It uses fixed synthetic points in Berlin and makes three requests.
 */
class LiveValhallaCheck {
    private val provider = ValhallaRoutingProvider(
        baseUrl = "https://valhalla1.openstreetmap.de",
        apiKey = "",
        info = RoutingProviderInfo("FOSSGIS", "", allowsStoringGeometry = true),
        http = HttpUrlConnectionPost("RouteMock/0.4.7 (live check; com.routemock.app)"),
        pacer = RequestPacer(1200) { System.currentTimeMillis() },
    )

    @Test fun `the live service returns comparable road routes and honest failures`() = runBlocking {
        assumeTrue(System.getenv("ROUTEMOCK_LIVE_ROUTING") == "1")

        val outcome = provider.findRoutes(RoutingRequest(GeoPoint(52.5167, 13.4142), GeoPoint(52.3906, 13.0645)))
        val comparison = RouteComparer.compare(outcome.routes, RouteComparer.DEFAULT_MAX_EXTRA_PERCENT)!!
        println("LIVE routes=${outcome.routes.size} distinct=${comparison.candidates.size} " +
            "shortestKm=${comparison.shortest.distanceMeters / 1000} " +
            "fastestMin=${comparison.fastest.estimatedSeconds / 60} longer=${comparison.longer::class.simpleName}")
        assertTrue(outcome.routes.size >= 2)
        assertTrue(outcome.routes.all { it.trafficBasis == TrafficBasis.ROAD_SPEED_MODEL })

        val failure = try {
            provider.findRoutes(RoutingRequest(GeoPoint(30.0, -40.0), GeoPoint(31.0, -41.0)))
            null
        } catch (e: RoutingException) {
            e.failure
        }
        assertEquals(RoutingFailure.NO_ROAD_NEAR_PIN, failure)
    }
}
