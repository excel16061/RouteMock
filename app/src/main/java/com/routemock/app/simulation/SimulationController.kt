package com.routemock.app.simulation

import com.routemock.app.domain.Route
import com.routemock.app.domain.SpeedUnit
import com.routemock.app.playback.LocationFixFactory
import com.routemock.app.playback.PlaybackEngine
import com.routemock.app.playback.PlaybackSnapshot
import com.routemock.app.playback.SimulationClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class SessionKind { PREVIEW, INJECTION }

data class SimulationConfig(
    val route: Route,
    val speedMetersPerSecond: Double,
    val repeat: Boolean,
    val displayUnit: SpeedUnit,
)

/** Why the most recent session is over, shown until the user dismisses it. */
sealed interface SessionEnd {
    data class Stopped(val kind: SessionKind, val cleanupComplete: Boolean) : SessionEnd
    data class NotAuthorized(val cleanupComplete: Boolean) : SessionEnd
    data class Failed(val detail: String, val cleanupComplete: Boolean) : SessionEnd

    /** The process died mid-session. Simulation was deliberately not restarted. */
    data class Interrupted(val cleanupComplete: Boolean) : SessionEnd
}

sealed interface SimulationState {
    data class Idle(val lastEnd: SessionEnd? = null) : SimulationState
    data class Active(
        val kind: SessionKind,
        val snapshot: PlaybackSnapshot,
        val providers: List<String>,
        val displayUnit: SpeedUnit,
        /** The route being played, which the draft being edited may differ from. */
        val route: Route,
        val startedAtElapsedNanos: Long,
    ) : SimulationState
}

/**
 * Owns the single running session. It lives for the whole process, outside any
 * Activity or ViewModel, so rotation and navigation cannot affect playback.
 */
class SimulationController(
    private val clock: SimulationClock,
    private val scope: CoroutineScope,
    private val tickIntervalMillis: Long = DEFAULT_TICK_INTERVAL_MILLIS,
    /** Checked at the boundary itself, so no caller can start injection without it. */
    private val injectionAllowed: () -> Boolean = { true },
) {
    private class Session(
        val kind: SessionKind,
        val engine: PlaybackEngine,
        val sink: LocationSink,
        val providers: List<String>,
        val displayUnit: SpeedUnit,
        val startedAtElapsedNanos: Long,
    ) {
        var ticker: Job? = null
    }

    private val lock = Any()
    private var session: Session? = null
    private var pendingInjection: SimulationConfig? = null
    private val mutableState = MutableStateFlow<SimulationState>(SimulationState.Idle())

    val state: StateFlow<SimulationState> = mutableState.asStateFlow()

    /**
     * Hands [config] to the foreground service, which calls [start] once it is in
     * the foreground. Returns false if a session is already running or starting.
     */
    fun requestInjection(config: SimulationConfig): Boolean = synchronized(lock) {
        if (session != null || pendingInjection != null) return false
        if (!injectionAllowed()) {
            mutableState.value = SimulationState.Idle(SessionEnd.Failed(CONSENT_REQUIRED, cleanupComplete = true))
            return false
        }
        pendingInjection = config
        mutableState.value = SimulationState.Idle()
        true
    }

    fun takePendingInjection(): SimulationConfig? = synchronized(lock) {
        pendingInjection.also { pendingInjection = null }
    }

    /** The service could not reach the foreground, so injection never began. */
    fun reportStartFailure(detail: String) = synchronized(lock) {
        pendingInjection = null
        if (session == null) {
            mutableState.value = SimulationState.Idle(SessionEnd.Failed(detail, cleanupComplete = true))
        }
    }

    fun reportInterrupted(cleanupComplete: Boolean) = synchronized(lock) {
        if (session == null) {
            mutableState.value = SimulationState.Idle(SessionEnd.Interrupted(cleanupComplete))
        }
    }

    fun start(config: SimulationConfig, sink: LocationSink, kind: SessionKind): Boolean = synchronized(lock) {
        if (session != null) return false
        if (kind == SessionKind.INJECTION && !injectionAllowed()) {
            mutableState.value = SimulationState.Idle(SessionEnd.Failed(CONSENT_REQUIRED, cleanupComplete = true))
            return false
        }

        val providers = try {
            sink.open()
        } catch (e: Exception) {
            val cleaned = closeQuietly(sink)
            mutableState.value = SimulationState.Idle(endFor(e, cleaned))
            return false
        }

        val engine = PlaybackEngine(config.route, config.speedMetersPerSecond, clock, config.repeat)
        val started = Session(kind, engine, sink, providers, config.displayUnit, clock.elapsedRealtimeNanos())
        session = started
        emit(started)
        if (session !== started) return false // The first fix was rejected.

        started.ticker = scope.launch {
            while (isActive) {
                delay(tickIntervalMillis)
                synchronized(lock) { if (session === started) emit(started) }
            }
        }
        true
    }

    fun pause() = command { it.pause() }
    fun resume() = command { it.resume() }
    fun restart() = command { it.restart() }
    fun setRepeat(enabled: Boolean) = command { it.setRepeat(enabled) }
    fun setSpeed(metersPerSecond: Double) = command { it.setSpeed(metersPerSecond) }

    fun stop() = synchronized(lock) {
        val current = session ?: return@synchronized
        val cleaned = release(current)
        mutableState.value = SimulationState.Idle(SessionEnd.Stopped(current.kind, cleaned))
    }

    fun dismissLastEnd() = synchronized(lock) {
        if (session == null) mutableState.value = SimulationState.Idle()
    }

    /** Commands publish at once, so a pause reaches listeners without waiting for a tick. */
    private fun command(action: (PlaybackEngine) -> Unit) = synchronized(lock) {
        val current = session ?: return@synchronized
        action(current.engine)
        emit(current)
    }

    private fun emit(current: Session) {
        val snapshot = current.engine.snapshot()
        try {
            current.sink.publish(LocationFixFactory.create(snapshot, clock))
        } catch (e: Exception) {
            val cleaned = release(current)
            mutableState.value = SimulationState.Idle(endFor(e, cleaned))
            return
        }
        mutableState.value = SimulationState.Active(
            kind = current.kind,
            snapshot = snapshot,
            providers = current.providers,
            displayUnit = current.displayUnit,
            route = current.engine.route,
            startedAtElapsedNanos = current.startedAtElapsedNanos,
        )
    }

    private fun release(current: Session): Boolean {
        session = null
        current.ticker?.cancel()
        return closeQuietly(current.sink)
    }

    private fun closeQuietly(sink: LocationSink): Boolean = try {
        sink.close()
    } catch (_: Exception) {
        false
    }

    private fun endFor(error: Exception, cleanupComplete: Boolean): SessionEnd = when (error) {
        is MockNotAuthorizedException -> SessionEnd.NotAuthorized(cleanupComplete)
        else -> SessionEnd.Failed(error.message ?: error.javaClass.simpleName, cleanupComplete)
    }

    companion object {
        const val DEFAULT_TICK_INTERVAL_MILLIS = 1000L
        const val CONSENT_REQUIRED = "Mock location consent is required"
    }
}

/** True when the last session ended without Stop, so the app should open on its recovery notice. */
fun opensOnSimulation(state: SimulationState): Boolean =
    (state as? SimulationState.Idle)?.lastEnd is SessionEnd.Interrupted
