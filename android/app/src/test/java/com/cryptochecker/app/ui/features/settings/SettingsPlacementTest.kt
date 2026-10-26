package com.cryptochecker.app.ui.features.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Kurs-Aktualisierung unter «Allgemein», Widgets und Coin-Logos unter «Darstellung»; kein «Erweitert» mehr
 * (Build 132): %-Änderung und Token & TradFi stehen auf «Merkliste», Entwickler auf «Über die App» (Markt-Meldungen
 * und Sprachausgabe haben eigene Routen, Zeilen auf «Alarme»). Portfolio hat genau einen Ort, die Zeile unter
 * «Allgemein» (Einstellungen › Portfolio). Die Suche zeigt den Pfad aus diesem Ort — wie
 * `testPagesSitWhereTheMainPageShowsThem` (iOS, SettingsSearchTests).
 */
class SettingsPlacementTest {

    @Test
    fun pagesSitWhereTheMainPageShowsThem() {
        assertEquals(SettingsHome.GENERAL, SettingsPage.UPDATES.home)
        assertEquals(SettingsHome.GENERAL, SettingsPage.ALARMS.home)
        assertEquals(SettingsHome.APPEARANCE, SettingsPage.WIDGETS.home)
        assertEquals(SettingsHome.APPEARANCE, SettingsPage.PRICE_COLORS.home)
        assertEquals(SettingsHome.GENERAL, SettingsPage.PORTFOLIO.home)
        assertEquals(SettingsHome.APPEARANCE, SettingsPage.COIN_LOGOS.home)
        assertEquals(setOf(SettingsHome.GENERAL, SettingsHome.APPEARANCE, SettingsHome.MORE), SettingsHome.entries.toSet())
        assertNull("frühere Route «Erweitert» führt zurück", SettingsPage.fromName("ADVANCED"))
    }

    @Test
    fun subpagesOfSubpagesHaveNoHome() {
        // Preis-Paare stehen auf «Alarme», Lizenzen und Entwickler auf «Über die App», %-Änderung und Token & TradFi
        // auf «Merkliste»
        assertNull(SettingsPage.PRICE_PAIRS.home)
        assertNull(SettingsPage.CHANGE_BASIS.home)
        assertNull(SettingsPage.MARKETS.home)
        assertNull(SettingsPage.LICENSES.home)
        assertNull(SettingsPage.DEVELOPER.home)
    }
}
