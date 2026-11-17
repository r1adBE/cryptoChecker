package com.cryptochecker.app.domain.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fällige Paare der Aktivitäts-Auswertung: am längsten nicht geprüfte zuerst, damit das Zeitbudget niemanden aushungert. */
class ActivityDueOrderTest {

    @Test
    fun `never checked first, then oldest check, tie in list order`() {
        val computed = mapOf(1L to 500L, 2L to null, 3L to 100L, 4L to 100L, 5L to null)
        val order = ActivityDueOrder.order(listOf(1L, 2L, 3L, 4L, 5L), { it }, { computed[it] }, emptyMap())
        assertEquals(listOf(2L, 5L, 3L, 4L, 1L), order)
    }

    @Test
    fun `attempt without result counts like a check`() {
        val order = ActivityDueOrder.order(listOf(1L, 2L), { it }, { null }, mapOf(1L to 900L))
        assertEquals(listOf(2L, 1L), order)
    }

    @Test
    fun `two runs with budget abort check all pairs at least once`() {
        // 10 Paare, das Budget reicht je Lauf für 5; nach jedem Lauf sind wieder alle fällig (15 min > 10 min)
        val ids = (1L..10L).toList()
        val computedAt = HashMap<Long, Long>()
        val checked = HashSet<Long>()
        listOf(1_000L, 2_000L).forEach { now ->
            val due = ActivityDueOrder.order(ids, { it }, { computedAt[it] }, emptyMap())
            due.take(5).forEach {
                computedAt[it] = now
                checked += it
            }
        }
        assertEquals(ids.toSet(), checked)
    }

    @Test
    fun `without sorting the rear pairs would stay unchecked`() {
        // Gegenprobe zur alten Listenreihenfolge: dieselben 5 jedes Mal
        val ids = (1L..10L).toList()
        val checked = HashSet<Long>()
        repeat(2) { checked += ids.take(5) }
        assertTrue(checked.size < ids.size)
    }
}
