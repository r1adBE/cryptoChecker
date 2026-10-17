package com.cryptochecker.app.ui.features.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Build 55: Kurs-Aktualisierung unter «Allgemein», Widgets unter «Darstellung»; «Erweitert» behält
 * Token & TradFi, Portfolio, %-Änderung, Coin-Logos und Entwickler (Markt-Meldungen und Sprachausgabe
 * haben eigene Routen). Die Suche zeigt den Pfad aus diesem Ort — wie
 * `testPagesSitWhereTheMainPageShowsThem` (iOS, SettingsSearchTests).
 */
class SettingsPlacementTest {

    @Test
    fun pagesSitWhereTheMainPageShowsThem() {
        assertEquals(SettingsHome.GENERAL, SettingsPage.UPDATES.home)
        assertEquals(SettingsHome.GENERAL, SettingsPage.ALARMS.home)
        assertEquals(SettingsHome.APPEARANCE, SettingsPage.WIDGETS.home)
        assertEquals(SettingsHome.APPEARANCE, SettingsPage.PRICE_COLORS.home)
        listOf(SettingsPage.MARKETS, SettingsPage.PORTFOLIO, SettingsPage.CHANGE_BASIS, SettingsPage.COIN_LOGOS, SettingsPage.DEVELOPER)
            .forEach { assertEquals(it.name, SettingsHome.ADVANCED, it.home) }
        assertEquals(SettingsHome.MORE, SettingsPage.ADVANCED.home)
    }

    @Test
    fun subpagesOfSubpagesHaveNoHome() {
        // Preis-Paare stehen auf «Alarme», Lizenzen auf «Über die App»
        assertNull(SettingsPage.PRICE_PAIRS.home)
        assertNull(SettingsPage.LICENSES.home)
    }
}
