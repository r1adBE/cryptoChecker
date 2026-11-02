package com.cryptochecker.app.ui.features.alarms

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Alarm setzen» der Paarseite nur ohne Referenzkurs, mit genug DEX-Liquidität und unter der Höchstzahl. */
class PairAlarmLimitsTest {

    @Test
    fun canAddOnlyWithoutAnyLimit() {
        assertTrue(PairAlarmLimits(referenceRate = false, lowLiquidity = false, full = false).canAdd)
        assertFalse(PairAlarmLimits(referenceRate = true, lowLiquidity = false, full = false).canAdd)
        assertFalse(PairAlarmLimits(referenceRate = false, lowLiquidity = true, full = false).canAdd)
        assertFalse(PairAlarmLimits(referenceRate = false, lowLiquidity = false, full = true).canAdd)
    }
}
