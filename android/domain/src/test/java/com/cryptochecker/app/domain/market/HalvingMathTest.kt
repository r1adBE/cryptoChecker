package com.cryptochecker.app.domain.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** Nächstes Halving aus Blockhöhe und mittlerer Blockzeit (Tag in UTC); Swift: `HalvingMathTests` (ExchangesTests.swift). */
class HalvingMathTest {

    @Test
    fun `next halving from block height`() {
        val now = LocalDate.of(2026, 10, 13).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val e = HalvingMath.estimate(tipHeight = 1_049_856, avgBlockSeconds = 600.0, nowMillis = now)!!
        assertEquals(1_050_000, e.height)
        assertEquals(144, e.remainingBlocks)
        // 144 Blöcke × 10 Minuten = 1 Tag
        assertEquals(LocalDate.of(2026, 10, 14), e.date)
    }

    @Test
    fun `invalid input gives no estimate`() {
        assertNull(HalvingMath.estimate(0, 600.0, 0L))
        assertNull(HalvingMath.estimate(900_000, 30.0, 0L))
        assertNull(HalvingMath.estimate(900_000, Double.NaN, 0L))
    }
}
