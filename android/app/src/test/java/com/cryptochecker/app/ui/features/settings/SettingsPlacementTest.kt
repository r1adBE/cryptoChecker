package com.cryptochecker.app.ui.features.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Kurs-Aktualisierung unter «Allgemein», Widgets unter «Darstellung»; «Erweitert» behält
 * Token & TradFi, Coin-Logos und Entwickler; %-Änderung steht auf «Merkliste» (Markt-Meldungen und Sprachausgabe
 * haben eigene Routen). Portfolio hat genau einen Ort, die Zeile unter «Allgemein» (Einstellungen ›
 * Portfolio) — kein Schalter mehr in «Mehr», keine Zeile mehr unter «Erweitert». Die Suche zeigt den Pfad aus
 * diesem Ort — wie `testPagesSitWhereTheMainPageShowsThem` (iOS, SettingsSearchTests).
 */
class SettingsPlacementTest {

    @Test
    fun pagesSitWhereTheMainPageShowsThem() {
        assertEquals(SettingsHome.GENERAL, SettingsPage.UPDATES.home)
        assertEquals(SettingsHome.GENERAL, SettingsPage.ALARMS.home)
        assertEquals(SettingsHome.APPEARANCE, SettingsPage.WIDGETS.home)
        assertEquals(SettingsHome.APPEARANCE, SettingsPage.PRICE_COLORS.home)
        assertEquals(SettingsHome.GENERAL, SettingsPage.PORTFOLIO.home)
        listOf(SettingsPage.MARKETS, SettingsPage.COIN_LOGOS, SettingsPage.DEVELOPER)
            .forEach { assertEquals(it.name, SettingsHome.ADVANCED, it.home) }
        assertEquals(SettingsHome.MORE, SettingsPage.ADVANCED.home)
    }

    @Test
    fun subpagesOfSubpagesHaveNoHome() {
        // Preis-Paare stehen auf «Alarme», Lizenzen auf «Über die App», %-Änderung auf «Merkliste»
        assertNull(SettingsPage.PRICE_PAIRS.home)
        assertNull(SettingsPage.CHANGE_BASIS.home)
        assertNull(SettingsPage.LICENSES.home)
    }
}
