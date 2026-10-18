package com.cryptochecker.app.settings

import com.cryptochecker.app.domain.activity.ActivitySensitivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Ausschnitte der Einstellungen ([WatchlistSettings] usw.): gleiche Werte wie AppSettings, nur ihr Teil zählt. */
class SettingsSlicesTest {

    @Test
    fun watchlistSliceCopiesFields() {
        val s = AppSettings(
            watchlistSparkline = true,
            watchlistActivityCard = false,
            watchlistColumnSort = "PRICE_DESC",
            watchlistGroup = "DeFi",
            showConverted = false,
            portfolioCurrency = "EUR",
            portfolioEnabled = true,
            activitySensitivity = ActivitySensitivity.MORE,
            sheetChartLine = true,
            gestureHintSeen = true,
            firstAlarmCardDismissed = true,
            backgroundIntervalMinutes = 30,
            showChangePeriod = true,
            priceNotifications = false,
        )
        val w = WatchlistSettings.of(s)
        assertEquals(true, w.sparkline)
        assertEquals(false, w.activityCard)
        assertEquals("PRICE_DESC", w.columnSort)
        assertEquals("DeFi", w.group)
        assertEquals(false, w.showConverted)
        assertEquals("EUR", w.portfolioCurrency)
        assertEquals(true, w.portfolioEnabled)
        assertEquals(ActivitySensitivity.MORE, w.activitySensitivity)
        assertEquals(true, w.sheetChartLine)
        assertEquals(true, w.gestureHintSeen)
        assertEquals(true, w.firstAlarmCardDismissed)
        assertEquals(30, w.backgroundIntervalMinutes)
        assertEquals(s.changeBasis, w.changeBasis)
        assertEquals(true, w.showChangePeriod)
        assertEquals(false, w.priceNotifications)
    }

    @Test
    fun unrelatedChangeKeepsSliceEqual() {
        // Ruhezeit oder Akzentfarbe ändern: der Merkliste-Ausschnitt bleibt gleich (distinctUntilChanged gibt nichts aus)
        val before = AppSettings()
        val after = before.copy(quietHoursEnabled = !before.quietHoursEnabled, accentColor = AccentColor.entries.last())
        assertEquals(WatchlistSettings.of(before), WatchlistSettings.of(after))
        assertEquals(UpdateSettings.of(before), UpdateSettings.of(after))
        assertEquals(PortfolioSettings.of(before), PortfolioSettings.of(after))
        assertNotEquals(AlarmSettings.of(before), AlarmSettings.of(after))
        // Gruppe wechseln betrifft nur die Merkliste
        val grouped = before.copy(watchlistGroup = "DeFi")
        assertNotEquals(WatchlistSettings.of(before), WatchlistSettings.of(grouped))
        assertEquals(AppearanceSettings.of(before), AppearanceSettings.of(grouped))
        assertEquals(AlarmSettings.of(before), AlarmSettings.of(grouped))
    }

    @Test
    fun otherSlicesCopyFields() {
        val s = AppSettings(
            highContrast = true, coinLogos = false, widgetCoinLogos = true, watchlistNames = false,
            hidePortfolioAmounts = true, portfolioCoinLogos = false, taxTemplateConfirmed = true,
            exactChecks = true, liveIntervalSeconds = 30, ttsEnabled = true, alarmCooldownMinutes = 15,
        )
        val a = AppearanceSettings.of(s)
        assertEquals(true, a.highContrast)
        assertEquals(false, a.coinLogos)
        assertEquals(true, a.widgetCoinLogos)
        assertEquals(false, a.watchlistNames)
        assertEquals(s.accentColor, a.accentColor)
        val p = PortfolioSettings.of(s)
        assertEquals(true, p.hideAmounts)
        assertEquals(false, p.coinLogos)
        assertEquals(true, p.taxTemplateConfirmed)
        assertEquals(s.portfolioCurrency, p.currency)
        val u = UpdateSettings.of(s)
        assertEquals(true, u.exactChecks)
        assertEquals(30, u.liveIntervalSeconds)
        assertEquals(s.updateMode, u.updateMode)
        val al = AlarmSettings.of(s)
        assertEquals(true, al.ttsEnabled)
        assertEquals(15, al.alarmCooldownMinutes)
        assertEquals(s.quietHoursStart, al.quietHoursStart)
    }
}
