package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.market.DexNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DEX-Namen in der Coin-Suche (`testdata/parity/dex_names.json`): «uniswap pepe» → DexScreener mit Hinweis «Pools von
 * Uniswap …» und «pepe» vorbelegt. Gegenstück: `Tests/DexNamesParityTests.swift`.
 */
class DexNamesParityTest {

    private val fixture = ParityJson.load("dex_names.json")

    @Test
    fun namenUndMindestlaengeWieGemeinsameListe() {
        assertEquals(fixture["named"].list(), DexNames.NAMED)
        assertEquals(fixture["generic"].list(), DexNames.GENERIC)
        assertEquals(fixture["minPrefix"].num()!!.toInt(), DexNames.MIN_PREFIX)
    }

    @Test
    fun trefferWieGemeinsameFaelle() {
        val cases = fixture["cases"].list().map { it.obj() }
        assertTrue(cases.size >= 20)
        for (c in cases) {
            val query = c["query"] as String
            val actual = DexNames.match(query)
            if (c["match"] as Boolean) {
                assertEquals(query, DexNames.Match(c["dex"] as String?, c["token"] as String), actual)
            } else {
                assertNull(query, actual)
            }
        }
    }

    /** Jeder Name findet sich selbst, auch klein geschrieben und schon mit den ersten drei Zeichen. */
    @Test
    fun jederNameFindetSich() {
        for (name in DexNames.NAMED) {
            assertEquals(name, name, DexNames.match(name)?.dexName)
            assertEquals(name, name, DexNames.match(name.lowercase())?.dexName)
            assertEquals(name, "", DexNames.match(name)?.token)
            assertEquals(name, name, DexNames.match(name.take(DexNames.MIN_PREFIX))?.dexName)
        }
        for (name in DexNames.GENERIC) {
            val hit = DexNames.match(name)
            assertEquals(name, DexNames.Match(null, ""), hit)
        }
    }
}
