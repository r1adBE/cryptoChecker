package com.cryptochecker.app.domain.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Marktbreite («Top 30») und ihr Satz in «Was gerade auffällt» (iOS: CryptoPulseBreadthTests). */
class CryptoPulseBreadthTest {

    private fun changes(up: Int, down: Int, flat: Int = 0) =
        List(up) { 1.0 } + List(down) { -1.0 } + List(flat) { 0.0 }

    @Test
    fun countsUpDownAndTotal() {
        assertEquals(PulseBreadth(up = 22, down = 7, total = 30), CryptoPulse.breadth(changes(22, 7, 1)))
    }

    @Test
    fun usesOnlyTheLargestThirtyAndDropsInvalid() {
        val list = listOf(Double.NaN) + changes(30, 0) + changes(0, 10)
        assertEquals(PulseBreadth(up = 30, down = 0, total = 30), CryptoPulse.breadth(list))
    }

    @Test
    fun tooFewValuesGiveNothing() {
        assertNull(CryptoPulse.breadth(changes(10, 4)))
        assertEquals(15, CryptoPulse.breadth(changes(10, 5))?.total)
    }

    @Test
    fun noteBroadAndNarrow() {
        assertEquals(PulseBreadthNote.BROAD_UP, CryptoPulse.breadthNote(PulseBreadth(23, 7, 30), 0.2))
        assertEquals(PulseBreadthNote.BROAD_DOWN, CryptoPulse.breadthNote(PulseBreadth(5, 25, 30), -0.2))
        // Bitcoin +2 %, aber nur 10 von 30 steigen
        assertEquals(PulseBreadthNote.NARROW_UP, CryptoPulse.breadthNote(PulseBreadth(10, 20, 30), 2.0))
        // Bitcoin −1.5 %, aber nur 9 von 30 fallen
        assertEquals(PulseBreadthNote.NARROW_DOWN, CryptoPulse.breadthNote(PulseBreadth(21, 9, 30), -1.5))
        // Nichts Deutliches
        assertNull(CryptoPulse.breadthNote(PulseBreadth(16, 14, 30), 0.5))
        assertNull(CryptoPulse.breadthNote(PulseBreadth(10, 20, 30), 0.9))
    }

    @Test
    fun reportCarriesBreadthAndMarket() {
        val input = PulseInput(
            btc24h = 2.0, eth24h = 1.0, sol24h = 0.5, btcVolumeRatio = null, fearGreed = null,
            fundingPercent = null, ethGasGwei = null, time = 1L,
            topChanges = changes(24, 6), marketCapUsd = 3.4e12, marketCap24h = 2.1,
        )
        val report = CryptoPulse.evaluate(input)!!
        assertEquals(PulseBreadth(24, 6, 30), report.breadth)
        assertEquals(PulseBreadthNote.BROAD_UP, report.breadthNote)
        assertEquals(3.4e12, report.marketCapUsd!!, 0.0)
        assertEquals(2.1, report.marketCap24h!!, 0.0)
        // Ohne Marktkapitalisierung auch keine Veränderung
        val noCap = CryptoPulse.evaluate(input.copy(marketCapUsd = null))!!
        assertNull(noCap.marketCap24h)
    }
}
