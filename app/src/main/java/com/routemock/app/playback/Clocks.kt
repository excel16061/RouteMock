package com.routemock.app.playback

/** Time sources, injected so playback can be tested without real waiting. */
interface SimulationClock {
    /** Monotonic time including deep sleep. Never affected by wall-clock changes. */
    fun elapsedRealtimeNanos(): Long

    /** Wall-clock UTC time, used only to timestamp emitted locations. */
    fun currentTimeMillis(): Long
}
