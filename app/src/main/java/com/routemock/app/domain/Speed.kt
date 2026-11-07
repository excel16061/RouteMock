package com.routemock.app.domain

import kotlinx.serialization.Serializable

@Serializable
enum class SpeedUnit(val label: String, val metersPerSecondPerUnit: Double) {
    KMH("km/h", 1000.0 / 3600.0),
    MPH("mph", 0.44704), // Exact: 1609.344 m per 3600 s.
    ;

    fun toMetersPerSecond(value: Double): Double = value * metersPerSecondPerUnit
    fun fromMetersPerSecond(metersPerSecond: Double): Double = metersPerSecond / metersPerSecondPerUnit
}

/** Rewrites a speed entered in [from] so the same physical speed reads in [to]. */
fun convertSpeedText(text: String, from: SpeedUnit, to: SpeedUnit): String {
    if (from == to) return text
    val value = RouteValidator.parseDecimal(text) ?: return text
    return formatSpeedValue(to.fromMetersPerSecond(from.toMetersPerSecond(value)))
}

/** Up to two decimals, without trailing zeros. */
fun formatSpeedValue(value: Double): String =
    "%.2f".format(java.util.Locale.US, value).trimEnd('0').trimEnd('.')
