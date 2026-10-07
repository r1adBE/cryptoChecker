package com.cryptochecker.app.domain.alarm

import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.data.local.model.AlarmEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kursmarken (PRICE_ABOVE/PRICE_BELOW): melden beim Überschreiten, nicht bei jeder Aktualisierung. */
class LevelAlarmTest {

    private val evaluator = AlarmEvaluator()
    private val start = 1_700_000_000_000L

    /**
     * Spielt eine Kursfolge durch wie PriceRefresher + WatchRepository: Wiederscharfstellung
     * setzt referenceAt = 0, Auslösen setzt referenceAt = Zeitpunkt (einmalige: abschalten).
     * @return Anzahl Meldungen
     */
    private fun run(alarm: AlarmEntity, prices: List<Double>, cooldownMinutes: Int = 0, stepMillis: Long = 15_000L): Int {
        var a = alarm
        var fired = 0
        prices.forEachIndexed { i, price ->
            val now = start + i * stepMillis
            if (!a.enabled) return@forEachIndexed
            if (evaluator.shouldRearmLevel(a, price)) {
                a = a.copy(referenceAt = 0)
                return@forEachIndexed
            }
            if (evaluator.shouldTrigger(a, price, previousPrice = null, now = now, cooldownMinutes = cooldownMinutes)) {
                fired++
                a = a.copy(lastTriggeredAt = now, lastTriggeredPrice = price, referenceAt = now, enabled = a.repeating)
            }
        }
        return fired
    }

    private fun alarm(condition: AlarmCondition, threshold: Double, repeating: Boolean = true) =
        AlarmEntity(id = 1, watchId = 1, condition = condition, threshold = threshold, repeating = repeating)

    @Test
    fun `repeating above fires exactly twice over the reference sequence`() {
        // below → above → above → above → below by the hysteresis → above
        val prices = listOf(99_000.0, 100_500.0, 101_000.0, 100_200.0, 99_700.0, 100_100.0)
        assertEquals(2, run(alarm(AlarmCondition.PRICE_ABOVE, 100_000.0), prices))
    }

    @Test
    fun `repeating below fires exactly twice over the mirrored sequence`() {
        val prices = listOf(101_000.0, 99_500.0, 99_000.0, 99_800.0, 100_300.0, 99_900.0)
        assertEquals(2, run(alarm(AlarmCondition.PRICE_BELOW, 100_000.0), prices))
    }

    @Test
    fun `dip inside the hysteresis does not re-arm`() {
        // 99'850 liegt nur 0,15 % unter der Marke (Hysterese 0,2 %)
        val prices = listOf(99_000.0, 100_500.0, 99_850.0, 100_500.0, 99_850.0, 100_500.0)
        assertEquals(1, run(alarm(AlarmCondition.PRICE_ABOVE, 100_000.0), prices))
    }

    @Test
    fun `already beyond on the first evaluation fires once`() {
        val prices = List(10) { 105_000.0 }
        assertEquals(1, run(alarm(AlarmCondition.PRICE_ABOVE, 100_000.0), prices))
    }

    @Test
    fun `one-shot alarm fires once and switches off`() {
        val prices = listOf(99_000.0, 100_500.0, 99_000.0, 100_500.0)
        assertEquals(1, run(alarm(AlarmCondition.PRICE_ABOVE, 100_000.0, repeating = false), prices))
    }

    @Test
    fun `cooldown still applies on top of re-arming`() {
        // 15-s-Schritte, 30 min Pause: die zweite Überschreitung fällt in die Pause
        val prices = listOf(99_000.0, 100_500.0, 99_000.0, 100_500.0)
        assertEquals(1, run(alarm(AlarmCondition.PRICE_ABOVE, 100_000.0), prices, cooldownMinutes = 30))
        // nach Ablauf der Pause meldet der (scharfe) Alarm, sofern der Kurs noch jenseits liegt
        assertEquals(2, run(alarm(AlarmCondition.PRICE_ABOVE, 100_000.0), prices, cooldownMinutes = 30, stepMillis = 20 * 60_000L))
    }

    @Test
    fun `fired alarm is disarmed and re-arms only beyond the hysteresis`() {
        val fired = alarm(AlarmCondition.PRICE_ABOVE, 100.0).copy(referenceAt = start)
        assertFalse(evaluator.shouldTrigger(fired, 150.0, null, start, 0))
        assertFalse(evaluator.shouldRearmLevel(fired, 99.85))
        assertTrue(evaluator.shouldRearmLevel(fired, 99.7))
        val firedBelow = alarm(AlarmCondition.PRICE_BELOW, 100.0).copy(referenceAt = start)
        assertFalse(evaluator.shouldRearmLevel(firedBelow, 100.15))
        assertTrue(evaluator.shouldRearmLevel(firedBelow, 100.3))
    }

    @Test
    fun `armed or non-level alarms never re-arm`() {
        assertFalse(evaluator.shouldRearmLevel(alarm(AlarmCondition.PRICE_ABOVE, 100.0), 50.0))
        val percent = alarm(AlarmCondition.CHANGE_PERCENT_UP, 5.0).copy(referenceAt = start)
        assertFalse(evaluator.shouldRearmLevel(percent, 1.0))
    }
}
