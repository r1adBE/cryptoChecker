package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.market.ExchangeOrder
import com.cryptochecker.marketdata.model.MarketCategory
import com.cryptochecker.marketdata.model.market.DexScreener
import com.cryptochecker.marketdata.util.GeckoTerminalNetworks
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reihenfolge der Börsenauswahl (`testdata/parity/picker_order.json`) und Netzwerke des DEX-Auswegs
 * (`gecko_terminal_network.json`). Gegenstück: `Tests/ParityFixtureTests.swift`.
 */
class ExchangeOrderParityTest {

    private val order = ParityJson.load("picker_order.json")
    private val networks = ParityJson.load("gecko_terminal_network.json")

    @Test
    fun abschnitteWieGemeinsameFaelle() {
        val cases = order["cases"].list().map { it.obj() }
        assertTrue(cases.size >= 3)
        for (c in cases) {
            val markets = c["markets"].list().map { it.obj() }
                .map { ExchangeOrder.Entry(it["key"] as String, MarketCategory.valueOf(it["category"] as String)) }
            val volumes = (c["volumes"] as Map<*, *>?)?.entries?.associate { (k, v) -> k as String to v.num()!!.toDouble() }
            val actual = ExchangeOrder.sections(markets, volumes).map { (category, keys) -> category.name to keys }
            val expected = c["expected"].list().map { it.obj() }
                .map { it["category"] as String to it["keys"].list().map { k -> k as String } }
            assertEquals(c["name"] as String, expected, actual)
        }
    }

    @Test
    fun volumenAusBeidenListen() {
        val parse = order["parse"].obj()
        val spot = JSONArray(parse["spot"].list().map { row -> JSONObject(row.obj()) }).toString()
        val derivatives = JSONArray(parse["derivatives"].list().map { row -> JSONObject(row.obj()) }).toString()
        val expected = parse["expected"].obj().mapValues { it.value.num()!!.toDouble() }
        assertEquals(expected, ExchangeOrder.parseVolumes(spot, derivatives))
    }

    @Test
    fun netzwerkeDesDexAuswegs() {
        for (c in networks["cases"].list().map { it.obj() }) {
            assertEquals(c["chain"] as String, c["network"], GeckoTerminalNetworks.network(c["chain"] as String))
        }
        val url = networks["url"].obj()
        assertEquals(url["expected"], DexScreener.geckoTerminalUrl(url["chain"] as String, url["address"] as String))
    }
}
