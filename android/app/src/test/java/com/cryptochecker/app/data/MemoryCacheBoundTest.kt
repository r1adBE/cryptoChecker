package com.cryptochecker.app.data

import com.cryptochecker.app.domain.watch.SheetChartCache
import com.cryptochecker.app.domain.watch.SheetChartRange
import com.cryptochecker.app.domain.watch.SheetChartResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Zwischenspeicher je Paar bzw. Coin im Arbeitsspeicher bleiben begrenzt ([MEMORY_CACHE_ENTRIES]):
 * neue Hilfen von [BoundedCache], das Blatt-Chart als Beispiel und eine Wache, dass die betroffenen Klassen keine
 * unbegrenzte `ConcurrentHashMap` mehr nehmen.
 */
class MemoryCacheBoundTest {

    @Test
    fun putIfAbsentRemoveUndRemoveKeysIf() {
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
    fun blattChartBehaeltHoechstensDieGrenze() {
        val cache = SheetChartCache(now = { 1_000L })
        val ready = SheetChartResult.Ready(candles = emptyList(), converted = false)
        repeat(MEMORY_CACHE_ENTRIES + 100) { cache.put("C$it", "USDT", SheetChartRange.DAY, ready) }
        // Die ersten sind herausgefallen, die letzten noch da
        assertNull(cache.get("C0", "USDT", SheetChartRange.DAY))
        assertNotNull(cache.get("C${MEMORY_CACHE_ENTRIES + 99}", "USDT", SheetChartRange.DAY))
        assertNotNull(cache.get("C100", "USDT", SheetChartRange.DAY))
        assertNull(cache.get("C99", "USDT", SheetChartRange.DAY))
    }

    @Test
    fun betroffeneKlassenOhneUnbegrenzteMap() {
        val files = listOf(
            "data/remote/VolumeDataSource.kt", "data/remote/NearExtremeDataSource.kt",
            "data/portfolio/PortfolioHistorySource.kt", "domain/alarm/DerivativesAlarmData.kt",
            "domain/watch/SheetChart.kt", "widget/SingleWidgetRenderer.kt",
        )
        for (name in files) {
            val path = "src/main/java/com/cryptochecker/app/$name"
            val file = listOf(File(path), File("app/$path"), File("../data/$path"), File("data/$path"), File("../domain/$path"), File("domain/$path")).first { it.exists() }
            val text = file.readText()
            assertFalse("$name: ConcurrentHashMap", text.contains("ConcurrentHashMap"))
            assertTrue("$name: BoundedCache", text.contains("BoundedCache"))
        }
        val historic = listOf(
            File("src/main/java/com/cryptochecker/app/data/portfolio/HistoricPriceSource.kt"),
            File("app/src/main/java/com/cryptochecker/app/data/portfolio/HistoricPriceSource.kt"),
            File("../data/src/main/java/com/cryptochecker/app/data/portfolio/HistoricPriceSource.kt"),
            File("data/src/main/java/com/cryptochecker/app/data/portfolio/HistoricPriceSource.kt"),
        ).first { it.exists() }.readText()
        assertTrue(historic.contains("routeMemo = BoundedCache"))
        // Eigene Kerzen (die Pausen je Host bleiben eine kleine Map, begrenzt durch die Zahl der Börsen)
        val ownCandles = listOf(
            File("src/main/java/com/cryptochecker/app/data/remote/candles/OwnCandleDataSource.kt"),
            File("app/src/main/java/com/cryptochecker/app/data/remote/candles/OwnCandleDataSource.kt"),
            File("../data/src/main/java/com/cryptochecker/app/data/remote/candles/OwnCandleDataSource.kt"),
            File("data/src/main/java/com/cryptochecker/app/data/remote/candles/OwnCandleDataSource.kt"),
        ).first { it.exists() }.readText()
        assertTrue(ownCandles.contains("cache = BoundedCache<String, Entry>(MEMORY_CACHE_ENTRIES)"))
        assertFalse(ownCandles.contains("cache = ConcurrentHashMap"))
    }
}
