package com.cryptochecker.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** Aufklappliste mit Abschnitten (Build 133, Börsenauswahl): Favoriten oben, Titelzeilen, Suche über alle. */
class ComboBoxEntriesTest {

    private val items = listOf("Binance", "Kraken", "Binance Futures", "DexScreener")
    private val sections = listOf("Spot", "Spot", "Futures", "DEX-Pools")

    private fun shown(query: String?, favorites: Set<String> = emptySet()) =
        comboEntries(items, query, favorites, sections).map { if (it.header) "# ${it.text}" else it.text }

    @Test
    fun abschnitteMitTitelzeilen() {
        assertEquals(
            listOf("# Spot", "Binance", "Kraken", "# Futures", "Binance Futures", "# DEX-Pools", "DexScreener"),
            shown(null),
        )
    }

    @Test
    fun favoritenObenOhneTitel() {
        assertEquals(
            listOf("Binance Futures", "# Spot", "Binance", "Kraken", "# DEX-Pools", "DexScreener"),
            shown(null, setOf("Binance Futures")),
        )
    }

    @Test
    fun sucheUeberAlleAbschnitteLeereFallenWeg() {
        assertEquals(listOf("# Spot", "Binance", "# Futures", "Binance Futures"), shown("bin"))
        assertEquals(listOf("# DEX-Pools", "DexScreener"), shown("screen"))
    }

    @Test
    fun ohneAbschnitteWieBisher() {
        assertEquals(listOf("Kraken", "Binance"), comboEntries(listOf("Binance", "Kraken"), null, setOf("Kraken")).map { it.text })
    }
}
