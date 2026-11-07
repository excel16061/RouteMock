package com.routemock.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedTest {
    @Test fun `kilometers per hour convert to meters per second`() {
        assertEquals(10.0, SpeedUnit.KMH.toMetersPerSecond(36.0), 1e-9)
    }

    @Test fun `miles per hour use the exact definition`() {
        assertEquals(26.8224, SpeedUnit.MPH.toMetersPerSecond(60.0), 1e-9)
    }

    @Test fun `conversion round-trips`() {
        val metersPerSecond = 13.37
        SpeedUnit.entries.forEach {
            assertEquals(metersPerSecond, it.toMetersPerSecond(it.fromMetersPerSecond(metersPerSecond)), 1e-9)
        }
    }

    @Test fun `switching units keeps the physical speed`() {
        assertEquals("62.14", convertSpeedText("100", SpeedUnit.KMH, SpeedUnit.MPH))
        assertEquals("96.56", convertSpeedText("60", SpeedUnit.MPH, SpeedUnit.KMH))
    }

    @Test fun `switching units leaves unusable text alone`() {
        assertEquals("abc", convertSpeedText("abc", SpeedUnit.KMH, SpeedUnit.MPH))
        assertEquals("", convertSpeedText("", SpeedUnit.KMH, SpeedUnit.MPH))
    }

    @Test fun `whole values lose their trailing zeros`() {
        assertEquals("50", formatSpeedValue(50.0))
        assertEquals("50.5", formatSpeedValue(50.5))
        assertEquals("0", formatSpeedValue(0.0))
    }
}
