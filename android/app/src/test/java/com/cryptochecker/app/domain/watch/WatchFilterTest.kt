package com.cryptochecker.app.domain.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Alle», «FAV», «Token» und Gruppen (iOS: gleiche Regeln in `WatchFilter.swift`). */
class WatchFilterTest {
    @Test
    fun allFavoritesAndGroups() {
        assertTrue(WatchFilter.matches(null, "Alts", favorite = false))
        assertTrue(WatchFilter.matches(WatchFilter.FAVORITES, "Alts", favorite = true))
        assertTrue(WatchFilter.matches(WatchFilter.FAVORITES, null, favorite = true))
        assertFalse(WatchFilter.matches(WatchFilter.FAVORITES, "Alts", favorite = false))
        assertTrue(WatchFilter.matches("Alts", "Alts", favorite = false))
        assertFalse(WatchFilter.matches("Alts", "Majors", favorite = true))
        // Eine Gruppe, die «FAV» heisst, ist nicht der Favoriten-Filter
        assertFalse(WatchFilter.isFavorites("FAV"))
        assertTrue(WatchFilter.matches("FAV", "FAV", favorite = false))
    }

    @Test
    fun tokensAreEverythingButTradFi() {
        assertEquals("\u0001TOK", WatchFilter.TOKENS)
        assertTrue(WatchFilter.matches(WatchFilter.TOKENS, null, favorite = false))
        assertTrue(WatchFilter.matches(WatchFilter.TOKENS, AutoGroup.DATED, favorite = false))
        assertTrue(WatchFilter.matches(WatchFilter.TOKENS, "Alts", favorite = true))
        assertFalse(WatchFilter.matches(WatchFilter.TOKENS, AutoGroup.TRADFI, favorite = false))
        assertFalse(WatchFilter.matches(WatchFilter.TOKENS, AutoGroup.TRADFI, favorite = true))
        assertTrue(WatchFilter.isTokens(WatchFilter.TOKENS))
        assertTrue(WatchFilter.isBuiltIn(WatchFilter.TOKENS))
        assertTrue(WatchFilter.isBuiltIn(WatchFilter.FAVORITES))
        assertFalse(WatchFilter.isBuiltIn(null))
        assertFalse(WatchFilter.isBuiltIn(AutoGroup.TRADFI))
        // Eine Gruppe, die «TOK» oder «Token» heisst, ist nicht der Token-Filter
        assertFalse(WatchFilter.isTokens("TOK"))
        assertTrue(WatchFilter.matches("Token", "Token", favorite = false))
        assertFalse(WatchFilter.matches("Token", null, favorite = false))
    }

    @Test
    fun tokenChipOnlyWithTradFiAndOtherPairs() {
        assertTrue(WatchFilter.showsTokens(listOf(AutoGroup.TRADFI, null)))
        assertTrue(WatchFilter.showsTokens(listOf(AutoGroup.TRADFI, AutoGroup.DATED)))
        assertTrue(WatchFilter.showsTokens(listOf("Alts", AutoGroup.TRADFI)))
        // Nur TradFi: gleich leer; kein TradFi: gleich «Alle»
        assertFalse(WatchFilter.showsTokens(listOf(AutoGroup.TRADFI, AutoGroup.TRADFI)))
        assertFalse(WatchFilter.showsTokens(listOf(null, "Alts", AutoGroup.DATED)))
        assertFalse(WatchFilter.showsTokens(emptyList()))
        // Gross-/Kleinschreibung zählt wie beim Gruppenfilter
        assertFalse(WatchFilter.showsTokens(listOf("tradfi", null)))
    }

    @Test
    fun vanishedSelectionFallsBackToAll() {
        val mixed = listOf(AutoGroup.TRADFI, null, "Alts")
        val onlyCrypto = listOf(null, "Alts")
        assertTrue(WatchFilter.isAvailable(null, onlyCrypto))
        assertTrue(WatchFilter.isAvailable(WatchFilter.FAVORITES, emptyList()))
        assertTrue(WatchFilter.isAvailable(WatchFilter.TOKENS, mixed))
        assertFalse(WatchFilter.isAvailable(WatchFilter.TOKENS, onlyCrypto))
        assertFalse(WatchFilter.isAvailable(WatchFilter.TOKENS, listOf(AutoGroup.TRADFI)))
        assertTrue(WatchFilter.isAvailable("Alts", mixed))
        assertFalse(WatchFilter.isAvailable("Old", mixed))
    }

    @Test
    fun alpha_isBuiltInFilterOnAlphaPairs() {
        assertEquals("\u0001ALP", WatchFilter.ALPHA)
        assertTrue(WatchFilter.isAlpha(WatchFilter.ALPHA))
        assertTrue(WatchFilter.isBuiltIn(WatchFilter.ALPHA))
        assertTrue(WatchFilter.matches(WatchFilter.ALPHA, null, favorite = false, alpha = true))
        assertTrue(WatchFilter.matches(WatchFilter.ALPHA, "Alts", favorite = true, alpha = true))
        assertFalse(WatchFilter.matches(WatchFilter.ALPHA, null, favorite = false, alpha = false))
        // «Token» zeigt Alpha-Tokens weiterhin (ein Alpha-Token ist auch ein Token)
        assertTrue(WatchFilter.matches(WatchFilter.TOKENS, null, favorite = false, alpha = true))
        // Eine Gruppe «ALP» ist kein Filter
        assertFalse(WatchFilter.isAlpha("ALP"))
    }

    @Test
    fun alpha_chipOnlyWithAlphaAndOtherPairs() {
        assertTrue(WatchFilter.showsAlpha(listOf(true, false)))
        assertFalse(WatchFilter.showsAlpha(listOf(true, true)))
        assertFalse(WatchFilter.showsAlpha(listOf(false)))
        assertFalse(WatchFilter.showsAlpha(emptyList()))
        assertTrue(WatchFilter.isAvailable(WatchFilter.ALPHA, listOf(null, null), listOf(true, false)))
        assertFalse(WatchFilter.isAvailable(WatchFilter.ALPHA, listOf(null), listOf(false)))
        // Ohne Angaben (z. B. Widgets) nie verfügbar
        assertFalse(WatchFilter.isAvailable(WatchFilter.ALPHA, listOf(null)))
    }
}
