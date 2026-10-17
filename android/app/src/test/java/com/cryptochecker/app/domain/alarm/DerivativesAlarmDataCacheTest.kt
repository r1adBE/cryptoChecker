package com.cryptochecker.app.domain.alarm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Funding/Open Interest: Werte 5 Min. zwischenspeichern, «keine Werte» nur kurz ([DerivativesAlarmData.isFresh]). */
class DerivativesAlarmDataCacheTest {

    private val at = 1_700_000_000_000L

    @Test
    fun valuesCountAsDataWhenOneIsPresent() {
        assertTrue(DerivativesAlarmData.hasData(DerivativesAlarmData.Values(0.01, null, at)))
        assertTrue(DerivativesAlarmData.hasData(DerivativesAlarmData.Values(null, 1_000.0, at)))
        assertFalse(DerivativesAlarmData.hasData(DerivativesAlarmData.Values(null, null, at)))
    }

    @Test
    fun successKeepsFiveMinutesFailureOnlyShortly() {
        val shortly = at + DerivativesAlarmData.NO_DATA_CACHE_MILLIS
        assertTrue(DerivativesAlarmData.isFresh(at, hasData = true, now = shortly))
        assertTrue(DerivativesAlarmData.isFresh(at, hasData = true, now = at + DerivativesAlarm.FETCH_INTERVAL_MILLIS - 1))
        assertFalse(DerivativesAlarmData.isFresh(at, hasData = true, now = at + DerivativesAlarm.FETCH_INTERVAL_MILLIS))

        assertTrue(DerivativesAlarmData.isFresh(at, hasData = false, now = shortly - 1))
        assertFalse(DerivativesAlarmData.isFresh(at, hasData = false, now = shortly))
        // Zeit vor dem Abruf (Uhr zurückgestellt): neu fragen
        assertFalse(DerivativesAlarmData.isFresh(at, hasData = true, now = at - 1))
    }
}
