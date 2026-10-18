package com.cryptochecker.app.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Typsichere Navigationsziele: Navigation legt die Argumente unter dem Feldnamen in den SavedStateHandle.
 * Wer dort per Schlüssel liest (AlarmsViewModel, Seite «Daten»), muss denselben Namen verwenden.
 */
class ScreenRouteTest {

    @Test
    fun argumentKeysMatchFieldNames() {
        assertEquals(ScreenRoute.AlarmsArgWatchId, ScreenRoute.Alarms.serializer().descriptor.getElementName(0))
        val subpage = ScreenRoute.MarketSubpage.serializer().descriptor
        assertEquals(ScreenRoute.MarketSubpageArgMacro, subpage.getElementName(1))
    }

    @Test
    fun macroJumpIsOptional() {
        // Zeilen «Einordnung»/«Daten» öffnen ohne Sprung; nur die Mitteilung setzt ihn
        assertFalse(ScreenRoute.MarketSubpage("DATA").macro)
        assertEquals(ScreenRoute.MarketSubpage("DATA", macro = false), ScreenRoute.MarketSubpage("DATA"))
    }

    @Test
    fun tabsAreDistinctRoutes() {
        val tabs = buildBottomTabs(portfolioEnabled = true).map { it.route }
        assertEquals(
            listOf(ScreenRoute.Watchlist, ScreenRoute.MarketPhase, ScreenRoute.Portfolio, ScreenRoute.Settings),
            tabs,
        )
        assertEquals(
            listOf(ScreenRoute.Watchlist, ScreenRoute.MarketPhase, ScreenRoute.Settings),
            buildBottomTabs(portfolioEnabled = false).map { it.route },
        )
    }
}
