package com.cryptochecker.app.domain.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wie `CoinIndicatorModelTests` (iOS, ExchangesTests.swift). */
class CoinIndicatorModelTest {

    private fun inputs(
        price: Double = 100.0,
        sma50d: Double? = 110.0,
        sma200d: Double? = 80.0,
        sma200w: Double? = 50.0,
        ath: Double? = 200.0,
        price30dAgo: Double? = 125.0,
        rsiDaily: Double? = 41.6,
        rsiWeekly: Double? = 58.2,
        vsBtc90d: Double? = -12.5,
    ) = CoinInputs(
        price = price, sma50d = sma50d, sma200d = sma200d, sma111d = null, sma350d = null,
        price30dAgo = price30dAgo, sma200w = sma200w, ath = ath, athDate = null,
        rsiDaily = rsiDaily, rsiWeekly = rsiWeekly, vsBtc90d = vsBtc90d, historyDays = 1500,
    )

    private fun CoinReport.value(id: IndicatorId): Double? = indicators.first { it.id == id }.value

    @Test
    fun `Kennzahlen sind reine Messwerte in fester Reihenfolge`() {
        val r = CoinIndicatorModel.evaluate("ETH", inputs())
        assertEquals("ETH", r.symbol)
        assertEquals(
            listOf(
                IndicatorId.MAYER, IndicatorId.MA200W, IndicatorId.ATH_DISTANCE, IndicatorId.CHANGE_30D,
                IndicatorId.SMA50_VS_200, IndicatorId.RSI_WEEKLY, IndicatorId.RSI_DAILY, IndicatorId.VS_BTC,
            ),
            r.indicators.map { it.id }
        )
        assertEquals(1.25, r.value(IndicatorId.MAYER) ?: 0.0, 1e-9)
        assertEquals(50.0, r.value(IndicatorId.MA200W) ?: 0.0, 1e-9)
        assertEquals(50.0, r.value(IndicatorId.ATH_DISTANCE) ?: 0.0, 1e-9)
        assertEquals(-20.0, r.value(IndicatorId.CHANGE_30D) ?: 0.0, 1e-9)
        assertEquals(37.5, r.value(IndicatorId.SMA50_VS_200) ?: 0.0, 1e-9)
        assertEquals(58.2, r.value(IndicatorId.RSI_WEEKLY) ?: 0.0, 1e-9)
        assertEquals(41.6, r.value(IndicatorId.RSI_DAILY) ?: 0.0, 1e-9)
        assertEquals(-12.5, r.value(IndicatorId.VS_BTC) ?: 0.0, 1e-9)
    }

    @Test
    fun `neuer Coin ohne Verlauf hat Kennzahlen ohne Wert`() {
        val r = CoinIndicatorModel.evaluate(
            "NEW",
            inputs(sma50d = null, sma200d = null, sma200w = null, ath = null, price30dAgo = null,
                rsiDaily = null, rsiWeekly = null, vsBtc90d = null)
        )
        assertEquals(8, r.indicators.size)
        assertTrue(r.indicators.all { it.value == null })
    }
}
