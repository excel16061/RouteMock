package com.routemock.app.simulation

import com.routemock.app.domain.GeoPoint
import com.routemock.app.domain.PolylineCodec
import com.routemock.app.domain.RoadRoute
import com.routemock.app.domain.RoadRouteSelection
import com.routemock.app.domain.RouteDraft
import com.routemock.app.domain.RouteMode
import com.routemock.app.domain.RouteValidator
import com.routemock.app.domain.SelectionTarget
import com.routemock.app.domain.SpeedUnit
import com.routemock.app.domain.StationaryRoute
import com.routemock.app.domain.StraightLineRoute
import com.routemock.app.domain.TrafficBasis
import com.routemock.app.domain.withMapPoint
import com.routemock.app.playback.LocationFix
import com.routemock.app.playback.PlaybackStatus
import com.routemock.app.playback.SimulationClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SimulationControllerTest {
    private class RecordingSink(
        private val openError: Exception? = null,
        private val closeResult: Boolean = true,
    ) : LocationSink {
        val fixes = mutableListOf<LocationFix>()
        var publishError: Exception? = null
        var closeCalls = 0

        override fun open(): List<String> {
            openError?.let { throw it }
            return listOf("gps")
        }

        override fun publish(fix: LocationFix) {
            publishError?.let { throw it }
            fixes += fix
        }

        override fun close(): Boolean {
            closeCalls++
            return closeResult
        }
    }

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    // Virtual coroutine time drives the clock, so ticks and playback stay in step.
    private val clock = object : SimulationClock {
        override fun elapsedRealtimeNanos() = dispatcher.scheduler.currentTime * 1_000_000 + 1
        override fun currentTimeMillis() = 1_700_000_000_000L + dispatcher.scheduler.currentTime
    }
    private val controller = SimulationController(clock, scope)
    private val route = StraightLineRoute(GeoPoint(0.0, 0.0), GeoPoint(0.0, 1.0))
    private val config = SimulationConfig(route, 10.0, repeat = false, displayUnit = SpeedUnit.KMH)

    private fun active() = controller.state.value as SimulationState.Active
    private fun lastEnd() = (controller.state.value as SimulationState.Idle).lastEnd

    private fun TestScope.advanceSeconds(seconds: Long) {
        advanceTimeBy(seconds * 1000)
        runCurrent()
    }

    @Test fun `starting publishes a fix immediately and ticks once a second`() = test {
        val sink = RecordingSink()
        assertTrue(controller.start(config, sink, SessionKind.INJECTION))
        assertEquals(1, sink.fixes.size)
        advanceSeconds(5)
        assertEquals(6, sink.fixes.size)
        assertEquals(50.0, active().snapshot.distanceTraveledMeters, 1e-6)
        assertEquals(listOf("gps"), active().providers)
        controller.stop()
    }

    @Test fun `fix timestamps increase monotonically`() = test {
        val sink = RecordingSink()
        controller.start(config, sink, SessionKind.INJECTION)
        advanceSeconds(5)
        val times = sink.fixes.map { it.elapsedRealtimeNanos }
        assertEquals(times.sorted().distinct(), times)
        controller.stop()
    }

    @Test fun `paused sessions keep supplying the same position at zero speed`() = test {
        val sink = RecordingSink()
        controller.start(config, sink, SessionKind.INJECTION)
        advanceSeconds(3)
        controller.pause()
        val pausedAt = sink.fixes.last().position
        assertEquals(0f, sink.fixes.last().speedMetersPerSecond, 0f) // Published at once.
        val countAtPause = sink.fixes.size
        advanceSeconds(10)
        assertEquals(countAtPause + 10, sink.fixes.size)
        assertTrue(sink.fixes.drop(countAtPause).all { it.position == pausedAt && it.speedMetersPerSecond == 0f })
        controller.stop()
    }

    @Test fun `resume continues from the paused position`() = test {
        controller.start(config, RecordingSink(), SessionKind.PREVIEW)
        advanceSeconds(3)
        controller.pause()
        advanceSeconds(100)
        controller.resume()
        advanceSeconds(2)
        assertEquals(50.0, active().snapshot.distanceTraveledMeters, 1e-6)
        controller.stop()
    }

    @Test fun `stationary sessions keep publishing until stopped`() = test {
        val point = GeoPoint(1.0, 2.0)
        val sink = RecordingSink()
        controller.start(config.copy(route = StationaryRoute(point)), sink, SessionKind.INJECTION)
        advanceSeconds(3600)
        assertEquals(3601, sink.fixes.size)
        assertTrue(sink.fixes.all { it.position == point })
        controller.stop()
        val count = sink.fixes.size
        advanceSeconds(10)
        assertEquals(count, sink.fixes.size)
    }

    @Test fun `completed sessions hold the destination and keep the session open`() = test {
        val short = StraightLineRoute(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.0001))
        val sink = RecordingSink()
        controller.start(config.copy(route = short), sink, SessionKind.INJECTION)
        advanceSeconds(10)
        assertEquals(PlaybackStatus.COMPLETED, active().snapshot.status)
        val count = sink.fixes.size
        advanceSeconds(3)
        assertEquals(count + 3, sink.fixes.size)
        assertEquals(short.destination, sink.fixes.last().position)
        controller.stop()
    }

    @Test fun `stop closes the sink and stops publishing`() = test {
        val sink = RecordingSink()
        controller.start(config, sink, SessionKind.INJECTION)
        controller.stop()
        assertEquals(1, sink.closeCalls)
        assertEquals(SessionEnd.Stopped(SessionKind.INJECTION, cleanupComplete = true), lastEnd())
        val count = sink.fixes.size
        advanceSeconds(10)
        assertEquals(count, sink.fixes.size)
    }

    @Test fun `stopping twice cleans up once`() = test {
        val sink = RecordingSink()
        controller.start(config, sink, SessionKind.INJECTION)
        controller.stop()
        controller.stop()
        assertEquals(1, sink.closeCalls)
    }

    @Test fun `failed cleanup is reported`() = test {
        controller.start(config, RecordingSink(closeResult = false), SessionKind.INJECTION)
        controller.stop()
        assertEquals(SessionEnd.Stopped(SessionKind.INJECTION, cleanupComplete = false), lastEnd())
    }

    @Test fun `missing authorization at start cleans up and names the cause`() = test {
        val sink = RecordingSink(openError = MockNotAuthorizedException())
        assertFalse(controller.start(config, sink, SessionKind.INJECTION))
        assertEquals(1, sink.closeCalls)
        assertEquals(SessionEnd.NotAuthorized(cleanupComplete = true), lastEnd())
    }

    @Test fun `losing authorization mid-session cleans up and stops ticking`() = test {
        val sink = RecordingSink()
        controller.start(config, sink, SessionKind.INJECTION)
        advanceSeconds(2)
        sink.publishError = MockNotAuthorizedException()
        advanceSeconds(1)
        assertEquals(SessionEnd.NotAuthorized(cleanupComplete = true), lastEnd())
        assertEquals(1, sink.closeCalls)
        sink.publishError = null
        val count = sink.fixes.size
        advanceSeconds(5)
        assertEquals(count, sink.fixes.size)
    }

    @Test fun `other provider errors surface their detail`() = test {
        val sink = RecordingSink()
        controller.start(config, sink, SessionKind.INJECTION)
        sink.publishError = IllegalArgumentException("provider gone")
        advanceSeconds(1)
        assertEquals(SessionEnd.Failed("provider gone", cleanupComplete = true), lastEnd())
    }

    @Test fun `a rejected first fix does not leave a session running`() = test {
        val sink = RecordingSink().apply { publishError = MockNotAuthorizedException() }
        assertFalse(controller.start(config, sink, SessionKind.INJECTION))
        assertEquals(SessionEnd.NotAuthorized(cleanupComplete = true), lastEnd())
        assertTrue(controller.start(config, RecordingSink(), SessionKind.INJECTION))
        controller.stop()
    }

    private fun guarded(allowed: () -> Boolean) = SimulationController(clock, scope, injectionAllowed = allowed)

    @Test fun `injection without consent is refused at the boundary, touching nothing`() = test {
        val guardedController = guarded { false }
        val sink = RecordingSink()
        assertFalse(guardedController.start(config, sink, SessionKind.INJECTION))
        assertEquals(0, sink.fixes.size)
        assertEquals(0, sink.closeCalls)
        val end = (guardedController.state.value as SimulationState.Idle).lastEnd as SessionEnd.Failed
        assertEquals(SimulationController.CONSENT_REQUIRED, end.detail)
    }

    @Test fun `a service request without consent is refused before it is queued`() = test {
        val guardedController = guarded { false }
        assertFalse(guardedController.requestInjection(config))
        assertNull(guardedController.takePendingInjection())
    }

    @Test fun `consent lost between request and start still blocks injection`() = test {
        var allowed = true
        val guardedController = guarded { allowed }
        assertTrue(guardedController.requestInjection(config))
        allowed = false
        val sink = RecordingSink()
        assertFalse(guardedController.start(guardedController.takePendingInjection()!!, sink, SessionKind.INJECTION))
        assertEquals(0, sink.fixes.size)
    }

    @Test fun `an in-app preview needs no injection consent`() = test {
        val guardedController = guarded { false }
        assertTrue(guardedController.start(config, PreviewSink, SessionKind.PREVIEW))
        guardedController.stop()
    }

    @Test fun `only one session runs at a time`() = test {
        val first = RecordingSink()
        val second = RecordingSink()
        controller.start(config, first, SessionKind.PREVIEW)
        assertFalse(controller.start(config, second, SessionKind.INJECTION))
        assertEquals(0, second.fixes.size)
        assertFalse(controller.requestInjection(config))
        controller.stop()
    }

    @Test fun `a pending injection is handed over exactly once`() = test {
        assertTrue(controller.requestInjection(config))
        assertFalse(controller.requestInjection(config))
        assertEquals(config, controller.takePendingInjection())
        assertNull(controller.takePendingInjection())
    }

    @Test fun `a failed service start clears the pending injection`() = test {
        controller.requestInjection(config)
        controller.reportStartFailure("not allowed")
        assertNull(controller.takePendingInjection())
        assertEquals(SessionEnd.Failed("not allowed", cleanupComplete = true), lastEnd())
    }

    @Test fun `commands without a session do nothing`() = test {
        controller.pause()
        controller.resume()
        controller.setSpeed(5.0)
        controller.stop()
        assertEquals(SimulationState.Idle(), controller.state.value)
    }

    @Test fun `an interrupted session is reported and nothing restarts`() = test {
        controller.reportInterrupted(cleanupComplete = true)
        advanceSeconds(10)
        assertEquals(SessionEnd.Interrupted(cleanupComplete = true), lastEnd())
        controller.dismissLastEnd()
        assertEquals(SimulationState.Idle(), controller.state.value)
    }

    @Test fun `previews never touch anything but the sink they are given`() = test {
        assertTrue(controller.start(config, PreviewSink, SessionKind.PREVIEW))
        assertEquals(SessionKind.PREVIEW, active().kind)
        assertEquals(emptyList<String>(), active().providers)
        controller.stop()
        assertEquals(SessionEnd.Stopped(SessionKind.PREVIEW, cleanupComplete = true), lastEnd())
    }

    @Test fun `editing the draft leaves the running session on its own route`() = test {
        var draft = RouteDraft(
            startLatitude = "0",
            startLongitude = "0",
            destinationLatitude = "0",
            destinationLongitude = "1",
            speed = "36",
            speedUnit = SpeedUnit.KMH,
        )
        val validated = RouteValidator.validate(draft).result!!
        val sink = RecordingSink()
        controller.start(
            SimulationConfig(validated.route, validated.speedMetersPerSecond, false, SpeedUnit.KMH),
            sink,
            SessionKind.INJECTION,
        )
        advanceSeconds(5)
        val before = active()

        // What tapping and dragging pins does, including a change of mode.
        draft = draft.withMapPoint(SelectionTarget.START, GeoPoint(40.0, 50.0))
        draft = draft.withMapPoint(SelectionTarget.DESTINATION, GeoPoint(41.0, 51.0))
        draft = draft.copy(mode = RouteMode.STATIONARY, speed = "999")

        assertEquals(before, active())
        advanceSeconds(5)
        assertEquals(validated.route, active().route)
        assertEquals(100.0, active().snapshot.distanceTraveledMeters, 1e-6)
        assertTrue(sink.fixes.all { it.position.latitude == 0.0 })
        controller.stop()
    }

    @Test fun `a road route plays its geometry through the sink`() = test {
        val road = RoadRoute(listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.01), GeoPoint(0.01, 0.01)))
        val sink = RecordingSink()
        controller.start(config.copy(route = road), sink, SessionKind.INJECTION)
        advanceSeconds(10)
        assertEquals(100.0, active().snapshot.distanceTraveledMeters, 1e-6)
        assertEquals(road, active().route)
        assertTrue(sink.fixes.all { it.position.latitude == 0.0 }) // Still on the first leg.
        assertEquals(90f, sink.fixes.last().bearingDegrees!!, 1e-3f)
        controller.stop()
    }

    @Test fun `selecting a new road route leaves the running one untouched`() = test {
        val running = RoadRoute(listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.01), GeoPoint(0.01, 0.01)))
        val sink = RecordingSink()
        controller.start(config.copy(route = running), sink, SessionKind.INJECTION)
        advanceSeconds(5)
        val position = active().snapshot.position

        // What selecting a candidate does: a new draft and nothing else.
        val newShape = listOf(GeoPoint(5.0, 5.0), GeoPoint(5.0, 5.1))
        val draft = RouteDraft(mode = RouteMode.ROAD).copy(
            roadRoute = RoadRouteSelection(
                encodedShape = PolylineCodec.encode(newShape),
                estimatedSeconds = 1.0,
                trafficBasis = TrafficBasis.ROAD_SPEED_MODEL,
                providerName = "Test",
                attribution = "",
                requestedStart = newShape.first(),
                requestedDestination = newShape.last(),
                calculatedAtMillis = 1L,
            ),
        )

        assertEquals(position, active().snapshot.position)
        advanceSeconds(5)
        assertEquals(running, active().route)
        assertEquals(100.0, active().snapshot.distanceTraveledMeters, 1e-6)
        assertTrue(draft.roadRoute!!.route != active().route)
        controller.stop()
    }

    @Test fun `estimated travel time never changes playback speed`() = test {
        val road = RoadRoute(listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.01), GeoPoint(0.01, 0.01)))
        controller.start(config.copy(route = road, speedMetersPerSecond = 7.0), RecordingSink(), SessionKind.PREVIEW)
        advanceSeconds(3)
        assertEquals(7.0, active().snapshot.speedMetersPerSecond, 0.0)
        assertEquals(21.0, active().snapshot.distanceTraveledMeters, 1e-6)
        controller.stop()
    }

    @Test fun `a stopped session does not restart when the draft changes`() = test {
        val sink = RecordingSink()
        controller.start(config, sink, SessionKind.INJECTION)
        controller.stop()
        val fixes = sink.fixes.size
        RouteDraft().withMapPoint(SelectionTarget.START, GeoPoint(1.0, 2.0))
        advanceSeconds(5)
        assertEquals(fixes, sink.fixes.size)
        assertTrue(controller.state.value is SimulationState.Idle)
    }

    @Test fun `an edited draft takes effect only when the next session starts`() = test {
        controller.start(config, RecordingSink(), SessionKind.PREVIEW)
        val draft = RouteDraft(startLatitude = "5", startLongitude = "6", mode = RouteMode.STATIONARY)
        val next = RouteValidator.validate(draft).result!!
        assertEquals(route, active().route)
        controller.stop()
        controller.start(config.copy(route = next.route), RecordingSink(), SessionKind.PREVIEW)
        assertEquals(next.route, active().route)
        controller.stop()
    }

    @Test fun `active state records the route and when the session began`() = test {
        advanceSeconds(7)
        controller.start(config, RecordingSink(), SessionKind.INJECTION)
        val startedAt = active().startedAtElapsedNanos
        assertEquals(clock.elapsedRealtimeNanos(), startedAt)
        advanceSeconds(3)
        assertEquals(startedAt, active().startedAtElapsedNanos)
        assertEquals(route, active().route)
        controller.stop()
    }

    private fun test(block: TestScope.() -> Unit) = scope.block()
}
