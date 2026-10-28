package com.cryptochecker.app.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsSummaryTest {

    @Test
    fun allOff_isZero() {
        assertEquals(0, SettingsSummary.marketAlertCount(0, 0, 0, 0, false, false))
    }

    @Test
    fun allOn_isTopicCount() {
        assertEquals(
            SettingsSummary.MARKET_ALERT_TOPICS,
            SettingsSummary.marketAlertCount(20, 80, 5, 3, true, true, indicators = true)
        )
    }

    @Test
    fun fearGreedBelowAndAbove_countAsOneTopic() {
        assertEquals(1, SettingsSummary.marketAlertCount(20, 80, 0, 0, false, false))
        assertEquals(1, SettingsSummary.marketAlertCount(0, 80, 0, 0, false, false))
    }

    @Test
    fun gasEthAndBtc_countAsOneTopic() {
        assertEquals(1, SettingsSummary.marketAlertCount(0, 0, 5, 3, false, false))
        assertEquals(1, SettingsSummary.marketAlertCount(0, 0, 0, 3, false, false))
    }

    @Test
    fun defaults_allOff() {
        // Standard der App: keine Markt-Meldung an
        assertEquals(0, SettingsSummary.marketAlertCount(0, 0, 0, 0, false, false))
    }

    @Test
    fun mixed() {
        assertEquals(2, SettingsSummary.marketAlertCount(0, 0, 0, 0, true, true))
    }

    @Test
    fun speech() {
        assertEquals(SettingsSummary.Speech.OFF, SettingsSummary.speech(enabled = false, alarmsOnly = true))
        assertEquals(SettingsSummary.Speech.ON, SettingsSummary.speech(enabled = true, alarmsOnly = false))
        assertEquals(SettingsSummary.Speech.ALARMS_ONLY, SettingsSummary.speech(enabled = true, alarmsOnly = true))
    }

    // Kurzwerte der neuen Zeilen

    // Kurzwert «Aktualisierung» = Wahl von «Wie aktuell?» (UpdateModeTest)

    @Test
    fun portfolio_lockCountsOnlyWhenEnabled() {
        assertEquals(SettingsSummary.Portfolio.OFF, SettingsSummary.portfolio(enabled = false, lock = true))
        assertEquals(SettingsSummary.Portfolio.OFF, SettingsSummary.portfolio(enabled = false, lock = false))
        assertEquals(SettingsSummary.Portfolio.ON, SettingsSummary.portfolio(enabled = true, lock = false))
        assertEquals(SettingsSummary.Portfolio.LOCKED, SettingsSummary.portfolio(enabled = true, lock = true))
    }

    @Test
    fun watchlist_partsInOrder() {
        assertEquals(emptyList<SettingsSummary.WatchlistPart>(), SettingsSummary.watchlist(false, false))
        assertEquals(listOf(SettingsSummary.WatchlistPart.SPARKLINE), SettingsSummary.watchlist(true, false))
        assertEquals(listOf(SettingsSummary.WatchlistPart.CONVERTED), SettingsSummary.watchlist(false, true))
        assertEquals(
            listOf(SettingsSummary.WatchlistPart.NAMES, SettingsSummary.WatchlistPart.SPARKLINE),
            SettingsSummary.watchlist(true, false, names = true),
        )
        assertEquals(
            listOf(SettingsSummary.WatchlistPart.SPARKLINE, SettingsSummary.WatchlistPart.CONVERTED),
            SettingsSummary.watchlist(true, true)
        )
    }
}
