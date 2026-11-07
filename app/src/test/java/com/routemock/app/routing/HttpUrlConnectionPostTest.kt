package com.routemock.app.routing

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Runs the real client against a loopback server. Nothing leaves this machine. */
class HttpUrlConnectionPostTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val base get() = "http://127.0.0.1:${server.address.port}"
    private val redirectTargetHits = AtomicInteger()
    private val released = CountDownLatch(1)
    private val client = HttpUrlConnectionPost("RouteMock/test", maxResponseBytes = 1024, timeoutMillis = 500)

    @Before fun startServer() {
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/ok") { exchange ->
            val agent = exchange.requestHeaders.getFirst("User-Agent").toByteArray()
            exchange.sendResponseHeaders(200, agent.size.toLong())
            exchange.responseBody.use { it.write(agent) }
        }
        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "$base/target?api_key=secret")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/target") { exchange ->
            redirectTargetHits.incrementAndGet()
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.createContext("/huge") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { body -> repeat(100) { body.write(ByteArray(1024)) } }
        }
        server.createContext("/slow") { exchange ->
            released.await(5, TimeUnit.SECONDS)
            exchange.close()
        }
        server.start()
    }

    @After fun stopServer() {
        released.countDown()
        server.stop(0)
    }

    @Test fun `a normal response is returned with the identifying user agent`() = runBlocking {
        val response = client.post("$base/ok", "{}")
        assertEquals(200, response.code)
        assertEquals("RouteMock/test", response.body)
    }

    @Test fun `a redirect is returned, not followed`() = runBlocking {
        val response = client.post("$base/redirect", "{}")
        assertEquals(302, response.code)
        assertEquals(0, redirectTargetHits.get())
    }

    @Test fun `a response over the limit is abandoned`() {
        assertThrows(ResponseTooLargeException::class.java) {
            runBlocking { client.post("$base/huge", "{}") }
        }
    }

    @Test fun `a server that never answers times out`() {
        assertThrows(SocketTimeoutException::class.java) {
            runBlocking { client.post("$base/slow", "{}") }
        }
    }

    @Test fun `cancelling ends a blocked request promptly`() = runBlocking {
        val patient = HttpUrlConnectionPost("RouteMock/test", timeoutMillis = 30_000)
        val started = System.currentTimeMillis()
        val request = async(Dispatchers.Default) { patient.post("$base/slow", "{}") }
        delay(200)
        withTimeout(2000) { request.cancelAndJoin() }
        assertTrue(System.currentTimeMillis() - started < 2000)
    }

    @Test fun `reading stops exactly at the limit`() {
        assertEquals(1024, HttpUrlConnectionPost.readLimited(ByteArrayInputStream(ByteArray(1024)), 1024).length)
        assertThrows(ResponseTooLargeException::class.java) {
            HttpUrlConnectionPost.readLimited(ByteArrayInputStream(ByteArray(1025)), 1024)
        }
    }

    @Test fun `a credential that cannot be sent fails the request instead of the process`() {
        assertThrows(java.io.IOException::class.java) {
            runBlocking { withTimeout(5_000) { client.send("GET", "$base/ok", null, "bad\ntoken") } }
        }
    }
}
