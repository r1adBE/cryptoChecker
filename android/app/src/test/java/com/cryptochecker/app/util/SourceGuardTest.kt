package com.cryptochecker.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.AssumptionViolatedException
import org.junit.Test

/**
 * Meta-Test der Pfadwächter: ein fehlender Pfad lässt den Wächtertest scheitern bzw. überspringen. Dass jeder Test, der
 * einen iOS-Pfad liest, [SourceGuard] nutzt, prüft die Regel `source-guard` in `.github/scripts/source_rules.py`.
 */
class SourceGuardTest {

    @Test
    fun missingAndroidSourceFails() {
        assertThrows(AssertionError::class.java) { SourceGuard.android(null as String?, "Gibt/Es/Nicht.kt") }
        assertEquals("da", SourceGuard.android("da", "x"))
    }

    @Test
    fun missingIosSourceIsVisible() {
        // Ohne Pflicht: übersprungen (nicht grün); mit Pflicht (CI): Fehler
        assertThrows(AssumptionViolatedException::class.java) { SourceGuard.ios(null as String?, "ios/Gibt.swift", requireIos = false) }
        assertThrows(AssertionError::class.java) { SourceGuard.ios(null as String?, "ios/Gibt.swift", requireIos = true) }
        assertEquals("da", SourceGuard.ios("da", "x", requireIos = true))
    }
}
