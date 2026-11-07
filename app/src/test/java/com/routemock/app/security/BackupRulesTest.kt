package com.routemock.app.security

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * Consent receipts are plain files, so a restored copy could present consent nobody gave on
 * that device. These checks keep every storage domain out of backup and device transfer on
 * every supported Android version.
 */
class BackupRulesTest {
    private val domains = setOf("root", "file", "database", "sharedpref", "external")

    private fun parse(path: String): Document =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File(path))

    private fun Element.excludedDomains(): Set<String> {
        val excludes = getElementsByTagName("exclude")
        return (0 until excludes.length).map { (excludes.item(it) as Element).getAttribute("domain") }.toSet()
    }

    private fun applicationAttribute(name: String): String =
        (parse("src/main/AndroidManifest.xml").getElementsByTagName("application").item(0) as Element)
            .getAttributeNS("http://schemas.android.com/apk/res/android", name)

    @Test fun `backup is off and both rule files are referenced`() {
        assertEquals("false", applicationAttribute("allowBackup"))
        assertEquals("@xml/data_extraction_rules", applicationAttribute("dataExtractionRules"))
        assertEquals("@xml/backup_rules", applicationAttribute("fullBackupContent"))
    }

    @Test fun `android 11 and lower exclude every domain and include nothing`() {
        val rules = parse("src/main/res/xml/backup_rules.xml").documentElement
        assertEquals(domains, rules.excludedDomains())
        assertEquals(0, rules.getElementsByTagName("include").length)
    }

    @Test fun `android 12 and higher exclude every domain from cloud backup and device transfer`() {
        val document = parse("src/main/res/xml/data_extraction_rules.xml")
        for (section in listOf("cloud-backup", "device-transfer")) {
            val rules = document.getElementsByTagName(section).item(0) as Element
            assertEquals(section, domains, rules.excludedDomains())
            assertEquals(section, 0, rules.getElementsByTagName("include").length)
        }
        assertTrue(document.getElementsByTagName("include").length == 0)
    }
}
