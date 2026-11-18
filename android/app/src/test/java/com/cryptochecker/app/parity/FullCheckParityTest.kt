package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.refresh.BackgroundSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wann ein Lauf als «Prüfung mit allen Alarmen» zählt (`testdata/parity/full_check.json`): [BackgroundSchedule.isFullCheck]
 * und [BackgroundSchedule.marksAlarmCheck] (Zeitpunkt von `lastFullCheck`, iOS `lastAlarmCheckAt`). Gegenstück:
 * `Tests/ExactCadenceParityTests.swift`.
 */
class FullCheckParityTest {

    private val data = ParityJson.load("full_check.json")

    @Test
    fun fullCheckAsSharedCases() {
        val cases = data["isFullCheck"].list().map { it.obj() }
        assertTrue(cases.size >= 15)
        for (c in cases) {
            val actual = BackgroundSchedule.isFullCheck(
                c["attempted"].num()!!.toInt(),
                c["failed"].num()!!.toInt(),
                c["notTraded"].num()?.toInt() ?: 0,
                c["cutByBudget"].num()?.toInt() ?: 0,
            )
            assertEquals(c.toString(), c["expected"], actual)
        }
    }

    /** Uhrzeit der Aktualisierung (Widget-Kopf): ein Kurs kam oder alle Paare sind nicht gehandelt bzw. stillgelegt. */
    @Test
    fun refreshesAsSharedCases() {
        val cases = data["refreshed"].list().map { it.obj() }
        assertTrue(cases.size >= 5)
        for (c in cases) {
            val actual = BackgroundSchedule.refreshed(
                c["attempted"].num()!!.toInt(),
                c["failed"].num()!!.toInt(),
                c["notTraded"].num()!!.toInt(),
            )
            assertEquals(c.toString(), c["expected"], actual)
        }
    }

    @Test
    fun timeAsSharedCases() {
        val cases = data["marksAlarmCheck"].list().map { it.obj() }
        assertEquals(8, cases.size)
        for (c in cases) {
            val actual = BackgroundSchedule.marksAlarmCheck(
                fullCheck = c["fullCheck"] as Boolean,
                deferredWaiting = c["deferredWaiting"] as Boolean,
                deferredChecked = c["deferredChecked"] as Boolean,
            )
            assertEquals(c.toString(), c["expected"], actual)
        }
        // Android wertet alle Alarmarten in einem Schritt aus: ohne wartende Alarme zählt jede volle Prüfung
        assertTrue(BackgroundSchedule.marksAlarmCheck(fullCheck = true))
        assertEquals(false, BackgroundSchedule.marksAlarmCheck(fullCheck = false))
    }
}
