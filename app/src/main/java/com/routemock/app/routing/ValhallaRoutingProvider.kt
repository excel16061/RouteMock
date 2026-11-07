package com.routemock.app.routing

import com.routemock.app.domain.GeoMath
import com.routemock.app.domain.PolylineCodec
import com.routemock.app.domain.TrafficBasis
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

data class HttpResponse(val code: Int, val body: String)

/** The network, kept behind an interface so tests use recorded responses. */
fun interface HttpPost {
    /** Throws [IOException] if no response arrives. */
    suspend fun post(url: String, jsonBody: String): HttpResponse
}

/**
 * Spaces out requests. The public FOSSGIS server allows at most one per second,
 * and that applies however fast the user taps.
 */
class RequestPacer(
    private val minimumIntervalMillis: Long,
    private val nowMillis: () -> Long,
) {
    private val mutex = Mutex()
    private var lastRequestAt: Long? = null

    suspend fun <T> paced(block: suspend () -> T): T = mutex.withLock {
        lastRequestAt?.let { last ->
            val wait = last + minimumIntervalMillis - nowMillis()
            if (wait > 0) delay(wait)
        }
        try {
            block()
        } finally {
            lastRequestAt = nowMillis()
        }
    }
}

/**
 * Valhalla's `/route` API. Each calculation makes two requests: the default search
 * with alternates, and a distance-only search, because Valhalla does not combine
 * alternates with `shortest`. Valhalla's public servers have no live traffic, so
 * times are always reported as estimates from road speeds.
 */
class ValhallaRoutingProvider(
    private val baseUrl: String,
    private val apiKey: String,
    override val info: RoutingProviderInfo,
    private val http: HttpPost,
    private val pacer: RequestPacer,
) : RoutingProvider {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun findRoutes(request: RoutingRequest): RoutingOutcome {
        if (!SecureEndpoint.isSecureBase(baseUrl)) throw RoutingException(RoutingFailure.INSECURE_ENDPOINT)
        val routes = send(request, requestBody(request, distanceOnly = false))
        return try {
            RoutingOutcome(routes + send(request, requestBody(request, distanceOnly = true)))
        } catch (_: RoutingException) {
            RoutingOutcome(routes, distanceSearchFailed = true)
        }
    }

    private suspend fun send(request: RoutingRequest, body: String): List<ProviderRoute> {
        val response = try {
            pacer.paced { http.post(endpoint(), body) }
        } catch (_: ResponseTooLargeException) {
            throw RoutingException(RoutingFailure.BAD_RESPONSE)
        } catch (_: SSLException) {
            throw RoutingException(RoutingFailure.SECURE_CONNECTION)
        } catch (_: SocketTimeoutException) {
            throw RoutingException(RoutingFailure.TIMEOUT)
        } catch (_: IOException) {
            throw RoutingException(RoutingFailure.NETWORK)
        }
        val routes = parseValhallaResponse(json, response)
        if (!routes.all { isPlausibleRoute(it, request) }) throw RoutingException(RoutingFailure.BAD_RESPONSE)
        return routes
    }

    private fun endpoint(): String {
        val url = baseUrl.trimEnd('/') + "/route"
        return if (apiKey.isEmpty()) url else "$url?api_key=$apiKey"
    }

    private fun requestBody(request: RoutingRequest, distanceOnly: Boolean): String = buildJsonObject {
        putJsonArray("locations") {
            for (point in listOf(request.start, request.destination)) {
                addJsonObject {
                    put("lat", point.latitude)
                    put("lon", point.longitude)
                }
            }
        }
        put("costing", "auto")
        put("units", "kilometers")
        put("directions_type", "none")
        if (distanceOnly) {
            putJsonObject("costing_options") {
                putJsonObject("auto") { put("shortest", true) }
            }
        } else {
            put("alternates", MAX_ALTERNATES)
        }
    }.toString()

    private companion object {
        const val MAX_ALTERNATES = 2
    }
}

internal fun parseValhallaResponse(json: Json, response: HttpResponse): List<ProviderRoute> {
    if (response.code == 429) throw RoutingException(RoutingFailure.RATE_LIMITED)
    if (response.code in 300..399) throw RoutingException(RoutingFailure.REDIRECT_REFUSED)
    val root = try {
        json.parseToJsonElement(response.body).jsonObject
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
    if (response.code !in 200..299) {
        val errorCode = (root?.get("error_code") as? JsonPrimitive)?.intOrNull
        throw RoutingException(failureFor(errorCode))
    }
    if (root == null) throw RoutingException(RoutingFailure.BAD_RESPONSE)

    return try {
        val alternates = (root["alternates"] as? JsonArray).orEmpty().map { it.jsonObject }
        (listOf(root) + alternates).map { parseTrip(it["trip"]!!.jsonObject) }
    } catch (_: NullPointerException) {
        throw RoutingException(RoutingFailure.BAD_RESPONSE)
    } catch (_: IllegalArgumentException) {
        throw RoutingException(RoutingFailure.BAD_RESPONSE)
    }
}

/** Longest believable gap between a pin and the road it snapped to. */
private const val MAX_SNAP_METERS = 10_000.0
private const val MAX_ROUTE_POINTS = 200_000

/** A response can be well formed and still not be a route between these pins. */
internal fun isPlausibleRoute(route: ProviderRoute, request: RoutingRequest): Boolean =
    route.points.size in 2..MAX_ROUTE_POINTS &&
        route.estimatedSeconds.isFinite() &&
        route.estimatedSeconds >= 0.0 &&
        GeoMath.distanceMeters(route.points.first(), request.start) <= MAX_SNAP_METERS &&
        GeoMath.distanceMeters(route.points.last(), request.destination) <= MAX_SNAP_METERS

private fun parseTrip(trip: JsonObject): ProviderRoute {
    val points = trip["legs"]!!.jsonArray.flatMap { leg ->
        PolylineCodec.decode(leg.jsonObject["shape"]!!.jsonPrimitive.content)
    }
    require(points.size >= 2) { "A route needs geometry" }
    return ProviderRoute(
        points = points,
        estimatedSeconds = trip["summary"]!!.jsonObject["time"]!!.jsonPrimitive.double,
        trafficBasis = TrafficBasis.ROAD_SPEED_MODEL,
    )
}

/** Codes from Valhalla's API documentation. */
private fun failureFor(errorCode: Int?): RoutingFailure = when (errorCode) {
    170, 171 -> RoutingFailure.NO_ROAD_NEAR_PIN
    442 -> RoutingFailure.UNREACHABLE
    150, 154 -> RoutingFailure.TOO_FAR
    else -> RoutingFailure.SERVICE_ERROR
}

