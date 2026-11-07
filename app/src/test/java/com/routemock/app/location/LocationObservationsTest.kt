package com.routemock.app.location

import com.routemock.app.domain.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationObservationsTest {
    private val second = 1_000_000_000L
    private val now = 1_000 * second
    private val realPoint = GeoPoint(10.0, 20.0)
    private val mockPoint = GeoPoint(30.0, 40.0)
    private val observations = LocationObservations()
    private val injection = InjectionWindow(startedAtElapsedNanos = now - 100 * second, intendedPosition = mockPoint)

    private fun fix(
        position: GeoPoint = realPoint,
        isMock: Boolean = false,
        elapsed: Long = now,
        accuracy: Float? = 8f,
    ) = RawLocationFix(position, accuracy, 1_700_000_000_000L, elapsed, isMock)

    @Test fun `an unflagged fix becomes the real location with its accuracy and time`() {
        observations.onFix(fix(), now, injection = null)
        val real = observations.realFix.value!!
        assertEquals(realPoint, real.position)
        assertEquals(8f, real.accuracyMeters!!, 0f)
        assertEquals(1_700_000_000_000L, real.timeMillis)
    }

    @Test fun `a mock-flagged fix never becomes the real location`() {
        observations.onFix(fix(mockPoint, isMock = true), now, injection = null)
        observations.onFix(fix(mockPoint, isMock = true), now, injection)
        assertNull(observations.realFix.value)
    }

    @Test fun `a mock-flagged fix cannot overwrite a held real fix`() {
        observations.onFix(fix(elapsed = now - 50 * second), now, injection = null)
        observations.onFix(fix(mockPoint, isMock = true), now, injection)
        assertEquals(realPoint, observations.realFix.value!!.position)
    }

    @Test fun `a cached mock fix after a session is still rejected`() {
        observations.onFix(fix(mockPoint, isMock = true), now, injection = null)
        assertNull(observations.realFix.value)
        assertNull(observations.observedMock.value)
    }

    @Test fun `an unflagged fix sitting on the simulated position is not trusted during injection`() {
        observations.onFix(fix(mockPoint, isMock = false), now, injection)
        assertNull(observations.realFix.value)
    }

    @Test fun `an unflagged fix elsewhere during injection is accepted`() {
        observations.onFix(fix(realPoint, isMock = false), now, injection)
        assertEquals(realPoint, observations.realFix.value!!.position)
        assertNull(observations.observedMock.value)
    }

    @Test fun `an unflagged fix never counts as delivery`() {
        observations.onFix(fix(mockPoint.copy(latitude = 30.5), isMock = false), now, injection)
        assertNull(observations.observedMock.value)
    }

    @Test fun `a flagged fix during injection counts as delivery`() {
        observations.onFix(fix(mockPoint, isMock = true), now, injection)
        assertEquals(mockPoint, observations.observedMock.value!!.position)
    }

    @Test fun `a flagged fix from before the session is not delivery`() {
        observations.onFix(fix(mockPoint, isMock = true, elapsed = now - 200 * second), now, injection)
        assertNull(observations.observedMock.value)
    }

    @Test fun `an older real fix cannot replace a newer one`() {
        observations.onFix(fix(GeoPoint(1.0, 1.0), elapsed = now), now, null)
        observations.onFix(fix(GeoPoint(2.0, 2.0), elapsed = now - 5 * second), now, null)
        assertEquals(GeoPoint(1.0, 1.0), observations.realFix.value!!.position)
    }

    @Test fun `impossible fixes are dropped`() {
        listOf(
            fix(GeoPoint(Double.NaN, 0.0)),
            fix(GeoPoint(91.0, 0.0)),
            fix(elapsed = 0),
            fix(elapsed = now + 60 * second),
        ).forEach { observations.onFix(it, now, null) }
        assertNull(observations.realFix.value)
    }

    @Test fun `a missing accuracy stays missing rather than being invented`() {
        observations.onFix(fix(accuracy = null), now, null)
        assertNull(observations.realFix.value!!.accuracyMeters)
    }

    @Test fun `no fix means real location is unavailable`() {
        assertEquals(RealLocationStatus.Unavailable, realLocationStatus(null, now))
    }

    @Test fun `a fresh fix reads as real and an old one as last real`() {
        val real = RealFix(realPoint, 5f, 0L, now - REAL_FIX_FRESH_SECONDS * second)
        assertEquals(RealLocationStatus.Live(real, REAL_FIX_FRESH_SECONDS), realLocationStatus(real, now))
        val later = now + second
        assertEquals(RealLocationStatus.Last(real, REAL_FIX_FRESH_SECONDS + 1), realLocationStatus(real, later))
    }

    @Test fun `the held fix ages into last real location during injection`() {
        observations.onFix(fix(elapsed = now), now, null)
        repeat(20) { step ->
            observations.onFix(fix(mockPoint, isMock = true, elapsed = now + step * second), now + step * second, injection)
        }
        val status = realLocationStatus(observations.realFix.value, now + 120 * second)
        assertTrue(status is RealLocationStatus.Last)
        assertEquals(120L, (status as RealLocationStatus.Last).ageSeconds)
        assertEquals(realPoint, status.fix.position)
    }

    @Test fun `delivery does not apply without injection`() {
        assertEquals(DeliveryStatus.NotApplicable, deliveryStatus(null, null, true, now))
    }

    @Test fun `simulator state alone never reads as delivered`() {
        assertEquals(DeliveryStatus.Waiting, deliveryStatus(injection, null, canObserve = true, now))
    }

    @Test fun `delivery cannot be claimed without permission to observe it`() {
        val observed = ObservedMockFix(mockPoint, now)
        assertEquals(DeliveryStatus.Unobservable, deliveryStatus(injection, observed, canObserve = false, now))
    }

    @Test fun `an observation from an earlier session does not carry over`() {
        val previous = ObservedMockFix(mockPoint, injection.startedAtElapsedNanos - second)
        assertEquals(DeliveryStatus.Waiting, deliveryStatus(injection, previous, canObserve = true, now))
    }

    @Test fun `a recent observation reports its age and distance from the intended position`() {
        val observed = ObservedMockFix(mockPoint.copy(latitude = 30.001), now - 2 * second)
        val status = deliveryStatus(injection, observed, canObserve = true, now) as DeliveryStatus.Observed
        assertEquals(2L, status.ageSeconds)
        assertEquals(111.2, status.offsetMeters, 0.1)
    }

    @Test fun `an old observation is reported as stale, not as working`() {
        val observed = ObservedMockFix(mockPoint, now - (DELIVERY_FRESH_SECONDS + 1) * second)
        assertTrue(deliveryStatus(injection, observed, canObserve = true, now) is DeliveryStatus.Stale)
    }
}
