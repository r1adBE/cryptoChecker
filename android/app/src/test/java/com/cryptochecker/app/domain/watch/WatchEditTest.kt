package com.cryptochecker.app.domain.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchEditTest {

    private val btc = WatchEdit.Key("Binance", "BTC", "USDT", "NONE")

    @Test
    fun `same selection is unchanged, even when the lookup finds the entry itself`() {
        assertEquals(WatchEdit.Outcome.UNCHANGED, WatchEdit.decide(7, btc, btc.copy(), 7))
        assertEquals(WatchEdit.Outcome.UNCHANGED, WatchEdit.decide(7, btc, btc, null))
    }

    @Test
    fun `other exchange, pair or contract is a change`() {
        assertEquals(WatchEdit.Outcome.CHANGED, WatchEdit.decide(7, btc, btc.copy(marketKey = "Kraken"), null))
        assertEquals(WatchEdit.Outcome.CHANGED, WatchEdit.decide(7, btc, btc.copy(quote = "USDC"), null))
        assertEquals(WatchEdit.Outcome.CHANGED, WatchEdit.decide(7, btc, btc.copy(contract = "PERPETUAL"), null))
        // Treffer ist der Eintrag selbst (kann bei einem Wettlauf vorkommen): kein Doppel
        assertEquals(WatchEdit.Outcome.CHANGED, WatchEdit.decide(7, btc, btc.copy(base = "ETH"), 7))
    }

    @Test
    fun `target already watched by another entry is a duplicate`() {
        assertEquals(WatchEdit.Outcome.DUPLICATE, WatchEdit.decide(7, btc, btc.copy(base = "ETH"), 3))
        assertEquals(WatchEdit.Outcome.DUPLICATE, WatchEdit.decide(7, btc, btc.copy(contract = "PERPETUAL"), 9))
    }

    @Test
    fun `alarm warning only for a different selection with alarms`() {
        assertTrue(WatchEdit.warnAlarms(btc, btc.copy(quote = "USDC"), 2))
        assertFalse(WatchEdit.warnAlarms(btc, btc.copy(quote = "USDC"), 0))
        assertFalse(WatchEdit.warnAlarms(btc, btc, 2))
        assertFalse(WatchEdit.warnAlarms(btc, null, 2))
    }
}
