package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.portfolio.HistoricCloseChain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gemeinsame Testfälle `historic_close_chain.json` (iOS: `HistoricCloseChainParityTests.swift`). */
class HistoricCloseChainParityTest {

    private val data = ParityJson.load("historic_close_chain.json")

    @Test
    fun order() {
        val cases = data["order"].list().map { it.obj() }
        assertTrue(cases.size >= 8)
        cases.forEach { c ->
            assertEquals("order: $c", c["expected"].list(), HistoricCloseChain.order(c["own"] as String?))
        }
        assertEquals(data["seriesChain"].list(), HistoricCloseChain.SERIES_CHAIN)
    }

    @Test
    fun provider() {
        data["provider"].list().map { it.obj() }.forEach { c ->
            assertEquals("provider: $c", c["expected"], HistoricCloseChain.provider(c["exchangeKey"] as String))
        }
    }

    @Test
    fun pairs() {
        data["pairs"].list().map { it.obj() }.forEach { c ->
            val expected = c["expected"].list().map { it.list() }.map { HistoricCloseChain.ChainPair(it[0] as String, it[1] as String) }
            assertEquals("pairs: $c", expected, HistoricCloseChain.pairs(c["exchangeKey"] as String, c["base"] as String))
        }
    }

    /** Gleiches Kürzel, anderer Token: Name entscheidet vor dem Kursband. */
    @Test
    fun names() {
        data["names"].list().map { it.obj() }.forEach { c ->
            val allowed = HistoricCloseChain.nameAllowed(
                c["base"] as String, c["own"] as Boolean, c["exchangeKey"] as String,
                c["ownName"] as String?, c["binanceName"] as String?, c["providerName"] as String?,
            )
            assertEquals("names: $c", c["expected"], allowed)
        }
    }

    @Test
    fun decide() {
        val cases = data["decide"].list().map { it.obj() }
        assertTrue(cases.size >= 10)
        cases.forEach { c ->
            val verdict = HistoricCloseChain.decide(
                c["close"].num(), c["latest"].num(), c["current"].num(), c["primary"] as Boolean, c["nameOk"] as Boolean,
            )
            assertEquals("decide: $c", c["expected"], verdict.name)
        }
    }

    /** Reihenfolge, Abbruch beim ersten Treffer, Anbieter und «ungeprüft». */
    @Test
    fun resolve() {
        data["resolve"].list().map { it.obj() }.forEach { c ->
            val outcomes = c["outcomes"].obj()
            val asked = ArrayList<String>()
            val r = HistoricCloseChain.resolve(c["order"].list().map { it as String }) { source ->
                asked += source
                val o = outcomes[source]?.obj()
                HistoricCloseChain.Outcome(o?.get("price").num(), o?.get("rejected") as Boolean? ?: false, o?.get("provider") as String?)
            }
            val e = c["expected"].obj()
            val name = c["name"]
            val price = e["price"].num()
            if (price == null) assertNull("price $name", r.price) else assertEquals("price $name", price, r.price!!, 1e-12)
            assertEquals("source $name", e["source"], r.source)
            assertEquals("provider $name", e["provider"], r.provider)
            assertEquals("unchecked $name", e["unchecked"], r.unchecked)
            assertEquals("asked $name", e["asked"].list(), asked)
        }
    }

    @Test
    fun acceptSeries() {
        data["acceptSeries"].list().map { it.obj() }.forEach { c ->
            assertEquals(
                "acceptSeries: $c", c["expected"],
                HistoricCloseChain.acceptSeries(c["latest"].num(), c["current"].num(), c["nameOk"] as Boolean),
            )
        }
    }

    @Test
    fun fillUsdtUsd() {
        fun days(v: Any?): Map<Long, Double> = v.obj().entries.associate { (k, x) -> k.toLong() to x.num()!! }
        data["fillUsdtUsd"].list().map { it.obj() }.forEach { c ->
            assertEquals("fillUsdtUsd: $c", days(c["expected"]), HistoricCloseChain.fillUsdtUsd(days(c["primary"]), days(c["secondary"])))
        }
    }
}
