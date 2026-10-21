package com.cryptochecker.app.settings

import com.cryptochecker.app.domain.activity.ActivitySensitivity
import com.cryptochecker.app.domain.alarm.AlarmSignal
import com.cryptochecker.app.domain.market.IndicatorAlertKind
import com.cryptochecker.app.domain.portfolio.CostMethod
import com.cryptochecker.app.domain.portfolio.TaxTemplate
import com.cryptochecker.app.domain.watch.ChangeBasis

/**
 * Was die Sicherung an Einstellungen liest und schreibt (`BackupSettingsCodec`): der jetzige Stand und je gesicherte
 * Einstellung der Setter von [SettingsRepository] (dort mit Grenzen und Nebenwirkungen). Seit Build 102 als Schnittstelle,
 * damit der Hin- und Rückweg aller Einstellungen ohne DataStore testbar ist (BackupSettingsCodecTest).
 */
interface SettingsBackupTarget {
    suspend fun current(): AppSettings
    suspend fun setUpdateMode(mode: UpdateMode)
    suspend fun setBackgroundInterval(minutes: Int)
    suspend fun setLiveInterval(seconds: Int)
    suspend fun setPriceNotifications(enabled: Boolean)
    suspend fun setOngoingNotifications(enabled: Boolean)
    suspend fun setNotificationChangePercent(percent: Double)
    suspend fun setTtsEnabled(enabled: Boolean)
    suspend fun setTtsAlarmsOnly(enabled: Boolean)
    suspend fun setTtsSpeechRate(rate: Float)
    suspend fun setAlarmCooldown(minutes: Int)
    suspend fun setIncludeRollingFutures(enabled: Boolean)
    suspend fun setAssetScope(scope: AssetScope)
    suspend fun setIncludeAlphaTokens(enabled: Boolean)
    suspend fun setAccentColor(accent: AccentColor)
    suspend fun setDarkMode(dark: Boolean?)
    suspend fun setFearGreedBelow(value: Int)
    suspend fun setFearGreedAbove(value: Int)
    suspend fun setIndicatorAlert(kind: IndicatorAlertKind, value: Int)
    suspend fun setGasAlertEth(tenths: Int)
    suspend fun setGasAlertBtc(satPerVb: Int)
    suspend fun setDepegAlarm(enabled: Boolean)
    suspend fun setDepegThreshold(tenths: Int)
    suspend fun setActivityAlerts(enabled: Boolean)
    suspend fun setActivitySensitivity(sensitivity: ActivitySensitivity)
    suspend fun setMacroNotifications(enabled: Boolean)
    suspend fun setPortfolioEnabled(enabled: Boolean)
    suspend fun setPortfolioCurrency(currency: String)
    suspend fun setShowConverted(show: Boolean)
    suspend fun setPriceColorScheme(scheme: PriceColorScheme)
    suspend fun setWatchlistSparkline(show: Boolean)
    suspend fun setWatchlistNames(show: Boolean)
    suspend fun setShowChangePeriod(show: Boolean)
    suspend fun setAppIconBadge(show: Boolean)
    suspend fun setWatchlistActivityCard(show: Boolean)
    suspend fun setCoinLogos(show: Boolean)
    suspend fun setWidgetCoinLogos(show: Boolean)
    suspend fun setPortfolioCoinLogos(show: Boolean)
    suspend fun setChangeBasis(basis: ChangeBasis)
    suspend fun setHighContrast(enabled: Boolean)
    suspend fun setPriceColorsInverted(inverted: Boolean)
    suspend fun setAlarmSignal(signal: AlarmSignal)
    suspend fun setQuietHoursEnabled(enabled: Boolean)
    suspend fun setQuietHoursStart(minute: Int)
    suspend fun setQuietHoursEnd(minute: Int)
    suspend fun setHidePortfolioAmounts(hidden: Boolean)
    suspend fun setPortfolioSystemBackup(allowed: Boolean)
    suspend fun setCostMethod(method: CostMethod)
    suspend fun setTaxTemplate(template: TaxTemplate)
    suspend fun setWatchlistColumnSort(value: String?)
    suspend fun setAppLock(enabled: Boolean)
}
