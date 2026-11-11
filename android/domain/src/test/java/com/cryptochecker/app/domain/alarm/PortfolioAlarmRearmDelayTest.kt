package com.cryptochecker.app.domain.alarm

import org.junit.Assert.assertEquals
import org.junit.Test

/** Portfolio-Alarm frühestens [AlarmEvaluator.MIN_LEVEL_REARM_MILLIS] nach der Meldung wieder scharf. */
class PortfolioAlarmRearmDelayTest {

    private val firedAt = 1_700_000_000_000L

    private val fired = PortfolioAlarmInput(
        PortfolioAlarmKind.VALUE_ABOVE, 50_000.0, "CHF", enabled = true, referenceAt = firedAt, lastTriggeredAt = firedAt,
    )

    private val back = PortfolioReading(total = 40_000.0, currency = "CHF", totalUsdt = 44_000.0, changePercent = null, empty = false)

    @Test
    fun noRearmWithinTheMinimumDelay() {
        assertEquals(PortfolioAlarmDecision.None, PortfolioAlarmLogic.decide(fired, back, firedAt + 1_000L, 0))
        assertEquals(
            PortfolioAlarmDecision.None,
            PortfolioAlarmLogic.decide(fired, back, firedAt + AlarmEvaluator.MIN_LEVEL_REARM_MILLIS - 1, 0),
        )
    }

    @Test
    fun rearmsAfterTheDelayAndWhenTheClockWentBack() {
        assertEquals(
            PortfolioAlarmDecision.Rearm,
            PortfolioAlarmLogic.decide(fired, back, firedAt + AlarmEvaluator.MIN_LEVEL_REARM_MILLIS, 0),
        )
        // Meldung «in der Zukunft» (Uhr zurückgestellt): nicht blockieren
        assertEquals(PortfolioAlarmDecision.Rearm, PortfolioAlarmLogic.decide(fired, back, firedAt - 5_000L, 0))
    }
}
