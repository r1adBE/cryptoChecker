package com.cryptochecker.app.parity

import com.cryptochecker.app.ui.features.explorer.ExchangeMark
import com.cryptochecker.app.ui.features.explorer.ExchangeSearch
import com.cryptochecker.app.ui.features.explorer.ForexLive
import com.cryptochecker.app.ui.features.explorer.MarketFilter
import com.cryptochecker.app.ui.features.explorer.RecentExchanges
import com.cryptochecker.marketdata.model.ExchangeCategory
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Übersicht von «Paar hinzufügen» (`testdata/parity/explorer_browse.json`): Markt-Chips, «Zuletzt genutzte Börsen»,
 * Zeichen der Börsen und Börsen zum Suchtext. Gegenstück: `Tests/ExplorerBrowseParityTests.swift`.
 */
class ExplorerBrowseParityTest {

    private val fixture = ParityJson.load("explorer_browse.json")

    @Suppress("UNCHECKED_CAST")
    private fun strings(value: Any?): List<String> = value as List<String>

    @Test
    fun marktChipsZeigenIhreArt() {
        val cases = fixture["markets"].list().map { it.obj() }
        assertEquals(MarketFilter.entries.map { it.name }, cases.map { it["filter"] })
        for (c in cases) {
            val filter = MarketFilter.valueOf(c["filter"] as String)
            val shown = ExchangeCategory.entries.filter { filter.allows(it) }.map { it.name }
            assertEquals(filter.name, strings(c["shows"]), shown)
        }
    }

    @Test
    fun zuletztGenutzteBoersen() {
        assertEquals(fixture["recentMax"].num()!!.toInt(), RecentExchanges.MAX)
        for (c in fixture["recent"].list().map { it.obj() }) {
            assertEquals(c.toString(), strings(c["expected"]), RecentExchanges.add(strings(c["list"]), c["add"] as String))
        }
        for (c in fixture["recentDecode"].list().map { it.obj() }) {
            val decoded = RecentExchanges.decode(c["text"] as String)
            assertEquals(c.toString(), strings(c["expected"]), decoded)
            assertEquals(decoded, RecentExchanges.decode(RecentExchanges.encode(decoded)))
        }
    }

    @Test
    fun zeichenDerBoersen() {
        for (c in fixture["marks"].list().map { it.obj() }) {
            val name = c["name"] as String
            assertEquals(name, c["initials"], ExchangeMark.initials(name))
            assertEquals(name, c["colorKey"], ExchangeMark.colorKey(name))
        }
    }

    @Test
    fun boersenZumSuchtext() {
        assertEquals(fixture["exchangeSearchLimit"].num()!!.toInt(), ExchangeSearch.LIMIT)
        for (c in fixture["exchangeSearch"].list().map { it.obj() }) {
            val actual = ExchangeSearch.find(c["query"] as String, strings(c["names"])) { it }
            assertEquals(c.toString(), strings(c["expected"]), actual)
        }
    }

    @Test
    fun devisenLiveNurFiatPaare() {
        val data = fixture["forexLive"].obj()
        assertEquals(strings(data["exchanges"]), ForexLive.EXCHANGES)
        for (c in data["pairs"].list().map { it.obj() }) {
            assertEquals(c.toString(), c["fiat"], ForexLive.isFiatPair(c["base"] as String, c["quote"] as String))
        }
        for (c in data["live"].list().map { it.obj() }) assertEquals(c.toString(), c["live"], ForexLive.isLive(c["key"] as String))
    }
}
