package com.routemock.app.routing

import java.net.URI
import java.net.URISyntaxException

/** Rules every configured remote address must meet before anything is sent to it. */
object SecureEndpoint {
    /** HTTPS, with a host, and no embedded credentials or fragment. */
    fun isSecure(url: String): Boolean {
        val uri = parse(url) ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrEmpty() &&
            uri.rawUserInfo == null &&
            uri.rawFragment == null
    }

    /** As [isSecure], and with no query, since a path is appended to this address. */
    fun isSecureBase(url: String): Boolean = isSecure(url) && parse(url)?.rawQuery == null

    private fun parse(url: String): URI? = try {
        URI(url.trim())
    } catch (_: URISyntaxException) {
        null
    }
}
