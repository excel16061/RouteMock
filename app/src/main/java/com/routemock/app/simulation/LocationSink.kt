package com.routemock.app.simulation

import com.routemock.app.playback.LocationFix

/** Where a session's fixes go. The controller knows nothing about Android providers. */
interface LocationSink {
    /** Prepares the sink and returns the names of the providers that will receive fixes. */
    fun open(): List<String>

    fun publish(fix: LocationFix)

    /** Releases everything. Returns false if something could not be cleaned up. */
    fun close(): Boolean
}

/** RouteMock is not the mock-location app selected in Developer options. */
class MockNotAuthorizedException(cause: Throwable? = null) : Exception(cause)

/** Sends nothing anywhere: a preview exists only inside the app. */
object PreviewSink : LocationSink {
    override fun open(): List<String> = emptyList()
    override fun publish(fix: LocationFix) = Unit
    override fun close(): Boolean = true
}
