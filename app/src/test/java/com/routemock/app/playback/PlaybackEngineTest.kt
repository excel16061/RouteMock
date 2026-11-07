package com.routemock.app.playback

import com.routemock.app.domain.GeoMath
import com.routemock.app.domain.GeoPoint
import com.routemock.app.domain.StationaryRoute
import com.routemock.app.domain.StraightLineRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackEngineTest {
    private val clock = FakeClock()
    private val route = StraightLineRoute(GeoPoint(0.0, 0.0), GeoPoint(0.0, 1.0))
    private val length = route.lengthMeters

    private fun engine(speed: Double = 10.0, repeat: Boolean = false) =
        PlaybackEngine(route, speed, clock, repeat)

    @Test fun `starts at the start with the selected speed and bearing`() {
        val snapshot = engine().snapshot()
        assertEquals(PlaybackStatus.RUNNING, snapshot.status)
        assertEquals(route.start, snapshot.position)
        assertEquals(10.0, snapshot.speedMetersPerSecond, 0.0)
        assertEquals(90.0, snapshot.bearingDegrees!!, 1e-9)
        assertEquals(length, snapshot.distanceRemainingMeters, 1e-9)
        assertEquals(length / 10.0, snapshot.remainingSeconds!!, 1e-9)
    }

    @Test fun `distance equals speed times elapsed time`() {
        val engine = engine(speed = 25.0)
        clock.advanceSeconds(100.0)
        val snapshot = engine.snapshot()
        assertEquals(2500.0, snapshot.distanceTraveledMeters, 1e-6)
        assertEquals(length - 2500.0, snapshot.distanceRemainingMeters, 1e-6)
        assertEquals(2500.0, GeoMath.distanceMeters(route.start, snapshot.position), 1e-3)
    }

    @Test fun `irregular sampling does not drift`() {
        val sampled = engine()
        val untouched = PlaybackEngine(route, 10.0, clock)
        // Bursts, stalls, and a long gap such as a delayed callback.
        listOf(0.001, 0.7, 3.3, 0.0, 120.0, 0.25, 17.0, 0.016, 42.5).forEach {
            clock.advanceSeconds(it)
            sampled.snapshot()
        }
        assertEquals(untouched.snapshot().position, sampled.snapshot().position)
    }

    @Test fun `snapshot is read-only`() {
        val engine = engine()
        clock.advanceSeconds(10.0)
        assertEquals(engine.snapshot(), engine.snapshot())
    }

    @Test fun `wall clock changes do not affect progress`() {
        val engine = engine()
        clock.advanceSeconds(10.0)
        clock.jumpWallClock(-3_600_000L)
        assertEquals(100.0, engine.snapshot().distanceTraveledMeters, 1e-6)
    }

    @Test fun `pausing stops progress and reports zero speed at the same position`() {
        val engine = engine()
        clock.advanceSeconds(50.0)
        val before = engine.snapshot().position
        engine.pause()
        clock.advanceSeconds(1000.0)
        val paused = engine.snapshot()
        assertEquals(PlaybackStatus.PAUSED, paused.status)
        assertEquals(before, paused.position)
        assertEquals(0.0, paused.speedMetersPerSecond, 0.0)
        assertNull(paused.bearingDegrees)
        assertEquals(10.0, paused.selectedSpeedMetersPerSecond, 0.0)
    }

    @Test fun `resuming excludes paused time`() {
        val engine = engine()
        clock.advanceSeconds(50.0)
        engine.pause()
        clock.advanceSeconds(1000.0)
        engine.resume()
        clock.advanceSeconds(25.0)
        val snapshot = engine.snapshot()
        assertEquals(PlaybackStatus.RUNNING, snapshot.status)
        assertEquals(750.0, snapshot.distanceTraveledMeters, 1e-6)
        assertEquals(10.0, snapshot.speedMetersPerSecond, 0.0)
    }

    @Test fun `repeated pause and resume cycles exclude all paused time`() {
        val engine = engine()
        repeat(5) {
            clock.advanceSeconds(10.0)
            engine.pause()
            clock.advanceSeconds(99.0)
            engine.resume()
        }
        assertEquals(500.0, engine.snapshot().distanceTraveledMeters, 1e-6)
    }

    @Test fun `pause and resume are idempotent`() {
        val engine = engine()
        clock.advanceSeconds(10.0)
        engine.pause()
        clock.advanceSeconds(10.0)
        engine.pause()
        engine.resume()
        clock.advanceSeconds(10.0)
        engine.resume() // Must not move the anchor and lose the last 10 seconds.
        assertEquals(200.0, engine.snapshot().distanceTraveledMeters, 1e-6)
    }

    @Test fun `changing speed preserves the current position`() {
        val engine = engine()
        clock.advanceSeconds(100.0)
        val before = engine.snapshot().position
        engine.setSpeed(40.0)
        assertEquals(before, engine.snapshot().position)
    }

    @Test fun `a new speed applies only from the moment it changes`() {
        val engine = engine()
        clock.advanceSeconds(100.0)
        engine.setSpeed(40.0)
        clock.advanceSeconds(10.0)
        val snapshot = engine.snapshot()
        assertEquals(1400.0, snapshot.distanceTraveledMeters, 1e-6)
        assertEquals((length - 1400.0) / 40.0, snapshot.remainingSeconds!!, 1e-6)
    }

    @Test fun `changing speed while paused keeps position and applies on resume`() {
        val engine = engine()
        clock.advanceSeconds(10.0)
        engine.pause()
        val paused = engine.snapshot()
        engine.setSpeed(100.0)
        clock.advanceSeconds(60.0)
        assertEquals(paused.position, engine.snapshot().position)
        assertEquals(0.0, engine.snapshot().speedMetersPerSecond, 0.0)
        engine.resume()
        clock.advanceSeconds(1.0)
        assertEquals(200.0, engine.snapshot().distanceTraveledMeters, 1e-6)
    }

    @Test fun `invalid speeds are refused and leave playback untouched`() {
        val engine = engine()
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach {
            assertThrows(IllegalArgumentException::class.java) { engine.setSpeed(it) }
        }
        assertEquals(10.0, engine.snapshot().speedMetersPerSecond, 0.0)
        assertThrows(IllegalArgumentException::class.java) { PlaybackEngine(route, 0.0, clock) }
    }

    @Test fun `arrival lands exactly on the destination with zero speed`() {
        val engine = engine()
        clock.advanceSeconds(length / 10.0 + 500.0)
        val snapshot = engine.snapshot()
        assertEquals(PlaybackStatus.COMPLETED, snapshot.status)
        assertEquals(route.destination, snapshot.position)
        assertEquals(0.0, snapshot.speedMetersPerSecond, 0.0)
        assertNull(snapshot.bearingDegrees)
        assertEquals(0.0, snapshot.distanceRemainingMeters, 0.0)
        assertEquals(0.0, snapshot.remainingSeconds!!, 0.0)
    }

    @Test fun `just short of arrival is still running`() {
        val engine = engine()
        clock.advanceSeconds(length / 10.0 - 1.0)
        val snapshot = engine.snapshot()
        assertEquals(PlaybackStatus.RUNNING, snapshot.status)
        assertEquals(10.0, snapshot.distanceRemainingMeters, 1e-3)
    }

    @Test fun `completion stays latched`() {
        val engine = engine()
        clock.advanceSeconds(length)
        engine.snapshot()
        engine.pause()
        engine.resume()
        engine.setSpeed(1.0)
        clock.advanceSeconds(100.0)
        val snapshot = engine.snapshot()
        assertEquals(PlaybackStatus.COMPLETED, snapshot.status)
        assertEquals(route.destination, snapshot.position)
    }

    @Test fun `pausing after arriving unobserved does not hide completion`() {
        val engine = engine()
        clock.advanceSeconds(length) // No tick ran in between.
        engine.pause()
        assertEquals(PlaybackStatus.COMPLETED, engine.snapshot().status)
    }

    @Test fun `changing speed after arriving unobserved cannot rewind the route`() {
        val engine = engine()
        clock.advanceSeconds(length)
        engine.setSpeed(0.001)
        assertEquals(route.destination, engine.snapshot().position)
    }

    @Test fun `restart runs the route again from the start`() {
        val engine = engine()
        clock.advanceSeconds(length)
        engine.snapshot()
        engine.restart()
        val restarted = engine.snapshot()
        assertEquals(PlaybackStatus.RUNNING, restarted.status)
        assertEquals(route.start, restarted.position)
        clock.advanceSeconds(10.0)
        assertEquals(100.0, engine.snapshot().distanceTraveledMeters, 1e-6)
    }

    @Test fun `repeat loops by elapsed time and counts passes`() {
        val engine = engine(repeat = true)
        clock.advanceSeconds(length / 10.0 * 2 + 30.0)
        val snapshot = engine.snapshot()
        assertEquals(PlaybackStatus.RUNNING, snapshot.status)
        assertEquals(2L, snapshot.completedLaps)
        assertEquals(300.0, snapshot.distanceTraveledMeters, 1e-3)
        assertEquals(10.0, snapshot.speedMetersPerSecond, 0.0)
    }

    @Test fun `turning repeat off finishes the current pass`() {
        val engine = engine(repeat = true)
        clock.advanceSeconds(length / 10.0 + 30.0)
        engine.setRepeat(false)
        assertEquals(300.0, engine.snapshot().distanceTraveledMeters, 1e-3)
        clock.advanceSeconds(length)
        assertEquals(PlaybackStatus.COMPLETED, engine.snapshot().status)
    }

    @Test fun `turning repeat off while paused keeps the position`() {
        val engine = engine(repeat = true)
        clock.advanceSeconds(length / 10.0 + 30.0)
        engine.pause()
        val before = engine.snapshot().position
        engine.setRepeat(false)
        assertEquals(before, engine.snapshot().position)
    }

    @Test fun `turning repeat on after arrival holds at the destination`() {
        val engine = engine()
        clock.advanceSeconds(length)
        engine.snapshot()
        engine.setRepeat(true)
        val snapshot = engine.snapshot()
        assertEquals(PlaybackStatus.COMPLETED, snapshot.status)
        assertEquals(route.destination, snapshot.position)
    }

    @Test fun `a zero-length route completes at once without NaN`() {
        val point = GeoPoint(10.0, 20.0)
        val snapshot = PlaybackEngine(StraightLineRoute(point, point), 10.0, clock).snapshot()
        assertEquals(PlaybackStatus.COMPLETED, snapshot.status)
        assertEquals(point, snapshot.position)
        assertEquals(0.0, snapshot.speedMetersPerSecond, 0.0)
        assertEquals(0.0, snapshot.remainingSeconds!!, 0.0)
    }

    @Test fun `a zero-length route with repeat also completes`() {
        val point = GeoPoint(10.0, 20.0)
        val engine = PlaybackEngine(StraightLineRoute(point, point), 10.0, clock, repeat = true)
        clock.advanceSeconds(100.0)
        val snapshot = engine.snapshot()
        assertEquals(PlaybackStatus.COMPLETED, snapshot.status)
        assertFalse(snapshot.position.latitude.isNaN())
    }

    @Test fun `stationary keeps supplying its point with zero speed until stopped`() {
        val point = GeoPoint(48.8584, 2.2945)
        val engine = PlaybackEngine(StationaryRoute(point), 0.0, clock)
        clock.advanceSeconds(86_400.0)
        val snapshot = engine.snapshot()
        assertEquals(PlaybackStatus.RUNNING, snapshot.status)
        assertEquals(point, snapshot.position)
        assertEquals(0.0, snapshot.speedMetersPerSecond, 0.0)
        assertNull(snapshot.bearingDegrees)
        assertNull(snapshot.remainingSeconds)
        assertTrue(snapshot.isStationary)
    }

    @Test fun `stationary can pause and resume without moving`() {
        val point = GeoPoint(48.8584, 2.2945)
        val engine = PlaybackEngine(StationaryRoute(point), 0.0, clock)
        engine.pause()
        assertEquals(PlaybackStatus.PAUSED, engine.snapshot().status)
        engine.resume()
        assertEquals(point, engine.snapshot().position)
        assertEquals(PlaybackStatus.RUNNING, engine.snapshot().status)
    }

    @Test fun `moving snapshots always carry a bearing`() {
        val engine = engine()
        clock.advanceSeconds(length / 20.0)
        assertNotNull(engine.snapshot().bearingDegrees)
    }
}
