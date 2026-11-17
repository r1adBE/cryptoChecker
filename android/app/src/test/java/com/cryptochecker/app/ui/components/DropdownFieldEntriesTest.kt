package com.cryptochecker.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** Aufklappliste mit Abschnitten (Börsenauswahl): Favoriten oben, Titelzeilen, Suche über alle. */
class DropdownFieldEntriesTest {

    private val items = listOf("Binance", "Kraken", "Binance Futures", "DexScreener")
    private val sections = listOf("Spot", "Spot", "Futures", "DEX-Pools")

    private fun shown(query: String?, favorites: Set<String> = emptySet()) =
        comboEntries(items, query, favorites, sections).map { if (it.header) "# ${it.text}" else it.text }

    @Test
    fun sectionsWithTitleRows() {
        assertEquals(
            listOf("# Spot", "Binance", "Kraken", "# Futures", "Binance Futures", "# DEX-Pools", "DexScreener"),
            shown(null),
        )
    }

    @Test
    fun favouritesOnTopWithoutTitle() {
        assertEquals(
            listOf("Binance Futures", "# Spot", "Binance", "Kraken", "# DEX-Pools", "DexScreener"),
            shown(null, setOf("Binance Futures")),
        )
    }

    @Test
    fun searchAcrossAllSectionsEmptyOnesAreDropped() {
        assertEquals(listOf("# Spot", "Binance", "# Futures", "Binance Futures"), shown("bin"))
        assertEquals(listOf("# DEX-Pools", "DexScreener"), shown("screen"))
    }

    @Test
    fun withoutSectionsAsBefore() {
        assertEquals(listOf("Kraken", "Binance"), comboEntries(listOf("Binance", "Kraken"), null, setOf("Kraken")).map { it.text })
    }
}
