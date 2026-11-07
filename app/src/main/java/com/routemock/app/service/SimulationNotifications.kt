package com.routemock.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.routemock.app.MainActivity
import com.routemock.app.R
import com.routemock.app.playback.Formatters
import com.routemock.app.playback.PlaybackStatus
import com.routemock.app.simulation.SimulationState

internal class SimulationNotifications(private val context: Context) {
    fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = context.getString(R.string.notification_channel_description) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun build(state: SimulationState.Active?): Notification {
        val status = state?.snapshot?.status
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(state?.let(::describe) ?: context.getString(R.string.notification_starting))
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        when (status) {
            PlaybackStatus.RUNNING -> builder.addAction(
                0,
                context.getString(R.string.action_pause),
                actionIntent(SimulationService.ACTION_PAUSE),
            )
            PlaybackStatus.PAUSED -> builder.addAction(
                0,
                context.getString(R.string.action_resume),
                actionIntent(SimulationService.ACTION_RESUME),
            )
            PlaybackStatus.COMPLETED, null -> Unit // Nothing left to pause.
        }
        builder.addAction(0, context.getString(R.string.action_stop), actionIntent(SimulationService.ACTION_STOP))
        return builder.build()
    }

    private fun describe(state: SimulationState.Active): String {
        val snapshot = state.snapshot
        val unit = state.displayUnit
        return when {
            snapshot.status == PlaybackStatus.PAUSED -> context.getString(R.string.notification_paused)
            snapshot.status == PlaybackStatus.COMPLETED -> context.getString(R.string.notification_completed)
            snapshot.isStationary -> context.getString(R.string.notification_stationary)
            else -> context.getString(
                R.string.notification_moving,
                Formatters.speed(snapshot.speedMetersPerSecond, unit),
                Formatters.distance(snapshot.distanceRemainingMeters, unit),
            )
        }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun actionIntent(action: String): PendingIntent = PendingIntent.getService(
        context,
        action.hashCode(),
        Intent(context, SimulationService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "simulation"
    }
}
