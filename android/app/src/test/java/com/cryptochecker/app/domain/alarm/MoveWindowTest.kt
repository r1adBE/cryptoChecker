package com.cryptochecker.app.domain.alarm

import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.data.local.model.AlarmEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gleitendes Fenster des Bewegungs-Alarms (Spiegel: AlarmLogicTests.swift, testMoveWindow…). */
class MoveWindowTest {

    private val start = 1_700_000_000_000L
    private val minute = 60_000L
    private val evaluator = AlarmEvaluator()

    @Test
    fun appendKeepsSpacingAndRetention() {
        var history = emptyList<MoveWindow.PricePoint>()
        history = MoveWindow.append(history, MoveWindow.PricePoint(100.0, start), start)
        // Zu kurz nach dem letzten Punkt: nicht angehängt
        history = MoveWindow.append(history, MoveWindow.PricePoint(101.0, start + minute), start + minute)
        assertEquals(1, history.size)
        history = MoveWindow.append(history, MoveWindow.PricePoint(101.0, start + 2 * minute), start + 2 * minute)
        assertEquals(2, history.size)
        // Ungültig: nicht angehängt
        history = MoveWindow.append(history, MoveWindow.PricePoint(Double.NaN, start + 10 * minute), start + 10 * minute)
        assertEquals(2, history.size)
        // Nach der Aufbewahrung ist alles weg
        val later = start + MoveWindow.RETENTION_MILLIS + 3 * minute
        assertTrue(MoveWindow.prune(history, later).isEmpty())
    }

    /** Kurse jede Minute über 30 Stunden: Verlauf bleibt klein und deckt alle Fenster (1, 4, 12, 24 h) ab. */
    @Test
    fun historyOverADayStaysSmall() {
        var history = emptyList<MoveWindow.PricePoint>()
        var now = start
        var price = 1_000.0
        repeat(30 * 60) {
            history = MoveWindow.append(history, MoveWindow.PricePoint(price, now), now)
            price += 0.1
            now += minute
        }
        val last = now - minute
        assertTrue("kept ${history.size}", history.size < 200)
        assertTrue(history.zipWithNext().all { (a, b) -> b.time > a.time })
        for (hours in listOf(1, 4, 12, 24)) {
            val change = MoveWindow.changePercent(history, null, price, hours, since = 0L, now = last)
            assertNotNull("window $hours", change)
            // Steigender Kurs: die grösste Bewegung ist die gegenüber dem ältesten Punkt im Fenster
            assertTrue("window $hours: $change", change!! > 0.0)
        }
        val one = MoveWindow.changePercent(history, null, price, 1, since = 0L, now = last)!!
        val day = MoveWindow.changePercent(history, null, price, 24, since = 0L, now = last)!!
        assertTrue(day > one)
    }

    /** Ablauf wie im Hintergrund (stündlich, mit Schwankung): meldet, wo das feste Fenster nie meldete. */
    @Test
    fun hourlyBackgroundRunsReportTheMove() {
        var a = AlarmEntity(id = 1, watchId = 1, condition = AlarmCondition.MOVE_PERCENT_WINDOW, threshold = 5.0, windowHours = 1)
        var history = emptyList<MoveWindow.PricePoint>()
        var fired = 0
        val times = listOf(0L, 55L, 118L, 175L).map { start + it * minute }
        val prices = listOf(100.0, 101.0, 104.0, 109.5)
        for ((t, p) in times.zip(prices)) {
            if (evaluator.needsReference(a, t)) {
                a = a.copy(referencePrice = p, referenceAt = t)
            } else if (evaluator.shouldTrigger(a, p, null, t, 0, moveHistory = history)) {
                fired++
                a = AlarmEvaluator.triggered(a, p, t)
            }
            history = MoveWindow.append(history, MoveWindow.PricePoint(p, t), t)
        }
        // 109,5 gegenüber 104 vor 57 Min.: 5,3 %
        assertEquals(1, fired)
        assertFalse(a.enabled)
    }

    @Test
    fun noComparisonWithoutPoints() {
        assertNull(MoveWindow.changePercent(emptyList(), null, 100.0, 1, 0L, start))
        assertEquals(5_400_000L, MoveWindow.maxAgeMillis(1))
        assertEquals(4 * 3_600_000L + 3_600_000L, MoveWindow.maxAgeMillis(4))
        assertEquals(30 * 3_600_000L, MoveWindow.maxAgeMillis(24))
    }
}
