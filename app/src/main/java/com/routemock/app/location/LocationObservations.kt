package com.routemock.app.location

import com.routemock.app.domain.GeoMath
import com.routemock.app.domain.GeoPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A fix as Android reported it, before anything has decided what it is. */
data class RawLocationFix(
    val position: GeoPoint,
    val accuracyMeters: Float?,
    val timeMillis: Long,
    val elapsedRealtimeNanos: Long,
    /** Android's own mock indicator. */
    val isMock: Boolean,
)

/** A fix Android did not flag as mock. Held in memory only, and never logged. */
data class RealFix(
    val position: GeoPoint,
    val accuracyMeters: Float?,
    val timeMillis: Long,
    val elapsedRealtimeNanos: Long,
)

/** A mock-flagged fix Android actually handed back during injection. */
data class ObservedMockFix(val position: GeoPoint, val elapsedRealtimeNanos: Long)

/** The injection session running when a fix arrived. */
data class InjectionWindow(val startedAtElapsedNanos: Long, val intendedPosition: GeoPoint)

/**
 * Routes each fix to exactly one place. The mock flag decides first, so a flagged
 * fix has no path to the real marker, and an unflagged one cannot count as delivery.
 */
class LocationObservations {
    private val mutableRealFix = MutableStateFlow<RealFix?>(null)
    private val mutableObservedMock = MutableStateFlow<ObservedMockFix?>(null)

    val realFix: StateFlow<RealFix?> = mutableRealFix.asStateFlow()
    val observedMock: StateFlow<ObservedMockFix?> = mutableObservedMock.asStateFlow()

    fun onFix(fix: RawLocationFix, nowElapsedNanos: Long, injection: InjectionWindow?) {
        val plausible = fix.position.isValid &&
            fix.elapsedRealtimeNanos > 0 &&
            fix.elapsedRealtimeNanos <= nowElapsedNanos + CLOCK_TOLERANCE_NANOS
        if (!plausible) return

        if (fix.isMock) {
            recordDelivery(fix, injection)
        } else {
            recordReal(fix, injection)
        }
    }

    private fun recordDelivery(fix: RawLocationFix, injection: InjectionWindow?) {
        // Outside a session, or from before it, this is only a stale cached fix.
        if (injection == null || fix.elapsedRealtimeNanos < injection.startedAtElapsedNanos) return
        val latest = mutableObservedMock.value
        if (latest == null || fix.elapsedRealtimeNanos > latest.elapsedRealtimeNanos) {
            mutableObservedMock.value = ObservedMockFix(fix.position, fix.elapsedRealtimeNanos)
        }
    }

    private fun recordReal(fix: RawLocationFix, injection: InjectionWindow?) {
        // Belt and braces: an unflagged fix sitting on the simulated position is more
        // likely a mock that lost its flag on the way here than a coincidence.
        if (injection != null &&
            GeoMath.distanceMeters(fix.position, injection.intendedPosition) < SAME_POSITION_METERS
        ) {
            return
        }
        val latest = mutableRealFix.value
        if (latest == null || fix.elapsedRealtimeNanos > latest.elapsedRealtimeNanos) {
            mutableRealFix.value = RealFix(
                position = fix.position,
                accuracyMeters = fix.accuracyMeters,
                timeMillis = fix.timeMillis,
                elapsedRealtimeNanos = fix.elapsedRealtimeNanos,
            )
        }
    }

    private companion object {
        const val CLOCK_TOLERANCE_NANOS = 1_000_000_000L
        const val SAME_POSITION_METERS = 1.0
    }
}

/** How the real-location marker should describe what it has. */
sealed interface RealLocationStatus {
    data object Unavailable : RealLocationStatus
    data class Live(val fix: RealFix, val ageSeconds: Long) : RealLocationStatus
    data class Last(val fix: RealFix, val ageSeconds: Long) : RealLocationStatus
}

const val REAL_FIX_FRESH_SECONDS = 30L

fun realLocationStatus(fix: RealFix?, nowElapsedNanos: Long): RealLocationStatus {
    if (fix == null) return RealLocationStatus.Unavailable
    val age = ageSeconds(fix.elapsedRealtimeNanos, nowElapsedNanos)
    return if (age <= REAL_FIX_FRESH_SECONDS) RealLocationStatus.Live(fix, age) else RealLocationStatus.Last(fix, age)
}

/** What can honestly be said about delivery, as opposed to the simulator's own state. */
sealed interface DeliveryStatus {
    data object NotApplicable : DeliveryStatus
    data object Unobservable : DeliveryStatus
    data object Waiting : DeliveryStatus
    data class Observed(val fix: ObservedMockFix, val ageSeconds: Long, val offsetMeters: Double) : DeliveryStatus
    data class Stale(val fix: ObservedMockFix, val ageSeconds: Long) : DeliveryStatus
}

const val DELIVERY_FRESH_SECONDS = 5L

fun deliveryStatus(
    injection: InjectionWindow?,
    observed: ObservedMockFix?,
    canObserve: Boolean,
    nowElapsedNanos: Long,
): DeliveryStatus {
    if (injection == null) return DeliveryStatus.NotApplicable
    if (!canObserve) return DeliveryStatus.Unobservable
    // A fix from an earlier session says nothing about this one.
    if (observed == null || observed.elapsedRealtimeNanos < injection.startedAtElapsedNanos) {
        return DeliveryStatus.Waiting
    }
    val age = ageSeconds(observed.elapsedRealtimeNanos, nowElapsedNanos)
    return if (age <= DELIVERY_FRESH_SECONDS) {
        DeliveryStatus.Observed(observed, age, GeoMath.distanceMeters(observed.position, injection.intendedPosition))
    } else {
        DeliveryStatus.Stale(observed, age)
    }
}

private fun ageSeconds(fixElapsedNanos: Long, nowElapsedNanos: Long): Long =
    ((nowElapsedNanos - fixElapsedNanos) / 1_000_000_000L).coerceAtLeast(0L)
