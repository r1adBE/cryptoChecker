package com.cryptochecker.app.ui.features.settings

import com.cryptochecker.app.ui.components.ScreenEvents
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Kleine Testklassen dieses Themas in einer Datei; jede Klasse unverändert.

// ---- aus SettingsPlacementTest

/**
 * Kurs-Aktualisierung unter «Allgemein», Widgets und Coin-Logos unter «Darstellung»; kein «Erweitert» mehr
 *: %-Änderung und Token & TradFi stehen auf «Merkliste», Entwickler auf «Über die App» (Markt-Meldungen
 * und Sprachausgabe haben eigene Routen, Zeilen auf «Alarme»). Portfolio hat genau einen Ort, die Zeile unter
 * «Allgemein» (Einstellungen › Portfolio). Die Suche zeigt den Pfad aus diesem Ort — wie
 * `testPagesSitWhereTheMainPageShowsThem` (iOS, SettingsSearchTests).
 */
class SettingsPlacementTest {

    @Test
    fun pagesSitWhereTheMainPageShowsThem() {
        assertEquals(SettingsHome.GENERAL, SettingsPage.UPDATES.home)
        assertEquals(SettingsHome.GENERAL, SettingsPage.USAGE.home)
        assertEquals(SettingsHome.GENERAL, SettingsPage.ALARMS.home)
        assertEquals(SettingsHome.APPEARANCE, SettingsPage.WIDGETS.home)
        assertEquals(SettingsHome.APPEARANCE, SettingsPage.PRICE_COLORS.home)
        assertEquals(SettingsHome.GENERAL, SettingsPage.PORTFOLIO.home)
        assertEquals(SettingsHome.APPEARANCE, SettingsPage.COIN_LOGOS.home)
        assertEquals(setOf(SettingsHome.GENERAL, SettingsHome.APPEARANCE, SettingsHome.MORE), SettingsHome.entries.toSet())
    }

    @Test
    fun subpagesOfSubpagesHaveNoHome() {
        // Preis-Paare stehen auf «Alarme», Lizenzen und Entwickler auf «Über die App», %-Änderung und Token & TradFi
        // auf «Merkliste»
        assertNull(SettingsPage.PRICE_PAIRS.home)
        assertNull(SettingsPage.CHANGE_BASIS.home)
        assertNull(SettingsPage.EXCHANGES.home)
        assertNull(SettingsPage.LICENSES.home)
        assertNull(SettingsPage.DEVELOPER.home)
    }
}

// ---- aus SettingsSearchAnchorsTest: Anker jedes Suchtreffers (beide Plattformen) → Regel settings-anchors in
// .github/scripts/source_rules.py (Struktur, nicht Verhalten)

// ---- aus SettingsUiEventsTest

/**
 * Einstellungen › Kursmitteilungen: Erlaubnis-Abfrage nur nach «Trotzdem» und nur beim Einschalten,
 * als Ereignis für den aktuellen Bildschirm statt als Rückruf in die alte Komposition.
 */
class SettingsUiEventsTest {

    @Test
    fun proceedWhileSwitchingOnAsksExactlyOnce() = runTest {
        val events = ScreenEvents<Unit>()
        askPermissionAfterWrite(events, enabled = true)(true)
        assertEquals(listOf(Unit), events.events.take(1).toList())
        assertNull(withTimeoutOrNull(50) { events.events.first() })
    }

    @Test
    fun cancelLockedOrSwitchingOffAsksNothing() = runTest {
        val events = ScreenEvents<Unit>()
        // «Abbrechen» bzw. gesperrt: nicht geschrieben
        askPermissionAfterWrite(events, enabled = true)(false)
        // Ausschalten: nie fragen
        askPermissionAfterWrite(events, enabled = false)(true)
        askPermissionAfterWrite(events, enabled = false)(false)
        assertNull(withTimeoutOrNull(50) { events.events.first() })
    }

    // Preis-Paare fragen über das Ereignis, nicht aus dem Rückruf: Regel permission-event (source_rules.py)
}
