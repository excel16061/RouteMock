package com.routemock.app.routing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureEndpointTest {
    @Test fun `https addresses pass`() {
        assertTrue(SecureEndpoint.isSecure("https://tiles.openfreemap.org/styles/liberty"))
        assertTrue(SecureEndpoint.isSecure("HTTPS://example.com/style.json?key=abc"))
        assertTrue(SecureEndpoint.isSecureBase("https://valhalla1.openstreetmap.de"))
        assertTrue(SecureEndpoint.isSecureBase("https://example.com:8443/valhalla/"))
    }

    @Test fun `anything but https fails`() {
        listOf("http://example.com", "ftp://example.com", "file:///etc/hosts", "asset://style.json", "example.com", "")
            .forEach { assertFalse(it, SecureEndpoint.isSecure(it)) }
    }

    @Test fun `embedded credentials fail`() {
        assertFalse(SecureEndpoint.isSecure("https://user:pass@example.com"))
        assertFalse(SecureEndpoint.isSecure("https://example.com@evil.example/"))
    }

    @Test fun `a host is required`() {
        assertFalse(SecureEndpoint.isSecure("https://"))
        assertFalse(SecureEndpoint.isSecure("https:///path"))
    }

    @Test fun `malformed addresses and fragments fail`() {
        assertFalse(SecureEndpoint.isSecure("https://exa mple.com"))
        assertFalse(SecureEndpoint.isSecure("https://example.com/#frag"))
    }

    @Test fun `a base address must not carry a query`() {
        assertTrue(SecureEndpoint.isSecure("https://example.com?key=abc"))
        assertFalse(SecureEndpoint.isSecureBase("https://example.com?key=abc"))
    }
}
