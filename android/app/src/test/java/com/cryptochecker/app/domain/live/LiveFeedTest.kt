package com.cryptochecker.app.domain.live

import com.cryptochecker.app.parity.ParityJson
import com.cryptochecker.app.parity.list
import com.cryptochecker.app.parity.num
import com.cryptochecker.app.parity.obj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Live-Kurse (WebSocket): gemeinsame Testfälle `testdata/parity/live_feed.json` (auch iOS
 * `LiveFeedTests.swift`) plus Puffer und Aufteilung auf mehrere Verbindungen.
 */
class LiveFeedTest {

    private val data = ParityJson.load("live_feed.json")

    private fun pair(spec: Map<String, Any?>) = LivePair(
        watchId = spec["watchId"].num()!!.toLong(),
        marketKey = spec["marketKey"] as String,
        marketName = spec["marketName"] as String,
        pairId = spec["pairId"] as String?,
        base = spec["base"] as String,
        quote = spec["quote"] as String,
        contract = spec["contract"] as String,
        notTraded = spec["notTraded"] as? Boolean ?: false,
    )

    private fun assertNumber(expected: Double?, actual: Double?, name: String) {
        if (expected == null) {
            assertNull(name, actual)
        } else {
            assertNotNull(name, actual)
            assertEquals(name, expected, actual!!, 1e-9)
        }
    }

    @Test
    fun parserMatchesFixtures() {
        var count = 0
        data["parse"].list().forEach { raw ->
            val c = raw.obj()
            val name = c["name"] as String
            val ticks = LiveParser.parse(LiveExchange.valueOf(c["exchange"] as String), c["message"] as String)
            val expected = c["ticks"].list()
            assertEquals("$name: count", expected.size, ticks.size)
            expected.forEachIndexed { i, e ->
                val want = e.obj()
                val tick = ticks[i]
                assertEquals("$name: symbol", want["symbol"], tick.symbol)
                assertNumber(want["price"].num(), tick.price, "$name: price")
                assertNumber(want["change24h"].num(), tick.change24h, "$name: change")
                assertEquals("$name: time", want["time"].num()?.toLong(), tick.time)
            }
            count++
        }
        assertTrue(count >= 10)
    }

    @Test
    fun symbolsMatchFixtures() {
        data["symbols"].list().forEach { raw ->
            val c = raw.obj()
            val exchange = LiveExchange.valueOf(c["exchange"] as String)
            assertEquals("symbol for ${c["pair"]}", c["symbol"], exchange.symbolFor(pair(c["pair"].obj())))
            // Börse und Adapter-Schlüssel gehören zusammen
            assertEquals(exchange, LiveExchange.fromMarketKey(c["pair"].obj()["marketKey"] as String))
        }
    }

    @Test
    fun subscribeMessagesMatchFixtures() {
        data["subscribe"].list().forEach { raw ->
            val c = raw.obj()
            val exchange = LiveExchange.valueOf(c["exchange"] as String)
            @Suppress("UNCHECKED_CAST")
            val symbols = c["symbols"] as List<String>
            assertEquals("${c["exchange"]}", c["messages"], exchange.subscribeMessages(symbols))
            // Jede Abo-Nachricht ist gültiges JSON
            exchange.subscribeMessages(symbols).forEach { assertNotNull(LiveJson.parse(it)) }
        }
    }

    @Test
    fun planMatchesFixtures() {
        data["plan"].list().forEach { raw ->
            val c = raw.obj()
            @Suppress("UNCHECKED_CAST")
            val paused = (c["paused"] as List<String>).toSet()
            val plan = LivePlanner.plan(c["pairs"].list().map { pair(it.obj()) }, paused)
            val expected = c["connections"].list().map { it.obj() }
            assertEquals("${c["name"]}: connections", expected.size, plan.connections.size)
            expected.forEachIndexed { i, e ->
                assertEquals(LiveExchange.valueOf(e["exchange"] as String), plan.connections[i].exchange)
                assertEquals(e["symbols"], plan.connections[i].symbols)
            }
            val ids = c["watchIds"].obj().map { (exchange, bySymbol) ->
                LiveExchange.valueOf(exchange) to bySymbol.obj().mapValues { (_, list) -> list.list().map { it.num()!!.toLong() } }
            }.toMap()
            assertEquals("${c["name"]}: ids", ids, plan.watchIds)
        }
    }

    @Test
    fun backoffMatchesFixtures() {
        data["backoff"].list().forEach { raw ->
            val c = raw.obj()
            assertEquals(
                "$c", c["millis"].num()!!.toLong(),
                LiveBackoff.delayMillis(c["attempt"].num()!!.toInt(), c["rateLimited"] as Boolean),
            )
        }
    }

