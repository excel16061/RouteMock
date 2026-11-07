package com.routemock.app.playback

import com.routemock.app.domain.GeoPoint
import com.routemock.app.domain.StationaryRoute
import com.routemock.app.domain.StraightLineRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationFixFactoryTest {
    private val clock = FakeClock()
    private val route = StraightLineRoute(GeoPoint(0.0, 0.0), GeoPoint(0.0, 1.0))

    @Test fun `a moving fix has every field populated from the current clocks`() {
        val engine = PlaybackEngine(route, 10.0, clock)
        clock.advanceSeconds(5.0)
        val fix = LocationFixFactory.create(engine.snapshot(), clock)
        assertEquals(clock.currentTimeMillis(), fix.timeMillis)
        assertEquals(clock.elapsedRealtimeNanos(), fix.elapsedRealtimeNanos)
        assertTrue(fix.accuracyMeters > 0f)
        assertEquals(10f, fix.speedMetersPerSecond, 0f)
        assertTrue(fix.speedAccuracyMetersPerSecond > 0f)
        assertEquals(90f, fix.bearingDegrees!!, 1e-3f)
        assertTrue(fix.bearingAccuracyDegrees!! > 0f)
    }

    @Test fun `a paused fix has zero speed, no bearing, and fresh timestamps`() {
        val engine = PlaybackEngine(route, 10.0, clock)
        engine.pause()
        clock.advanceSeconds(60.0)
        val fix = LocationFixFactory.create(engine.snapshot(), clock)
        assertEquals(0f, fix.speedMetersPerSecond, 0f)
        assertNull(fix.bearingDegrees)
        assertNull(fix.bearingAccuracyDegrees)
        assertEquals(clock.elapsedRealtimeNanos(), fix.elapsedRealtimeNanos)
    }

    @Test fun `a stationary fix keeps the coordinates exact`() {
        val point = GeoPoint(-33.8567844, 151.2152967)
        val engine = PlaybackEngine(StationaryRoute(point), 0.0, clock)
        val fix = LocationFixFactory.create(engine.snapshot(), clock)
        assertEquals(point, fix.position)
        assertNull(fix.bearingDegrees)
    }
}
