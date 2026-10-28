package com.cryptochecker.app.widget

import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.watch.WatchFilter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Listen-Widgets lesen die Paare einmal je Zeichnen ([WidgetOnceRead]) und wählen nach der Aktualisierung eines Paares
 * aus derselben Abfrage ([WidgetRefreshScope.listsShowing]) — mit demselben Ergebnis wie bisher (je Widget gelesen,
 * Auswahl wie in `WidgetUpdater.updateForWatch` vorher).
 */
class WidgetOnceReadTest {

    @Test
    fun readsOnceAndReturnsTheSameList() = runBlocking {
        var reads = 0
        val list = listOf(1, 2, 3)
        val once = WidgetOnceRead {
            reads++
            list
        }
        assertNull(once.peek())
        // Drei Widgets je Leer-Hinweis, Titel und Zeilen: eine Abfrage
        repeat(9) { assertSame(list, once.get()) }
        assertEquals(1, reads)
        assertSame(list, once.peek())
    }

    @Test
    fun failureIsThrownOnEveryAccessLikeEachReadBefore() = runBlocking {
        var reads = 0
        val once = WidgetOnceRead<List<Int>> {
            reads++
            throw IllegalStateException("Datenbank gesperrt")
        }
        repeat(3) {
            try {
                once.get()
                fail("erwartet: Ausnahme")
            } catch (e: IllegalStateException) {
                assertEquals("Datenbank gesperrt", e.message)
            }
        }
        assertEquals(1, reads)
        assertNull(once.peek())
    }

    private fun watch(id: Long, group: String?) = WatchEntity(
        id = id, exchangeKey = "Binance", exchangeName = "Binance", baseAsset = "B$id", quoteAsset = "USDT", groupName = group,
    )

    /** Auswahl wie bisher in `WidgetUpdater.updateForWatch` (Referenz). */
    private fun before(
        listIds: IntArray,
        watchId: Long,
        watches: List<WatchEntity>,
        groupOf: (Int) -> String?,
    ): IntArray {
        val watch = watches.firstOrNull { it.id == watchId }
        val groups = watches.mapTo(HashSet()) { it.groupName }
        return listIds.filter { id ->
            watch == null || WidgetRefreshScope.listShowsWatch(groupOf(id), watch.groupName, groups)
        }.toIntArray()
    }

    private fun select(listIds: IntArray, watchId: Long, watches: List<WatchEntity>, groupOf: (Int) -> String?) =
        WidgetRefreshScope.listsShowing(listIds, watchId, watches, { it.id }, { it.groupName }, groupOf)

    @Test
    fun selectionMatchesThePreviousWay() {
        val watches = listOf(watch(1, null), watch(2, "Majors"), watch(3, "Alts"), watch(4, "Majors"))
        val groupsByWidget = mapOf(
            10 to null, 11 to "Majors", 12 to "Alts", 13 to "Gone",
            14 to WatchFilter.FAVORITES, 15 to WatchFilter.TOKENS,
        )
        val listIds = groupsByWidget.keys.toIntArray()
        var checked = 0
        for (watchId in listOf(1L, 2L, 3L, 4L, 99L)) {
            val expected = before(listIds, watchId, watches, groupsByWidget::get)
            val got = select(listIds, watchId, watches, groupsByWidget::get)
            assertArrayEquals("Paar $watchId", expected, got)
            checked += got.size
        }
        // Gelöschtes Paar: alle Listen
        assertArrayEquals(listIds, select(listIds, 99L, watches, groupsByWidget::get))
        assertTrue(checked > listIds.size)
    }
}
