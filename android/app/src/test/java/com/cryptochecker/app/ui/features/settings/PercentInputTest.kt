package com.cryptochecker.app.ui.features.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Eigene Melde-Schwelle: Eingabefilter und Prüfung — gleich wie iOS `SettingsPercentInputTests`.
 * Das arabische Dezimalzeichen «٫» darf der Filter nicht verschlucken (sonst «٢٫٥» → 25 %).
 */
class PercentInputTest {

    private fun typed(input: String): Double? = parsePercent(filterPercentInput(input))

    @Test
    fun arabischesUndPersischesDezimalzeichenBleibt() {
        assertEquals("٢٫٥", filterPercentInput("٢٫٥"))
        assertEquals(2.5, typed("٢٫٥")!!, 0.0)
        assertEquals(2.5, typed("۲٫۵")!!, 0.0)
        assertEquals(2.5, typed("2,5")!!, 0.0)
        assertEquals(2.5, typed("2.5")!!, 0.0)
    }

    @Test
    fun tausendertrennungWirdAbgelehntStattVerschluckt() {
        // «١٬٥» bliebe sonst still 15; so markiert das Feld den Fehler
        assertEquals("١٬٥", filterPercentInput("١٬٥"))
        assertNull(typed("١٬٥"))
        assertTrue(percentInputError(filterPercentInput("١٬٥")))
    }

    @Test
    fun filterEntferntFremdesUndKuerztAufSechsZeichen() {
        assertEquals("25", filterPercentInput("2a5 %"))
        assertEquals("123456", filterPercentInput("1234567"))
    }

    @Test
    fun fehlerErstBeiUngueltigerEingabe() {
        assertFalse(percentInputError(""))
        assertFalse(percentInputError("0"))
        assertFalse(percentInputError("100"))
        assertTrue(percentInputError("150"))
        assertTrue(percentInputError("1.2.3"))
        assertTrue(percentInputError(","))
    }
}
