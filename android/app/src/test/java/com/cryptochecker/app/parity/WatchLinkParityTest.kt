package com.cryptochecker.app.parity

import com.cryptochecker.marketdata.watch.WatchInterval
import com.cryptochecker.marketdata.watch.WatchPhoneQuotes
import com.cryptochecker.marketdata.watch.WatchPortfolio
import com.cryptochecker.marketdata.watch.WatchSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Uhr und Handy (`watch_link.json`) — wie `WatchLinkParityTests.swift`: Intervall der Uhr, Kursquelle und Intervall in
 * der Wahl (immer vom Handy), Kurse vom Handy (Auswahl, JSON, was die Uhr selbst abfragt) und Portfolio-Wert (verborgen
 * ohne Zahlen, Löschen). Der Teil «cacheTimed» läuft in `:wear` (`WatchLinkWearTest`).
 */
class WatchLinkParityTest {

    private val data = ParityJson.load("watch_link.json")

    @Suppress("UNCHECKED_CAST")
    private fun strings(v: Any?): List<String> = v as List<String>

    private fun source(v: Any?): WatchSettings.Source = WatchSettings.Source.entries.first { it.key == v }

    private fun settings(v: Any?): WatchSettings {
        val o = v.obj()
        return WatchSettings(
            mode = WatchSettings.Mode.entries.first { it.key == o["mode"] },
            own = strings(o["own"]),
            phone = strings(o["phone"]),
            revision = o["revision"].num()!!.toLong(),
            source = source(o["source"]),
            interval = o["interval"].num()!!.toInt(),
        )
    }

    private fun quote(v: Any?): WatchPhoneQuotes.Quote {
        val o = v.obj()
        return WatchPhoneQuotes.Quote(
            o["symbol"] as String, o["price"].num()!!, o["change24h"].num(), o["exchange"] as String, o["time"].num()!!.toLong(),
        )
    }

    private fun portfolio(v: Any?): WatchPortfolio.Message? = when (v) {
        null -> null
        "clear" -> WatchPortfolio.Message.Clear
        else -> v.obj().let { o ->
            WatchPortfolio.Message.Value(
                WatchPortfolio(o["total"].num(), o["changePercent"].num(), o["currency"] as String, o["time"].num()!!.toLong(),
                    o["hidden"] as Boolean)
            )
        }
    }

    @Test
    fun interval() {
        for (c in data.getValue("interval").list().map { it.obj() }) {
            val minutes = c["minutes"].num()!!.toInt()
            assertEquals(c["clamped"].num()!!.toInt(), WatchInterval.clamp(minutes))
            assertEquals(c["refetchAfterMillis"].num()!!.toLong(), WatchInterval.refetchAfterMillis(minutes))
        }
    }

    @Test
    fun settingsJson() {
        for (c in data.getValue("settingsJson").list().map { it.obj() }) {
            val parsed = WatchSettings.fromJson(c["text"] as String)!!
            assertEquals(c["name"] as String, source(c["source"]), parsed.source)
            assertEquals(c["name"] as String, c["interval"].num()!!.toInt(), parsed.interval)
            assertEquals(parsed, WatchSettings.fromJson(parsed.toJson()))
        }
    }

    @Test
    fun settingsMerge() {
        for (c in data.getValue("settingsMerge").list().map { it.obj() }) {
            val merged = WatchSettings.merge(settings(c["local"]), settings(c["remote"]), c["remoteIsPhone"] as Boolean)
            assertEquals(c["name"] as String, settings(c["expected"]), merged)
        }
    }

    /** Uhr meldet einen anderen Handy-Teil: das Handy sendet den ganzen Stand noch einmal. */
    @Test
    fun phoneSideDiffers() {
        for (c in data.getValue("phoneSideDiffers").list().map { it.obj() }) {
            assertEquals(c["name"] as String, c["expected"] as Boolean,
                WatchSettings.phoneSideDiffers(settings(c["phone"]), settings(c["watch"])))
        }
    }

