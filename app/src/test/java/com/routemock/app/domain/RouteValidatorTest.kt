package com.routemock.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteValidatorTest {
    private val valid = RouteDraft(
        mode = RouteMode.STRAIGHT_LINE,
        startLatitude = "37.7749",
        startLongitude = "-122.4194",
        destinationLatitude = "37.8044",
        destinationLongitude = "-122.2712",
        speed = "50",
        speedUnit = SpeedUnit.KMH,
    )

    @Test fun `accepts plain decimals`() {
        listOf("0", "-12.5", "+12.5", "12.", ".5", " 45.25 ").forEach {
            assertNotNull("'$it' should parse", RouteValidator.parseDecimal(it))
        }
    }

    @Test fun `rejects what Double parsing would otherwise allow`() {
        listOf("NaN", "Infinity", "-Infinity", "1e3", "0x1p3", "12d", "12f", "1,5", "", " ", "-", ".", "1.2.3").forEach {
            assertNull("'$it' should not parse", RouteValidator.parseDecimal(it))
        }
    }

    @Test fun `coordinate limits are inclusive`() {
        assertNull(RouteValidator.validateLatitude("90"))
        assertNull(RouteValidator.validateLatitude("-90"))
        assertNull(RouteValidator.validateLongitude("180"))
        assertNull(RouteValidator.validateLongitude("-180"))
    }

    @Test fun `coordinates outside the limits are rejected`() {
        assertEquals(InputError.LATITUDE_OUT_OF_RANGE, RouteValidator.validateLatitude("90.0001"))
        assertEquals(InputError.LATITUDE_OUT_OF_RANGE, RouteValidator.validateLatitude("-90.0001"))
        assertEquals(InputError.LONGITUDE_OUT_OF_RANGE, RouteValidator.validateLongitude("180.0001"))
        assertEquals(InputError.LONGITUDE_OUT_OF_RANGE, RouteValidator.validateLongitude("-180.0001"))
    }

    @Test fun `blank and malformed coordinates have distinct errors`() {
        assertEquals(InputError.REQUIRED, RouteValidator.validateLatitude("  "))
        assertEquals(InputError.NOT_A_NUMBER, RouteValidator.validateLatitude("NaN"))
    }

    @Test fun `speed must be positive`() {
        assertEquals(InputError.SPEED_NOT_POSITIVE, RouteValidator.validateSpeed("0", SpeedUnit.KMH))
        assertEquals(InputError.SPEED_NOT_POSITIVE, RouteValidator.validateSpeed("-5", SpeedUnit.MPH))
    }

    @Test fun `speed limit applies in either unit`() {
        assertNull(RouteValidator.validateSpeed("1200", SpeedUnit.KMH))
        assertEquals(InputError.SPEED_TOO_HIGH, RouteValidator.validateSpeed("1200.1", SpeedUnit.KMH))
        assertNull(RouteValidator.validateSpeed("745", SpeedUnit.MPH))
        assertEquals(InputError.SPEED_TOO_HIGH, RouteValidator.validateSpeed("746", SpeedUnit.MPH))
    }

    @Test fun `speed rejects non-numbers`() {
        assertEquals(InputError.REQUIRED, RouteValidator.validateSpeed("", SpeedUnit.KMH))
        assertEquals(InputError.NOT_A_NUMBER, RouteValidator.validateSpeed("Infinity", SpeedUnit.KMH))
    }

    @Test fun `a valid draft becomes a straight-line route at the converted speed`() {
        val result = RouteValidator.validate(valid.copy(speed = "36")).result!!
        assertTrue(result.route is StraightLineRoute)
        assertEquals(10.0, result.speedMetersPerSecond, 1e-9)
    }

    @Test fun `identical endpoints are rejected with a route error`() {
        val validation = RouteValidator.validate(
            valid.copy(destinationLatitude = valid.startLatitude, destinationLongitude = valid.startLongitude),
        )
        assertNull(validation.result)
        assertEquals(RouteError.IDENTICAL_ENDPOINTS, validation.routeError)
    }

    @Test fun `antipodal endpoints are rejected because no single path exists`() {
        val validation = RouteValidator.validate(
            valid.copy(
                startLatitude = "10",
                startLongitude = "20",
                destinationLatitude = "-10",
                destinationLongitude = "-160",
            ),
        )
        assertNull(validation.result)
        assertEquals(RouteError.ANTIPODAL_ENDPOINTS, validation.routeError)
    }

    @Test fun `each invalid field is reported separately`() {
        val validation = RouteValidator.validate(
            valid.copy(startLatitude = "91", destinationLongitude = "x", speed = "0"),
        )
        assertNull(validation.result)
        assertEquals(InputError.LATITUDE_OUT_OF_RANGE, validation.startLatitudeError)
        assertNull(validation.startLongitudeError)
        assertEquals(InputError.NOT_A_NUMBER, validation.destinationLongitudeError)
        assertEquals(InputError.SPEED_NOT_POSITIVE, validation.speedError)
    }

    @Test fun `invalid speed alone blocks a route`() {
        val validation = RouteValidator.validate(valid.copy(speed = "0"))
        assertNull(validation.result)
        assertNull(validation.routeError)
    }

    @Test fun `stationary mode ignores destination and speed`() {
        val validation = RouteValidator.validate(
            valid.copy(mode = RouteMode.STATIONARY, destinationLatitude = "bad", speed = "0"),
        )
        val route = validation.result!!.route
        assertTrue(route is StationaryRoute)
        assertEquals(GeoPoint(37.7749, -122.4194), route.start)
    }

    @Test fun `stationary mode still validates its coordinates`() {
        val validation = RouteValidator.validate(valid.copy(mode = RouteMode.STATIONARY, startLongitude = "181"))
        assertNull(validation.result)
        assertEquals(InputError.LONGITUDE_OUT_OF_RANGE, validation.startLongitudeError)
    }
}
