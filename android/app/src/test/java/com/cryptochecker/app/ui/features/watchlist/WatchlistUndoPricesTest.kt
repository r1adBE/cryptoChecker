package com.cryptochecker.app.ui.features.watchlist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Rückgängig» zeigt Kurs-Meldungen nur für wenige Paare gleich wieder; nach «Merkliste leeren» keine Meldung je Paar
 * (Android drosselt rund 5 pro Sekunde und verwirft den Rest still) — wie iOS, dort meldet die nächste Aktualisierung.
 */
class WatchlistUndoPricesTest {

    @Test
    fun einzelnesPaarZeigtDieMeldungWieder() {
        assertTrue(restoresPriceNotifications(1))
    }

    @Test
    fun wenigePaareBisZurGrenze() {
        assertTrue(restoresPriceNotifications(UNDO_PRICE_POST_MAX))
    }

    @Test
    fun vieleOderKeinePaareOhneMeldung() {
        assertFalse(restoresPriceNotifications(UNDO_PRICE_POST_MAX + 1))
        assertFalse(restoresPriceNotifications(40))
        assertFalse(restoresPriceNotifications(0))
    }
}
