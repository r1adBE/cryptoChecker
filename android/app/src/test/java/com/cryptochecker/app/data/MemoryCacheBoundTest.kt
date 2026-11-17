package com.cryptochecker.app.data

import com.cryptochecker.app.domain.BoundedCache
import com.cryptochecker.app.domain.MEMORY_CACHE_ENTRIES
import com.cryptochecker.app.domain.watch.SheetChartCache
import com.cryptochecker.app.domain.watch.SheetChartRange
import com.cryptochecker.app.domain.watch.SheetChartResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Zwischenspeicher je Paar bzw. Coin im Arbeitsspeicher bleiben begrenzt ([MEMORY_CACHE_ENTRIES]):
 * neue Hilfen von [BoundedCache] und das Blatt-Chart als Beispiel. Dass die betroffenen Klassen keine unbegrenzte
 * `ConcurrentHashMap` mehr nehmen, prüft die Regel `bounded-caches` in `.github/scripts/source_rules.py`.
 */
class MemoryCacheBoundTest {

    @Test
    fun putIfAbsentRemoveAndRemoveKeysIf() {
        val cache = BoundedCache<String, Int>(4)
        assertNull(cache.putIfAbsent("A", 1))
        assertEquals(1, cache.putIfAbsent("A", 2))
        assertEquals(1, cache["A"])
        cache["BTC|1"] = 3
        cache["BTC|2"] = 4
        cache.removeKeysIf { it.startsWith("BTC|") }
        assertEquals(1, cache.size)
        assertEquals(1, cache.remove("A"))
        assertNull(cache.remove("A"))
    }

    @Test
    fun sheetChartKeepsAtMostTheLimit() {
        val cache = SheetChartCache(now = { 1_000L })
        val ready = SheetChartResult.Ready(candles = emptyList(), converted = false)
        repeat(MEMORY_CACHE_ENTRIES + 100) { cache.put("C$it", "USDT", SheetChartRange.DAY, ready) }
        // Die ersten sind herausgefallen, die letzten noch da
        assertNull(cache.get("C0", "USDT", SheetChartRange.DAY))
        assertNotNull(cache.get("C${MEMORY_CACHE_ENTRIES + 99}", "USDT", SheetChartRange.DAY))
        assertNotNull(cache.get("C100", "USDT", SheetChartRange.DAY))
        assertNull(cache.get("C99", "USDT", SheetChartRange.DAY))
    }
}
