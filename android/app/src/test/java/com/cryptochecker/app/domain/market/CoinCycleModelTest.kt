package com.cryptochecker.app.domain.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoinCycleModelTest {

    private fun inputs(
        price: Double,
        sma200d: Double?,
        sma50d: Double? = sma200d,
        sma200w: Double? = null,
        ath: Double? = price,
        rsiDaily: Double? = 50.0,
        rsiWeekly: Double? = 50.0,
        sma111d: Double? = null,
        sma350d: Double? = null,
        price30dAgo: Double? = price,
    ) = CoinInputs(
        price = price, sma50d = sma50d, sma200d = sma200d, sma111d = sma111d, sma350d = sma350d,
        price30dAgo = price30dAgo, sma200w = sma200w, ath = ath, athDate = null,
        rsiDaily = rsiDaily, rsiWeekly = rsiWeekly, vsBtc90d = null, historyDays = 1500,
    )

    @Test
    fun `Überhitzung ergibt Extrem Bull`() {
        val r = CoinCycleModel.evaluate(
            "ETH",
            inputs(
                price = 100.0, sma200d = 40.0, sma200w = 25.0, rsiDaily = 85.0, rsiWeekly = 85.0,
                sma111d = 80.0, sma350d = 35.0, price30dAgo = 55.0,
            )
        )
        assertEquals(MarketZone.EXTREME_BULL, r.zone)
        assertTrue(r.topScore >= 7)
    }

    @Test
    fun `Tiefer Absturz ergibt Bear oder Extrem Bear`() {
        val r = CoinCycleModel.evaluate(
            "SOL",
            inputs(price = 10.0, sma200d = 20.0, sma50d = 15.0, sma200w = 12.0, ath = 50.0, rsiDaily = 20.0, rsiWeekly = 25.0)
        )
        assertTrue(r.zone == MarketZone.EXTREME_BEAR || r.zone == MarketZone.BEAR)
        assertTrue(r.bottomScore >= 7)
    }

    @Test
    fun `fehlende Daten werden als fehlend markiert`() {
        val r = CoinCycleModel.evaluate("NEW", inputs(price = 1.0, sma200d = null, sma50d = null, ath = null, rsiWeekly = null))
        assertNull(r.signals.first { it.id == CoinSignalId.MAYER }.value)
        assertEquals(MarketZone.NEUTRAL, r.zone)
    }

    @Test
    fun `RSI steigt bei reinem Anstieg auf 100`() {
        val closes = (1..30).map { it.toDouble() }
        assertEquals(100.0, Indicators.rsi(closes)!!, 0.001)
    }
}
