package com.cryptochecker.app.domain.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Portfolio-Alarme: Wert über/unter Betrag, Veränderung ±x % (Spiegel: PortfolioAlarmLogicTests.swift). */
class PortfolioAlarmLogicTest {

    private val start = 1_700_000_000_000L

    private fun alarm(kind: PortfolioAlarmKind, threshold: Double, currency: String? = "CHF") =
        PortfolioAlarmInput(kind, threshold, currency, enabled = true, referenceAt = 0, lastTriggeredAt = 0)

    private fun reading(total: Double, change: Double? = null, currency: String = "CHF", usdt: Double? = total * 1.1) =
        PortfolioReading(total = total, currency = currency, totalUsdt = usdt, changePercent = change, empty = false)

    /**
     * Spielt eine Folge durch wie PortfolioAlarmChecker: Rearm → referenceAt 0, Fire → referenceAt = Zeit
     * (einmalige schalten ab). @return Zahl der Meldungen
     */
    private fun run(start: PortfolioAlarmInput, readings: List<PortfolioReading>, repeating: Boolean = true, cooldown: Int = 0): Int {
        var a = start
        var fired = 0
        readings.forEachIndexed { i, r ->
            val now = this.start + i * 60_000L
            when (val d = PortfolioAlarmLogic.decide(a, r, now, cooldown)) {
                PortfolioAlarmDecision.None -> Unit
                PortfolioAlarmDecision.Rearm -> a = a.copy(referenceAt = 0)
                is PortfolioAlarmDecision.Fire -> {
                    fired++
                    a = a.copy(referenceAt = now, lastTriggeredAt = now, enabled = PortfolioAlarmLogic.enabledAfterFire(repeating))
                }
            }
        }
        return fired
    }

    @Test
    fun valueAbove_firesOnceWhileAbove_andRearmsWithHysteresis() {
        val a = alarm(PortfolioAlarmKind.VALUE_ABOVE, 50_000.0)
        // Über die Marke, bleibt drüber, pendelt knapp darunter (innerhalb 0.2 %), dann klar darunter und wieder drüber
        val values = listOf(49_000.0, 50_100.0, 50_500.0, 49_950.0, 50_200.0, 49_000.0, 50_300.0)
        assertEquals(2, run(a, values.map { reading(it) }))
    }

    @Test
    fun valueBelow_andOnceDisables() {
        val a = alarm(PortfolioAlarmKind.VALUE_BELOW, 10_000.0)
        val values = listOf(12_000.0, 9_900.0, 11_000.0, 9_000.0)
        assertEquals(2, run(a, values.map { reading(it) }))
        assertEquals(1, run(a, values.map { reading(it) }, repeating = false))
    }

    @Test
    fun valueAlarm_usesMatchingCurrencyOrUsdt() {
        val r = reading(45_000.0, currency = "CHF", usdt = 52_000.0)
        assertEquals(45_000.0, PortfolioAlarmLogic.totalIn("chf", r)!!, 1e-9)
        assertEquals(52_000.0, PortfolioAlarmLogic.totalIn("USD", r)!!, 1e-9)
        assertEquals(52_000.0, PortfolioAlarmLogic.totalIn("USDT", r)!!, 1e-9)
        // Andere Währung ohne Devisenkurs: diesmal nicht prüfen
        assertNull(PortfolioAlarmLogic.totalIn("EUR", r))
        val eur = alarm(PortfolioAlarmKind.VALUE_ABOVE, 1.0, currency = "EUR")
        assertEquals(PortfolioAlarmDecision.None, PortfolioAlarmLogic.decide(eur, r, start, 0))
    }

    @Test
    fun changeUpAndDown() {
        val up = alarm(PortfolioAlarmKind.CHANGE_UP, 5.0, currency = null)
        val down = alarm(PortfolioAlarmKind.CHANGE_DOWN, 5.0, currency = null)
        val changes = listOf(1.0, 5.2, 6.0, -2.0, -5.5, -7.0, 0.0, 5.0)
        assertEquals(2, run(up, changes.map { reading(1000.0, it) }))
        assertEquals(1, run(down, changes.map { reading(1000.0, it) }))
        // Ohne Vergleichsbasis nie
        assertEquals(0, run(up, listOf(reading(1000.0, null))))
    }

    @Test
    fun cooldown_blocksFiring() {
        val a = alarm(PortfolioAlarmKind.VALUE_ABOVE, 100.0)
        val values = listOf(101.0, 90.0, 101.0)
        assertEquals(2, run(a, values.map { reading(it) }))
        assertEquals(1, run(a, values.map { reading(it) }, cooldown = 5))
    }

    @Test
    fun emptyDisabledOrInvalid_neverFire() {
        val a = alarm(PortfolioAlarmKind.VALUE_BELOW, 100.0)
        assertEquals(PortfolioAlarmDecision.None, PortfolioAlarmLogic.decide(a, reading(50.0).copy(empty = true), start, 0))
        assertEquals(PortfolioAlarmDecision.None, PortfolioAlarmLogic.decide(a.copy(enabled = false), reading(50.0), start, 0))
        assertEquals(PortfolioAlarmDecision.None, PortfolioAlarmLogic.decide(a, reading(0.0), start, 0))
        assertEquals(PortfolioAlarmDecision.None, PortfolioAlarmLogic.decide(a.copy(threshold = 0.0), reading(50.0), start, 0))
        assertTrue(PortfolioAlarmLogic.decide(a, reading(50.0), start, 0) is PortfolioAlarmDecision.Fire)
    }

    @Test
    fun validThresholdAndNames() {
        assertTrue(PortfolioAlarmLogic.isValidThreshold(PortfolioAlarmKind.VALUE_ABOVE, 1_000_000.0))
        assertFalse(PortfolioAlarmLogic.isValidThreshold(PortfolioAlarmKind.CHANGE_UP, 1_500.0))
        assertFalse(PortfolioAlarmLogic.isValidThreshold(PortfolioAlarmKind.CHANGE_DOWN, 0.0))
        assertFalse(PortfolioAlarmLogic.isValidThreshold(PortfolioAlarmKind.VALUE_BELOW, null))
        assertEquals(PortfolioAlarmKind.CHANGE_DOWN, PortfolioAlarmKind.fromName("CHANGE_DOWN"))
        assertNull(PortfolioAlarmKind.fromName("SOMETHING_NEW"))
    }
}
