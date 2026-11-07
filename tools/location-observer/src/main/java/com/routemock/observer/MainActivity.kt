package com.routemock.observer

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Shows whether this separate app receives mock fixes through the standard GPS provider. It listens
 * only between Start and Stop, and only while on screen. Leaving the screen ends the observation.
 */
class MainActivity : Activity(), LocationListener {
    private lateinit var status: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var markButton: Button
    private lateinit var lastKnownButton: Button
    private lateinit var exportButton: Button

    private val locations by lazy { getSystemService(LocationManager::class.java) }
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, TICK_MILLIS)
        }
    }

    private var log: ObservationLog? = null
    private var observing = false
    private var note = "Not observing."

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = (16 * resources.displayMetrics.density).toInt()
        status = TextView(this).apply { setTextIsSelectable(true) }
        startButton = button("Start observation") { start() }
        stopButton = button("Stop observation") { stopObservation("Observation stopped.") }
        markButton = button("Mark RouteMock stopped") { mark() }
        lastKnownButton = button("Read last known (cached)") { readLastKnown() }
        exportButton = button("Export CSV") { export() }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(padding, padding, padding, padding)
            addView(TextView(this@MainActivity).apply { text = INTRO })
            listOf(startButton, stopButton, markButton, lastKnownButton, exportButton).forEach(::addView)
            addView(status)
        }
        setContentView(ScrollView(this).apply { addView(column) })
        render()
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
    }

    /** Leaving the screen ends the observation, so nothing is ever collected in the background. */
    override fun onStop() {
        super.onStop()
        if (observing) stopObservation("Ended because the app left the screen.")
    }

    private fun hasFineLocation() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun start() {
        if (hasFineLocation()) {
            begin()
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                PERMISSION_REQUEST,
            )
        }
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code != PERMISSION_REQUEST) return
        if (hasFineLocation()) {
            begin()
        } else {
            note = "Precise location was not granted, so the GPS provider cannot be observed."
            render()
        }
    }

    private fun begin() {
        try {
            log = ObservationLog(SystemClock.elapsedRealtime())
            locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, this, Looper.getMainLooper())
        } catch (_: SecurityException) {
            note = "Location access was refused."
            render()
            return
        } catch (_: IllegalArgumentException) {
            note = "This device has no GPS provider."
            render()
            return
        }
        observing = true
        note = "Observing."
        handler.post(ticker)
    }

    private fun stopObservation(message: String) {
        if (!observing) return
        locations.removeUpdates(this)
        observing = false
        log?.end(SystemClock.elapsedRealtime())
        handler.removeCallbacks(ticker)
        note = message
        render()
    }

    override fun onLocationChanged(location: Location) {
        val current = log ?: return
        // The flag is checked first. Without it, nothing below reads the coordinates.
        current.offer(Source.CALLBACK, location.isMockFix()) { location.toMockFix(Source.CALLBACK) }
    }

    private fun mark() {
        log?.mark(SystemClock.elapsedRealtime())
        note = "Marked."
        render()
    }

    private fun readLastKnown() {
        val current = log
        if (current == null || !hasFineLocation()) {
            note = "Start an observation first."
            render()
            return
        }
        try {
            val cached = locations.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            note = when {
                cached == null -> "Last known: none.".also { current.noLastKnown() }
                current.offer(Source.LAST_KNOWN, cached.isMockFix()) { cached.toMockFix(Source.LAST_KNOWN) } == null ->
                    "Last known: not mock-flagged, so it was not read."
                else -> "Last known: mock-flagged, recorded as cached."
            }
        } catch (_: SecurityException) {
            note = "Location access was refused."
        }
        render()
    }

    private fun export() {
        val current = log
        if (current == null) {
            note = "Nothing to export."
            render()
            return
        }
        note = try {
            val folder = File(filesDir, EXPORT_FOLDER).apply { mkdirs() }
            val file = File(folder, "observation-${(folder.list()?.size ?: 0) + 1}.csv")
            file.writeText(current.toCsv())
            "Exported to app storage: $EXPORT_FOLDER/${file.name}"
        } catch (_: IOException) {
            "Export failed."
        }
        render()
    }

    private fun render() {
        val current = log
        val latest = current?.lastCallback
        val now = SystemClock.elapsedRealtime()
        status.text = buildString {
            append(note).append("\n\n")
            if (current == null) return@buildString
            append("Mock callbacks: ${current.mockCallbacks}\n")
            append("Non-mock callbacks ignored: ${current.ignoredNonMockCallbacks}\n")
            append("Cached mock reads: ${current.lastKnownReads}\n")
            if (latest != null) {
                append("Seconds since last mock callback: ${(now - latest.receivedElapsedMillis) / 1000}\n")
                append(String.format(Locale.ROOT, "Latest mock fix: %.6f, %.6f\n", latest.latitude, latest.longitude))
                latest.speedMetersPerSecond?.let { append(String.format(Locale.ROOT, "Speed: %.3f m/s\n", it)) }
            }
        }
        startButton.isEnabled = !observing
        stopButton.isEnabled = observing
        markButton.isEnabled = observing
        lastKnownButton.isEnabled = observing
        exportButton.isEnabled = current != null && !observing
    }

    @Deprecated("Abstract before API 30, so it must exist on Android 10.")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onProviderEnabled(provider: String) = Unit

    override fun onProviderDisabled(provider: String) = Unit

    private fun Location.isMockFix(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) isMock else @Suppress("DEPRECATION") isFromMockProvider

    private fun Location.toMockFix(source: Source) = MockFix(
        source = source,
        fixElapsedMillis = elapsedRealtimeNanos / NANOS_PER_MILLI,
        receivedElapsedMillis = SystemClock.elapsedRealtime(),
        latitude = latitude,
        longitude = longitude,
        speedMetersPerSecond = if (hasSpeed()) speed else null,
    )

    private companion object {
        const val PERMISSION_REQUEST = 1
        const val TICK_MILLIS = 1_000L
        const val NANOS_PER_MILLI = 1_000_000L
        const val EXPORT_FOLDER = "exports"
        const val INTRO = "A test tool for RouteMock. It records only mock-flagged GPS fixes, between Start " +
            "and Stop, while on screen. It has no internet access."
    }
}
