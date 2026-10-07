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
    fun `move window expires and needs a new reference`() {
        val a = alarm(AlarmCondition.MOVE_PERCENT_WINDOW, 5.0, referencePrice = 100.0)
            .copy(windowHours = 1, referenceAt = now - 2 * 60 * 60_000L)
        assertTrue(evaluator.needsWindowReset(a, now))
        assertFalse(evaluator.shouldTrigger(a, price = 120.0, previousPrice = 100.0, now = now, cooldownMinutes = 0))
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
