package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.watch.WatchIdentity
import com.cryptochecker.marketdata.watch.WatchCoins
import com.cryptochecker.marketdata.watch.WatchPair
import com.cryptochecker.marketdata.watch.WatchPhoneQuotes
import com.cryptochecker.marketdata.watch.WatchSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * «Wie Handy» mit dem Paar des Favoriten (`watch_pairs.json`) — wie `WatchPairsParityTests.swift`: Paar-Schlüssel und
 * Anzeige, Paare in der Wahl, Kurse vom Handy genau für dieses Paar (die Quelle folgt dem Paar des Handys) und was die
 * Uhr selbst abfragt. Börsen-Reihenfolge und Abruf laufen in `:wear` (`WatchPairQuotesParityTest`).
 */
class WatchPairsParityTest {

    private val data = ParityJson.load("watch_pairs.json")

    @Suppress("UNCHECKED_CAST")
    private fun strings(v: Any?): List<String> = v as List<String>

    private fun pair(v: Any?): WatchPair? = v?.obj()?.let { o ->
        WatchPair.of(o["base"] as String, o["quote"] as String, o["exchange"] as String, o["contract"] as String)
    }

    private fun settings(v: Any?): WatchSettings {
        val o = v.obj()
        return WatchSettings(
            mode = WatchSettings.Mode.entries.first { it.key == o["mode"] },
            own = strings(o["own"]),
            phone = strings(o["phone"]),
            revision = o["revision"].num()!!.toLong(),
            pairs = o["pairs"].list().mapNotNull { pair(it) },
        )
    }

    @Test
    fun pairs() {
        for (c in data.getValue("pairs").list().map { it.obj() }) {
            val name = c["name"] as String
            val p = pair(c)
            if (c["key"] == null) {
                assertNull(name, p)
                continue
            }
            assertEquals(name, c["key"], p!!.key)
            assertEquals(name, c["label"], p.label)
            assertEquals(name, c["selfFetchable"], p.selfFetchable)
            assertEquals(name, p, WatchPair.parse(p.key))
        }
        // Pool nur bei DexScreener (WatchIdentity.pool), mit den Stellen seiner Kennung; Börsen-Kennungen bleiben Spot
        for (c in data.getValue("contractOf").list().map { it.obj() }) {
            val pool = WatchIdentity.pool(c["exchangeKey"] as String, c["pairId"] as String?)
            assertEquals(c["name"] as String, c["expected"], WatchPair.contractOf(c["short"] as String?, pool))
        }
        for (c in data.getValue("parse").list().map { it.obj() }) {
            assertEquals(c["key"] as String, c["valid"], WatchPair.parse(c["key"] as String) != null)
        }
        for (c in data.getValue("labelOf").list().map { it.obj() }) {
            assertEquals(c["label"], WatchPair.label(c["key"] as String))
        }
        for (c in data.getValue("favoritePairs").list().map { it.obj() }) {
            val got = WatchCoins.favoritePairs(strings(c["bases"]), c["pairs"].list().map { pair(it) })
            assertEquals(c["name"] as String, strings(c["expected"]), got.map { it.key })
        }
    }

    @Test
    fun settings() {
        for (c in data.getValue("settings").list().map { it.obj() }) {
            val name = c["name"] as String
            val parsed = WatchSettings.fromJson(c["text"] as String)!!
            assertEquals(name, strings(c["phoneKeys"]), parsed.phoneKeys)
            assertEquals(name, strings(c["shown"]), parsed.shown)
            assertEquals(name, strings(c["kept"]), parsed.kept)
            assertEquals(name, parsed, WatchSettings.fromJson(parsed.toJson()))
        }
        for (c in data.getValue("merge").list().map { it.obj() }) {
            val merged = WatchSettings.merge(settings(c["local"]), settings(c["remote"]), c["remoteIsPhone"] as Boolean)
            assertEquals(c["name"] as String, settings(c["expected"]), merged)
        }
    }

    /** Die Quelle folgt dem Paar des Handys: genau dieses Paar, nicht ein neuerer Kurs einer anderen Börse. */
    @Test
    fun pick() {
        val p = data.getValue("pick").obj()
        val rows = p.getValue("rows").list().map { it.obj() }.map { o ->
            WatchPhoneQuotes.Row(
                base = o["base"] as String,
                quote = o["quote"] as String,
                spot = o["spot"] as Boolean,
                disabled = o["disabled"] as Boolean,
                price = o["price"].num(),
                change24h = o["change24h"].num(),
                exchange = o["exchange"] as String,
                time = o["time"].num()!!.toLong(),
                sortOrder = o["sortOrder"].num()!!.toInt(),
                contract = o["contract"] as String,
            )
        }
        val expected = p.getValue("expected").list().map { it.obj() }.map { o ->
            WatchPhoneQuotes.Quote(
                o["symbol"] as String, o["price"].num()!!, o["change24h"].num(), o["exchange"] as String,
                o["time"].num()!!.toLong(),
            )
        }
        val picked = WatchPhoneQuotes.pick(rows, strings(p["coins"]))
        assertEquals(p["name"] as String, expected, picked)
        assertEquals(picked, WatchPhoneQuotes.fromJson(WatchPhoneQuotes.toJson(picked)))
    }

    @Test
    fun selfFetch() {
        val s = data.getValue("selfFetch").obj()
        val got = WatchPhoneQuotes.selfFetch(
            strings(s["shown"]), s.getValue("times").obj().mapValues { it.value.num()!!.toLong() },
            strings(s["phoneCoins"]).toSet(), s["usePhone"] as Boolean, s["interval"].num()!!.toInt(),
            s["now"].num()!!.toLong(), s["force"] as Boolean,
        )
        assertEquals(strings(s["expected"]), got)
    }
}
