package com.cryptochecker.app.domain.market

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/** Wie `BitcoinHalvingTests` (iOS, ExchangesTests.swift). */
class BitcoinHalvingTest {

    @Test
    fun `last halving is the actual date`() {
        val info = BitcoinHalving.info(LocalDate.of(2026, 10, 13))
        assertEquals(LocalDate.of(2024, 4, 20), info.lastHalving)
    }

    @Test
    fun `next halving estimated in April 2028`() {
        val next = BitcoinHalving.info(LocalDate.of(2026, 10, 13)).nextHalvingEstimate
        assertEquals(2028, next.year)
        assertEquals(4, next.monthValue)
    }

    @Test
    fun `on halving day the new halving counts`() {
        assertEquals(LocalDate.of(2020, 5, 11), BitcoinHalving.info(LocalDate.of(2020, 5, 11)).lastHalving)
        assertEquals(LocalDate.of(2016, 7, 9), BitcoinHalving.info(LocalDate.of(2020, 5, 10)).lastHalving)
    }

    @Test
    fun `after the estimated date counting continues`() {
        val estimate = BitcoinHalving.info(LocalDate.of(2026, 1, 1)).nextHalvingEstimate
        val later = BitcoinHalving.info(estimate.plusDays(30))
        assertEquals(estimate, later.lastHalving)
        assertEquals(estimate.plusDays(BitcoinHalving.INTERVAL_DAYS), later.nextHalvingEstimate)
    }
}
