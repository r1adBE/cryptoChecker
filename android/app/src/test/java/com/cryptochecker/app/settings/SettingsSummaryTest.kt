package com.cryptochecker.app.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsSummaryTest {

    @Test
    fun allOff_isZero() {
        assertEquals(0, SettingsSummary.marketAlertCount(false, 0, 0, 0, 0, false, false))
    }

    @Test
    fun allOn_isTopicCount() {
        assertEquals(
            SettingsSummary.MARKET_ALERT_TOPICS,
            SettingsSummary.marketAlertCount(true, 20, 80, 5, 3, true, true)
        )
    }

    @Test
    fun fearGreedBelowAndAbove_countAsOneTopic() {
        assertEquals(1, SettingsSummary.marketAlertCount(false, 20, 80, 0, 0, false, false))
        assertEquals(1, SettingsSummary.marketAlertCount(false, 0, 80, 0, 0, false, false))
    }

    @Test
    fun gasEthAndBtc_countAsOneTopic() {
        assertEquals(1, SettingsSummary.marketAlertCount(false, 0, 0, 5, 3, false, false))
        assertEquals(1, SettingsSummary.marketAlertCount(false, 0, 0, 0, 3, false, false))
    }

    @Test
    fun defaults_zoneOnly() {
        // Standard der App: nur die Marktphase ist an
        assertEquals(1, SettingsSummary.marketAlertCount(true, 0, 0, 0, 0, false, false))
    }

    @Test
    fun mixed() {
        assertEquals(3, SettingsSummary.marketAlertCount(true, 0, 0, 0, 0, true, true))
    }

    @Test
    fun speech() {
        assertEquals(SettingsSummary.Speech.OFF, SettingsSummary.speech(enabled = false, alarmsOnly = true))
        assertEquals(SettingsSummary.Speech.ON, SettingsSummary.speech(enabled = true, alarmsOnly = false))
        assertEquals(SettingsSummary.Speech.ALARMS_ONLY, SettingsSummary.speech(enabled = true, alarmsOnly = true))
    }
}
