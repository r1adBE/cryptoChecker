package com.cryptochecker.app.data.local

import com.cryptochecker.app.util.SourceGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Auto-Backup-Regeln für Android 8/8.1 (res/xml/backup_rules.xml): Dort wirkt `requireFlags` nicht, jede
 * eingeschlossene Datei ginge ohne Ende-zu-Ende-Verschlüsselung in die Cloud. Darum nur App-Sprache, Favoriten und
 * die Übernahme-Marke — keine Einstellungen (Adresse eines eigenen Esplora-Servers), kein Portfolio, keine Merkliste.
 */
class BackupRulesAndroid8Test {

    private val includes: List<Pair<String, String>> by lazy {
        val file = SourceGuard.android(resourceFile("xml/backup_rules.xml"), "res/xml/backup_rules.xml")
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val doc = factory.newDocumentBuilder().parse(file)
        assertEquals("full-backup-content", doc.documentElement.tagName)
        val nodes = doc.getElementsByTagName("include")
        (0 until nodes.length).map { i ->
            val e = nodes.item(i) as Element
            e.getAttribute("domain") to e.getAttribute("path")
        }
    }

    @Test
    fun settingsAreNotBackedUp() {
        assertFalse(includes.any { (_, path) -> "settings.preferences_pb" in path || path.startsWith("datastore") })
        assertFalse(includes.any { (domain, _) -> domain == "file" })
    }

    @Test
    fun neitherPortfolioNorWatchlistAreBackedUp() {
        assertFalse(includes.any { (domain, _) -> domain == "database" })
        assertFalse(includes.any { (_, path) -> path.startsWith("cryptochecker") || "portfolio_system_backup" in path })
    }

    @Test
    fun onlyLanguageFavoritesAndTakeoverMark() {
        val expected = setOf("app_language.xml", "favorites.xml", "portfolio.xml").map { "sharedpref" to it }.toSet()
        assertEquals(expected, includes.toSet())
        // je Datei beide Zeilen (requireFlags wie xml-v28, hier wirkungslos)
        assertTrue(includes.size == expected.size * 2)
    }

    private fun resourceFile(rel: String): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (prefix in listOf("", "app/", "android/app/")) {
                val f = File(dir, prefix + "src/main/res/" + rel)
                if (f.isFile) return f
            }
            dir = dir.parentFile
        }
        return null
    }
}
