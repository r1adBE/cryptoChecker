package com.cryptochecker.app.wearable

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «App zurücksetzen» räumt auch die Uhr auf: die Einträge des Handys liegen bei den Play-Diensten, nicht im App-Speicher
 * — sie werden vor `clearApplicationUserData()` gelöscht ([PhoneWatchSync.clearWatch]), die Uhr vergisst dann
 * Portfolio-Wert, Favoriten und Kurse vom Handy (`WatchDataDeletionTest` in `:wear`). Wie iOS `PhoneWatchLink.reset`.
 * Reihenfolge und Löschweg prüft die Regel `android-wiring` in `.github/scripts/source_rules.py`.
 */
class WatchResetTest {

    /** Das Löschen unter dem Präfix /cc/watch trifft Wahl, Kurse und Portfolio-Wert. */
    @Test
    fun clearWatchDeletesAllOwnEntries() {
        assertTrue(PhoneWatchFeed.QUOTES_PATH.startsWith(PhoneWatchSync.PATH))
        assertTrue(PhoneWatchFeed.PORTFOLIO_PATH.startsWith(PhoneWatchSync.PATH))
    }
}
