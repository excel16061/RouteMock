package com.routemock.app.routing

import com.routemock.app.domain.GeoMath
import com.routemock.app.domain.GeoPoint
import com.routemock.app.domain.PolylineCodec
import com.routemock.app.domain.RoadRoute
import com.routemock.app.domain.TrafficBasis
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException

/**
 * `valhalla_recorded_*` files are real responses, captured once from the public
 * server for synthetic points in Berlin. The tests never touch the network, so they
 * show the parser handles that format, not that the live service still behaves so.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ValhallaRoutingProviderTest {
    private val request = RoutingRequest(GeoPoint(52.5167, 13.4142), GeoPoint(52.3906, 13.0645))
    private val info = RoutingProviderInfo("Test", "Attribution", allowsStoringGeometry = true)

    private fun fixture(name: String) =
        checkNotNull(javaClass.classLoader!!.getResource("fixtures/$name")).readText()

    private val alternates get() = HttpResponse(200, fixture("valhalla_recorded_berlin_potsdam_alternates.json"))
    private val distanceOnly get() = HttpResponse(200, fixture("valhalla_recorded_berlin_potsdam_distance_only.json"))

    private inner class Scripted(vararg responses: () -> HttpResponse) : HttpPost {
        val queue = ArrayDeque(responses.toList())
        val bodies = mutableListOf<String>()
        val urls = mutableListOf<String>()
        override suspend fun post(url: String, jsonBody: String): HttpResponse {
            urls += url
            bodies += jsonBody
            return queue.removeFirst()()
        }
    }

    private fun TestScope.provider(http: HttpPost, apiKey: String = "") = ValhallaRoutingProvider(
        baseUrl = "https://routing.example/",
        apiKey = apiKey,
        info = info,
        http = http,
        pacer = RequestPacer(1200) { testScheduler.currentTime },
    )

    private fun failureOf(block: suspend TestScope.() -> Unit): RoutingFailure {
        var failure: RoutingFailure? = null
        runTest {
            try {
                block()
            } catch (e: RoutingException) {
                failure = e.failure
            }
        }
        return checkNotNull(failure) { "Expected a routing failure" }
    }

    @Test fun `a recorded response yields the main route and its alternates`() = runTest {
        val outcome = provider(Scripted({ alternates }, { distanceOnly })).findRoutes(request)
        assertEquals(4, outcome.routes.size)
        assertFalse(outcome.distanceSearchFailed)
        val main = outcome.routes.first()
        assertEquals(2811.219, main.estimatedSeconds, 1e-3)
        assertEquals(40.57, RoadRoute(main.points).lengthMeters / 1000, 0.5)
    }

    @Test fun `recorded geometry starts near the requested pin`() = runTest {
        val main = provider(Scripted({ alternates }, { distanceOnly })).findRoutes(request).routes.first()
        assertTrue(GeoMath.distanceMeters(main.points.first(), request.start) < 200.0)
        assertTrue(GeoMath.distanceMeters(main.points.last(), request.destination) < 200.0)
    }

    @Test fun `this provider never reports live traffic`() = runTest {
        val outcome = provider(Scripted({ alternates }, { distanceOnly })).findRoutes(request)
        assertTrue(outcome.routes.all { it.trafficBasis == TrafficBasis.ROAD_SPEED_MODEL })
    }

    @Test fun `it asks for alternates, then a distance-only search`() = runTest {
        val http = Scripted({ alternates }, { distanceOnly })
        provider(http).findRoutes(request)
        assertTrue(http.bodies[0].contains("\"alternates\":2"))
        assertFalse(http.bodies[0].contains("shortest"))
        assertTrue(http.bodies[1].contains("\"shortest\":true"))
        assertFalse(http.bodies[1].contains("alternates"))
        assertEquals("https://routing.example/route", http.urls[0])
    }

    @Test fun `a key goes in the URL only when configured`() = runTest {
        val http = Scripted({ alternates }, { distanceOnly })
        provider(http, apiKey = "abc").findRoutes(request)
        assertEquals("https://routing.example/route?api_key=abc", http.urls[0])
    }

    @Test fun `requests are spaced apart to respect the service limit`() = runTest {
        val times = mutableListOf<Long>()
        val http = HttpPost { _, _ ->
            times += testScheduler.currentTime
            alternates
        }
        provider(http).findRoutes(request)
        assertTrue(times[1] - times[0] >= 1200)
    }

    @Test fun `concurrent callers share the same pacing`() = runTest {
        val pacer = RequestPacer(1200) { testScheduler.currentTime }
        val times = mutableListOf<Long>()
        repeat(3) { launch { pacer.paced { times += testScheduler.currentTime } } }
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(0L, 1200L, 2400L), times)
    }

    @Test fun `slow requests do not add needless waiting`() = runTest {
        val pacer = RequestPacer(1200) { testScheduler.currentTime }
        pacer.paced { delay(5000) }
        val before = testScheduler.currentTime
        pacer.paced { }
        assertEquals(before + 1200, testScheduler.currentTime)
    }

    @Test fun `a recorded no-road error is reported as such`() {
        val failure = failureOf {
            provider(Scripted({ HttpResponse(400, fixture("valhalla_recorded_error_171.json")) })).findRoutes(request)
        }
        assertEquals(RoutingFailure.NO_ROAD_NEAR_PIN, failure)
    }

    @Test fun `an unreachable destination is distinct from a missing road`() {
        val body = """{"error_code":442,"error":"No path could be found for input"}"""
        assertEquals(
            RoutingFailure.UNREACHABLE,
            failureOf { provider(Scripted({ HttpResponse(400, body) })).findRoutes(request) },
        )
    }

    @Test fun `a route beyond the service's limit is reported`() {
        val body = """{"error_code":154,"error":"Path distance exceeds the max distance limit"}"""
        assertEquals(
            RoutingFailure.TOO_FAR,
            failureOf { provider(Scripted({ HttpResponse(400, body) })).findRoutes(request) },
        )
    }

    @Test fun `rate limiting is reported`() {
        assertEquals(
            RoutingFailure.RATE_LIMITED,
            failureOf { provider(Scripted({ HttpResponse(429, "") })).findRoutes(request) },
        )
    }

    @Test fun `a server error page is a service error, not a crash`() {
        assertEquals(
            RoutingFailure.SERVICE_ERROR,
            failureOf { provider(Scripted({ HttpResponse(502, "<html>Bad gateway</html>") })).findRoutes(request) },
        )
    }

    @Test fun `an error with an unusable code is still a service error`() {
        assertEquals(
            RoutingFailure.SERVICE_ERROR,
            failureOf { provider(Scripted({ HttpResponse(400, """{"error_code":"oops"}""") })).findRoutes(request) },
        )
    }

    @Test fun `no connection is a network failure`() {
        assertEquals(
            RoutingFailure.NETWORK,
            failureOf { provider(HttpPost { _, _ -> throw IOException("offline") }).findRoutes(request) },
        )
    }

    @Test fun `unreadable success responses are bad responses`() {
        listOf("not json", "[]", "{}", """{"trip":{"legs":[]}}""", """{"trip":{"legs":[{"shape":"!!"}]}}""").forEach { body ->
            assertEquals(
                body,
                RoutingFailure.BAD_RESPONSE,
                failureOf { provider(Scripted({ HttpResponse(200, body) })).findRoutes(request) },
            )
        }
    }

    @Test fun `a failed distance search keeps the other routes and says so`() = runTest {
        val outcome = provider(Scripted({ alternates }, { HttpResponse(500, "") })).findRoutes(request)
        assertEquals(3, outcome.routes.size)
        assertTrue(outcome.distanceSearchFailed)
    }

    private fun TestScope.providerAt(baseUrl: String, http: HttpPost) = ValhallaRoutingProvider(
        baseUrl = baseUrl,
        apiKey = "secret",
        info = info,
        http = http,
        pacer = RequestPacer(1200) { testScheduler.currentTime },
    )

    @Test fun `insecure endpoints are refused before anything is sent, including the key`() {
        listOf("http://routing.example", "https://user:pass@routing.example", "https://routing.example?x=1", "ftp://a").forEach { url ->
            var sent = false
            val failure = failureOf {
                providerAt(url, HttpPost { _, _ ->
                    sent = true
                    alternates
                }).findRoutes(request)
            }
            assertEquals(url, RoutingFailure.INSECURE_ENDPOINT, failure)
            assertFalse(url, sent)
        }
    }

    @Test fun `redirects are refused, never followed`() {
        listOf(301, 302, 307, 308).forEach { code ->
            val http = Scripted({ HttpResponse(code, "") })
            assertEquals(RoutingFailure.REDIRECT_REFUSED, failureOf { provider(http).findRoutes(request) })
            assertEquals(1, http.urls.size)
        }
    }

    @Test fun `TLS failures are reported distinctly`() {
        assertEquals(
            RoutingFailure.SECURE_CONNECTION,
            failureOf { provider(HttpPost { _, _ -> throw SSLHandshakeException("bad certificate") }).findRoutes(request) },
        )
    }

    @Test fun `timeouts are reported distinctly`() {
        assertEquals(
            RoutingFailure.TIMEOUT,
            failureOf { provider(HttpPost { _, _ -> throw SocketTimeoutException() }).findRoutes(request) },
        )
    }

    @Test fun `an oversized response is a bad response`() {
        assertEquals(
            RoutingFailure.BAD_RESPONSE,
            failureOf { provider(HttpPost { _, _ -> throw ResponseTooLargeException() }).findRoutes(request) },
        )
    }

    private fun tripBody(points: List<GeoPoint>, seconds: String = "100.0") =
        """{"trip":{"legs":[{"shape":"${PolylineCodec.encode(points)}"}],"summary":{"time":$seconds}}}"""

    @Test fun `geometry nowhere near the pins is rejected`() {
        val elsewhere = listOf(GeoPoint(10.0, 10.0), GeoPoint(10.1, 10.1))
        assertEquals(
            RoutingFailure.BAD_RESPONSE,
            failureOf { provider(Scripted({ HttpResponse(200, tripBody(elsewhere)) })).findRoutes(request) },
        )
    }

    @Test fun `a negative travel time is rejected`() {
        val near = listOf(request.start, request.destination)
        assertEquals(
            RoutingFailure.BAD_RESPONSE,
            failureOf { provider(Scripted({ HttpResponse(200, tripBody(near, "-5")) })).findRoutes(request) },
        )
    }

    @Test fun `a route ending near its pins is accepted`() = runTest {
        val near = listOf(GeoPoint(52.517, 13.414), GeoPoint(52.39, 13.064))
        val outcome = provider(Scripted({ HttpResponse(200, tripBody(near)) }, { HttpResponse(200, tripBody(near)) }))
            .findRoutes(request)
        assertEquals(2, outcome.routes.size)
    }

    @Test fun `failures expose nothing about the request`() {
        val exception = RoutingException(RoutingFailure.NETWORK)
        assertEquals("NETWORK", exception.message)
    }
}
