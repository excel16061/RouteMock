package com.routemock.app.domain

import kotlin.math.roundToLong

/** Encoded polylines with six decimals, the format Valhalla returns. */
object PolylineCodec {
    private const val PRECISION = 1e6

    /** Returns an empty list if the text is damaged, rather than a partial path. */
    fun decode(encoded: String): List<GeoPoint> {
        val points = mutableListOf<GeoPoint>()
        var index = 0
        var latitude = 0L
        var longitude = 0L
        while (index < encoded.length) {
            val deltaLatitude = readValue(encoded, index) ?: return emptyList()
            index = deltaLatitude.second
            val deltaLongitude = readValue(encoded, index) ?: return emptyList()
            index = deltaLongitude.second
            latitude += deltaLatitude.first
            longitude += deltaLongitude.first
            val point = GeoPoint(latitude / PRECISION, longitude / PRECISION)
            if (!point.isValid) return emptyList()
            points += point
        }
        return points
    }

    fun encode(points: List<GeoPoint>): String = buildString {
        var previousLatitude = 0L
        var previousLongitude = 0L
        for (point in points) {
            val latitude = (point.latitude * PRECISION).roundToLong()
            val longitude = (point.longitude * PRECISION).roundToLong()
            writeValue(latitude - previousLatitude)
            writeValue(longitude - previousLongitude)
            previousLatitude = latitude
            previousLongitude = longitude
        }
    }

    private fun readValue(encoded: String, startIndex: Int): Pair<Long, Int>? {
        var index = startIndex
        var result = 0L
        var shift = 0
        while (true) {
            if (index >= encoded.length || shift > 60) return null
            val chunk = encoded[index++].code - 63
            if (chunk < 0 || chunk > 0x7f) return null
            result = result or ((chunk and 0x1f).toLong() shl shift)
            shift += 5
            if (chunk < 0x20) break
        }
        val value = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
        return value to index
    }

    private fun StringBuilder.writeValue(value: Long) {
        var remaining = if (value < 0) (value shl 1).inv() else value shl 1
        while (remaining >= 0x20) {
            append((((remaining and 0x1f) or 0x20) + 63).toInt().toChar())
            remaining = remaining shr 5
        }
        append((remaining + 63).toInt().toChar())
    }

}
