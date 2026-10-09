package com.cryptochecker.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PriceColorChoiceTest {

    @Test
    fun roundTrip_everyChoice() {
        PriceColorChoice.entries.forEach { choice ->
            assertEquals(choice, PriceColorChoice.of(choice.scheme, choice.inverted))
        }
    }

    @Test
    fun everyStoredCombination_hasOneChoice() {
        val combos = PriceColorScheme.entries.flatMap { s -> listOf(s to false, s to true) }
        assertEquals(combos.size, PriceColorChoice.entries.size)
        assertEquals(combos.size, combos.map { (s, i) -> PriceColorChoice.of(s, i) }.toSet().size)
    }

    @Test
    fun default_isGreenUp() {
        assertEquals(PriceColorChoice.GREEN_UP, PriceColorChoice.of(PriceColorScheme.DEFAULT, false))
        assertFalse(PriceColorChoice.GREEN_UP.inverted)
    }

    @Test
    fun redUp_swapsColorsOfGreenRed() {
        val c = PriceColorChoice.RED_UP
        assertEquals(PriceColorScheme.GREEN_RED, c.scheme)
        // Steigend bekommt die Fallend-Farbe des Schemas
        assertEquals(
            PriceColorScheme.GREEN_RED.down(dark = false),
            c.scheme.up(dark = false, inverted = c.inverted)
        )
        assertNotEquals(
            PriceColorChoice.GREEN_UP.scheme.up(dark = true),
            c.scheme.up(dark = true, inverted = c.inverted)
        )
    }

    @Test
    fun blueAndOrange_useColorBlindScheme() {
        assertEquals(PriceColorScheme.BLUE_ORANGE, PriceColorChoice.BLUE_UP.scheme)
        assertEquals(PriceColorScheme.BLUE_ORANGE, PriceColorChoice.ORANGE_UP.scheme)
        assertEquals(true, PriceColorChoice.ORANGE_UP.inverted)
    }
}
