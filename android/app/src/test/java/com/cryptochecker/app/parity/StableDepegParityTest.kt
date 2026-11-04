package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.alarm.DepegAlarm
import com.cryptochecker.app.domain.alarm.StableDepeg
import com.cryptochecker.app.domain.convert.CurrencyConversion
import com.cryptochecker.app.domain.portfolio.PortfolioExchangeRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gemeinsame Testfälle `stable_depeg.json` (iOS: `StableDepegParityTests.swift`). */
class StableDepegParityTest {

    private val data = ParityJson.load("stable_depeg.json")
    private val now = 1_800_000_000_000L

    @Test
    fun coins() {
        assertEquals(data["coins"].list(), StableDepeg.COINS)
        // Dieselbe Liste wie die übrigen Stablecoins der App (ohne USD und USDT)
        assertEquals(CurrencyConversion.USD_STABLES - setOf("USD", "USDT"), StableDepeg.COINS.toSet())
        StableDepeg.COINS.forEachIndexed { i, c -> assertEquals(i, StableDepeg.notificationIndex(c.lowercase())) }
        assertEquals(-1, StableDepeg.notificationIndex("USDT"))
    }

    @Test
    fun requireTwo() {
        data["requireTwo"].list().map { it.obj() }.forEach { c ->
            assertEquals("requireTwo: $c", c["expected"], StableDepeg.requiresTwo(c["coin"] as String))
        }
    }

    @Test
    fun watched() {
        data["watched"].list().map { it.obj() }.forEach { c ->
            val got = StableDepeg.watched(c["watchBases"].list().map { it as String }, c["holdings"].list().map { it as String })
            assertEquals("watched: $c", c["expected"].list(), got)
        }
    }

    @Test
    fun pickPair() {
        data["pickPair"].list().map { it.obj() }.forEach { c ->
            val pairs = c["pairs"].list().map { p -> p.list().let { PortfolioExchangeRoute.Pair3(it[0] as String, it[1] as String, it[2] as String?) } }
            val got = StableDepeg.pickPair(c["coin"] as String, pairs)
            val expected = c["expected"]?.list()
            if (expected == null) assertNull("pickPair: $c", got) else assertEquals("pickPair: $c", expected, listOf(got?.base, got?.quote, got?.pairId))
        }
    }

    @Test
    fun toUsd() {
        data["toUsd"].list().map { it.obj() }.forEach { c ->
            val got = StableDepeg.toUsd(c["price"].num(), c["quote"] as String, c["usdtUsd"].num(), c["usdcUsd"].num())
            val expected = c["expected"].num()
            if (expected == null) assertNull("toUsd: $c", got) else assertEquals("toUsd: $c", expected, got!!, 1e-12)
        }
    }

    /** Je Börse eine Quelle; Kreuzkurs allein bzw. (ausser USDC) weniger als zwei Quellen nie alarmfähig. */
    @Test
    fun measure() {
        val cases = data["measure"].list().map { it.obj() }
        assertTrue(cases.size >= 12)
        cases.forEach { c ->
            val sources = c["sources"].list().map { it.obj() }.map { StableDepeg.Source(it["usd"].num(), it["cross"] as Boolean) }
            val m = StableDepeg.measure(sources, c["requireTwo"] as Boolean)
            val expected = c["expected"].num()
            if (expected == null) {
                assertNull("measure: ${c["name"]}", m)
            } else {
                assertEquals("measure: ${c["name"]}", expected, m!!.price, 1e-9)
                assertEquals("alarmable: ${c["name"]}", c["alarmable"], m.alarmable)
            }
        }
    }

    @Test
    fun lineItems() {
        fun reading(v: Any?) = v?.obj()?.let { DepegAlarm.Reading(it["price"].num()!!, now - it["ageMillis"].num()!!.toLong()) }
        data["lineItems"].list().map { it.obj() }.forEach { c ->
            val readings = c["readings"].obj().mapValues { (_, v) -> reading(v)!! }
            val got = StableDepeg.lineItems(c["watched"].list().map { it as String }, reading(c["usdt"]), readings, now)
            val expected = c["expected"].list().map { it.list().let { e -> (e[0] as String) to e[1].num() } }
            assertEquals("lineItems: $c", expected, got)
        }
    }
}
