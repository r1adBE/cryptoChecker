package com.cryptochecker.app.domain.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Live-Strom für Merkliste und Paarseite ([LiveDemand]). */
class LiveDemandTest {
    private fun pair(id: Long) = LivePair(id, "binance", "Binance", null, "BTC", "USDT", "NONE")

    @Test
    fun pagePairIsAddedOnlyWhenTheListLacksIt() {
        val demand = LiveDemand()
        assertEquals(listOf(pair(1)), demand.setListPairs(listOf(pair(1))))
        // Paar der Seite steht schon in der Merkliste: dieselbe Liste (keine neue Verbindung)
        assertEquals(listOf(pair(1)), demand.setPagePair(pair(1)))
        // Seite aus Markt bzw. Widget: Paar fehlt in der gezeigten Gruppe → dazu
        assertEquals(listOf(pair(1), pair(2)), demand.setPagePair(pair(2)))
        // Seite zu: wieder nur die Merkliste
        assertEquals(listOf(pair(1)), demand.setPagePair(null))
    }

    /** Merkliste stoppt erst, nachdem die Paarseite gestartet ist: der Strom bleibt an. */
    @Test
    fun overlappingStartAndStopKeepsTheStreamOn() {
        val demand = LiveDemand()
        assertTrue(demand.setVisible(LiveDemand.Owner.WATCHLIST, true))
        assertTrue(demand.setVisible(LiveDemand.Owner.PAIR_PAGE, true))
        assertTrue(demand.setVisible(LiveDemand.Owner.WATCHLIST, false))
        assertFalse(demand.setVisible(LiveDemand.Owner.PAIR_PAGE, false))
    }
}
