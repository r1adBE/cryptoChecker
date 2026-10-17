package com.cryptochecker.app.domain.gas

import com.cryptochecker.app.domain.alarm.DepegAlarmChecker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gas- und Depeg-Prüfung: Abstand zur letzten Prüfung, auch nach zurückgestellter Uhr. */
class CheckIntervalDueTest {
    private val now = 1_800_000_000_000L
    private val minute = 60_000L
    private val months = 90L * 24 * 60 * minute

    @Test
    fun `gas check is due after ten minutes`() {
        assertTrue(GasAlertChecker.isDue(now, 0L))
        assertFalse(GasAlertChecker.isDue(now, now))
        assertFalse(GasAlertChecker.isDue(now, now - 9 * minute))
        assertTrue(GasAlertChecker.isDue(now, now - 10 * minute))
    }

    @Test
    fun `gas check stamped in the future counts as due`() {
        // Uhr war vorgestellt (letzte Prüfung «in der Zukunft») und wurde korrigiert: nicht monatelang stumm
        assertTrue(GasAlertChecker.isDue(now, now + months))
        assertTrue(GasAlertChecker.isDue(now, now + 1))
    }

    @Test
    fun `failed gas fetch retries after two minutes`() {
        // Fehlschlag merkt «jetzt − 10 min + 2 min»: in 2 Minuten wieder fällig, vorher nicht
        val stamp = now - 10 * minute + 2 * minute
        assertFalse(GasAlertChecker.isDue(now + minute, stamp))
        assertTrue(GasAlertChecker.isDue(now + 2 * minute, stamp))
    }

    @Test
    fun `depeg check is due after five minutes and after clock correction`() {
        assertFalse(DepegAlarmChecker.isDue(now, now - 4 * minute))
        assertTrue(DepegAlarmChecker.isDue(now, now - 5 * minute))
        assertTrue(DepegAlarmChecker.isDue(now, now + months))
    }
}