    @Test
    fun rulesMatchFixtures() {
        data["chooseChange"].list().forEach { raw ->
            val c = raw.obj()
            assertNumber(
                c["expected"].num(),
                LiveRules.chooseChange(
                    c["rolling"] as Boolean, c["stampCurrent"] as Boolean, c["exchangeRolling"] as Boolean,
                    c["live"].num(), c["existing"].num(),
                ),
                "chooseChange $c",
            )
        }
        data["skipRest"].list().forEach { raw ->
            val c = raw.obj()
            assertEquals(
                "skipRest $c", c["expected"],
                LiveRules.skipRest(
                    c["lastTickAt"].num()?.toLong(), c["now"].num()!!.toLong(),
                    c["rolling"] as Boolean, c["stampUnchanged"] as Boolean, c["exchangeRolling"] as Boolean,
                ),
            )
        }
    }

    @Test
    fun manySymbolsAreSplitAcrossConnections() {
        val pairs = (0 until 450).map { i ->
            LivePair(i.toLong(), "Binance", "Binance", "C${i.toString().padStart(3, '0')}USDT", "C$i", "USDT", "NONE")
        }
        val plan = LivePlanner.plan(pairs)
        assertEquals(listOf(200, 200, 50), plan.connections.map { it.symbols.size })
        assertEquals(listOf("BINANCE#0", "BINANCE#1", "BINANCE#2"), plan.connections.map { it.key })
        assertEquals(450, plan.watchIds.getValue(LiveExchange.BINANCE).size)

        // Mehr als drei Verbindungen öffnet die App nicht; der Rest bleibt bei REST
        val many = (0 until 900).map { i ->
            LivePair(i.toLong(), "Bybit", "Bybit", "S${i.toString().padStart(3, '0')}", "S$i", "USDT", "NONE")
        }
        val capped = LivePlanner.plan(many)
        assertEquals(LivePlanner.MAX_CONNECTIONS_PER_EXCHANGE, capped.connections.size)
        assertEquals(600, capped.watchIds.getValue(LiveExchange.BYBIT).size)
        // Bybit Spot: höchstens 10 Symbole je Abo-Nachricht
        assertEquals(20, LiveExchange.BYBIT.subscribeMessages(capped.connections[0].symbols).size)
    }

    @Test
    fun bufferMergesDeltasAndTracksUnsaved() {
        val buffer = LiveBuffer()
        // Delta ohne Kurs vor dem ersten Stand: nichts
        assertFalse(buffer.offer(listOf(1L), LiveTick("BTCUSDT", null, null, 10), now = 100))
        assertNull(buffer.takeForUi())

        assertTrue(buffer.offer(listOf(1L, 2L), LiveTick("BTCUSDT", 100.0, 1.5, 10), now = 100))
        assertEquals(LiveQuote(100.0, 1.5, 10), buffer.takeForUi()!![1L])
        assertNull("nothing new for the UI", buffer.takeForUi())

        // Delta mit Kurs, ohne Veränderung: Veränderung bleibt; ohne Zeit → Empfangszeit
        assertTrue(buffer.offer(listOf(1L), LiveTick("BTCUSDT", 101.0, null, null), now = 200))
        assertEquals(LiveQuote(101.0, 1.5, 200), buffer.takeForUi()!![1L])

        val batch = buffer.takeForDb()
        assertEquals(setOf(1L, 2L), batch.keys)
        assertEquals(101.0, batch.getValue(1L).price, 0.0)
        assertTrue("saved", buffer.takeForDb().isEmpty())

        // Gleicher Kurs, nur neue Zeit: Anzeige ja, Datenbank nein
        assertTrue(buffer.offer(listOf(1L), LiveTick("BTCUSDT", 101.0, 1.5, 300), now = 300))
        assertTrue(buffer.takeForDb().isEmpty())

        // Speichern ging nicht: beim nächsten Mal nochmals
        buffer.offer(listOf(2L), LiveTick("BTCUSDT", 99.0, null, 400), now = 400)
        val failed = buffer.takeForDb()
        assertEquals(setOf(2L), failed.keys)
        buffer.markUnsaved(failed.keys)
        assertEquals(setOf(2L), buffer.takeForDb().keys)

        // Andere Gruppe: nur noch Paar 1
        buffer.offer(listOf(2L), LiveTick("BTCUSDT", 98.0, null, 500), now = 500)
        buffer.retain(setOf(1L))
        assertTrue(buffer.takeForDb().isEmpty())
        assertEquals(setOf(1L), buffer.takeForUi()!!.keys)
        assertEquals(1, buffer.size)

        buffer.clear()
        assertTrue(buffer.takeForUi()!!.isEmpty())
    }

    @Test
    fun jsonReaderHandlesEscapesAndRejectsGarbage() {
        val parsed = LiveJson.parse("""{"a":"x\"yé","b":[1,-2.5e1,true,null]}""")!!.obj()
        assertEquals("x\"yé", parsed["a"])
        assertEquals(listOf(1.0, -25.0, true, null), parsed["b"])
        assertNull(LiveJson.parse("""{"a":1"""))
        assertNull(LiveJson.parse("pong"))
        assertNull(LiveJson.parse("""{"a":1} x"""))
    }
}
