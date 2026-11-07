package com.routemock.app.playback

import com.routemock.app.domain.SpeedUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class FormattersTest {
    @Test fun `durations are hours minutes seconds and round up`() {
        assertEquals("0:00:00", Formatters.duration(0.0))
        assertEquals("0:00:01", Formatters.duration(0.2))
        assertEquals("1:01:01", Formatters.duration(3661.0))
    }

    @Test fun `metric distances switch to kilometers`() {
        assertEquals("999 m", Formatters.distance(999.0, SpeedUnit.KMH))
        assertEquals("1.50 km", Formatters.distance(1500.0, SpeedUnit.KMH))
    }

    @Test fun `imperial distances switch to miles`() {
        assertEquals("100 ft", Formatters.distance(30.48, SpeedUnit.MPH))
        assertEquals("1.00 mi", Formatters.distance(1609.344, SpeedUnit.MPH))
    }

    @Test fun `ages use the largest sensible unit`() {
        assertEquals("59 s", Formatters.age(59))
        assertEquals("2 min", Formatters.age(120))
        assertEquals("1 h 1 min", Formatters.age(3660))
        assertEquals("2 d", Formatters.age(172_800))
    }

    @Test fun `speed displays in the chosen unit`() {
        assertEquals("36.0 km/h", Formatters.speed(10.0, SpeedUnit.KMH))
        assertEquals("60.0 mph", Formatters.speed(26.8224, SpeedUnit.MPH))
    }
}
