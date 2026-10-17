package com.cryptochecker.app.domain.convert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Swift-Spiegel: SatsTests.swift. */
class SatsTest {

    @Test
    fun onlyBaseBtcCounts() {
        assertTrue(Sats.isBitcoin("BTC"))
        assertTrue(Sats.isBitcoin(" btc "))
        assertFalse(Sats.isBitcoin("WBTC"))
        assertFalse(Sats.isBitcoin("ETH"))
        assertFalse(Sats.isBitcoin(null))
    }

    @Test
    fun satsPerUnitUsesConvertedPrice() {
        // BTC/USDT 100’000, 1 USDT = 0.8 CHF → 80’000 CHF → 1’250 Sats je CHF
        assertEquals(1_250.0, Sats.perUnit(100_000.0, 0.8)!!, 1e-9)
        // Gleiche Währung: Faktor 1
        assertEquals(1_000.0, Sats.perUnit(100_000.0, 1.0)!!, 1e-9)
    }

    @Test
    fun invalidInputsGiveNothing() {
        assertNull(Sats.perUnit(null, 1.0))
        assertNull(Sats.perUnit(100_000.0, null))
        assertNull(Sats.perUnit(0.0, 1.0))
        assertNull(Sats.perUnit(-5.0, 1.0))
        assertNull(Sats.perUnit(100_000.0, 0.0))
        assertNull(Sats.perUnit(Double.NaN, 1.0))
        assertNull(Sats.perUnit(100_000.0, Double.POSITIVE_INFINITY))
    }

    @Test
    fun decimalsFollowSize() {
        assertEquals(0, Sats.decimals(1_250.0))
        assertEquals(0, Sats.decimals(100.0))
        assertEquals(1, Sats.decimals(6.7))
        assertEquals(3, Sats.decimals(0.0625))
    }
}
