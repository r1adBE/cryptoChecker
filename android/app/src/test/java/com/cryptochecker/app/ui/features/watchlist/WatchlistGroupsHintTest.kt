package com.cryptochecker.app.ui.features.watchlist

import com.cryptochecker.app.domain.watch.QuickView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** G-14: Gruppen-Hinweis erst ab einigen Paaren — wie `WatchlistGroupsHintTests.swift`. */
class WatchlistGroupsHintTest {

    private fun hint(
        pairs: Int,
        groups: List<String> = emptyList(),
        favorites: Boolean = false,
        searching: Boolean = false,
        quickView: QuickView? = null,
        onboarded: Boolean = true,
    ) = showsGroupsHint(groups, null, favorites, false, quickView, searching, false, pairCount = pairs, onboarded = onboarded)

    @Test
    fun hintOnlyFromFivePairs() {
        assertFalse(hint(pairs = 0))
        assertFalse(hint(pairs = GROUPS_HINT_MIN_PAIRS - 1))
        assertTrue(hint(pairs = GROUPS_HINT_MIN_PAIRS))
    }

    @Test
    fun chipsInsteadOfHintWithGroupOrFavorite() {
        // Gruppe oder Favorit: Chips statt Hinweis, auch mit wenigen Paaren
        assertTrue(showsGroupChips(listOf("DeFi"), null, false))
        assertTrue(showsGroupChips(emptyList(), null, true))
        assertFalse(hint(pairs = 10, groups = listOf("DeFi")))
        assertFalse(hint(pairs = 10, favorites = true))
    }

    @Test
    fun noHintWhileSearchingOrInQuickView() {
        assertFalse(hint(pairs = 10, searching = true))
        assertFalse(hint(pairs = 10, quickView = QuickView.STALE))
    }

    @Test
    fun noHintBeforeFirstUseIsOver() {
        // Erster Bildschirm nur für Kurse und den ersten Alarm (WatchlistOnboarding)
        assertFalse(hint(pairs = 10, onboarded = false))
        assertTrue(hint(pairs = 10, onboarded = true))
    }
}
