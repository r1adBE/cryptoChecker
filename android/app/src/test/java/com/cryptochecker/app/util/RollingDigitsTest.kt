package com.cryptochecker.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RollingDigitsTest {

    @Test
    fun `digits single, other runs kept together, keys from the right`() {
        val slots = RollingDigits.slots("61,234.5 USDT")
        assertEquals(
            listOf("6", "1", ",", "2", "3", "4", ".", "5", " USDT"),
            slots.map { it.text }
        )
        assertEquals((slots.size - 1 downTo 0).toList(), slots.map { it.key })
    }

    @Test
    fun `text around a number stays one slot`() {
        assertEquals(listOf("▲ ", "7", " steigen"), RollingDigits.slots("▲ 7 steigen").map { it.text })
        assertEquals(listOf("—"), RollingDigits.slots("—").map { it.text })
        assertEquals(emptyList<RollingDigits.Slot>(), RollingDigits.slots(""))
    }

    @Test
    fun `only changed digits roll`() {
        assertTrue(RollingDigits.rolls("4", "5"))
        assertFalse(RollingDigits.rolls("4", "4"))
        assertFalse(RollingDigits.rolls(",", "5"))
        assertFalse(RollingDigits.rolls("5", " USDT"))
        assertFalse(RollingDigits.rolls(" USDT", " EUR"))
    }

    @Test
    fun `rolling keys align from the right`() {
        // 61,234.50 → 61,239.70: Zehntel (Platz 2 von rechts) und Einer (Platz 4)
        assertEquals(setOf(2, 4), RollingDigits.rollingKeys("61,234.50 USDT", "61,239.70 USDT"))
        // Neue Stelle vorn: Einer bleiben Einer
        assertEquals(setOf(1), RollingDigits.rollingKeys("▲ 9 up", "▲ 10 up"))
        assertEquals(emptySet<Int>(), RollingDigits.rollingKeys("1.00 USDT", "1.00 USDT"))
    }

    @Test
    fun `right to left scripts are not split`() {
        assertTrue(RollingDigits.canRoll("61,234.50 USDT"))
        assertTrue(RollingDigits.canRoll("▲ 7 steigen"))
        assertTrue(RollingDigits.canRoll("७ बढ़े"))
        assertFalse(RollingDigits.canRoll("7 עולים"))
        assertFalse(RollingDigits.canRoll("٧ صاعد"))
    }

    @Test
    fun `direction follows the value`() {
        val d = RollingDirection(100.0)
        assertTrue(d.update(100.0))
        assertFalse(d.update(99.0))
        // Gleicher Wert: Richtung bleibt
        assertFalse(d.update(99.0))
        // Ohne Wert: Richtung bleibt, letzter Wert bleibt
        assertFalse(d.update(null))
        assertTrue(d.update(101.0))
    }

    @Test
    fun `direction without start value`() {
        val d = RollingDirection(null)
        assertTrue(d.update(5.0))
        assertFalse(d.update(4.0))
    }
}
