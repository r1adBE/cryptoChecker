package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.logos.CoinLogos
import com.cryptochecker.app.domain.logos.bundledIconName
import com.cryptochecker.app.domain.market.ExchangeLogos
import com.cryptochecker.app.domain.market.ExchangeOrder
import com.cryptochecker.marketdata.config.ExchangesConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Börsen-Logos (`testdata/parity/exchange_logos.json`): Zuordnung jeder registrierten Börse (Logo, Symbol oder
 * bewusst keins), Reihenfolge, Dateinamen, Seiten und Einzelseite der CoinGecko-Börsenliste, erneuter Versuch.
 * Gegenstück: `Tests/ExchangeLogosParityTests.swift`.
 */
class ExchangeLogosParityTest {

    private val fixture = ParityJson.load("exchange_logos.json")

    @Test
    fun jedeBoerseHatLogoSymbolOderBewusstKeins() {
        val keys = fixture["keys"].obj()
        // Die Testfälle nennen genau die registrierten Börsen (neue Börse → hier und in iOS eintragen)
        assertEquals(ExchangesConfig.EXCHANGES.keys, keys.keys)
        for ((key, raw) in keys) {
            val spec = raw.obj()
            assertEquals(key, spec["logo"], ExchangeLogos.logoKey(key))
            assertEquals(key, spec["symbol"], ExchangeLogos.SYMBOLS[key]?.name)
            assertEquals(key, spec["none"] == true, key in ExchangeLogos.NO_LOGO)
            val kinds = listOf(ExchangeLogos.logoKey(key) != null, key in ExchangeLogos.SYMBOLS, key in ExchangeLogos.NO_LOGO)
            assertEquals(key, 1, kinds.count { it })
        }
    }

    @Test
    fun reihenfolgeUndKennungenWieDieReihenfolgeDerAuswahl() {
        val head = fixture["order_head"].list()
        assertEquals(head, ExchangeLogos.ORDER.take(head.size))
        assertEquals(fixture["order_tail"], ExchangeLogos.ORDER.last())
        assertEquals(ExchangeLogos.ORDER.size, ExchangeLogos.ORDER.toSet().size)
        // Seite 1 ist dieselbe Abfrage wie für die Reihenfolge; gleiche Kennungen für Logo und Volumen (HashKey: «hashkey»)
        assertEquals(ExchangeOrder.SPOT_URL, ExchangeLogos.pageUrl(1))
        for ((key, id) in ExchangeOrder.SPOT_IDS) assertEquals(key, id, ExchangeLogos.SPOT_IDS[key])
        assertEquals("hashkey", ExchangeOrder.SPOT_IDS["HashKey"])
        assertEquals(fixture["perPage"].num()!!.toInt(), ExchangeLogos.PER_PAGE)
        assertEquals(fixture["maxPages"].num()!!.toInt(), ExchangeLogos.MAX_PAGES)
    }

    @Test
    fun dateinamenImEigenenNamensraum() {
        for (c in fixture["fileNames"].list().map { it.obj() }) {
            assertEquals(c["key"] as String, c["file"], CoinLogos.fileName(c["key"] as String))
        }
        assertNull(CoinLogos.bundledIconName("EXCHANGE:binance"))
        assertTrue(ExchangeLogos.ORDER.all { CoinLogos.fileName(it) != null })
    }

    @Test
    fun seiteEinzelseiteUndWeiterlesen() {
        val page = fixture["page"].obj()
        val body = JSONArray(page["rows"].list().map { row -> JSONObject(row.obj()) }).toString()
        val parsed = ExchangeLogos.parsePage(body)
        assertEquals(page["expected"].obj(), parsed.images)
        assertEquals(page["expectedRows"].num()!!.toInt(), parsed.rows)
        val logos = ExchangeLogos.images(parsed.images)
        assertEquals(page["logoImages"].obj(), logos)
        assertEquals(ExchangeLogos.ORDER.filter { it in logos }, logos.keys.toList())
        assertEquals(ExchangeLogos.IDS.size - 2, ExchangeLogos.missing(parsed.images).size)
        for (c in fixture["detail"].list().map { it.obj() }) {
            assertEquals(c["expected"], ExchangeLogos.parseDetail(JSONObject(c["body"].obj()).toString()))
        }
        for (c in fixture["nextPage"].list().map { it.obj() }) {
            val actual = ExchangeLogos.needsNextPage(c["page"].num()!!.toInt(), c["rows"].num()!!.toInt(), c["missing"].num()!!.toInt())
            assertEquals(c.toString(), c["expected"], actual)
        }
        for (c in fixture["retry"].list().map { it.obj() }) {
            val actual = ExchangeLogos.retryDue(c["complete"] as Boolean, c["attemptAt"].num()!!.toLong(), c["now"].num()!!.toLong())
            assertEquals(c.toString(), c["expected"], actual)
        }
    }
}
