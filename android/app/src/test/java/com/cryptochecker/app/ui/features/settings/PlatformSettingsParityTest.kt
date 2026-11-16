package com.cryptochecker.app.ui.features.settings

import com.cryptochecker.app.util.SourceGuard
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Gleiche Einstellungen auf Android und iOS: Die Einträge der Einstellungs-Suche (`add("…"` in beiden Katalogen, ohne
 * die Seiten «page.…») sind auf beiden Plattformen dieselben – bis auf die gewollten Android-Abweichungen
 * ([ANDROID_ONLY], begründet in DEVELOPMENT.md, Abschnitt «Platform differences»). Kommt eine Einstellung nur auf einer
 * Plattform dazu, wird dieser Test rot, statt dass die Lücke erst beim Gerätetest auffällt.
 */
class PlatformSettingsParityTest {

    @Test
    fun searchEntriesMatchExceptDocumentedDifferences() {
        val android = File(SourceGuard.android(findUp("android/app/src/main/java/com/cryptochecker/app"), "Android-Quellen"),
            "ui/features/settings/SettingsSearchCatalog.kt").readText()
        val ios = File(SourceGuard.ios(findUp("ios/App"), "ios/App"), "Features/Settings/SettingsSearchCatalog.swift").readText()
        val androidIds = ids(android)
        val iosIds = ids(ios)
        assertEquals("Nur auf Android (nicht in der Liste der gewollten Abweichungen)", ANDROID_ONLY, androidIds - iosIds)
        assertEquals("Nur auf iOS", emptySet<String>(), iosIds - androidIds)
    }

    private fun ids(source: String): Set<String> =
        Regex("""add\("([^"]+)"""").findAll(source).map { it.groupValues[1] }.filterNot { it.startsWith("page.") }.toSet()

    private fun findUp(rel: String): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val f = File(dir, rel)
            if (f.isDirectory) return f
            dir = dir.parentFile
        }
        return null
    }

    companion object {
        /** Gibt es unter iOS nicht: Akku-Optimierung, exakte Wecker, dauerhafte Mitteilung, Kurs-Overlay, Uhr-Weiterleitung. */
        val ANDROID_ONLY = setOf("updates.battery", "updates.exact", "updates.ongoing", "updates.overlay", "updates.watch")
    }
}
