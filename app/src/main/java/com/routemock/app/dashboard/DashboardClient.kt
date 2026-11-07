package com.routemock.app.dashboard

import com.routemock.app.routing.HttpResponse
import com.routemock.app.routing.SecureEndpoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException

/** Sends one request. Implementations must not follow redirects. */
fun interface DashboardHttp {
    suspend fun send(method: String, url: String, body: String?, token: String?): HttpResponse
}

data class Pairing(val baseUrl: String, val token: String, val label: String)

class DashboardException(val status: Int) : Exception("Dashboard error $status")

/**
 * Every call is made by this phone to the dashboard. Nothing listens for connections,
 * so the phone exposes no server.
 */
class DashboardClient(private val http: DashboardHttp) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Throws [DashboardException] with status 0 if the address is not acceptable. */
    suspend fun pair(
        baseUrl: String,
        code: String,
        model: String,
        androidVersion: String,
        receiptJson: String,
        debugBuild: Boolean,
    ): Pairing {
        if (!isAllowedAddress(baseUrl, debugBuild)) throw DashboardException(0)
        val body = buildJsonObject {
            put("code", code)
            put("model", model)
            put("android_version", androidVersion)
            // The dashboard refuses to pair without proof of this consent.
            put("consent_receipt", json.parseToJsonElement(receiptJson))
        }
        val reply = call("POST", baseUrl, "/api/device/pair", body.toString(), null)
        val token = reply.text("token")
        // It is sent later as a header, so it must be safe to put in one.
        if (!TOKEN_FORMAT.matches(token)) throw unreadable()
        return Pairing(baseUrl = baseUrl.trimEnd('/'), token = token, label = reply.text("label"))
    }

    suspend fun fetchCommands(pairing: Pairing): List<JsonObject> {
        val items = call("GET", pairing.baseUrl, "/api/device/commands", null, pairing.token)["commands"]
        return (items as? JsonArray ?: throw unreadable()).map { it as? JsonObject ?: throw unreadable() }
    }

    suspend fun reportStatus(pairing: Pairing, command: RemoteCommand, status: String, error: String = "") {
        val body = buildJsonObject {
            put("nonce", command.nonce)
            put("status", status)
            put("error", error)
        }
        call("POST", pairing.baseUrl, "/api/device/commands/${command.id}/status", body.toString(), pairing.token)
    }

    suspend fun uploadConsent(pairing: Pairing, receiptJson: String) {
        call("POST", pairing.baseUrl, "/api/device/consent", receiptJson, pairing.token)
    }

    /** Ends this credential on the dashboard, and with it remote control and uploads. */
    suspend fun revokeSelf(pairing: Pairing) {
        call("POST", pairing.baseUrl, "/api/device/withdraw", "{}", pairing.token)
    }

    /** Only these fields exist, so no coordinates can be included by mistake. */
    suspend fun uploadSessionSummary(
        pairing: Pairing,
        appVersion: String,
        selectedSpeedKmh: Double?,
        durationSeconds: Double,
    ) {
        val body = buildJsonObject {
            put("title", "Mock location session")
            put("protocol_version", "session-summary-1")
            put("app_version", appVersion)
            selectedSpeedKmh?.let { put("selected_speed_kmh", it) }
            put("monotonic_duration_s", durationSeconds)
        }
        call("POST", pairing.baseUrl, "/api/device/runs", body.toString(), pairing.token)
    }

    private suspend fun call(method: String, baseUrl: String, path: String, body: String?, token: String?): JsonObject {
        val response = http.send(method, baseUrl.trimEnd('/') + path, body, token)
        if (response.code !in 200..299) throw DashboardException(response.code)
        return try {
            json.parseToJsonElement(response.body).jsonObject
        } catch (_: IllegalArgumentException) {
            throw unreadable()
        }
    }

    /** A malformed reply is a failed request, never a crash. */
    private fun JsonObject.text(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw unreadable()

    private fun unreadable() = IOException("Unreadable dashboard response")

    companion object {
        private val TOKEN_FORMAT = Regex("""[A-Za-z0-9_-]{32,128}""")

        /**
         * HTTPS always. A debug build may also use this phone's loopback address, which
         * `adb reverse` carries over USB. No release build accepts it.
         */
        fun isAllowedAddress(url: String, debugBuild: Boolean): Boolean =
            SecureEndpoint.isSecureBase(url) ||
                (debugBuild && Regex("""http://127\.0\.0\.1:\d{1,5}/?""").matches(url))
    }
}
