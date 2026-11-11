package com.cryptochecker.app.domain.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoSlotTest {

    @Test
    fun takeOnceForMatchingKey() {
        val slot = UndoSlot<Long, String>()
        slot.put(1L, "BTC/USDT")
        assertTrue(slot.holds(1L))
        assertNull(slot.take(2L))
        assertEquals("BTC/USDT", slot.take(1L))
        // Zweites «Rückgängig» (Doppeltipp) tut nichts
        assertNull(slot.take(1L))
        assertFalse(slot.holds(1L))
    }

    @Test
    fun newerDeleteReplacesOlder() {
        val slot = UndoSlot<Long, String>()
        slot.put(1L, "BTC/USDT")
        slot.put(2L, "ETH/USDT")
        // Verspätetes «Rückgängig» der ersten Löschung: ohne Wirkung
        assertNull(slot.take(1L))
        assertEquals("ETH/USDT", slot.take(2L))
    }

    @Test
    fun clearDropsEntry() {
        val slot = UndoSlot<Long, String>()
        slot.put(3L, "SOL/USDT")
        slot.clear()
        assertNull(slot.take(3L))
    }

    @Test
    fun mergeNimmtZumGleichenSchluesselDazu() {
        val slot = UndoSlot<Long, List<String>>()
        slot.merge(1L, listOf("BTC/USDT")) { a, b -> a + b }
        slot.merge(1L, listOf("ETH/USDT")) { a, b -> a + b }
        assertEquals(listOf("BTC/USDT", "ETH/USDT"), slot.take(1L))
    }

    @Test
    fun mergeMitAnderemSchluesselErsetzt() {
        val slot = UndoSlot<Long, List<String>>()
        slot.put(-1L, listOf("Alt"))
        slot.merge(5L, listOf("SOL/USDT")) { a, b -> a + b }
        assertNull(slot.take(-1L))
        assertEquals(listOf("SOL/USDT"), slot.take(5L))
    }
}