    /** Handy ohne Stand (Wear: Eintrag gelöscht; Apple Watch: iPhone-App entfernt). */
    @Test
    fun forgetPhone() {
        for (c in data.getValue("forgetPhone").list().map { it.obj() }) {
            assertEquals(c["name"] as String, settings(c["expected"]), WatchSettings.forgetPhone(settings(c["local"])))
        }
    }

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
            )
        }
        val picked = WatchPhoneQuotes.pick(rows, strings(p["coins"]))
        assertEquals(p.getValue("expected").list().map { quote(it) }, picked)
        // Über JSON unverändert (Handy → Uhr)
        assertEquals(picked, WatchPhoneQuotes.fromJson(WatchPhoneQuotes.toJson(picked)))
    }

    @Test
    fun quotesJson() {
        for (c in data.getValue("quotesJson").list().map { it.obj() }) {
            val parsed = WatchPhoneQuotes.fromJson(c["text"] as String)
            val expected = c["expected"]
            if (expected == null) {
                assertNull(c["name"] as String, parsed)
            } else {
                assertEquals(c["name"] as String, expected.list().map { quote(it) }, parsed)
            }
        }
    }

    @Test
    fun selfFetch() {
        val s = data.getValue("selfFetch").obj()
        val now = s["now"].num()!!.toLong()
        val times = s.getValue("times").obj().mapValues { it.value.num()!!.toLong() }
        val phoneCoins = strings(s["phoneCoins"]).toSet()
        for (c in s.getValue("cases").list().map { it.obj() }) {
            val got = WatchPhoneQuotes.selfFetch(
                strings(s["shown"]), times, phoneCoins, c["usePhone"] as Boolean, c["interval"].num()!!.toInt(), now,
                c["force"] as Boolean,
            )
            assertEquals(c["name"] as String, strings(c["expected"]), got)
        }
    }

    /** Handy weg: gleiche Grenze auf beiden Uhren (Intervall + 5 Min. nach der letzten Lieferung). */
    @Test
    fun usePhone() {
        val u = data.getValue("usePhone").obj()
        val now = u["now"].num()!!.toLong()
        for (s in u.getValue("silentAfter").list().map { it.obj() }) {
            assertEquals(s["millis"].num()!!.toLong(), WatchPhoneQuotes.phoneSilentAfterMillis(s["interval"].num()!!.toInt()))
        }
        for (d in u.getValue("deliveryTime").list().map { it.obj() }) {
            val quotes = d.getValue("times").list().map { WatchPhoneQuotes.Quote("BTC", 1.0, null, "Binance", it.num()!!.toLong()) }
            assertEquals(d["expected"].num()!!.toLong(), WatchPhoneQuotes.deliveryTime(quotes))
        }
        for (c in u.getValue("cases").list().map { it.obj() }) {
            val got = WatchPhoneQuotes.usePhone(
                source(c["source"]), c["connected"] as Boolean, c["lastDelivery"].num()!!.toLong(), now,
                c["interval"].num()!!.toInt(),
            )
            assertEquals(c["name"] as String, c["expected"] as Boolean, got)
        }
    }

    @Test
    fun portfolioMessage() {
        for (c in data.getValue("portfolioMessage").list().map { it.obj() }) {
            val message = WatchPortfolio.message(
                onWatch = c["onWatch"] as Boolean,
                portfolioEnabled = c["portfolioEnabled"] as Boolean,
                hidden = c["hidden"] as Boolean,
                locked = c["locked"] as Boolean,
                total = c["total"].num(),
                changePercent = c["changePercent"].num(),
                currency = c["currency"] as String?,
                time = c["time"].num()!!.toLong(),
                fallbackCurrency = c["fallbackCurrency"] as String?,
                now = c["now"].num()?.toLong() ?: 0L,
            )
            assertEquals(c["name"] as String, portfolio(c["expected"]), message)
            // Über JSON unverändert (Handy → Uhr)
            message?.let { assertEquals(c["name"] as String, it, WatchPortfolio.fromJson(WatchPortfolio.toJson(it))) }
        }
    }

    @Test
    fun portfolioJson() {
        for (c in data.getValue("portfolioJson").list().map { it.obj() }) {
            assertEquals(c["name"] as String, portfolio(c["expected"]), WatchPortfolio.fromJson(c["text"] as String))
        }
    }
}
