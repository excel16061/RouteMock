package com.routemock.app.dashboard

import com.routemock.app.domain.RouteDraft
import com.routemock.app.domain.RouteMode
import com.routemock.app.domain.RouteValidator
import com.routemock.app.domain.formatSpeedValue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.format.DateTimeParseException

/** A simulation request from the dashboard. It runs only if approved on this phone. */
data class RemoteCommand(
    val id: String,
    val nonce: String,
    val expiresAtMillis: Long,
    val draft: RouteDraft,
    val draftVersion: Int,
)

object RemoteCommandParser {
    /** Returns null for anything malformed, expired, already seen, or not startable. */
    fun parse(item: JsonObject, nowMillis: Long, seenNonces: Set<String>): RemoteCommand? = try {
        val id = item.text("id")
        val nonce = item.text("nonce")
        val expires = Instant.parse(item.text("expires_at")).toEpochMilli()
        val draft = draftFrom(item["draft"]!!.jsonObject)
        val fresh = expires > nowMillis && nonce !in seenNonces && item.text("kind") == "start_simulation"
        // Road mode needs a route calculated on this phone, so it cannot start remotely.
        if (fresh && RouteValidator.validate(draft).result != null) {
            RemoteCommand(id, nonce, expires, draft, item["draft_version"]!!.jsonPrimitive.content.toInt())
        } else {
            null
        }
    } catch (_: NullPointerException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: DateTimeParseException) {
        null
    }

    private fun JsonObject.text(key: String) = this[key]!!.jsonPrimitive.content

    private fun draftFrom(payload: JsonObject): RouteDraft {
        val mode = RouteMode.valueOf(payload.text("mode"))
        val start = payload["start"]!!.jsonObject
        val destination = payload["destination"]?.jsonObject
        val speed = payload["speed_kmh"]?.jsonPrimitive?.doubleOrNull ?: 50.0
        return RouteDraft(
            mode = mode,
            startLatitude = start.text("latitude"),
            startLongitude = start.text("longitude"),
            destinationLatitude = destination?.text("latitude").orEmpty(),
            destinationLongitude = destination?.text("longitude").orEmpty(),
            speed = formatSpeedValue(speed),
        )
    }
}
