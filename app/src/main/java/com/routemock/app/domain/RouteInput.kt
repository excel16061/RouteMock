package com.routemock.app.domain

import kotlinx.serialization.Serializable

@Serializable
enum class RouteMode { STATIONARY, ROAD, STRAIGHT_LINE }

/** Why a text field was rejected. The UI maps each case to a message. */
enum class InputError {
    REQUIRED,
    NOT_A_NUMBER,
    LATITUDE_OUT_OF_RANGE,
    LONGITUDE_OUT_OF_RANGE,
    SPEED_NOT_POSITIVE,
    SPEED_TOO_HIGH,
}

/** A problem with the route as a whole, rather than with one field. */
enum class RouteError {
    IDENTICAL_ENDPOINTS,
    ANTIPODAL_ENDPOINTS,

    /** Road mode has no route selected. There is no fallback to a straight line. */
    ROAD_ROUTE_MISSING,

    /** The pins moved after the road route was calculated. */
    ROAD_ROUTE_OUTDATED,
}

/**
 * The raw text of the route form.
 *
 * Do not change these defaults. JSON omits values equal to the default, so routes
 * saved before road mode existed have no `mode` and rely on it staying STRAIGHT_LINE.
 */
@Serializable
data class RouteDraft(
    val mode: RouteMode = RouteMode.STRAIGHT_LINE,
    val startLatitude: String = "",
    val startLongitude: String = "",
    val destinationLatitude: String = "",
    val destinationLongitude: String = "",
    val speed: String = "50",
    val speedUnit: SpeedUnit = SpeedUnit.KMH,
    val roadRoute: RoadRouteSelection? = null,
)

data class ValidatedRoute(val route: Route, val speedMetersPerSecond: Double)

data class RouteValidation(
    val result: ValidatedRoute? = null,
    val startLatitudeError: InputError? = null,
    val startLongitudeError: InputError? = null,
    val destinationLatitudeError: InputError? = null,
    val destinationLongitudeError: InputError? = null,
    val speedError: InputError? = null,
    val routeError: RouteError? = null,
)

object RouteValidator {
    /** Upper bound on simulated speed, roughly a commercial airliner. */
    const val MAX_SPEED_KMH = 1200.0
    val maxSpeedMetersPerSecond: Double = SpeedUnit.KMH.toMetersPerSecond(MAX_SPEED_KMH)

    /** Endpoints closer than this are the same place at the precision of a coordinate. */
    const val MIN_ROUTE_LENGTH_METERS = 0.01

    /** Near the antipode every direction is a shortest path, so no single line exists. */
    private const val ANTIPODAL_TOLERANCE_METERS = 1.0
    private val halfCircumferenceMeters = Math.PI * GeoMath.EARTH_RADIUS_METERS

    // Plain decimals only. Double parsing alone would also accept "NaN",
    // "Infinity", "1e3", "0x1p3" and a trailing "d" or "f".
    private val decimal = Regex("""[+-]?(\d+(\.\d*)?|\.\d+)""")

    fun parseDecimal(text: String): Double? {
        val trimmed = text.trim()
        return if (decimal.matches(trimmed)) trimmed.toDouble() else null
    }

    fun validateLatitude(text: String): InputError? =
        validateRange(text, GeoPoint.MIN_LATITUDE..GeoPoint.MAX_LATITUDE, InputError.LATITUDE_OUT_OF_RANGE)

    fun validateLongitude(text: String): InputError? =
        validateRange(text, GeoPoint.MIN_LONGITUDE..GeoPoint.MAX_LONGITUDE, InputError.LONGITUDE_OUT_OF_RANGE)

    fun validateSpeed(text: String, unit: SpeedUnit): InputError? {
        if (text.isBlank()) return InputError.REQUIRED
        val value = parseDecimal(text) ?: return InputError.NOT_A_NUMBER
        val metersPerSecond = unit.toMetersPerSecond(value)
        return when {
            metersPerSecond <= 0.0 -> InputError.SPEED_NOT_POSITIVE
            metersPerSecond > maxSpeedMetersPerSecond -> InputError.SPEED_TOO_HIGH
            else -> null
        }
    }

    fun validate(draft: RouteDraft): RouteValidation {
        val startLatError = validateLatitude(draft.startLatitude)
        val startLonError = validateLongitude(draft.startLongitude)

        if (draft.mode == RouteMode.STATIONARY) {
            val failed = startLatError != null || startLonError != null
            return RouteValidation(
                result = if (failed) null else ValidatedRoute(StationaryRoute(startOf(draft)), 0.0),
                startLatitudeError = startLatError,
                startLongitudeError = startLonError,
            )
        }

        val destLatError = validateLatitude(draft.destinationLatitude)
        val destLonError = validateLongitude(draft.destinationLongitude)
        val speedError = validateSpeed(draft.speed, draft.speedUnit)
        val fieldErrors = RouteValidation(
            startLatitudeError = startLatError,
            startLongitudeError = startLonError,
            destinationLatitudeError = destLatError,
            destinationLongitudeError = destLonError,
            speedError = speedError,
        )
        val coordinatesValid = listOf(startLatError, startLonError, destLatError, destLonError).all { it == null }
        if (!coordinatesValid) return fieldErrors

        val straightLine = StraightLineRoute(
            start = startOf(draft),
            destination = GeoPoint(
                parseDecimal(draft.destinationLatitude)!!,
                parseDecimal(draft.destinationLongitude)!!,
            ),
        )
        val routeError = when {
            straightLine.lengthMeters < MIN_ROUTE_LENGTH_METERS -> RouteError.IDENTICAL_ENDPOINTS
            halfCircumferenceMeters - straightLine.lengthMeters < ANTIPODAL_TOLERANCE_METERS ->
                RouteError.ANTIPODAL_ENDPOINTS
            draft.mode == RouteMode.ROAD -> roadRouteError(draft.roadRoute, straightLine)
            else -> null
        }
        if (routeError != null) return fieldErrors.copy(routeError = routeError)
        if (speedError != null) return fieldErrors

        val speed = draft.speedUnit.toMetersPerSecond(parseDecimal(draft.speed)!!)
        val route = if (draft.mode == RouteMode.ROAD) draft.roadRoute!!.route!! else straightLine
        return fieldErrors.copy(result = ValidatedRoute(route, speed))
    }

    private fun roadRouteError(selection: RoadRouteSelection?, pins: StraightLineRoute): RouteError? = when {
        selection?.route == null -> RouteError.ROAD_ROUTE_MISSING
        !selection.matches(pins.start, pins.destination) -> RouteError.ROAD_ROUTE_OUTDATED
        else -> null
    }

    private fun startOf(draft: RouteDraft) =
        GeoPoint(parseDecimal(draft.startLatitude)!!, parseDecimal(draft.startLongitude)!!)

    private fun validateRange(
        text: String,
        range: ClosedFloatingPointRange<Double>,
        outOfRange: InputError,
    ): InputError? {
        if (text.isBlank()) return InputError.REQUIRED
        val value = parseDecimal(text) ?: return InputError.NOT_A_NUMBER
        return if (value in range) null else outOfRange
    }
}
