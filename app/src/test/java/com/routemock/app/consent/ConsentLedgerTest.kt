package com.routemock.app.consent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ConsentLedgerTest {
    @get:Rule val folder = TemporaryFolder()
    private val file: File get() = File(folder.root, "receipt.json")
    private var now = 1000L

    private fun documents(termsText: String = "terms v1", termsVersion: Int = 1) = listOf(
        ConsentDocument(ConsentScope.TERMS, termsVersion, "t", termsText),
        ConsentDocument(ConsentScope.PRIVACY, 1, "p", "privacy"),
        ConsentDocument(ConsentScope.MOCK_LOCATION, 1, "m", "mock"),
        ConsentDocument(ConsentScope.DASHBOARD_UPLOAD, 1, "d", "dashboard"),
    )

    private fun ledger(docs: List<ConsentDocument> = documents()) = ConsentLedger(file, docs, "1.0", { now })

    @Test fun `nothing is granted by default`() {
        val ledger = ledger()
        assertFalse(ledger.requiredGranted())
        ConsentScope.entries.forEach { assertFalse(ledger.isGranted(it)) }
    }

    @Test fun `accepting only some required documents is not enough`() {
        val ledger = ledger()
        ledger.accept(setOf(ConsentScope.TERMS, ConsentScope.PRIVACY))
        assertFalse(ledger.requiredGranted())
    }

    @Test fun `required consent does not grant the optional upload scope`() {
        val ledger = ledger()
        ledger.accept(ConsentLedger.REQUIRED)
        assertTrue(ledger.requiredGranted())
        assertFalse(ledger.isGranted(ConsentScope.DASHBOARD_UPLOAD))
    }

    @Test fun `the receipt records versions, hashes, time, and app version`() {
        val ledger = ledger()
        now = 5000L
        ledger.accept(setOf(ConsentScope.TERMS))
        val event = ledger.receipt.value.events.single()
        assertEquals(ConsentEventType.ACCEPTED, event.type)
        assertEquals(5000L, event.atUtcMillis)
        assertEquals("1.0", event.appVersion)
        assertEquals(DocumentRef(ConsentScope.TERMS, 1, sha256("terms v1")), event.documents.single())
    }

    @Test fun `consent survives a restart`() {
        ledger().accept(ConsentLedger.REQUIRED)
        assertTrue(ledger().requiredGranted())
    }

    @Test fun `a changed document needs accepting again, even at the same version`() {
        ledger().accept(ConsentLedger.REQUIRED)
        val changed = ledger(documents(termsText = "terms edited"))
        assertFalse(changed.isGranted(ConsentScope.TERMS))
        assertFalse(changed.requiredGranted())
        assertTrue(changed.isGranted(ConsentScope.PRIVACY))
    }

    @Test fun `accepting a new document records a replacement`() {
        ledger().accept(ConsentLedger.REQUIRED)
        val changed = ledger(documents(termsText = "terms v2", termsVersion = 2))
        changed.accept(setOf(ConsentScope.TERMS))
        assertEquals(ConsentEventType.REPLACED, changed.receipt.value.events.last().type)
        assertTrue(changed.requiredGranted())
    }

    @Test fun `withdrawal removes the grant and keeps the history`() {
        val ledger = ledger()
        ledger.accept(ConsentLedger.REQUIRED)
        ledger.withdraw(setOf(ConsentScope.MOCK_LOCATION))
        assertFalse(ledger.requiredGranted())
        assertTrue(ledger.isGranted(ConsentScope.TERMS))
        assertEquals(ConsentEventType.WITHDRAWN, ledger.receipt.value.events.last().type)
        assertEquals(2, ledger.receipt.value.events.size)
    }

    @Test fun `consent can be given again after withdrawal`() {
        val ledger = ledger()
        ledger.accept(ConsentLedger.REQUIRED)
        ledger.withdraw(setOf(ConsentScope.MOCK_LOCATION))
        ledger.accept(setOf(ConsentScope.MOCK_LOCATION))
        assertTrue(ledger.requiredGranted())
        assertEquals(ConsentEventType.ACCEPTED, ledger.receipt.value.events.last().type)
    }

    @Test fun `withdrawing what was never granted records nothing`() {
        val ledger = ledger()
        ledger.withdraw(setOf(ConsentScope.DASHBOARD_UPLOAD))
        assertTrue(ledger.receipt.value.events.isEmpty())
    }

    @Test fun `accepting twice records once`() {
        val ledger = ledger()
        ledger.accept(ConsentLedger.REQUIRED)
        ledger.accept(ConsentLedger.REQUIRED)
        assertEquals(1, ledger.receipt.value.events.size)
    }

    @Test fun `a damaged receipt means no consent, never consent`() {
        file.writeText("{broken")
        val ledger = ledger()
        assertFalse(ledger.requiredGranted())
        assertTrue(File(file.path + ".corrupt").exists())
    }

    @Test fun `the export holds no document text and no personal fields`() {
        val ledger = ledger()
        ledger.accept(ConsentLedger.REQUIRED)
        val export = ledger.exportJson()
        assertFalse(export.contains("terms v1"))
        listOf("name", "email", "birth", "latitude").forEach { assertFalse(it, export.contains(it)) }
        assertTrue(export.contains(sha256("terms v1")))
    }

    @Test fun `decisions are the user's unless marked as automated testing`() {
        ledger().accept(setOf(ConsentScope.TERMS))
        assertEquals(ConsentOrigin.USER, ledger().receipt.value.events.single().origin)
    }

    @Test fun `automated test acceptance is labeled, and survives a restart labeled`() {
        val automated = ConsentLedger(file, documents(), "1.0", { now }, { ConsentOrigin.AUTOMATED_TEST })
        automated.accept(ConsentLedger.REQUIRED)
        automated.withdraw(ConsentLedger.REQUIRED)
        val events = ledger().receipt.value.events
        assertTrue(events.all { it.origin == ConsentOrigin.AUTOMATED_TEST })
        assertTrue(ledger().exportJson().contains("AUTOMATED_TEST"))
    }

    @Test fun `receipts from before origin existed load as unknown, not as the user`() {
        file.writeText("""{"schema":1,"events":[{"type":"ACCEPTED","atUtcMillis":1,"appVersion":"0.4.0","documents":[]}]}""")
        assertEquals(ConsentOrigin.UNKNOWN, ledger().receipt.value.events.single().origin)
    }

    @Test fun `a false user label on an event from before origins existed is corrected on disk`() {
        val event = """{"type":"WITHDRAWN","atUtcMillis":1,"appVersion":"0.4.0","documents":[],"origin":"USER"}"""
        file.writeText("""{"schema":1,"events":[$event]}""")
        assertEquals(ConsentOrigin.UNKNOWN, ledger().receipt.value.events.single().origin)
        assertFalse(file.readText().contains("\"USER\""))
    }

    @Test fun `events from later builds keep their recorded origin`() {
        val event = """{"type":"ACCEPTED","atUtcMillis":1,"appVersion":"0.4.2","documents":[],"origin":"USER"}"""
        file.writeText("""{"schema":1,"events":[$event]}""")
        assertEquals(ConsentOrigin.USER, ledger().receipt.value.events.single().origin)
    }

    private val withCorrection = """{"schema":1,"events":[
        {"type":"ACCEPTED","atUtcMillis":1,"appVersion":"0.4.3","documents":[],"origin":"AUTOMATED_TEST"},
        {"type":"WITHDRAWN","atUtcMillis":2,"appVersion":"0.4.3","documents":[],"origin":"USER"}],
        "corrections":[{"eventIndexes":[0],"origin":"UNKNOWN","reason":"r","atUtcMillis":3}]}"""

    @Test fun `a correction changes the origin to rely on, and leaves the event as recorded`() {
        file.writeText(withCorrection)
        val receipt = ledger().receipt.value
        assertEquals(ConsentOrigin.UNKNOWN, receipt.effectiveOrigin(0))
        assertEquals(ConsentOrigin.AUTOMATED_TEST, receipt.events[0].origin)
        assertEquals(ConsentOrigin.USER, receipt.effectiveOrigin(1))
    }

    @Test fun `corrections survive later events and appear in exports`() {
        file.writeText(withCorrection)
        val ledger = ledger()
        ledger.accept(setOf(ConsentScope.TERMS))
        val reloaded = ledger()
        assertEquals(1, reloaded.receipt.value.corrections.size)
        assertEquals(3, reloaded.receipt.value.events.size)
        assertTrue(reloaded.exportJson().contains("corrections"))
    }

    @Test fun `a correction does not change whether consent is in force`() {
        val ledger = ledger()
        ledger.accept(ConsentLedger.REQUIRED)
        val text = file.readText().trimEnd().removeSuffix("}") +
            ""","corrections":[{"eventIndexes":[0],"origin":"UNKNOWN","reason":"r","atUtcMillis":3}]}"""
        file.writeText(text)
        assertTrue(ledger().requiredGranted())
    }

    @Test fun `different text has a different hash`() {
        assertNotEquals(sha256("a"), sha256("b"))
    }
}
