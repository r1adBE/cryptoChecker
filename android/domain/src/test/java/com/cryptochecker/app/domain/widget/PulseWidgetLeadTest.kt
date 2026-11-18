package com.cryptochecker.app.domain.widget

import com.cryptochecker.app.domain.market.CryptoPulse
import com.cryptochecker.app.domain.market.MarketMoversResult
import com.cryptochecker.app.domain.market.MoverCoin
import com.cryptochecker.app.domain.market.PulseDetail
import com.cryptochecker.app.domain.market.PulseInput
import com.cryptochecker.app.domain.market.PulseReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Leitsatz des Pulse-Widgets ([PulseWidgetLead]); Gegenstück iOS `PulseWidgetLeadTests`. */
class PulseWidgetLeadTest {

    private fun coin(symbol: String, change: Double) = MoverCoin(symbol, symbol, null, change)

    private fun report(btc: Double, eth: Double, sol: Double, movers: MarketMoversResult?): PulseReport =
        CryptoPulse.evaluate(
            PulseInput(btc, eth, sol, 1.0, null, 0.01, null, time = 1_000L, movers = movers)
        )!!

    private val movers = MarketMoversResult(
        gainers = listOf(coin("SOL", 7.2), coin("ETH", 3.0)),
        losers = listOf(coin("DOGE", -6.4), coin("XRP", -2.0)),
    )

    @Test
    fun `broad fall names the strongest loser`() {
        val choice = PulseWidgetLead.choose(report(-4.0, -5.0, -6.0, movers))
        assertEquals(PulseWidgetLeadKind.DOWN, choice.kind)
        assertEquals("DOGE", choice.bottom?.symbol)
    }

    @Test
    fun `broad rise names the strongest gainer`() {
        val choice = PulseWidgetLead.choose(report(4.0, 5.0, 6.0, movers))
        assertEquals(PulseWidgetLeadKind.UP, choice.kind)
        assertEquals("SOL", choice.top?.symbol)
    }

    @Test
    fun `without the ranking the card sentence stays`() {
        assertEquals(PulseWidgetLeadKind.CARD, PulseWidgetLead.choose(report(-4.0, -5.0, -6.0, null)).kind)
        // Breiter Rückgang ohne einen Coin im Minus: Satz der Karte
        val onlyGainers = MarketMoversResult(listOf(coin("SOL", 1.0)), emptyList())
        assertEquals(PulseWidgetLeadKind.CARD, PulseWidgetLead.choose(report(-4.0, -5.0, -6.0, onlyGainers)).kind)
    }

    @Test
    fun `normal and neutral parts of the detail are dropped`() {
        assertNull(PulseWidgetLead.notable(PulseDetail.VOL_NORMAL_FUND_NEUTRAL))
        assertNull(PulseWidgetLead.notable(PulseDetail.FUND_NEUTRAL))
        assertNull(PulseWidgetLead.notable(PulseDetail.VOL_NORMAL))
        assertNull(PulseWidgetLead.notable(null))
        assertEquals(PulseDetail.VOL_ABOVE, PulseWidgetLead.notable(PulseDetail.VOL_ABOVE_FUND_NEUTRAL))
        assertEquals(PulseDetail.FUND_HIGH, PulseWidgetLead.notable(PulseDetail.VOL_NORMAL_FUND_HIGH))
        assertEquals(PulseDetail.VOL_BELOW_FUND_NEGATIVE, PulseWidgetLead.notable(PulseDetail.VOL_BELOW_FUND_NEGATIVE))
    }
}
