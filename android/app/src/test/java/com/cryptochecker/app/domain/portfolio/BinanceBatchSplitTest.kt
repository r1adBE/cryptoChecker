package com.cryptochecker.app.domain.portfolio

import com.cryptochecker.app.data.portfolio.PortfolioPriceIssue
import com.cryptochecker.app.domain.portfolio.BinanceBatchSplit.Reply
import com.cryptochecker.app.parity.ParityJson
import com.cryptochecker.app.parity.list
import com.cryptochecker.app.parity.num
import com.cryptochecker.app.parity.obj
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Binance-Sammelabfrage mit Halbieren bei HTTP 400 ([BinanceBatchSplit]) und die Einteilung «Kurs ungeprüft» /
 * «Kein Markt» ([PortfolioPriceIssue.nameUnchecked]) — Fixture `binance_batch_split.json`, wie iOS.
 */
class BinanceBatchSplitTest {

    /** Attrappe: Teil mit [failing] scheitert, sonst Teil mit [unknown] → 400, sonst die Kurse aus [tickers]. */
    private fun fake(tickers: Map<String, Double>, unknown: Set<String>, failing: Set<String>, asked: MutableList<List<String>>) =
        { part: List<String> ->
            asked += part
            when {
                part.any { it in failing } -> Reply.Failed
                part.any { it in unknown } -> Reply.Rejected
                else -> Reply.Prices(part.mapNotNull { c -> tickers[c]?.let { c to it } }.toMap())
            }
        }

    @Test
    fun parityCases() = runBlocking {
        val data = ParityJson.load("binance_batch_split.json")
        val tickers = data["tickers"].obj().mapValues { it.value.num()!! }
        data["cases"].list().forEach { raw ->
            val c = raw.obj()
            val name = c["case"] as String
            val asked = mutableListOf<List<String>>()
            val request = fake(
                tickers,
                c["unknown"].list().map { it as String }.toSet(),
                c["failing"].list().map { it as String }.toSet(),
                asked,
            )
            val got = BinanceBatchSplit.fetch(
                c["coins"].list().map { it as String },
                c["maxSymbols"].num()!!.toInt(),
                c["budget"].num()!!.toInt(),
            ) { request(it) }
            assertEquals(name, c["prices"].obj().mapValues { it.value.num()!! }, got.prices)
            assertEquals(name, c["failed"], got.failed)
            assertEquals(name, c["unlisted"].list(), got.unlisted.toList())
            assertEquals(name, c["requests"].num()!!.toInt(), got.requests)
            assertEquals(name, got.requests, asked.size)
        }
    }

    /**
     * Erste Aktualisierung (frisch installiert, keine Coin-Liste): IOTA bekommt den Binance-Kurs, obwohl ICX und WTC
     * Binance unbekannt sind — nicht gescheitert, also auch nicht «Kurs gerade nicht abrufbar».
     */
    @Test
    fun firstRefreshWithoutListPricesKnownCoins() = runBlocking {
        val asked = mutableListOf<List<String>>()
        val request = fake(mapOf("BTC" to 60000.0, "IOTA" to 0.21), setOf("ICX", "WTC"), emptySet(), asked)
        val got = BinanceBatchSplit.fetch(listOf("ICX", "IOTA", "WTC", "BTC")) { request(it) }
        assertEquals(mapOf("IOTA" to 0.21, "BTC" to 60000.0), got.prices)
        assertFalse(got.failed)
        assertEquals(setOf("ICX", "WTC"), got.unlisted)
        // Erste Abfrage mit allen Coins (kein Filter), danach nur Hälften
        assertEquals(listOf("ICX", "IOTA", "WTC", "BTC"), asked.first())
        assertTrue(asked.drop(1).all { it.size < 4 })
    }

    /** Weg (b) ohne jeden Kurs → «Kein Markt»; Weg (a) → «Kurs ungeprüft»; gescheitert oder verworfen → keiner. */
    @Test
    fun nameUncheckedSplitsNoMarket() {
        val got = PortfolioPriceIssue.nameUnchecked(
            skipped = setOf("ICX", "WTC", "NOREF", "DOWN", "OTHER"),
            rejected = setOf("OTHER"),
            noPrice = setOf("ICX", "WTC", "PLAIN"),
            unavailable = setOf("DOWN"),
        )
        assertEquals(setOf("ICX", "WTC"), got.noMarket)
        assertEquals(setOf("NOREF"), got.unchecked)
    }
}
