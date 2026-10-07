package com.cryptochecker.app.domain.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CycleModelTest {

    private fun inputs(
        price: Double,
        sma200d: Double,
        mvrv: Double?,
        puell: Double?,
        ath: Double,
        athDate: LocalDate,
        today: LocalDate,
        sma200w: Double = price / 2,
        price30dAgo: Double = price,
        hash30: Double = 100.0,
        hash60: Double = 95.0,
    ) = CycleInputs(
        price = price, sma200d = sma200d, sma111d = sma200d, sma350d = sma200d,
        price30dAgo = price30dAgo, sma200w = sma200w, ath = ath, athDate = athDate,
        mvrv = mvrv, puell = puell, hash30d = hash30, hash60d = hash60, today = today,
    )

    @Test
    fun `Top 2021 ist extrem bullisch`() {
        // Etwa April 2021: MVRV ~3,9, Puell ~3,8, Mayer ~2,4, 11–12 Monate nach Halving
        val r = CycleModel.evaluate(
            inputs(price = 63_000.0, sma200d = 26_000.0, mvrv = 3.9, puell = 3.8,
                ath = 64_800.0, athDate = LocalDate.of(2021, 4, 14), today = LocalDate.of(2021, 5, 12),
                sma200w = 12_000.0, price30dAgo = 58_000.0)
        )
        assertEquals(MarketZone.EXTREME_BULL, r.zone)
        assertTrue(r.topScore >= 7)
    }

    @Test
    fun `Tief Ende 2022 ist extrem baerisch`() {
        // Nov./Dez. 2022: MVRV ~0,75, Puell ~0,45, −77 % vom Hoch, Kurs unter 200W-Schnitt
        val r = CycleModel.evaluate(
            inputs(price = 15_800.0, sma200d = 21_000.0, mvrv = 0.75, puell = 0.45,
                ath = 69_000.0, athDate = LocalDate.of(2021, 11, 10), today = LocalDate.of(2022, 12, 1),
                sma200w = 24_000.0, hash30 = 90.0, hash60 = 95.0)
        )
        assertEquals(MarketZone.EXTREME_BEAR, r.zone)
        assertTrue(r.bottomScore >= 7)
    }

    @Test
    fun `Oktober 2026 ist weder Top noch Bottom`() {
        // Werte aus Anfang Okt. 2026: MVRV ~1,58, Puell ~1,03, Kurs unter 200-Tage-Schnitt
        val r = CycleModel.evaluate(
            inputs(price = 85_000.0, sma200d = 95_000.0, mvrv = 1.58, puell = 1.03,
                ath = 126_000.0, athDate = LocalDate.of(2025, 10, 6), today = LocalDate.of(2026, 10, 3),
                sma200w = 58_000.0)
        )
        assertEquals(0, r.topScore)
        assertTrue(r.zone == MarketZone.BEAR || r.zone == MarketZone.NEUTRAL)
    }

    @Test
    fun `ohne On-Chain-Daten rechnet das Modell trotzdem`() {
        val r = CycleModel.evaluate(
            inputs(price = 85_000.0, sma200d = 70_000.0, mvrv = null, puell = null,
                ath = 90_000.0, athDate = LocalDate.of(2026, 9, 1), today = LocalDate.of(2026, 10, 3))
        )
        assertEquals(false, r.onChainAvailable)
        assertEquals(MarketZone.BULL, r.zone)
    }
}
