package com.routemock.app.playback

import com.routemock.app.domain.GeoPoint
import com.routemock.app.domain.Route
import com.routemock.app.domain.StationaryRoute
import kotlin.math.floor

enum class PlaybackStatus { RUNNING, PAUSED, COMPLETED }

data class PlaybackSnapshot(
    val status: PlaybackStatus,
    val position: GeoPoint,
    /** Speed to report. Zero unless actually moving. */
    val speedMetersPerSecond: Double,
    /** Direction of travel. Null unless actually moving. */
    val bearingDegrees: Double?,
    val selectedSpeedMetersPerSecond: Double,
    val distanceTraveledMeters: Double,
    val distanceRemainingMeters: Double,
    /** Time to the destination at the selected speed. Null when there is none. */
    val remainingSeconds: Double?,
    val isStationary: Boolean,
    val repeat: Boolean,
    /** Finished passes over the route when repeating. */
    val completedLaps: Long,
)

/**
 * Derives position from monotonic elapsed time.
 *
 * Progress is `anchorDistance + speed * (now - anchorTime)`, evaluated on demand.
 * Nothing accumulates per callback, so late or irregular ticks change how often a
 * position is sampled but never the position itself. Pausing, resuming, and
 * changing speed each move the anchor to the current position.
 *
 * Not thread-safe: the owner must serialize calls.
 */
class PlaybackEngine(
    val route: Route,
    speedMetersPerSecond: Double,
    private val clock: SimulationClock,
    repeat: Boolean = false,
) {
    private var speed = speedMetersPerSecond
    private var anchorTimeNanos = clock.elapsedRealtimeNanos()

    /** Total distance covered, including earlier laps when repeating. */
    private var anchorDistance = 0.0
    private var repeatEnabled = repeat
    private val isStationary = route is StationaryRoute

    var status: PlaybackStatus = PlaybackStatus.RUNNING
        private set

    init {
        require(isStationary || isUsableSpeed(speedMetersPerSecond)) { "Speed must be positive and finite" }
        settleCompletion()
    }

    fun pause() {
        settleCompletion()
        if (status != PlaybackStatus.RUNNING) return
        rebase()
        status = PlaybackStatus.PAUSED
    }

    fun resume() {
        if (status != PlaybackStatus.PAUSED) return
        anchorTimeNanos = clock.elapsedRealtimeNanos()
        status = PlaybackStatus.RUNNING
    }

    fun setSpeed(metersPerSecond: Double) {
        require(isUsableSpeed(metersPerSecond)) { "Speed must be positive and finite" }
        settleCompletion()
        if (status == PlaybackStatus.RUNNING) rebase()
        speed = metersPerSecond
    }

    fun setRepeat(enabled: Boolean) {
        settleCompletion()
        if (repeatEnabled && !enabled && status != PlaybackStatus.COMPLETED) {
            rebase()
            // Without repeat, total distance must be within the current lap.
            anchorDistance = lapDistance(anchorDistance)
        }
        repeatEnabled = enabled
    }

    /** Runs the route again from its start. */
    fun restart() {
        anchorDistance = 0.0
        anchorTimeNanos = clock.elapsedRealtimeNanos()
        status = PlaybackStatus.RUNNING
        settleCompletion()
    }

    fun snapshot(): PlaybackSnapshot {
        settleCompletion()
        val total = totalDistance()
        val lapDistance = lapDistance(total)
        val length = route.lengthMeters
        val moving = status == PlaybackStatus.RUNNING && !isStationary
        val remaining = (length - lapDistance).coerceAtLeast(0.0)
        return PlaybackSnapshot(
            status = status,
            position = route.positionAt(lapDistance),
            speedMetersPerSecond = if (moving) speed else 0.0,
            bearingDegrees = if (moving) route.bearingAt(lapDistance) else null,
            selectedSpeedMetersPerSecond = speed,
            distanceTraveledMeters = lapDistance,
            distanceRemainingMeters = remaining,
            remainingSeconds = if (isStationary) null else remaining / speed,
            isStationary = isStationary,
            repeat = repeatEnabled,
            completedLaps = if (length > 0.0) floor(total / length).toLong() else 0L,
        )
    }

    private fun totalDistance(): Double {
        if (status != PlaybackStatus.RUNNING || isStationary) return anchorDistance
        val elapsedSeconds = (clock.elapsedRealtimeNanos() - anchorTimeNanos) / NANOS_PER_SECOND
        return anchorDistance + speed * elapsedSeconds
    }

    private fun lapDistance(total: Double): Double {
        val length = route.lengthMeters
        return when {
            length <= 0.0 -> 0.0
            status == PlaybackStatus.COMPLETED -> length
            repeatEnabled -> total % length
            else -> total.coerceAtMost(length)
        }
    }

    private fun rebase() {
        anchorDistance = totalDistance()
        anchorTimeNanos = clock.elapsedRealtimeNanos()
    }

    /** Latches arrival. A zero-length route arrives immediately rather than dividing by zero. */
    private fun settleCompletion() {
        if (status != PlaybackStatus.RUNNING || isStationary) return
        val length = route.lengthMeters
        val arrived = totalDistance() >= length
        if (arrived && (!repeatEnabled || length <= 0.0)) {
            anchorDistance = length
            status = PlaybackStatus.COMPLETED
        }
    }

    private fun isUsableSpeed(value: Double) = value.isFinite() && value > 0.0

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
