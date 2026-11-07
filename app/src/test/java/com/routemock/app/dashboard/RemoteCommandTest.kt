package com.routemock.app.dashboard

import com.routemock.app.domain.RouteMode
import com.routemock.app.routing.HttpResponse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

class RemoteCommandTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()

    private fun item(
        expires: String = "2026-01-01T00:05:00Z",
        nonce: String = "n1",
        kind: String = "start_simulation",
        draft: String = """{"mode":"STATIONARY","start":{"latitude":10.0,"longitude":20.0}}""",
    ) = Json.parseToJsonElement(
        """{"id":"c1","kind":"$kind","nonce":"$nonce","expires_at":"$expires","draft":$draft,"draft_version":3}""",
    ).jsonObject

    @Test fun `a valid request is parsed into a draft`() {
        val command = RemoteCommandParser.parse(item(), now, emptySet())!!
        assertEquals(RouteMode.STATIONARY, command.draft.mode)
        assertEquals("10.0", command.draft.startLatitude)
        assertEquals(3, command.draftVersion)
    }

    @Test fun `an expired request is refused`() {
        assertNull(RemoteCommandParser.parse(item(expires = "2025-12-31T23:59:59Z"), now, emptySet()))
    }

    @Test fun `a replayed nonce is refused`() {
        assertNull(RemoteCommandParser.parse(item(), now, setOf("n1")))
    }

    @Test fun `an unknown command kind is refused`() {
        assertNull(RemoteCommandParser.parse(item(kind = "wipe"), now, emptySet()))
    }

    @Test fun `malformed requests are refused without crashing`() {
        listOf(
            """{"mode":"BOGUS","start":{"latitude":1,"longitude":1}}""",
            """{"mode":"STATIONARY"}""",
            """{"mode":"STATIONARY","start":{"latitude":95,"longitude":1}}""",
            """{"mode":"STATIONARY","start":"x"}""",
        ).forEach { assertNull(it, RemoteCommandParser.parse(item(draft = it), now, emptySet())) }
        assertNull(RemoteCommandParser.parse(item(expires = "soon"), now, emptySet()))
        assertNull(RemoteCommandParser.parse(Json.parseToJsonElement("{}").jsonObject, now, emptySet()))
    }

    @Test fun `a road route cannot start remotely, as it has no calculated route`() {
        val draft = """{"mode":"ROAD","start":{"latitude":1,"longitude":1},"destination":{"latitude":2,"longitude":2}}"""
        assertNull(RemoteCommandParser.parse(item(draft = draft), now, emptySet()))
    }

    @Test fun `release builds accept only https addresses`() {
        assertTrue(DashboardClient.isAllowedAddress("https://dashboard.example", debugBuild = false))
        listOf("http://dashboard.example", "http://127.0.0.1:8000", "https://user:p@x.example", "ftp://x").forEach {
            assertFalse(it, DashboardClient.isAllowedAddress(it, debugBuild = false))
        }
    }

    @Test fun `debug builds add only this phone's loopback address`() {
        assertTrue(DashboardClient.isAllowedAddress("http://127.0.0.1:8000", debugBuild = true))
        listOf("http://192.168.1.5:8000", "http://localhost:8000", "http://127.0.0.1.evil.example:80").forEach {
            assertFalse(it, DashboardClient.isAllowedAddress(it, debugBuild = true))
        }
    }

    @Test fun `pairing refuses an insecure address before sending anything`() = runTest {
        var sent = false
        val client = DashboardClient { _, _, _, _ ->
            sent = true
            HttpResponse(200, "{}")
        }
        try {
            client.pair("http://dashboard.example", "code", "model", "13", "{}", debugBuild = false)
            fail("Expected a refusal")
        } catch (e: DashboardException) {
            assertEquals(0, e.status)
        }
        assertFalse(sent)
    }

    @Test fun `pairing sends proof of consent`() = runTest {
        var sent: String? = null
        val client = DashboardClient { _, _, body, _ ->
            sent = body
            HttpResponse(200, """{"token":"ttttttttttttttttttttttttttttttttttttttttttt","label":"Phone"}""")
        }
        client.pair("https://d.example", "code", "model", "13", """{"events":[]}""", debugBuild = false)
        assertTrue(sent!!.contains("consent_receipt"))
    }

    @Test fun `revoking calls the dashboard with this credential`() = runTest {
        var called: Pair<String, String?>? = null
        val client = DashboardClient { _, url, _, token ->
            called = url to token
            HttpResponse(200, "{}")
        }
        client.revokeSelf(Pairing("https://d.example", "secret", "Phone"))
        assertEquals("https://d.example/api/device/withdraw" to "secret", called)
    }

    @Test fun `the token goes only to the paired dashboard, and session uploads hold no coordinates`() = runTest {
        val calls = mutableListOf<Triple<String, String?, String?>>()
        val client = DashboardClient { _, url, body, token ->
            calls += Triple(url, body, token)
            HttpResponse(200, "{}")
        }
        client.uploadSessionSummary(Pairing("https://d.example", "secret", "Phone"), "1.0", 50.0, 12.5)
        val (url, body, token) = calls.single()
        assertEquals("https://d.example/api/device/runs", url)
        assertEquals("secret", token)
        assertNotNull(body)
        listOf("latitude", "longitude", "lat", "lon", "secret").forEach { assertFalse(it, body!!.contains(it)) }
    }

    private val pairing = Pairing("https://dashboard.example", "t".repeat(43), "Test phone")

    private fun clientReplying(body: String) = DashboardClient { _, _, _, _ -> HttpResponse(200, body) }

    private suspend fun assertUnreadable(action: suspend () -> Unit) {
        try {
            action()
            fail("expected a failed request")
        } catch (_: java.io.IOException) {
            // Anything else would escape the app's handlers and end the process.
        }
    }

    @Test fun `malformed command lists are failed requests, not crashes`() = runTest {
        for (body in listOf("""{"commands":5}""", """{"commands":{}}""", """{"commands":[1]}""", "{}", "[]", "not json")) {
            assertUnreadable { clientReplying(body).fetchCommands(pairing) }
        }
    }

    @Test fun `a pairing reply must carry a credential that is safe to send`() = runTest {
        val good = "t".repeat(43)
        val replies = listOf(
            """{"label":"Test phone"}""",
            """{"token":"$good"}""",
            """{"token":5,"label":"Test phone"}""",
            """{"token":"short","label":"Test phone"}""",
            """{"token":"${good}\r\nX-Injected: 1","label":"Test phone"}""",
        )
        for (body in replies) {
            assertUnreadable {
                clientReplying(body).pair("https://dashboard.example", "code", "m", "13", "{}", debugBuild = false)
            }
        }
        val paired = clientReplying("""{"token":"$good","label":"Test phone"}""")
            .pair("https://dashboard.example", "code", "m", "13", "{}", debugBuild = false)
        assertEquals(good, paired.token)
    }
}
