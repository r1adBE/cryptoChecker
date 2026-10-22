package com.cryptochecker.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.AssumptionViolatedException
import org.junit.Test

/** Meta-Test der Pfadwächter (seit Build 105): ein fehlender Pfad lässt den Wächtertest scheitern bzw. überspringen. */
class SourceGuardTest {

    @Test
    fun fehlendeAndroidQuelleScheitert() {
        assertThrows(AssertionError::class.java) { SourceGuard.android(null as String?, "Gibt/Es/Nicht.kt") }
        assertEquals("da", SourceGuard.android("da", "x"))
    }

    @Test
    fun fehlendeIosQuelleIstSichtbar() {
        // Ohne Pflicht: übersprungen (nicht grün); mit Pflicht (CI): Fehler
        assertThrows(AssumptionViolatedException::class.java) { SourceGuard.ios(null as String?, "ios/Gibt.swift", requireIos = false) }
        assertThrows(AssertionError::class.java) { SourceGuard.ios(null as String?, "ios/Gibt.swift", requireIos = true) }
        assertEquals("da", SourceGuard.ios("da", "x", requireIos = true))
    }
}
