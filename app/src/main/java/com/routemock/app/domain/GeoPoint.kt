package com.routemock.app.domain

import kotlinx.serialization.Serializable

/** A WGS84 coordinate in decimal degrees. */
@Serializable
data class GeoPoint(val latitude: Double, val longitude: Double) {
    val isValid: Boolean
        get() = latitude.isFinite() && longitude.isFinite() &&
            latitude in MIN_LATITUDE..MAX_LATITUDE &&
            longitude in MIN_LONGITUDE..MAX_LONGITUDE

    companion object {
        const val MIN_LATITUDE = -90.0
        const val MAX_LATITUDE = 90.0
        const val MIN_LONGITUDE = -180.0
        const val MAX_LONGITUDE = 180.0
    }
}
