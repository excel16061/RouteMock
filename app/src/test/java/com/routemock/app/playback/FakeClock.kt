package com.routemock.app.playback

/** Starts away from zero so nothing can pass by assuming time begins at zero. */
class FakeClock(
    private var elapsedNanos: Long = 5_000_000_000_000L,
    private var wallMillis: Long = 1_700_000_000_000L,
) : SimulationClock {
    override fun elapsedRealtimeNanos() = elapsedNanos
    override fun currentTimeMillis() = wallMillis

    fun advanceSeconds(seconds: Double) {
        elapsedNanos += (seconds * 1e9).toLong()
        wallMillis += (seconds * 1e3).toLong()
    }

    /** The user or network changed the wall clock. Monotonic time is unaffected. */
    fun jumpWallClock(millis: Long) {
        wallMillis += millis
    }
}
