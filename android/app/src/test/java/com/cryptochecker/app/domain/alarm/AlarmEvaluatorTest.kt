package com.cryptochecker.app.domain.alarm

import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.data.local.model.AlarmEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmEvaluatorTest {

    private val evaluator = AlarmEvaluator()
    private val now = 1_700_000_000_000L

    private fun alarm(
        condition: AlarmCondition,
        threshold: Double,
        enabled: Boolean = true,
        referencePrice: Double? = null,
        lastTriggeredAt: Long = 0,
    ) = AlarmEntity(
        id = 1,
        watchId = 1,
        condition = condition,
        threshold = threshold,
        enabled = enabled,
        referencePrice = referencePrice,
        lastTriggeredAt = lastTriggeredAt,
    )

    @Test
    fun `price above fires when threshold is reached`() {
        val a = alarm(AlarmCondition.PRICE_ABOVE, 100.0)
        assertTrue(evaluator.shouldTrigger(a, price = 100.0, previousPrice = 90.0, now = now, cooldownMinutes = 30))
        assertTrue(evaluator.shouldTrigger(a, price = 101.0, previousPrice = 90.0, now = now, cooldownMinutes = 30))
        assertFalse(evaluator.shouldTrigger(a, price = 99.99, previousPrice = 90.0, now = now, cooldownMinutes = 30))
    }

    @Test
    fun `price below fires when threshold is undercut`() {
        val a = alarm(AlarmCondition.PRICE_BELOW, 100.0)
        assertTrue(evaluator.shouldTrigger(a, price = 100.0, previousPrice = 110.0, now = now, cooldownMinutes = 30))
        assertFalse(evaluator.shouldTrigger(a, price = 100.01, previousPrice = 110.0, now = now, cooldownMinutes = 30))
    }

    @Test
    fun `disabled alarm never fires`() {
        val a = alarm(AlarmCondition.PRICE_ABOVE, 1.0, enabled = false)
        assertFalse(evaluator.shouldTrigger(a, price = 1000.0, previousPrice = 1.0, now = now, cooldownMinutes = 30))
    }

    @Test
    fun `cooldown suppresses a repeated alarm`() {
        val a = alarm(AlarmCondition.PRICE_ABOVE, 100.0, lastTriggeredAt = now - 5 * 60_000L)
        assertFalse(evaluator.shouldTrigger(a, price = 120.0, previousPrice = 90.0, now = now, cooldownMinutes = 30))
        assertTrue(evaluator.shouldTrigger(a, price = 120.0, previousPrice = 90.0, now = now, cooldownMinutes = 0))
    }

    @Test
    fun `percent up uses the reference price`() {
        val a = alarm(AlarmCondition.CHANGE_PERCENT_UP, 5.0, referencePrice = 100.0)
        assertTrue(evaluator.shouldTrigger(a, price = 105.0, previousPrice = null, now = now, cooldownMinutes = 30))
        assertFalse(evaluator.shouldTrigger(a, price = 104.9, previousPrice = null, now = now, cooldownMinutes = 30))
    }

    @Test
    fun `percent down falls back to the previous price`() {
        val a = alarm(AlarmCondition.CHANGE_PERCENT_DOWN, 10.0)
        assertTrue(evaluator.shouldTrigger(a, price = 90.0, previousPrice = 100.0, now = now, cooldownMinutes = 30))
        assertFalse(evaluator.shouldTrigger(a, price = 95.0, previousPrice = 100.0, now = now, cooldownMinutes = 30))
    }

    @Test
    fun `percent alarm without any reference does not fire`() {
        val a = alarm(AlarmCondition.CHANGE_PERCENT_UP, 5.0)
        assertFalse(evaluator.shouldTrigger(a, price = 1000.0, previousPrice = null, now = now, cooldownMinutes = 30))
    }

    @Test
    fun `no price means no alarm`() {
        val a = alarm(AlarmCondition.PRICE_BELOW, 100.0)
        assertFalse(evaluator.shouldTrigger(a, price = 0.0, previousPrice = 100.0, now = now, cooldownMinutes = 30))
    }

    @Test
    fun `move within window fires in both directions`() {
        val a = alarm(AlarmCondition.MOVE_PERCENT_WINDOW, 5.0, referencePrice = 100.0)
            .copy(windowHours = 4, referenceAt = now - 60 * 60_000L)
        assertTrue(evaluator.shouldTrigger(a, price = 105.0, previousPrice = 100.0, now = now, cooldownMinutes = 0))
        assertTrue(evaluator.shouldTrigger(a, price = 95.0, previousPrice = 100.0, now = now, cooldownMinutes = 0))
        assertFalse(evaluator.shouldTrigger(a, price = 103.0, previousPrice = 100.0, now = now, cooldownMinutes = 0))
    }

    @Test
    fun `move window with a too old reference and no history stays quiet`() {
        // Bezug 2 h alt, Fenster 1 h (Spielraum bis 1,5 h): kein Vergleich, aber auch kein Neubeginn
        val a = alarm(AlarmCondition.MOVE_PERCENT_WINDOW, 5.0, referencePrice = 100.0)
            .copy(windowHours = 1, referenceAt = now - 2 * 60 * 60_000L)
        assertFalse(evaluator.needsWindowReset(a, now))
        assertFalse(evaluator.shouldTrigger(a, price = 120.0, previousPrice = 100.0, now = now, cooldownMinutes = 0))
    }

    @Test
    fun `move window checks the old reference after the window ran out`() {
        // Hintergrund im Stundentakt: Bezug 61 Min. alt, Fenster 1 h — früher nur neu angesetzt, nie gemeldet
        val a = alarm(AlarmCondition.MOVE_PERCENT_WINDOW, 5.0, referencePrice = 100.0)
            .copy(windowHours = 1, referenceAt = now - 61 * 60_000L)
        assertFalse(evaluator.needsReference(a, now))
        assertTrue(evaluator.shouldTrigger(a, price = 106.0, previousPrice = 100.0, now = now, cooldownMinutes = 0))
        assertFalse(evaluator.shouldTrigger(a, price = 104.0, previousPrice = 100.0, now = now, cooldownMinutes = 0))
    }

    @Test
    fun `move window slides over the price history`() {
        // Bezug 200 Min. alt (ausserhalb des Fensters), dazu der Kursverlauf des Paars
        val minute = 60_000L
        val a = alarm(AlarmCondition.MOVE_PERCENT_WINDOW, 5.0, referencePrice = 100.0)
            .copy(windowHours = 1, referenceAt = now - 200 * minute)
        val history = listOf(
            MoveWindow.PricePoint(100.0, now - 200 * minute),
            MoveWindow.PricePoint(100.0, now - 50 * minute),
            MoveWindow.PricePoint(101.0, now - 20 * minute),
            MoveWindow.PricePoint(103.0, now - 10 * minute),
        )
        // 105.5 gegenüber 100 vor 50 Min.: über die Grenze eines festen Blocks hinweg gemeldet
        assertTrue(evaluator.shouldTrigger(a, 105.5, null, now, 0, moveHistory = history))
        assertFalse(evaluator.shouldTrigger(a, 104.5, null, now, 0, moveHistory = history))
        // Punkte vor referenceAt (letzte Meldung) zählen nicht
        val reported = a.copy(referencePrice = 103.0, referenceAt = now - 10 * minute)
        assertFalse(evaluator.shouldTrigger(reported, 105.5, null, now, 0, moveHistory = history))
    }

    @Test
    fun `missing or future reference needs a new one`() {
        val move = alarm(AlarmCondition.MOVE_PERCENT_WINDOW, 5.0)
        assertTrue(evaluator.needsReference(move, now))
        assertTrue(evaluator.needsReference(move.copy(referencePrice = 100.0, referenceAt = 0), now))
        assertTrue(evaluator.needsReference(move.copy(referencePrice = 100.0, referenceAt = now + 10 * 60_000L), now))
        assertFalse(evaluator.needsReference(move.copy(referencePrice = 100.0, referenceAt = now - 1), now))
        // Prozentalarm ohne Bezug (angelegt ohne Kurs): erst Bezug setzen
        assertTrue(evaluator.needsReference(alarm(AlarmCondition.CHANGE_PERCENT_UP, 5.0), now))
        assertTrue(evaluator.needsReference(alarm(AlarmCondition.CHANGE_PERCENT_DOWN, 5.0, referencePrice = 0.0), now))
        assertFalse(evaluator.needsReference(alarm(AlarmCondition.CHANGE_PERCENT_DOWN, 5.0, referencePrice = 90.0), now))
        assertFalse(evaluator.needsReference(alarm(AlarmCondition.PRICE_ABOVE, 5.0), now))
    }

    @Test
    fun `fields after triggering`() {
        val t = now
        val percent = AlarmEvaluator.triggered(alarm(AlarmCondition.CHANGE_PERCENT_UP, 5.0, referencePrice = 100.0).copy(referenceAt = 7), 105.0, t)
        assertTrue(percent.referencePrice == 105.0 && percent.referenceAt == 7L && !percent.enabled && percent.lastTriggeredAt == t)
        val move = AlarmEvaluator.triggered(alarm(AlarmCondition.MOVE_PERCENT_WINDOW, 5.0, referencePrice = 100.0).copy(repeating = true), 106.0, t)
        assertTrue(move.referencePrice == 106.0 && move.referenceAt == t && move.enabled)
        val volume = AlarmEvaluator.triggered(alarm(AlarmCondition.VOLUME_SPIKE, 3.0), 50.0, t, candleOpenTime = 123L)
        assertTrue(volume.referenceAt == 123L && volume.referencePrice == null && volume.lastTriggeredPrice == 50.0)
        val near = AlarmEvaluator.triggered(alarm(AlarmCondition.NEAR_HIGH, 0.0), 101.0, t, nearLevel = 101.0)
        assertTrue(near.referencePrice == 101.0 && near.referenceAt == t)
        val level = AlarmEvaluator.triggered(alarm(AlarmCondition.PRICE_ABOVE, 100.0), 101.0, t)
        assertTrue(level.referencePrice == null && level.referenceAt == t)
    }

    private val candle = now - 2 * 60 * 60_000L

    @Test
    fun `volume spike fires when the ratio reaches the factor`() {
        val a = alarm(AlarmCondition.VOLUME_SPIKE, 3.0)
        assertTrue(evaluator.shouldTriggerVolumeSpike(a, ratio = 3.0, candleOpenTime = candle, now = now, cooldownMinutes = 0))
        assertTrue(evaluator.shouldTriggerVolumeSpike(a, ratio = 4.2, candleOpenTime = candle, now = now, cooldownMinutes = 0))
        assertFalse(evaluator.shouldTriggerVolumeSpike(a, ratio = 2.99, candleOpenTime = candle, now = now, cooldownMinutes = 0))
    }

    @Test
    fun `volume spike fires only once per candle`() {
        val a = alarm(AlarmCondition.VOLUME_SPIKE, 3.0).copy(referenceAt = candle)
        assertFalse(evaluator.shouldTriggerVolumeSpike(a, ratio = 10.0, candleOpenTime = candle, now = now, cooldownMinutes = 0))
        val next = candle + 60 * 60_000L
        assertTrue(evaluator.shouldTriggerVolumeSpike(a, ratio = 10.0, candleOpenTime = next, now = now, cooldownMinutes = 0))
    }

    @Test
    fun `volume spike without data does not fire`() {
        val a = alarm(AlarmCondition.VOLUME_SPIKE, 3.0)
        assertFalse(evaluator.shouldTriggerVolumeSpike(a, ratio = null, candleOpenTime = candle, now = now, cooldownMinutes = 0))
        assertFalse(evaluator.shouldTriggerVolumeSpike(a, ratio = 5.0, candleOpenTime = 0, now = now, cooldownMinutes = 0))
        assertFalse(evaluator.shouldTriggerVolumeSpike(a, ratio = Double.NaN, candleOpenTime = candle, now = now, cooldownMinutes = 0))
    }

    @Test
    fun `volume spike respects enabled flag and cooldown`() {
        val disabled = alarm(AlarmCondition.VOLUME_SPIKE, 3.0, enabled = false)
        assertFalse(evaluator.shouldTriggerVolumeSpike(disabled, ratio = 9.0, candleOpenTime = candle, now = now, cooldownMinutes = 0))
        val recent = alarm(AlarmCondition.VOLUME_SPIKE, 3.0, lastTriggeredAt = now - 5 * 60_000L)
        assertFalse(evaluator.shouldTriggerVolumeSpike(recent, ratio = 9.0, candleOpenTime = candle, now = now, cooldownMinutes = 30))
    }

    @Test
    fun `price evaluation ignores volume spike alarms`() {
        val a = alarm(AlarmCondition.VOLUME_SPIKE, 3.0)
        assertFalse(evaluator.shouldTrigger(a, price = 1000.0, previousPrice = 1.0, now = now, cooldownMinutes = 0))
        assertFalse(evaluator.needsWindowReset(a, now))
    }

    @Test
    fun `other conditions are never treated as volume spike`() {
        val a = alarm(AlarmCondition.PRICE_ABOVE, 3.0)
        assertFalse(evaluator.shouldTriggerVolumeSpike(a, ratio = 9.0, candleOpenTime = candle, now = now, cooldownMinutes = 0))
    }
}
