package com.cryptochecker.app.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetRefreshScopeTest {

    private val groups = setOf(null, "Majors", "Alts")

    @Test
    fun listWithoutGroupShowsEveryPair() {
        assertTrue(WidgetRefreshScope.listShowsWatch(null, null, groups))
        assertTrue(WidgetRefreshScope.listShowsWatch(null, "Alts", groups))
    }

    @Test
    fun favoritesListAlwaysRedraws() {
        // «FAV» filtert nach dem Favoriten-Kennzeichen, nicht nach der Gruppe
        assertTrue(WidgetRefreshScope.listShowsWatch(com.cryptochecker.app.domain.watch.WatchFilter.FAVORITES, "Alts", groups))
        assertTrue(WidgetRefreshScope.listShowsWatch(com.cryptochecker.app.domain.watch.WatchFilter.FAVORITES, null, groups))
    }

    @Test
    fun listWithGroupShowsOnlyItsPairs() {
        assertTrue(WidgetRefreshScope.listShowsWatch("Majors", "Majors", groups))
        assertFalse(WidgetRefreshScope.listShowsWatch("Majors", "Alts", groups))
        assertFalse(WidgetRefreshScope.listShowsWatch("Majors", null, groups))
    }

    @Test
    fun listWithVanishedGroupFallsBackToAll() {
        // Wie PriceWidgetService: keine Paare in der Gruppe → alle
        assertTrue(WidgetRefreshScope.listShowsWatch("Old", "Alts", groups))
        assertTrue(WidgetRefreshScope.listShowsWatch("Old", null, groups))
        // Gruppennamen unterscheiden Gross-/Kleinschreibung wie der Filter
        assertTrue(WidgetRefreshScope.listShowsWatch("majors", "Alts", groups))
    }

    @Test
    fun portfolioRedrawsOnlyWhenSnapshotChanged() {
        val state = PortfolioDrawState(100.0, 5L)
        assertTrue(WidgetRefreshScope.portfolioChanged(null, state))
        assertFalse(WidgetRefreshScope.portfolioChanged(PortfolioDrawState(100.0, 5L), state))
        assertTrue(WidgetRefreshScope.portfolioChanged(PortfolioDrawState(100.0, 4L), state))
        assertTrue(WidgetRefreshScope.portfolioChanged(PortfolioDrawState(101.0, 5L), state))
        assertTrue(WidgetRefreshScope.portfolioChanged(PortfolioDrawState(null, null), state))
        assertFalse(WidgetRefreshScope.portfolioChanged(PortfolioDrawState(null, null), PortfolioDrawState(null, null)))
    }

    @Test
    fun latestPerWidgetReusesOnlyForSameKey() {
        val cache = LatestPerWidget<List<Int>, Any>()
        val first = Any()
        assertNull(cache.get(1, listOf(1, 2)))
        cache.put(1, listOf(1, 2), first)
        assertSame(first, cache.get(1, listOf(1, 2)))
        assertNull(cache.get(1, listOf(1, 3)))
        assertNull(cache.get(2, listOf(1, 2)))

        // Nur das letzte Bild je Widget
        val second = Any()
        cache.put(1, listOf(1, 3), second)
        assertNull(cache.get(1, listOf(1, 2)))
        assertSame(second, cache.get(1, listOf(1, 3)))
        assertEquals(1, cache.size)

        cache.put(2, listOf(9), first)
        cache.remove(1)
        assertNull(cache.get(1, listOf(1, 3)))
        assertEquals(1, cache.size)
    }
}
