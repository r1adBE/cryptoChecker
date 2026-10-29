// Ausschnitte der Einstellungen ([AppSettings] hat über 70 Felder): Bildschirme und ViewModels beobachten nur den
// Teil, den sie brauchen ([SettingsRepository.watchlistSettings] usw., je mit distinctUntilChanged). So löst z. B.
// das Umstellen der Ruhezeit keine neue Ausgabe für die Merkliste aus. Gespeichert wird weiter nur AppSettings.
// Eigene Datei, weil es reine Abbildungen ohne Android-Bezug sind (Unit-Tests in SettingsSlicesTest).
package com.cryptochecker.app.settings

import com.cryptochecker.app.domain.activity.ActivitySensitivity
import com.cryptochecker.app.domain.alarm.AlarmSignal
import com.cryptochecker.app.domain.portfolio.CostMethod
import com.cryptochecker.app.domain.portfolio.TaxTemplate
import com.cryptochecker.app.domain.watch.ChangeBasis

/** Was die Merkliste (Liste, Kopf, Aktionsblatt) aus den Einstellungen liest. */
data class WatchlistSettings(
    val sparkline: Boolean,
    val activityCard: Boolean,
    /** Gespeicherte Spalten-Sortierung (`ColumnSort.encode`), null = eigene Reihenfolge. */
    val columnSort: String?,
    /** Gewählte Gruppe der Chips, null = «Alle». */
    val group: String?,
    val showConverted: Boolean,
    val portfolioCurrency: String,
    val portfolioEnabled: Boolean,
    val activitySensitivity: ActivitySensitivity,
    val sheetChartLine: Boolean,
    val gestureHintSeen: Boolean,
    val firstAlarmCardDismissed: Boolean,
    /** Verschiedene Tage mit offener App ([com.cryptochecker.app.domain.watch.WatchlistOnboarding]). */
    val usageDays: Int,
    val backgroundIntervalMinutes: Int,
    val changeBasis: ChangeBasis,
    val priceNotifications: Boolean,
) {
    companion object {
        fun of(s: AppSettings) = WatchlistSettings(
            sparkline = s.watchlistSparkline,
            activityCard = s.watchlistActivityCard,
            columnSort = s.watchlistColumnSort,
            group = s.watchlistGroup,
            showConverted = s.showConverted,
            portfolioCurrency = s.portfolioCurrency,
            portfolioEnabled = s.portfolioEnabled,
            activitySensitivity = s.activitySensitivity,
            sheetChartLine = s.sheetChartLine,
            gestureHintSeen = s.gestureHintSeen,
            firstAlarmCardDismissed = s.firstAlarmCardDismissed,
            usageDays = s.usageDays,
            backgroundIntervalMinutes = s.backgroundIntervalMinutes,
            changeBasis = s.changeBasis,
            priceNotifications = s.priceNotifications,
        )
    }
}

/** Alarme und Mitteilungen: Schwellen, Ton, Ruhezeit und Sprachausgabe. */
data class AlarmSettings(
    val priceNotifications: Boolean,
    val ongoingNotifications: Boolean,
    val notificationChangePercent: Double,
    val alarmCooldownMinutes: Int,
    val alarmSoundUri: String?,
    val alarmSoundName: String?,
    val alarmSignal: AlarmSignal,
    val quietHoursEnabled: Boolean,
    val quietHoursStart: Int,
    val quietHoursEnd: Int,
    val ttsEnabled: Boolean,
    val ttsAlarmsOnly: Boolean,
    val ttsSpeechRate: Float,
) {
    companion object {
        fun of(s: AppSettings) = AlarmSettings(
            priceNotifications = s.priceNotifications,
            ongoingNotifications = s.ongoingNotifications,
            notificationChangePercent = s.notificationChangePercent,
            alarmCooldownMinutes = s.alarmCooldownMinutes,
            alarmSoundUri = s.alarmSoundUri,
            alarmSoundName = s.alarmSoundName,
            alarmSignal = s.alarmSignal,
            quietHoursEnabled = s.quietHoursEnabled,
            quietHoursStart = s.quietHoursStart,
            quietHoursEnd = s.quietHoursEnd,
            ttsEnabled = s.ttsEnabled,
            ttsAlarmsOnly = s.ttsAlarmsOnly,
            ttsSpeechRate = s.ttsSpeechRate,
        )
    }
}

/** Portfolio: Schalter, Währung, Anzeige und Steuer-Rechnung. */
data class PortfolioSettings(
    val enabled: Boolean,
    val currency: String,
    val hideAmounts: Boolean,
    val coinLogos: Boolean,
    val costMethod: CostMethod,
    val taxTemplate: TaxTemplate,
    val taxTemplateConfirmed: Boolean,
) {
    companion object {
        fun of(s: AppSettings) = PortfolioSettings(
            enabled = s.portfolioEnabled,
            currency = s.portfolioCurrency,
            hideAmounts = s.hidePortfolioAmounts,
            coinLogos = s.portfolioCoinLogos,
            costMethod = s.costMethod,
            taxTemplate = s.taxTemplate,
            taxTemplateConfirmed = s.taxTemplateConfirmed,
        )
    }
}

/** «Wie aktuell?»: Modus und Intervalle der Aktualisierung. */
data class UpdateSettings(
    val updateMode: UpdateMode,
    val backgroundIntervalMinutes: Int,
    val exactChecks: Boolean,
) {
    companion object {
        fun of(s: AppSettings) = UpdateSettings(
            updateMode = s.updateMode,
            backgroundIntervalMinutes = s.backgroundIntervalMinutes,
            exactChecks = s.exactChecks,
        )
    }
}
