package com.cryptochecker.app.domain.market

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class BitcoinCycleTest {

    @Test
    fun `kurz nach dem Halving 2024 ist frueher Bull`() {
        val info = BitcoinCycle.info(LocalDate.of(2024, 7, 1))
        assertEquals(CyclePhase.EARLY_BULL, info.phase)
        assertEquals(LocalDate.of(2024, 4, 20), info.lastHalving)
    }

    @Test
    fun `Hoch Okt 2025 faellt in die Bull-Phase`() {
        assertEquals(CyclePhase.BULL, BitcoinCycle.info(LocalDate.of(2025, 10, 6)).phase)
    }

    @Test
    fun `Herbst 2026 ist Bear-Phase`() {
        val info = BitcoinCycle.info(LocalDate.of(2026, 10, 3))
        assertEquals(CyclePhase.BEAR, info.phase)
        assertEquals(29L, info.monthsSinceHalving)
    }

    @Test
    fun `Bear 2022 im Zyklus 2020`() {
        assertEquals(CyclePhase.BEAR, BitcoinCycle.info(LocalDate.of(2022, 6, 18)).phase)
    }

    @Test
    fun `vor dem naechsten Halving ist Erholung`() {
        assertEquals(CyclePhase.RECOVERY, BitcoinCycle.info(LocalDate.of(2027, 9, 1)).phase)
    }

    @Test
    fun `nach geschaetztem Halving 2028 beginnt neuer Zyklus`() {
        val info = BitcoinCycle.info(LocalDate.of(2028, 6, 1))
        assertEquals(CyclePhase.EARLY_BULL, info.phase)
    }
}
