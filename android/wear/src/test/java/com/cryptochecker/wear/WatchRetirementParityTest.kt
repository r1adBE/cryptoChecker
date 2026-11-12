package com.cryptochecker.wear

import com.cryptochecker.marketdata.watch.WatchPair
import com.cryptochecker.marketdata.watch.WatchPhoneQuotes
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Uhr bei stillgelegter Börse (`testdata/parity/exchange_retirement.json`, Abschnitt «watchMissing»; iOS:
 * `ExchangeRetirementTests.swift`): nach dem Ende fragt die Uhr keine Ersatzbörse, «—» nennt den Grund
 * ([WatchPairQuotes.Missing.RETIRED]); vor dem Ende wie bisher. Das Handy schickt keinen alten Kurs.
 */
class WatchRetirementParityTest {

    private val data = JSONObject(fixtureDir().resolve("exchange_retirement.json").readText(Charsets.UTF_8))

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

    private fun strings(a: JSONArray): List<String> = (0 until a.length()).map { a.getString(it) }

    @Test
    fun missingAsFixture() {
        val cases = data.getJSONArray("watchMissing")
        assertTrue(cases.length() > 0)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val name = c.getString("name")
            val pair = WatchPair.parse(c.getString("key"))!!
            val now = c.getLong("now")
            assertEquals(name, c.getString("expected"), WatchPairQuotes.missingReason(pair, null, now).name)
            assertEquals("$name › venues", strings(c.getJSONArray("venues")), WatchPairQuotes.venues(pair, now).map { it.label })
        }
    }

    /** Ohne Kurs vom Handy (stillgelegt: `price` null) bietet das Handy der Uhr nichts an. */
    @Test
    fun phoneSendsNoOldPrice() {
        val row = WatchPhoneQuotes.Row(
            base = "BTC", quote = "EUR", spot = true, disabled = false, price = null, change24h = null,
            exchange = "Bitpanda", time = 1L, sortOrder = 0,
        )
        assertTrue(WatchPhoneQuotes.pick(listOf(row), listOf("BTC/EUR|Bitpanda|")).isEmpty())
    }
}
