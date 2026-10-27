package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.watch.CompareMath
import com.cryptochecker.app.domain.watch.ComparePoint
import com.cryptochecker.app.widget.WidgetCandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Vergleichsansicht (`compare.json`) — wie `CompareParityTests.swift`: Auswahl, Linien in %, Kennzahlen, Achsen. */
class CompareParityTest {

    private val data = ParityJson.load("compare.json")

    private fun candle(raw: Any?): WidgetCandle {
        val o = raw.obj()
        val close = o["close"].num()!!
        return WidgetCandle(o["t"].num()!!.toLong(), close, o["high"].num()!!, close, close)
    }

    private fun assertNear(label: String, expected: Double?, actual: Double?) {
        if (expected == null) assertNull(label, actual) else assertEquals(label, expected, actual!!, 1e-9)
    }

    @Test
    fun canCompare() {
        for (raw in data.getValue("canCompare").list()) {
            val c = raw.obj()
            assertEquals("${c["count"]}", c["expected"] as Boolean, CompareMath.canCompare(c["count"].num()!!.toInt()))
        }
    }

    @Test
    fun normalized() {
        for (raw in data.getValue("normalized").list()) {
            val c = raw.obj()
            val actual = CompareMath.normalized(c["candles"].list().map(::candle))
            val expected = c["expected"].list().map { p -> p.list().let { ComparePoint(it[0].num()!!.toLong(), it[1].num()!!) } }
            assertEquals(c["name"] as String, expected.size, actual.size)
            expected.zip(actual).forEach { (e, a) ->
                assertEquals(c["name"] as String, e.time, a.time)
                assertEquals(c["name"] as String, e.percent, a.percent, 1e-9)
            }
        }
    }

    @Test
    fun figures() {
        for (raw in data.getValue("figures").list()) {
            val c = raw.obj()
            val name = c["name"] as String
            val f = CompareMath.figures(c["daily"].list().map(::candle), c["price"].num(), c["now"].num()!!.toLong())
            val e = c["expected"].obj()
            assertNear("$name 7 T", e["change7d"].num(), f.change7d)
            assertNear("$name 30 T", e["change30d"].num(), f.change30d)
            assertNear("$name Hoch", e["fromHigh30d"].num(), f.fromHigh30d)
        }
    }

    @Test
    fun bounds() {
        for (raw in data.getValue("bounds").list()) {
            val c = raw.obj()
            val name = c["name"] as String
            val series = c["series"].list().map { s -> s.list().map { p -> p.list().let { ComparePoint(it[0].num()!!.toLong(), it[1].num()!!) } } }
            val b = CompareMath.bounds(series)
            val e = c["expected"]
            if (e == null) { assertNull(name, b); continue }
            val o = e.obj()
            assertEquals(name, o["minTime"].num()!!.toLong(), b!!.minTime)
            assertEquals(name, o["maxTime"].num()!!.toLong(), b.maxTime)
            assertEquals(name, o["minPercent"].num()!!, b.minPercent, 1e-9)
            assertEquals(name, o["maxPercent"].num()!!, b.maxPercent, 1e-9)
        }
    }
}
