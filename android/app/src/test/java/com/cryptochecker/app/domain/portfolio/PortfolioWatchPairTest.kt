package com.cryptochecker.app.domain.portfolio

import com.cryptochecker.app.domain.portfolio.PortfolioWatchPair.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Kurs aus einem Merklisten-Paar an einer anderen Börse statt der ungeprüften Kerze (L-2). Wie `PortfolioTxFormTests` (iOS). */
class PortfolioWatchPairTest {

    private fun w(
        market: String, base: String, quote: String, spot: Boolean = true, disabled: Boolean = false, at: Long = 0,
    ) = Candidate(market, base, quote, null, spot, disabled, at)

    @Test
    fun picksActiveSpotPairOnAnotherExchange() {
        val kraken = w("Kraken", "xyz", "EUR")
        assertEquals(kraken, PortfolioWatchPair.pick("XYZ", listOf(kraken, w("Kraken", "ABC", "USD"))))
    }

    @Test
    fun ignoresBinanceFuturesDisabledAndUnknownQuotes() {
        assertNull(PortfolioWatchPair.pick("XYZ", listOf(w("Binance", "XYZ", "USDT"))))
        assertNull(PortfolioWatchPair.pick("XYZ", listOf(w("BybitFutures", "XYZ", "USDT"))))
        assertNull(PortfolioWatchPair.pick("XYZ", listOf(w("Kraken", "XYZ", "USD", spot = false))))
        assertNull(PortfolioWatchPair.pick("XYZ", listOf(w("Kraken", "XYZ", "USD", disabled = true))))
        assertNull(PortfolioWatchPair.pick("XYZ", listOf(w("DexScreener", "XYZ", "USD"))))
        // Andere Stablecoins und Krypto als Gegenwährung: nicht umrechenbar
        assertNull(PortfolioWatchPair.pick("XYZ", listOf(w("Kraken", "XYZ", "USDC"), w("Kraken", "XYZ", "BTC"))))
    }

    @Test
    fun prefersQuoteOrderThenFreshestThenMarket() {
        val eur = w("Kraken", "XYZ", "EUR", at = 50)
        val usdOld = w("Bitstamp", "XYZ", "USD", at = 10)
        val usdNew = w("Coinbase", "XYZ", "USD", at = 20)
        val usdt = w("Kucoin", "XYZ", "USDT", at = 1)
        assertEquals(usdt, PortfolioWatchPair.pick("XYZ", listOf(eur, usdOld, usdNew, usdt)))
        assertEquals(usdNew, PortfolioWatchPair.pick("XYZ", listOf(eur, usdOld, usdNew)))
        val a = w("Alpha", "XYZ", "USD", at = 5)
        val b = w("Beta", "XYZ", "USD", at = 5)
        assertEquals(a, PortfolioWatchPair.pick("XYZ", listOf(b, a)))
    }

    @Test
    fun conflictingNamesGiveNoPick() {
        // K-8: zwei verschiedene Token unter «CAT» in der Merkliste → unklar, welcher im Portfolio steht
        val gate = Candidate("GateIo", "CAT", "USDT", null, true, false, 5, name = "Simon's Cat")
        val other = Candidate("Upbit", "CAT", "KRW", null, true, false, 9, name = "Catcoin")
        assertNull(PortfolioWatchPair.pick("CAT", listOf(gate, other)))
        // Gleicher Name in anderer Schreibweise oder unbekannt: wie bisher
        val same = other.copy(name = "Simons Cat")
        assertEquals(gate, PortfolioWatchPair.pick("CAT", listOf(gate, same)))
        assertEquals(gate, PortfolioWatchPair.pick("CAT", listOf(gate, other.copy(name = null))))
        // Unbenutzbares Paar (deaktiviert) zählt nicht als Widerspruch
        assertEquals(gate, PortfolioWatchPair.pick("CAT", listOf(gate, other.copy(disabled = true))))
    }
}
