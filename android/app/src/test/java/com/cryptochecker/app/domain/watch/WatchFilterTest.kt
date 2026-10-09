package com.cryptochecker.app.domain.watch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Alle», «FAV» und Gruppen (iOS: gleiche Regeln in `WatchFilter.swift`). */
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
}
