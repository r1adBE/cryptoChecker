package com.cryptochecker.app.widget

import com.cryptochecker.app.domain.widget.ListWidgetDataVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stand der Listen-Widget-Zeilen ([ListWidgetDataVersion]): Zeilen gehören immer zum letzten Zeichnen. */
class ListWidgetDataVersionTest {

    @Test
    fun `every draw gives a new version per widget`() {
        val v = ListWidgetDataVersion()
        assertEquals(0L, v.current(7))
        assertEquals(1L, v.next(7))
        assertEquals(2L, v.next(7))
        assertEquals(1L, v.next(8))
        assertEquals(2L, v.current(7))
    }

    @Test
    fun `factory with rows from the previous draw counts as stale`() {
        val v = ListWidgetDataVersion()
        // Nie geladen: veraltet, auch vor dem ersten Zeichnen
        assertTrue(v.isStale(7, null))
        val drawn = v.next(7)
        val loaded = v.current(7)
        assertFalse(v.isStale(7, loaded))
        // Neue Uhrzeit im Kopf (neues Zeichnen) ohne Meldung an die Fabrik: deren Zeilen sind alt
        v.next(7)
        assertTrue(v.isStale(7, drawn))
        assertFalse(v.isStale(7, v.current(7)))
    }

    @Test
    fun `version of one widget leaves other widgets untouched`() {
        val v = ListWidgetDataVersion()
        val a = v.next(1)
        v.next(2)
        assertFalse(v.isStale(1, a))
        v.remove(1)
        assertTrue(v.isStale(1, a))
    }

    @Test
    fun `token changes with every draw and wraps around`() {
        val tokens = (1L..12L).map { ListWidgetDataVersion.token(it) }
        // Zwei aufeinanderfolgende Zeichnen haben nie denselben Token (neue Adapter-Intent)
        tokens.zipWithNext().forEach { (a, b) -> assertNotEquals(a, b) }
        assertEquals(ListWidgetDataVersion.TOKEN_CYCLE, tokens.toSet().size)
        assertTrue(tokens.all { it in 0 until ListWidgetDataVersion.TOKEN_CYCLE })
    }
}
