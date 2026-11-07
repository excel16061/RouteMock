package com.routemock.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolylineCodecTest {
    @Test fun `geometry survives a round trip exactly at six decimals`() {
        val points = listOf(
            GeoPoint(52.516700, 13.414200),
            GeoPoint(-33.856784, 151.215297),
            GeoPoint(0.0, 0.0),
            GeoPoint(-0.000001, 179.999999),
        )
        assertEquals(points, PolylineCodec.decode(PolylineCodec.encode(points)))
    }

    @Test fun `empty input is an empty path`() {
        assertEquals(emptyList<GeoPoint>(), PolylineCodec.decode(""))
        assertEquals("", PolylineCodec.encode(emptyList()))
    }

    @Test fun `a truncated polyline is rejected whole rather than partly used`() {
        val encoded = PolylineCodec.encode(listOf(GeoPoint(52.5, 13.4), GeoPoint(52.6, 13.5)))
        assertTrue(PolylineCodec.decode(encoded.dropLast(1)).isEmpty())
    }

    @Test fun `characters outside the format are rejected`() {
        assertTrue(PolylineCodec.decode("\u0001\u0002").isEmpty())
    }

    @Test fun `coordinates outside the valid range are rejected`() {
        assertTrue(PolylineCodec.decode(PolylineCodec.encode(listOf(GeoPoint(95.0, 0.0)))).isEmpty())
    }
}
