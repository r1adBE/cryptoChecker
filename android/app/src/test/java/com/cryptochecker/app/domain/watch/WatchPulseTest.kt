package com.cryptochecker.app.domain.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchPulseTest {

    @Test
    fun `fewer than two changes - no pulse`() {
        assertNull(WatchPulse.of(emptyList()))
        assertNull(WatchPulse.of(listOf(1.0)))
        assertNull(WatchPulse.of(listOf(1.0, null, null)))
        assertNull(WatchPulse.of(listOf(Double.NaN, 2.0)))
    }

    @Test
    fun `counts up and down, unchanged in neither`() {
        val pulse = WatchPulse.of(listOf(2.0, 0.5, -1.0, 0.0, 0.004, -0.004, null))!!
        assertEquals(2, pulse.up)
        assertEquals(1, pulse.down)
    }

    @Test
    fun `threshold matches the pill`() {
        val pulse = WatchPulse.of(listOf(0.005, -0.005))!!
        assertEquals(1, pulse.up)
        assertEquals(1, pulse.down)
    }

    @Test
    fun `average is equal weight over all values with a change value`() {
        val pulse = WatchPulse.of(listOf(3.0, -1.0, 0.0, null))!!
        assertEquals(2.0 / 3.0, pulse.average, 1e-12)
        assertFalse(pulse.flat)
    }

    @Test
    fun `flat average`() {
        val pulse = WatchPulse.of(listOf(1.0, -1.0))!!
        assertEquals(0.0, pulse.average, 0.0)
        assertTrue(pulse.flat)
    }

    @Test
    fun `infinite values are ignored`() {
        val pulse = WatchPulse.of(listOf(Double.POSITIVE_INFINITY, 1.0, 3.0))!!
        assertEquals(2, pulse.up)
        assertEquals(2.0, pulse.average, 1e-12)
    }

    @Test
    fun `pairs without a 24h value are left out of counts and average`() {
        // 24-h-Werte; null = keine Kerzen (Pille «—»), zählt nirgends mit
        val pulse = WatchPulse.of(listOf(2.4, null, -0.6, null))!!
        assertEquals(1, pulse.up)
        assertEquals(1, pulse.down)
        assertEquals(0.9, pulse.average, 1e-12)
        assertNull(WatchPulse.of(listOf(1.8, null, null)))
    }

    @Test
    fun `missing counts null and invalid values by default`() {
        val pulse = WatchPulse.of(listOf(2.4, null, -0.6, Double.NaN))!!
        assertEquals(2, pulse.missing)
        assertEquals(0, WatchPulse.of(listOf(1.0, 2.0))!!.missing)
    }

    @Test
    fun `missing only counts pairs that have a price`() {
        // Paar 3 ohne Kurs (keine Pille) zählt nicht als «ohne 24h-Wert»
        val pulse = WatchPulse.of(
            changes = listOf(1.0, -2.0, null, null, null),
            hasPrice = listOf(true, true, false, true, true),
        )!!
        assertEquals(1, pulse.up)
        assertEquals(1, pulse.down)
        assertEquals(2, pulse.missing)
    }

    @Test
    fun `honest pulse - many pairs without value`() {
        val changes = List(19) { 1.0 } + List(80) { -1.0 } + List(510) { null }
        val pulse = WatchPulse.of(changes, List(changes.size) { true })!!
        assertEquals(19, pulse.up)
        assertEquals(80, pulse.down)
        assertEquals(510, pulse.missing)
    }

    @Test
    fun `still hidden with fewer than two values even if pairs are missing`() {
        assertNull(WatchPulse.of(listOf(1.0, null, null), listOf(true, true, true)))
    }
}
