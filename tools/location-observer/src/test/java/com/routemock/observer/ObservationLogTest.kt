package com.routemock.observer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ObservationLogTest {
    private fun fix(source: Source = Source.CALLBACK, speed: Float? = 8.333f) =
        MockFix(source, 1_000, 1_020, 52.480101, 13.329937, speed)

    private val mustNotRead: () -> MockFix = { fail("a non-mock fix was read"); fix() }

    @Test fun `a non-mock callback is counted and never read`() {
        val log = ObservationLog(0)
        assertNull(log.offer(Source.CALLBACK, false, mustNotRead))
        assertEquals(1, log.ignoredNonMockCallbacks)
        assertEquals(0, log.mockCallbacks)
    }

    @Test fun `a non-mock last known location is counted separately and never read`() {
        val log = ObservationLog(0)
        assertNull(log.offer(Source.LAST_KNOWN, false, mustNotRead))
        assertEquals(1, log.ignoredNonMockLastKnown)
        assertEquals(0, log.ignoredNonMockCallbacks)
    }

    @Test fun `non-mock coordinates cannot reach the export`() {
        val log = ObservationLog(0)
        log.offer(Source.CALLBACK, false) { MockFix(Source.CALLBACK, 1, 1, 12.345678, 98.765432, 1f) }
        assertFalse(log.toCsv().contains("12.345678"))
        assertTrue(log.toCsv().contains("ignored_non_mock_callbacks=1"))
    }

    @Test fun `cached reads are never counted as delivered callbacks`() {
        val log = ObservationLog(0)
        log.offer(Source.LAST_KNOWN, true) { fix(Source.LAST_KNOWN) }
        assertEquals(0, log.mockCallbacks)
        assertEquals(1, log.lastKnownReads)
        assertNull(log.lastCallback)
    }

    @Test fun `a mock callback keeps its flag, times, coordinates, and speed`() {
        val log = ObservationLog(5)
        assertNotNull(log.offer(Source.CALLBACK, true) { fix() })
        log.end(2_000)
        val csv = log.toCsv()
        assertTrue(csv, csv.contains("fix,callback,1000,1020,20,true,52.480101,13.329937,8.333\n"))
        assertTrue(csv, csv.contains("observation_start_elapsed_ms=5\n"))
        assertTrue(csv, csv.contains("observation_end_elapsed_ms=2000\n"))
    }

    @Test fun `a missing speed stays empty rather than becoming zero`() {
        val log = ObservationLog(0)
        log.offer(Source.CALLBACK, true) { fix(speed = null) }
        assertTrue(log.toCsv().contains(",52.480101,13.329937,\n"))
    }

    @Test fun `marks are exported so fixes can be compared with a stop`() {
        val log = ObservationLog(0)
        log.mark(1_500)
        assertTrue(log.toCsv().contains("mark,,,1500,,,,,\n"))
    }

    @Test fun `empty last known reads are counted`() {
        val log = ObservationLog(0)
        log.noLastKnown()
        assertTrue(log.toCsv().contains("empty_last_known_reads=1\n"))
    }

    @Test fun `a full log drops fixes and says so`() {
        val log = ObservationLog(0, maxEntries = 1)
        log.offer(Source.CALLBACK, true) { fix() }
        assertNull(log.offer(Source.CALLBACK, true) { fix() })
        assertEquals(1, log.droppedFixes)
    }
}
