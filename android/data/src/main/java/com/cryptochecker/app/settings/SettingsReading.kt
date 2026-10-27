// Einstellungen aus der DataStore-Ablage lesen (Teil von `SettingsRepository`): Schlüssel → [AppSettings] samt
// Standardwerten und Grenzen.
package com.cryptochecker.app.settings

import com.cryptochecker.app.domain.market.IndicatorAlertKind
import com.cryptochecker.app.domain.activity.ActivitySensitivity
import com.cryptochecker.app.domain.alarm.AlarmSignal
import com.cryptochecker.app.domain.alarm.DepegAlarm
import com.cryptochecker.app.domain.alarm.QuietHours
import com.cryptochecker.app.domain.portfolio.CostMethod
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryRange
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryView
import com.cryptochecker.app.domain.watch.ChangeBasis
import androidx.datastore.preferences.core.Preferences

/**
 * Einstellungen aus der Ablage [prefs]: fehlende Werte als Standard ([AppSettings]), Grenzen wie beim Setzen.
 * Für [SettingsRepository.settings].
 */
fun appSettingsOf(prefs: Preferences): AppSettings {
    val defaults = AppSettings()
    return AppSettings(
        // Die Migration ([UpdateModeMigration]) hat den Wert beim ersten Lesen gesetzt
        updateMode = UpdateMode.fromNameOrNull(prefs[SettingsKeys.updateMode]) ?: defaults.updateMode,
        // Grenzen wie beim Setzen (früher gespeicherte, zu grosse Werte), wie iOS
        backgroundIntervalMinutes = AppSettings.clampBackgroundInterval(
            prefs[SettingsKeys.backgroundInterval] ?: defaults.backgroundIntervalMinutes
        ),
        exactChecks = prefs[SettingsKeys.exactChecks] ?: defaults.exactChecks,
        liveIntervalSeconds = AppSettings.clampLiveInterval(prefs[SettingsKeys.liveInterval] ?: defaults.liveIntervalSeconds),
        priceNotifications = prefs[SettingsKeys.priceNotifications] ?: defaults.priceNotifications,
        ongoingNotifications = prefs[SettingsKeys.ongoingNotifications]
            ?: defaults.ongoingNotifications,
        notificationChangePercent = prefs[SettingsKeys.notificationChange]
            ?: prefs[SettingsKeys.notificationChangeLegacy]?.toDouble()
            ?: defaults.notificationChangePercent,
        ttsEnabled = prefs[SettingsKeys.tts] ?: defaults.ttsEnabled,
        ttsAlarmsOnly = prefs[SettingsKeys.ttsAlarmsOnly] ?: defaults.ttsAlarmsOnly,
        ttsSpeechRate = prefs[SettingsKeys.ttsSpeechRate] ?: defaults.ttsSpeechRate,
        alarmCooldownMinutes = AppSettings.clampAlarmCooldown(prefs[SettingsKeys.alarmCooldown] ?: defaults.alarmCooldownMinutes),
        includeRollingFutures = prefs[SettingsKeys.includeRollingFutures]
            ?: defaults.includeRollingFutures,
        // Fehlt der neue Wert (Update): einmalig aus dem früheren Schalter übernehmen
        assetScope = AssetScope.resolve(prefs[SettingsKeys.assetScope], prefs[SettingsKeys.includeTradFiFuturesLegacy]),
        includeAlphaTokens = prefs[SettingsKeys.includeAlphaTokens] ?: defaults.includeAlphaTokens,
        accentColor = AccentColor.fromName(prefs[SettingsKeys.accentColor]),
        darkMode = prefs[SettingsKeys.darkMode],
        showHttpLog = prefs[SettingsKeys.showHttpLog] ?: defaults.showHttpLog,
        developerUnlocked = prefs[SettingsKeys.developerUnlocked] ?: defaults.developerUnlocked,
        gestureHintSeen = prefs[SettingsKeys.gestureHintSeen] ?: defaults.gestureHintSeen,
        firstAlarmCardDismissed = prefs[SettingsKeys.firstAlarmCardDismissed] ?: defaults.firstAlarmCardDismissed,
        usageDays = prefs[SettingsKeys.usageDays] ?: defaults.usageDays,
        usageLastDay = prefs[SettingsKeys.usageLastDay],
        fearGreedBelow = prefs[SettingsKeys.fearGreedBelow] ?: defaults.fearGreedBelow,
        fearGreedAbove = prefs[SettingsKeys.fearGreedAbove] ?: defaults.fearGreedAbove,
        indicatorAlerts = IndicatorAlertKind.entries
            .mapNotNull { kind -> prefs[SettingsKeys.indicatorAlert(kind)]?.takeIf { it in kind.choices && it > 0 }?.let { kind to it } }
            .toMap(),
        aboutSeen = prefs[SettingsKeys.aboutSeen] ?: defaults.aboutSeen,
        batteryPromptSeen = prefs[SettingsKeys.batteryPromptSeen] ?: defaults.batteryPromptSeen,
        watchlistGroup = prefs[SettingsKeys.watchlistGroup],
        watchlistColumnSort = prefs[SettingsKeys.watchlistColumnSort],
        activityAlerts = prefs[SettingsKeys.activityAlerts] ?: defaults.activityAlerts,
        activitySensitivity = ActivitySensitivity.fromName(prefs[SettingsKeys.activitySensitivity]),
        macroNotifications = prefs[SettingsKeys.macroNotifications] ?: defaults.macroNotifications,
        portfolioEnabled = prefs[SettingsKeys.portfolioEnabled] ?: defaults.portfolioEnabled,
        portfolioCurrency = prefs[SettingsKeys.portfolioCurrency] ?: defaults.portfolioCurrency,
        showConverted = prefs[SettingsKeys.showConverted] ?: defaults.showConverted,
        priceColorScheme = PriceColorScheme.fromName(prefs[SettingsKeys.priceColorScheme]),
        watchlistSparkline = prefs[SettingsKeys.watchlistSparkline] ?: defaults.watchlistSparkline,
        watchlistNames = prefs[SettingsKeys.watchlistNames] ?: defaults.watchlistNames,
        appIconBadge = prefs[SettingsKeys.appIconBadge] ?: defaults.appIconBadge,
        watchlistActivityCard = prefs[SettingsKeys.watchlistActivityCard] ?: defaults.watchlistActivityCard,
        coinLogos = prefs[SettingsKeys.coinLogos] ?: defaults.coinLogos,
        widgetCoinLogos = prefs[SettingsKeys.widgetCoinLogos] ?: defaults.widgetCoinLogos,
        portfolioCoinLogos = prefs[SettingsKeys.portfolioCoinLogos] ?: defaults.portfolioCoinLogos,
        changeBasis = ChangeBasis.fromName(prefs[SettingsKeys.changeBasis]),
        highContrast = prefs[SettingsKeys.highContrast] ?: defaults.highContrast,
        // Nie gesetzt: Standard nach Region des Geräts
        priceColorsInverted = prefs[SettingsKeys.priceColorsInverted] ?: defaults.priceColorsInverted,
        gasAlertEthTenths = prefs[SettingsKeys.gasAlertEth] ?: defaults.gasAlertEthTenths,
        gasAlertBtc = prefs[SettingsKeys.gasAlertBtc] ?: defaults.gasAlertBtc,
        depegAlarm = prefs[SettingsKeys.depegAlarm] ?: defaults.depegAlarm,
        depegThresholdTenths = DepegAlarm.threshold(prefs[SettingsKeys.depegThreshold] ?: defaults.depegThresholdTenths),
        alarmSoundUri = prefs[SettingsKeys.alarmSoundUri],
        alarmSoundName = prefs[SettingsKeys.alarmSoundName],
        alarmChannelVersion = prefs[SettingsKeys.alarmChannelVersion] ?: defaults.alarmChannelVersion,
        alarmSignal = AlarmSignal.fromName(prefs[SettingsKeys.alarmSignal]),
        quietHoursEnabled = prefs[SettingsKeys.quietHoursEnabled] ?: defaults.quietHoursEnabled,
        quietHoursStart = prefs[SettingsKeys.quietHoursStart]?.takeIf { QuietHours.isValidMinute(it) }
            ?: defaults.quietHoursStart,
        quietHoursEnd = prefs[SettingsKeys.quietHoursEnd]?.takeIf { QuietHours.isValidMinute(it) }
            ?: defaults.quietHoursEnd,
        appLock = prefs[SettingsKeys.appLock] ?: defaults.appLock,
        hidePortfolioAmounts = prefs[SettingsKeys.hidePortfolioAmounts] ?: defaults.hidePortfolioAmounts,
        portfolioSystemBackup = prefs[SettingsKeys.portfolioSystemBackup] ?: defaults.portfolioSystemBackup,
        firstAlarmShown = prefs[SettingsKeys.firstAlarmShown] ?: defaults.firstAlarmShown,
        alarmTimingHintShown = prefs[SettingsKeys.alarmTimingHintShown] ?: defaults.alarmTimingHintShown,
        firstPairAdded = prefs[SettingsKeys.firstPairAdded] ?: defaults.firstPairAdded,
        sheetChartLine = prefs[SettingsKeys.sheetChartLine] ?: defaults.sheetChartLine,
        portfolioHistoryExpanded = prefs[SettingsKeys.portfolioHistoryExpanded] ?: defaults.portfolioHistoryExpanded,
        portfolioHistoryRange = PortfolioHistoryRange.fromName(prefs[SettingsKeys.portfolioHistoryRange]),
        portfolioHistoryView = PortfolioHistoryView.fromName(prefs[SettingsKeys.portfolioHistoryView]),
        portfolioHistoryCandles = prefs[SettingsKeys.portfolioHistoryCandles] ?: defaults.portfolioHistoryCandles,
        portfolioHistoryPnl = prefs[SettingsKeys.portfolioHistoryPnl] ?: defaults.portfolioHistoryPnl,
        // Fehlt oder unbekannt (auch bestehende Installationen): FIFO
        costMethod = CostMethod.fromName(prefs[SettingsKeys.costMethod]),
        // Fehlt oder unbekannt: Neutral
        taxTemplate = com.cryptochecker.app.domain.portfolio.TaxTemplate.fromName(prefs[SettingsKeys.taxTemplate]),
        taxTemplateConfirmed = prefs[SettingsKeys.taxTemplateConfirmed] ?: defaults.taxTemplateConfirmed,
        marketTabSeen = prefs[SettingsKeys.marketTabSeen] ?: defaults.marketTabSeen,
        marketMoreExpanded = prefs[SettingsKeys.marketMoreExpanded] ?: defaults.marketMoreExpanded,
    )
}
