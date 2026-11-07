package com.routemock.app.playback

import com.routemock.app.domain.SpeedUnit
import java.util.Locale

/** Display text for playback values. Fixed locale keeps coordinates pasteable. */
object Formatters {
    private const val METERS_PER_MILE = 1609.344
    private const val METERS_PER_FOOT = 0.3048

    fun coordinate(value: Double): String = "%.6f".format(Locale.US, value)

    fun speed(metersPerSecond: Double, unit: SpeedUnit): String =
        "%.1f %s".format(Locale.US, unit.fromMetersPerSecond(metersPerSecond), unit.label)

    fun distance(meters: Double, unit: SpeedUnit): String = when (unit) {
        SpeedUnit.KMH ->
            if (meters < 1000.0) "%.0f m".format(Locale.US, meters)
            else "%.2f km".format(Locale.US, meters / 1000.0)
        SpeedUnit.MPH ->
            if (meters < METERS_PER_MILE / 10) "%.0f ft".format(Locale.US, meters / METERS_PER_FOOT)
            else "%.2f mi".format(Locale.US, meters / METERS_PER_MILE)
    }

    /** How old something is, in the largest sensible unit. */
    fun age(seconds: Long): String = when {
        seconds < 60 -> "$seconds s"
        seconds < 3600 -> "${seconds / 60} min"
        seconds < 86_400 -> "${seconds / 3600} h ${seconds % 3600 / 60} min"
        else -> "${seconds / 86_400} d"
    }

    /** H:MM:SS, rounding up so a route never reads 0:00:00 before it has arrived. */
    fun duration(seconds: Double): String {
        val total = kotlin.math.ceil(seconds).toLong()
        return "%d:%02d:%02d".format(Locale.US, total / 3600, total % 3600 / 60, total % 60)
    }
}
