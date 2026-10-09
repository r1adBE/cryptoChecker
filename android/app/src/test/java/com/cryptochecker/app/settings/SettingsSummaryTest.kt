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

    // Runde 23f: Kurzwerte der neuen Zeilen

    @Test
    fun updates_liveWinsOverBackground() {
        assertEquals(SettingsSummary.Updates.LIVE, SettingsSummary.updates(liveService = true, backgroundUpdates = true))
        assertEquals(SettingsSummary.Updates.LIVE, SettingsSummary.updates(liveService = true, backgroundUpdates = false))
        assertEquals(SettingsSummary.Updates.BACKGROUND, SettingsSummary.updates(liveService = false, backgroundUpdates = true))
        assertEquals(SettingsSummary.Updates.OFF, SettingsSummary.updates(liveService = false, backgroundUpdates = false))
    }

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
