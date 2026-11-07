package com.routemock.observer

import java.util.Locale

/** How a fix reached this app. Only [CALLBACK] is evidence of delivery. */
enum class Source(val label: String) {
    /** Delivered to this app's listener, as a new update. */
    CALLBACK("callback"),

    /** Read from Android's cache on request. It may be old, and proves nothing about delivery. */
    LAST_KNOWN("last_known"),
}

data class MockFix(
    val source: Source,
    val fixElapsedMillis: Long,
    val receivedElapsedMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val speedMetersPerSecond: Float?,
)

/**
 * One observation. Real locations never enter it: [offer] takes the mock flag first, and only reads
 * a fix through [offer]'s `read` function when that flag is set. A non-mock fix becomes a count.
 */
class ObservationLog(
    val startElapsedMillis: Long,
    private val maxEntries: Int = 20_000,
) {
    private val fixes = mutableListOf<MockFix>()
    private val marks = mutableListOf<Long>()

    var ignoredNonMockCallbacks = 0
        private set
    var ignoredNonMockLastKnown = 0
        private set
    var emptyLastKnown = 0
        private set
    var droppedFixes = 0
        private set
    var endElapsedMillis: Long? = null
        private set

    val mockCallbacks: Int get() = fixes.count { it.source == Source.CALLBACK }
    val lastCallback: MockFix? get() = fixes.lastOrNull { it.source == Source.CALLBACK }
    val lastKnownReads: Int get() = fixes.count { it.source == Source.LAST_KNOWN }

    /** Returns the stored fix, or null if it was not mock-flagged or the log is full. */
    fun offer(source: Source, isMock: Boolean, read: () -> MockFix): MockFix? {
        if (!isMock) {
            if (source == Source.CALLBACK) ignoredNonMockCallbacks++ else ignoredNonMockLastKnown++
            return null
        }
        if (fixes.size >= maxEntries) {
            droppedFixes++
            return null
        }
        return read().also(fixes::add)
    }

    fun noLastKnown() {
        emptyLastKnown++
    }

    /** A moment noted by the tester, such as when RouteMock was stopped. */
    fun mark(elapsedMillis: Long) {
        marks += elapsedMillis
    }

    fun end(elapsedMillis: Long) {
        endElapsedMillis = elapsedMillis
    }

    /** Only mock-flagged fixes, elapsed-realtime times, and counts. No clock time or device data. */
    fun toCsv(): String = buildString {
        append("# RouteMock location observer export. Provider: gps.\n")
        append("# Only mock-flagged fixes are stored. Non-mock fixes are counted and discarded unread.\n")
        append("# Rows with source last_known come from Android's cache and are not evidence of delivery.\n")
        append("# observation_start_elapsed_ms=$startElapsedMillis\n")
        append("# observation_end_elapsed_ms=${endElapsedMillis ?: ""}\n")
        append("# mock_callbacks=$mockCallbacks\n")
        append("# ignored_non_mock_callbacks=$ignoredNonMockCallbacks\n")
        append("# mock_last_known_reads=$lastKnownReads\n")
        append("# ignored_non_mock_last_known_reads=$ignoredNonMockLastKnown\n")
        append("# empty_last_known_reads=$emptyLastKnown\n")
        append("# dropped_fixes=$droppedFixes\n")
        append("record,source,fix_elapsed_ms,received_elapsed_ms,age_ms,android_mock_flag,latitude,longitude,speed_m_per_s\n")
        for (mark in marks) append("mark,,,$mark,,,,,\n")
        for (fix in fixes) {
            val speed = fix.speedMetersPerSecond?.let { String.format(Locale.ROOT, "%.3f", it) }.orEmpty()
            append(
                String.format(
                    Locale.ROOT,
                    "fix,%s,%d,%d,%d,true,%.6f,%.6f,%s\n",
                    fix.source.label,
                    fix.fixElapsedMillis,
                    fix.receivedElapsedMillis,
                    fix.receivedElapsedMillis - fix.fixElapsedMillis,
                    fix.latitude,
                    fix.longitude,
                    speed,
                ),
            )
        }
    }
}
