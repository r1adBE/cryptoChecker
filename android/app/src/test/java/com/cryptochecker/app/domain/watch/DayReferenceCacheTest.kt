package com.cryptochecker.app.domain.watch

import com.cryptochecker.app.domain.watch.DayReferenceCache.Stored
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DayReferenceCacheTest {

    private val now = 1_800_000_000_000L
    private val hour = 60 * 60_000L

    @Test
    fun `fresh for sixty minutes, usable for three hours`() {
        assertTrue(DayReferenceCache.fresh(now - 59 * 60_000L, now))
        assertFalse(DayReferenceCache.fresh(now - hour, now))
        assertTrue(DayReferenceCache.usable(now - 3 * hour, now))
        assertFalse(DayReferenceCache.usable(now - 3 * hour - 1, now))
        // Zukunft (Uhr verstellt) oder ohne Zeit: nicht benutzen
        assertFalse(DayReferenceCache.usable(now + 1, now))
        assertFalse(DayReferenceCache.usable(0, now))
    }

    @Test
    fun `restore ignores other versions`() {
        val entries = listOf(Stored("BTC|USDT", now - hour, 100.0, 101.0))
        assertTrue(DayReferenceCache.restore(null, entries, now).isEmpty())
        assertTrue(DayReferenceCache.restore(DayReferenceCache.FORMAT_VERSION + 1, entries, now).isEmpty())
        assertEquals(1, DayReferenceCache.restore(DayReferenceCache.FORMAT_VERSION, entries, now).size)
    }

    @Test
    fun `restore drops corrupt, old and malformed entries and keeps the newest per key`() {
        val v = DayReferenceCache.FORMAT_VERSION
        val restored = DayReferenceCache.restore(
            v,
            listOf(
                Stored("BTC|USDT", now - 2 * hour, 100.0, 101.0),
                Stored("BTC|USDT", now - hour, 200.0, 201.0),
                Stored("ETH|USDT", now - 4 * hour, 10.0, 11.0),
                Stored("SOL|USDT", now - hour, Double.NaN, 11.0),
                Stored("ADA|USDT", now - hour, 0.0, 11.0),
                Stored("xrp|usdt", now - hour, 1.0, 1.0),
                Stored("DOGE", now - hour, 1.0, 1.0),
                Stored("", now - hour, 1.0, 1.0),
                Stored("BTC|EUR", now - hour, 90.0, 91.0),
            ),
            now,
        )
        assertEquals(setOf("BTC|USDT", "BTC|EUR"), restored.keys)
        assertEquals(200.0, restored.getValue("BTC|USDT").open, 0.0)
    }

    @Test
    fun `save keeps only usable entries, newest first, capped`() {
        val many = (0 until DayReferenceCache.MAX_ENTRIES + 10).map { Stored("C$it|USDT", now - it, 1.0, 1.0) }
        val saved = DayReferenceCache.toSave(many + Stored("OLD|USDT", now - 5 * hour, 1.0, 1.0), now)
        assertEquals(DayReferenceCache.MAX_ENTRIES, saved.size)
        assertEquals("C0|USDT", saved.first().key)
        assertFalse(saved.any { it.key == "OLD|USDT" })
    }
}
