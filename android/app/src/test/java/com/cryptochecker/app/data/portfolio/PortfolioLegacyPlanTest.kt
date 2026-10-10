package com.cryptochecker.app.data.portfolio

import org.junit.Assert.assertEquals
import org.junit.Test

/** Entscheidung der einmaligen Übernahme aus der Hauptdatenbank (PortfolioLegacyImport). */
class PortfolioLegacyPlanTest {

    private fun decide(legacy: Boolean, imported: Boolean, empty: Boolean) =
        PortfolioLegacyPlan.decide(legacyPresent = legacy, alreadyImported = imported, portfolioEmpty = empty)

    @Test
    fun withoutLegacyTablesNothingHappens() {
        // Neuinstallation, oder Übernahme längst erledigt — egal, was in der Portfolio-Datenbank steht
        for (imported in listOf(false, true)) for (empty in listOf(false, true)) {
            assertEquals(PortfolioLegacyPlan.NOTHING, decide(legacy = false, imported = imported, empty = empty))
        }
    }

    @Test
    fun firstUpdateCopiesWithOldIds() {
        assertEquals(PortfolioLegacyPlan.COPY_KEEP_IDS, decide(legacy = true, imported = false, empty = true))
    }

    @Test
    fun abortAfterCopyOnlyDropsLegacy() {
        // Merker gesetzt: nie ein zweites Mal kopieren (sonst doppelte Käufe)
        assertEquals(PortfolioLegacyPlan.DROP_LEGACY, decide(legacy = true, imported = true, empty = false))
        assertEquals(PortfolioLegacyPlan.DROP_LEGACY, decide(legacy = true, imported = true, empty = true))
    }

    @Test
    fun failedCopyWithNewEntriesAppendsInsteadOfOverwriting() {
        assertEquals(PortfolioLegacyPlan.COPY_NEW_IDS, decide(legacy = true, imported = false, empty = false))
    }
}
