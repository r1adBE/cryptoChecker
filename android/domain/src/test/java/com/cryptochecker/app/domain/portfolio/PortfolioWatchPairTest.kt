package com.cryptochecker.app.domain.portfolio

import com.cryptochecker.app.domain.portfolio.PortfolioWatchPair.Candidate
import com.cryptochecker.app.parity.ParityJson
import com.cryptochecker.app.parity.list
import com.cryptochecker.app.parity.num
import com.cryptochecker.app.parity.obj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kurs aus einem Merklisten-Paar an einer anderen Börse statt der ungeprüften Kerze. Wie `PortfolioTxFormTests` (iOS). */
class PortfolioWatchPairTest {

    private fun w(
        exchange: String, base: String, quote: String, spot: Boolean = true, disabled: Boolean = false, at: Long = 0,
    ) = Candidate(exchange, base, quote, null, spot, disabled, at)

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
        // Zwei verschiedene Token unter «CAT» in der Merkliste → unklar, welcher im Portfolio steht
        val gate = Candidate("GateIo", "CAT", "USDT", null, true, false, 5, name = "Simon's Cat")
        val other = Candidate("Upbit", "CAT", "KRW", null, true, false, 9, name = "Catcoin")
        assertNull(PortfolioWatchPair.pick("CAT", listOf(gate, other)))
        // Gleicher Name in anderer Schreibweise oder unbekannt: gilt als derselbe Token
        val same = other.copy(name = "Simons Cat")
        assertEquals(gate, PortfolioWatchPair.pick("CAT", listOf(gate, same)))
        assertEquals(gate, PortfolioWatchPair.pick("CAT", listOf(gate, other.copy(name = null))))
        // Unbenutzbares Paar (deaktiviert) zählt nicht als Widerspruch
        assertEquals(gate, PortfolioWatchPair.pick("CAT", listOf(gate, other.copy(disabled = true))))
    }

    /** Gemeinsame Fälle (`portfolio_watch_pair.json`, iOS `PortfolioTxFormTests`): nicht gehandelt bzw. stillgelegt nie. */
    @Test
    fun pickAsFixture() {
        val cases = ParityJson.load("portfolio_watch_pair.json")["pick"].list().map { it.obj() }
        assertTrue(cases.size >= 6)
        for (c in cases) {
            val watches = c["watches"].list().map { it.obj() }.map { x ->
                Candidate(
                    exchangeKey = x["exchange"] as String, base = x["base"] as String, quote = x["quote"] as String,
                    pairId = null, spot = x["spot"] as Boolean? ?: true, disabled = x["disabled"] as Boolean? ?: false,
                    lastUpdate = x["at"].num()!!.toLong(), notTraded = x["notTraded"] as Boolean? ?: false,
                )
            }
            val got = PortfolioWatchPair.pick(c["coin"] as String, watches)?.let { "${it.exchangeKey}|${it.quote}" }
            assertEquals(c["name"] as String, c["expected"] as String?, got)
        }
    }
}
