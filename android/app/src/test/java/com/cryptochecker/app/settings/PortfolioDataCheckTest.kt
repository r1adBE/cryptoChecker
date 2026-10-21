package com.cryptochecker.app.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Merker «Portfolio-Daten prüfen» nach neu angelegten Einstellungen ([PortfolioDataCheck.keepMarker]), seit Build 95. */
class PortfolioDataCheckTest {

    @Test
    fun `leeres Portfolio nur im Arbeitsspeicher behaelt den Merker`() {
        // Schlüssel gerade nicht lesbar: der gespeicherte Stand ist nicht zu sehen — nicht als «keine Daten» entscheiden
        assertTrue(PortfolioDataCheck.keepMarker(hasData = false, portfolioInMemory = true))
    }

    @Test
    fun `lesbare Datenbank entscheidet`() {
        assertFalse(PortfolioDataCheck.keepMarker(hasData = false, portfolioInMemory = false))
        assertFalse(PortfolioDataCheck.keepMarker(hasData = true, portfolioInMemory = false))
        // Daten im Arbeitsspeicher gefunden (z. B. Kopie einer alten Datei): Portfolio einschalten
        assertFalse(PortfolioDataCheck.keepMarker(hasData = true, portfolioInMemory = true))
    }
}
