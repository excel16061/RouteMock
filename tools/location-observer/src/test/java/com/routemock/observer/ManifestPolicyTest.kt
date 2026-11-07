package com.routemock.observer

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

/** The tool's limits are part of its purpose, so a change to them must fail loudly. */
class ManifestPolicyTest {
    private val androidNs = "http://schemas.android.com/apk/res/android"
    private val manifest: Document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))

    private fun names(tag: String): List<String> {
        val nodes = manifest.getElementsByTagName(tag)
        return (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS(androidNs, "name") }
    }

    @Test fun `only foreground location is requested`() {
        assertEquals(
            setOf("android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_FINE_LOCATION"),
            names("uses-permission").toSet(),
        )
    }

    @Test fun `nothing can run in the background`() {
        for (tag in listOf("service", "receiver", "provider")) assertEquals(tag, 0, names(tag).size)
        assertEquals(listOf(".MainActivity"), names("activity"))
    }

    @Test fun `exports cannot leave through backup`() {
        val application = manifest.getElementsByTagName("application").item(0) as Element
        assertEquals("false", application.getAttributeNS(androidNs, "allowBackup"))
    }
}
