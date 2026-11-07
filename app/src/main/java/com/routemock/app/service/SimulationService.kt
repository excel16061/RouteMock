package com.routemock.app.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.routemock.app.RouteMockApp
import com.routemock.app.mock.TestProviderSink
import com.routemock.app.playback.PlaybackStatus
import com.routemock.app.simulation.SessionKind
import com.routemock.app.simulation.SimulationConfig
import com.routemock.app.simulation.SimulationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps injection alive outside the UI and shows the required notification.
 * Playback itself belongs to the controller; this class only hosts it.
 */
class SimulationService : Service() {
    private val app get() = application as RouteMockApp
    private val controller get() = app.simulationController
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val notifications by lazy { SimulationNotifications(this) }
    private var wakeLock: PowerManager.WakeLock? = null
    private var observer: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> beginInjection()
            ACTION_PAUSE -> controller.pause()
            ACTION_RESUME -> controller.resume()
            ACTION_STOP -> controller.stop()
        }
        if (observer == null) stopSelf() // A stale intent with no session to serve.
        // Never restart by itself: a killed simulation stays stopped until the user acts.
        return START_NOT_STICKY
    }

    private fun beginInjection() {
        if (observer != null) return
        // startForeground comes first: Android crashes a service started with
        // startForegroundService that exits without calling it.
        notifications.createChannel()
        try {
            ServiceCompat.startForeground(
                this,
                SimulationNotifications.NOTIFICATION_ID,
                notifications.build(null),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                },
            )
        } catch (e: RuntimeException) {
            controller.reportStartFailure(e.message ?: e.javaClass.simpleName)
            return
        }

        val config = controller.takePendingInjection()
        val sink = TestProviderSink(this, app.sessionJournal)
        if (config == null || !controller.start(config, sink, SessionKind.INJECTION)) {
            shutDown()
            return
        }
        holdWakeLock()
        observer = serviceScope.launch { observeSession() }
    }

    private suspend fun observeSession() {
        val manager = getSystemService(NotificationManager::class.java)
        var lastStatus: PlaybackStatus? = null
        var lastPostedAt = 0L
        controller.state.collect { state ->
            if (state !is SimulationState.Active) {
                shutDown()
                return@collect
            }
            val now = app.clock.elapsedRealtimeNanos()
            val status = state.snapshot.status
            if (status != lastStatus || now - lastPostedAt >= NOTIFICATION_INTERVAL_NANOS) {
                lastStatus = status
                lastPostedAt = now
                manager.notify(SimulationNotifications.NOTIFICATION_ID, notifications.build(state))
            }
        }
    }

    /**
     * Without a wake lock the CPU can sleep with the screen off, and fixes would
     * stop. The short renewing timeout means a stuck service cannot hold it forever.
     */
    private fun holdWakeLock() {
        val lock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RouteMock:simulation")
            .apply { setReferenceCounted(false) }
        wakeLock = lock
        serviceScope.launch {
            while (true) {
                lock.acquire(WAKE_LOCK_TIMEOUT_MILLIS)
                delay(WAKE_LOCK_RENEW_MILLIS)
            }
        }
    }

    private fun shutDown() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // Removes test providers if the system destroys us mid-session.
        if (observer != null) controller.stop()
        serviceScope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "com.routemock.app.action.START"
        const val ACTION_PAUSE = "com.routemock.app.action.PAUSE"
        const val ACTION_RESUME = "com.routemock.app.action.RESUME"
        const val ACTION_STOP = "com.routemock.app.action.STOP"

        private const val NOTIFICATION_INTERVAL_NANOS = 5_000_000_000L
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 5 * 60 * 1000L
        private const val WAKE_LOCK_RENEW_MILLIS = 60 * 1000L

        /** Must be called from a visible Activity, as Android requires. */
        fun startInjection(context: Context, app: RouteMockApp, config: SimulationConfig) {
            if (!app.simulationController.requestInjection(config)) return
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, SimulationService::class.java).setAction(ACTION_START),
                )
            } catch (e: RuntimeException) {
                app.simulationController.reportStartFailure(e.message ?: e.javaClass.simpleName)
            }
        }
    }
}
