package com.routemock.app.routing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The response was larger than any route should be. */
class ResponseTooLargeException : IOException("Response too large")

/**
 * Plain HTTP POST using the platform's certificate and hostname checks, which are
 * never altered here. It logs nothing, as requests contain the user's chosen pins.
 */
class HttpUrlConnectionPost(
    private val userAgent: String,
    private val maxResponseBytes: Int = MAX_RESPONSE_BYTES,
    private val timeoutMillis: Int = TIMEOUT_MILLIS,
) : HttpPost {
    override suspend fun post(url: String, jsonBody: String): HttpResponse = send("POST", url, jsonBody, null)

    /** The token, if any, goes in a header and only to this exact URL. */
    suspend fun send(method: String, url: String, jsonBody: String?, bearerToken: String?): HttpResponse =
        suspendCancellableCoroutine { continuation ->
            val connection = URL(url).openConnection() as HttpURLConnection
            // Closing the socket is what stops a blocking read when the user moves on.
            continuation.invokeOnCancellation { connection.disconnect() }
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val response = exchange(connection, method, jsonBody, bearerToken)
                    if (continuation.isActive) continuation.resume(response)
                } catch (e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                } catch (e: RuntimeException) {
                    // Uncaught here it would end the process, and with it any running session.
                    if (continuation.isActive) continuation.resumeWithException(IOException("Request failed", e))
                } finally {
                    connection.disconnect()
                }
            }
        }

    private fun exchange(
        connection: HttpURLConnection,
        method: String,
        jsonBody: String?,
        bearerToken: String?,
    ): HttpResponse {
        connection.requestMethod = method
        connection.connectTimeout = timeoutMillis
        connection.readTimeout = timeoutMillis
        // A redirect could take a key in the URL to another host, or down to HTTP.
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", userAgent)
        bearerToken?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        if (jsonBody != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(jsonBody.toByteArray()) }
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        return HttpResponse(code, stream?.use { readLimited(it, maxResponseBytes) }.orEmpty())
    }

    companion object {
        const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
        const val TIMEOUT_MILLIS = 20_000

        /** Stops reading as soon as the limit passes, whatever the server claims. */
        internal fun readLimited(stream: InputStream, limit: Int): String {
            val buffer = ByteArray(16 * 1024)
            val bytes = java.io.ByteArrayOutputStream()
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                if (bytes.size() + read > limit) throw ResponseTooLargeException()
                bytes.write(buffer, 0, read)
            }
            return bytes.toString(Charsets.UTF_8.name())
        }
    }
}
