package com.cryptochecker.app.data.local

import com.cryptochecker.app.domain.local.WatchlistWriteRefusal
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hinweis «nicht gespeichert» bei gesperrter Merkliste ([WatchlistWriteRefusal]): höchstens einmal je Häufung.
 * Dass jeder Schreibweg des Nutzers gesperrt ablehnt, der Hintergrund weiterschreibt und «Rückgängig» die Obergrenze hält,
 * prüft `WatchlistLockDeviceTest` mit echtem Room; dass die Oberfläche gesperrt keine Folgen auslöst
 * `WatchlistViewModelTest`, die Reihenfolge an jeder Aufrufstelle die Regeln `write-consent` und `write-order` in
 * `.github/scripts/source_rules.py`; das Verhalten der Zustimmung `WatchlistWriteConsentTest`.
 */
class WatchlistWriteRefusalTest {

    @Test
    fun refusalHintIsThrottled() {
        val gap = WatchlistWriteRefusal.MIN_GAP_MILLIS
        assertTrue(WatchlistWriteRefusal.shouldNotify(lastAt = 0L, now = 5L))
        assertFalse(WatchlistWriteRefusal.shouldNotify(lastAt = 1_000L, now = 1_000L + gap - 1))
        assertTrue(WatchlistWriteRefusal.shouldNotify(lastAt = 1_000L, now = 1_000L + gap))
        // Uhr rückwärts (anderer Bezug): nicht als «zu früh» schlucken
        assertTrue(WatchlistWriteRefusal.shouldNotify(lastAt = 10_000L, now = 5_000L))
    }

    @Test
    fun reportFiresOncePerBurst() {
        // Weit weg von anderen Tests (gemeinsamer Zustand des Objekts)
        val start = 9_000_000_000L
        assertTrue(WatchlistWriteRefusal.report(start))
        assertFalse(WatchlistWriteRefusal.report(start + 10))
        assertFalse(WatchlistWriteRefusal.report(start + WatchlistWriteRefusal.MIN_GAP_MILLIS - 1))
        assertTrue(WatchlistWriteRefusal.report(start + WatchlistWriteRefusal.MIN_GAP_MILLIS))
    }
}
