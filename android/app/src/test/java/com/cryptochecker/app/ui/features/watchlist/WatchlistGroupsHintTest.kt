package com.cryptochecker.app.ui.features.watchlist

import com.cryptochecker.app.domain.watch.QuickView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gruppen-Hinweis immer, wenn keine Chips da sind — gleiche Fälle wie `WatchlistGroupsHintTests.swift`. */
class WatchlistGroupsHintTest {

    private fun hint(
        groups: List<String> = emptyList(),
        favorites: Boolean = false,
        searching: Boolean = false,
        sortMode: Boolean = false,
        quickView: QuickView? = null,
    ) = showsGroupsHint(groups, null, favorites, false, quickView, searching, sortMode)

    @Test
    fun hintWithoutGroupsFromFirstStart() {
        // Ohne Gruppen und Favoriten immer der Hinweis — auch mit 0–4 Paaren und ohne Alarm
        // (die Regel kennt weder Paarzahl noch ersten Gebrauch)
        assertTrue(hint())
    }

    @Test
    fun chipsInsteadOfHintWithGroupOrFavorite() {
        assertTrue(showsGroupChips(listOf("DeFi"), null, false))
        assertTrue(showsGroupChips(emptyList(), null, true))
        assertFalse(hint(groups = listOf("DeFi")))
        assertFalse(hint(favorites = true))
    }

    @Test
    fun noHintWhileSearchingSortingOrInQuickView() {
        assertFalse(hint(searching = true))
        assertFalse(hint(sortMode = true))
        assertFalse(hint(quickView = QuickView.STALE))
    }
}
