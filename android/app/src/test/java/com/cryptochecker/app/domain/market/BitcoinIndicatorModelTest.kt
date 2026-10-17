package com.cryptochecker.app.domain.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Wie `BitcoinIndicatorModelTests` (iOS, MarketsTests.swift). */
class BitcoinIndicatorModelTest {

    private fun inputs(
        price: Double = 85_000.0,
        sma200d: Double? = 100_000.0,
        sma200w: Double? = 48_200.0,
        ath: Double? = 125_000.0,
        price30dAgo: Double? = 80_000.0,
        mvrv: Double? = 1.58,
        puell: Double? = 1.03,
        hash30: Double? = 102.0,
        hash60: Double? = 100.0,
    ) = CycleInputs(
        price = price, sma200d = sma200d, sma111d = null, sma350d = null,
        price30dAgo = price30dAgo, sma200w = sma200w, ath = ath, athDate = LocalDate.of(2025, 10, 6),
        mvrv = mvrv, puell = puell, hash30d = hash30, hash60d = hash60, today = LocalDate.of(2026, 10, 13),
    )

    private fun BitcoinReport.value(id: IndicatorId): Double? = indicators.first { it.id == id }.value

    @Test
    fun `Kennzahlen sind reine Messwerte in fester Reihenfolge`() {
        val r = BitcoinIndicatorModel.evaluate(inputs())
        assertEquals(
            listOf(
                IndicatorId.MAYER, IndicatorId.MA200W, IndicatorId.ATH_DISTANCE, IndicatorId.CHANGE_30D,
                IndicatorId.PI_CYCLE_GAP, IndicatorId.MVRV, IndicatorId.PUELL, IndicatorId.HASHRATE_30_60,
            ),
            r.indicators.map { it.id }
        )
        assertEquals(0.85, r.value(IndicatorId.MAYER) ?: 0.0, 1e-9)
        assertEquals(48_200.0, r.value(IndicatorId.MA200W) ?: 0.0, 1e-9)
        assertEquals(32.0, r.value(IndicatorId.ATH_DISTANCE) ?: 0.0, 1e-9)
        assertEquals(6.25, r.value(IndicatorId.CHANGE_30D) ?: 0.0, 1e-9)
        assertEquals(1.58, r.value(IndicatorId.MVRV) ?: 0.0, 1e-9)
        assertEquals(1.03, r.value(IndicatorId.PUELL) ?: 0.0, 1e-9)
        assertEquals(2.0, r.value(IndicatorId.HASHRATE_30_60) ?: 0.0, 1e-9)
        assertTrue(r.onChainAvailable)
    }

    @Test
    fun `ueber dem Allzeithoch ist der Abstand null`() {
        val r = BitcoinIndicatorModel.evaluate(inputs(price = 130_000.0))
        assertEquals(0.0, r.value(IndicatorId.ATH_DISTANCE) ?: -1.0, 1e-9)
    }

    @Test
    fun `fehlende Daten ergeben Kennzahlen ohne Wert`() {
        val r = BitcoinIndicatorModel.evaluate(
            inputs(sma200d = null, sma200w = null, ath = null, price30dAgo = null, mvrv = null, puell = null, hash30 = null)
        )
        assertEquals(8, r.indicators.size)
        assertTrue(r.indicators.all { it.value == null })
        assertFalse(r.onChainAvailable)
    }

    @Test
    fun `ungueltige Basis ergibt keinen Wert`() {
        val r = BitcoinIndicatorModel.evaluate(inputs(sma200d = 0.0, hash60 = 0.0))
        assertNull(r.value(IndicatorId.MAYER))
        assertNull(r.value(IndicatorId.HASHRATE_30_60))
    }

    @Test
    fun `pi cycle gap is the distance of the two averages`() {
        val r = BitcoinIndicatorModel.evaluate(inputs().copy(sma111d = 90_000.0, sma350d = 50_000.0))
        // 90'000 ÷ (2 × 50'000) − 1 = −10 %
        assertEquals(-10.0, r.value(IndicatorId.PI_CYCLE_GAP)!!, 1e-9)
    }

    @Test
    fun `history gives percentile and flat price projection, no verdict`() {
        val start = LocalDate.of(2016, 6, 1)
        // 1000 Tage steigend von 1000 auf 2000
        val closes = (0 until 1000).map { start.plusDays(it.toLong()) to 1000.0 + it }
        val current = mapOf(IndicatorId.MAYER to 1.0, IndicatorId.CHANGE_30D to 0.0)
        val h = IndicatorHistoryMath.compute(closes, current, price = 2000.0)!!
        // Mayer in einer stetig steigenden Reihe immer > 1 → an 0 % der Tage tiefer als 1.0
        assertEquals(0.0, h.stats.getValue(IndicatorId.MAYER).percentileBelow!!, 1e-9)
        // 30-Tage-Veränderung immer > 0 → 0 % tiefer als 0
        assertEquals(0.0, h.stats.getValue(IndicatorId.CHANGE_30D).percentileBelow!!, 1e-9)
        // Bei gleichem Kurs 2000 sinkt der Abstand zum Schnitt nicht ins Negative: Mayer > 1
        assertTrue(h.stats.getValue(IndicatorId.MAYER).flat30!! > 1.0)
        assertEquals(start.plusDays(199), h.since)
        // Ohne heutigen Wert keine Einordnung
        assertNull(h.stats[IndicatorId.PI_CYCLE_GAP])
    }

    @Test
    fun `percentile needs a year of data`() {
        assertNull(IndicatorHistoryMath.percentileBelow(List(100) { it.toDouble() }, 50.0))
        assertEquals(50.0, IndicatorHistoryMath.percentileBelow(List(400) { it.toDouble() }, 200.0)!!, 1e-9)
    }
}
