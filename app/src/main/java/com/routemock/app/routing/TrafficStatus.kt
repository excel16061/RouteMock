package com.routemock.app.routing

/**
 * The state of a live traffic layer, separate from how a route's time was estimated.
 * No traffic provider is configured, so the app always reports [NotConfigured].
 * A provider added later must supply real timestamps: nothing here may be invented.
 */
sealed interface TrafficStatus {
    data object NotConfigured : TrafficStatus
    data class Live(val attribution: String, val updatedAtMillis: Long) : TrafficStatus
    data class Stale(val attribution: String, val updatedAtMillis: Long) : TrafficStatus
    data class Unavailable(val attribution: String, val updatedAtMillis: Long?) : TrafficStatus
}
