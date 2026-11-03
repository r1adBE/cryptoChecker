package com.cryptochecker.wear

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Kurse vom Handy auf der Uhr: Zusammenführen je Coin nach der Zeit des Handys ([WatchCache.mergeTimed],
 * `testdata/parity/watch_link.json`, Abschnitt «cacheTimed», wie iOS `WatchLinkParityTests`) und Abrufe der Uhr-App mit
 * Plan ([WatchFetcher]): nichts fällig (das Handy liefert) → kein Abruf und kein Fehler; sonst nur die fälligen Coins.
 */
class WatchLinkWearTest {

    private val data = JSONObject(fixtureDir().resolve("watch_link.json").readText(Charsets.UTF_8))

    /** Ordner `testdata/parity`: Gradle startet Unit-Tests im Modulordner (`wear/`), andere Läufer im Projektordner. */
    private fun fixtureDir(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "testdata/parity")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        error("testdata/parity not found above ${System.getProperty("user.dir")}")
    }

    private fun objects(a: JSONArray): List<JSONObject> = (0 until a.length()).map { a.getJSONObject(it) }

    private fun cached(o: JSONObject): WatchCached = WatchCached(
        WatchQuote(o.getString("symbol"), o.getDouble("price"), if (o.isNull("change24h")) null else o.getDouble("change24h"),
            o.getString("source")),
        o.getLong("time"),
    )

    @Test
    fun cacheTimed() {
        for (c in objects(data.getJSONArray("cacheTimed"))) {
            val name = c.getString("name")
            val old = objects(c.getJSONArray("old")).associate { it.getString("symbol") to cached(it) }
            val fresh = objects(c.getJSONArray("fresh")).associate { it.getString("symbol") to cached(it) }
            val keep = c.getJSONArray("keep").let { a -> (0 until a.length()).map { a.getString(it) } }
            val merged = WatchCache.mergeTimed(old, fresh, keep)
            val expected = objects(c.getJSONArray("expected")).associate { it.getString("symbol") to cached(it) }
            assertEquals(name, expected, merged)
        }
    }

    private suspend fun settle(fetcher: WatchFetcher) {
        repeat(100) {
            if (!fetcher.loading.value) return
            yield()
        }
    }

    @Test
    fun nothingDueMeansNoFetchAndNoError() {
        runBlocking {
            var fetched = 0
            val fetcher = WatchFetcher(this, shown = { listOf("BTC") }, fetch = { fetched++; emptyMap() },
                save = { _, _ -> }, plan = { _, _ -> emptyList() })
            fetcher.refresh(force = false)
            settle(fetcher)
            assertEquals(0, fetched)
            assertFalse(fetcher.failed.value)
            assertFalse(fetcher.loading.value)
        }
    }

    @Test
    fun onlyDueCoinsAreFetched() {
        runBlocking {
            val asked = ArrayList<List<String>>()
            val forced = ArrayList<Boolean>()
            val fetcher = WatchFetcher(this, shown = { listOf("BTC", "ETH", "SOL") }, fetch = { coins ->
                asked += coins
                emptyMap()
            }, save = { _, _ -> }, plan = { coins, force ->
                forced += force
                coins.filter { it != "BTC" }
            })
            fetcher.refresh(force = false)
            settle(fetcher)
            assertEquals(listOf(listOf("ETH", "SOL")), asked)
            assertEquals(listOf(false), forced)
            // Fällige Coins ohne Ergebnis: Fehler wie bisher
            assertTrue(fetcher.failed.value)
        }
    }
}
