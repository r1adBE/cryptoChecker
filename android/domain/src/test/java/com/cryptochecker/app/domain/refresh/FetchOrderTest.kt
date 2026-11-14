package com.cryptochecker.app.domain.refresh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Einzelabfragen: Paare mit aktivem Alarm zuerst, sonst stabile Reihenfolge. Wie iOS `FetchOrderTests`. */
class FetchOrderTest {

    @Test
    fun alarmedPairsComeFirstInStableOrder() {
        val ids = listOf(1L, 2L, 3L, 4L, 5L)
        assertEquals(listOf(2L, 5L, 1L, 3L, 4L), FetchOrder.alarmsFirst(ids, setOf(5L, 2L)) { it })
    }

    @Test
    fun withoutAlarmsOrAllAlarmedNothingChanges() {
        val ids = listOf(3L, 1L, 2L)
        assertSame(ids, FetchOrder.alarmsFirst(ids, emptySet()) { it })
        assertSame(ids, FetchOrder.alarmsFirst(ids, setOf(1L, 2L, 3L)) { it })
        assertSame(ids, FetchOrder.alarmsFirst(ids, setOf(9L)) { it })
    }

    /** Mit Zeitbudget je Lauf anderer Beginn — Alarm-Paare bleiben vorn (B3-03). */
    @Test
    fun rotationShiftsBothPartsAndKeepsAlarmsFirst() {
        val ids = listOf(1L, 2L, 3L, 4L, 5L)
        assertEquals(listOf(5L, 2L, 3L, 4L, 1L), FetchOrder.alarmsFirst(ids, setOf(5L, 2L), rotation = 1) { it })
        assertEquals(listOf(3L, 1L, 2L), FetchOrder.alarmsFirst(listOf(1L, 2L, 3L), emptySet(), rotation = -1) { it })
        assertEquals(ids, FetchOrder.alarmsFirst(ids, emptySet(), rotation = 5) { it })
    }
}
