package com.routemock.app.playback

import com.routemock.app.domain.GeoMath
import com.routemock.app.domain.GeoPoint
import com.routemock.app.domain.RoadRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same playback rules as a straight line, on a path with corners. */
class RoadPlaybackTest {
    private val clock = FakeClock()
    private val corner = GeoPoint(0.0, 0.01)
    private val route = RoadRoute(listOf(GeoPoint(0.0, 0.0), corner, corner, GeoPoint(0.01, 0.01)))
    private val leg = GeoMath.distanceMeters(GeoPoint(0.0, 0.0), corner)

    private fun engine(speed: Double = 10.0, repeat: Boolean = false) = PlaybackEngine(route, speed, clock, repeat)

    @Test fun `travel is measured along the path`() {
        val engine = engine()
        clock.advanceSeconds(leg / 10.0 + 50.0)
        val snapshot = engine.snapshot()
        assertEquals(leg + 500.0, snapshot.distanceTraveledMeters, 1e-3)
        assertEquals(0.01, snapshot.position.longitude, 1e-9)
        assertEquals(500.0, GeoMath.distanceMeters(corner, snapshot.position), 1e-3)
    }

    @Test fun `bearing changes on passing a corner`() {
        val engine = engine()
        clock.advanceSeconds(leg / 10.0 - 5.0)
        assertEquals(90.0, engine.snapshot().bearingDegrees!!, 1e-6)
        clock.advanceSeconds(10.0)
        assertEquals(0.0, engine.snapshot().bearingDegrees!!, 1e-6)
    }

    @Test fun `no step jumps further than speed allows, even across a corner`() {
        val engine = engine()
        var previous = engine.snapshot().position
        var elapsed = 0.0
        while (elapsed < route.lengthMeters / 10.0 - 1.0) {
            clock.advanceSeconds(1.0)
            elapsed += 1.0
            val next = engine.snapshot().position
            assertTrue(GeoMath.distanceMeters(previous, next) <= 10.0 + 1e-3)
            previous = next
        }
    }

    @Test fun `irregular ticks land on the same point as a single late tick`() {
        val sampled = engine()
        val untouched = PlaybackEngine(route, 10.0, clock)
        listOf(0.3, 400.0, 0.01, 900.0, 17.0, 1000.0).forEach {
            clock.advanceSeconds(it)
            sampled.snapshot()
        }
        assertEquals(untouched.snapshot().position, sampled.snapshot().position)
    }

    @Test fun `pausing past a corner holds position at zero speed with no bearing`() {
        val engine = engine()
        clock.advanceSeconds(leg / 10.0 + 10.0)
        engine.pause()
        val held = engine.snapshot()
        clock.advanceSeconds(5000.0)
        val later = engine.snapshot()
        assertEquals(held.position, later.position)
        assertEquals(0.0, later.speedMetersPerSecond, 0.0)
        assertNull(later.bearingDegrees)
    }

    @Test fun `resuming continues along the path, excluding paused time`() {
        val engine = engine()
        clock.advanceSeconds(leg / 10.0 + 10.0)
        engine.pause()
        val held = engine.snapshot().position
        clock.advanceSeconds(5000.0)
        engine.resume()
        assertEquals(held, engine.snapshot().position)
        clock.advanceSeconds(10.0)
        assertEquals(leg + 200.0, engine.snapshot().distanceTraveledMeters, 1e-3)
    }

    @Test fun `changing speed in mid-route keeps position and applies from then on`() {
        val engine = engine()
        clock.advanceSeconds(leg / 10.0 + 10.0)
        val before = engine.snapshot().position
        engine.setSpeed(50.0)
        assertEquals(before, engine.snapshot().position)
        clock.advanceSeconds(4.0)
        assertEquals(leg + 300.0, engine.snapshot().distanceTraveledMeters, 1e-3)
    }

    @Test fun `completion lands exactly on the last point and stays there`() {
        val engine = engine()
        clock.advanceSeconds(route.lengthMeters / 10.0 + 100.0)
        val snapshot = engine.snapshot()
        assertEquals(PlaybackStatus.COMPLETED, snapshot.status)
        assertEquals(route.destination, snapshot.position)
        assertEquals(0.0, snapshot.speedMetersPerSecond, 0.0)
        assertEquals(0.0, snapshot.distanceRemainingMeters, 0.0)
    }

    @Test fun `repeat restarts along the path`() {
        val engine = engine(repeat = true)
        clock.advanceSeconds(route.lengthMeters / 10.0 + 10.0)
        val snapshot = engine.snapshot()
        assertEquals(PlaybackStatus.RUNNING, snapshot.status)
        assertEquals(1L, snapshot.completedLaps)
        assertEquals(100.0, snapshot.distanceTraveledMeters, 1e-3)
    }

    @Test fun `a degenerate road route completes at once without NaN`() {
        val point = GeoPoint(1.0, 2.0)
        val snapshot = PlaybackEngine(RoadRoute(listOf(point, point)), 10.0, clock).snapshot()
        assertEquals(PlaybackStatus.COMPLETED, snapshot.status)
        assertEquals(point, snapshot.position)
    }
}
