package com.cryptochecker.app.settings

import com.cryptochecker.app.domain.market.IndicatorAlertKind
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import com.cryptochecker.app.domain.activity.ActivitySensitivity
import com.cryptochecker.app.domain.alarm.AlarmSignal
import com.cryptochecker.app.domain.alarm.DepegAlarm
import com.cryptochecker.app.domain.alarm.QuietHours
import com.cryptochecker.app.domain.portfolio.CostMethod
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryRange
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryView
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.domain.watch.UsageDays
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Einstellungen lesen und schreiben. Ablage und Migrationen in `SettingsDataStore.kt`, Schlüssel in [SettingsKeys].
 */
@Singleton
class SettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    val settings: Flow<AppSettings> = context.settingsDataStore.data
        // Lesefehler (I/O): mit Standardwerten weiter statt Absturz; andere Fehler weiterreichen
        .catch { e ->
            if (e !is IOException) throw e
            Timber.w(e, "Einstellungen nicht lesbar – Standardwerte")
            emit(emptyPreferences())
        }
        .map { prefs ->
        val defaults = AppSettings()
        AppSettings(
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
            showChangePeriod = prefs[SettingsKeys.showChangePeriod] ?: defaults.showChangePeriod,
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
        ).also {
            cached = it
            loaded = true
        }
    }

    /*
     * Ausschnitte von settings (SettingsSlices.kt): geben nur neu aus, wenn sich ihr Teil ändert — wer nur
     * die Merkliste bzw. das Aussehen braucht, wird nicht bei jeder anderen Einstellung neu benachrichtigt.
     */

    /** Was die Merkliste liest ([WatchlistSettings]). */
    val watchlistSettings: Flow<WatchlistSettings> = settings.map { WatchlistSettings.of(it) }.distinctUntilChanged()

    /** Aussehen ([AppearanceSettings]). */
    val appearanceSettings: Flow<AppearanceSettings> = settings.map { AppearanceSettings.of(it) }.distinctUntilChanged()

    /** Alarme, Mitteilungen und Sprachausgabe ([AlarmSettings]). */
    val alarmSettings: Flow<AlarmSettings> = settings.map { AlarmSettings.of(it) }.distinctUntilChanged()

    /** Portfolio ([PortfolioSettings]). */
    val portfolioSettings: Flow<PortfolioSettings> = settings.map { PortfolioSettings.of(it) }.distinctUntilChanged()

    /** «Wie aktuell?» ([UpdateSettings]). */
    val updateSettings: Flow<UpdateSettings> = settings.map { UpdateSettings.of(it) }.distinctUntilChanged()

    /**
     * Zuletzt gelesene Einstellungen, ohne zu warten. Für Stellen, die nicht
     * suspendieren können (z. B. Farbe einer Benachrichtigung). Vor dem ersten
     * Lesen die Standardwerte.
     */
    @Volatile
    var cached: AppSettings = AppSettings()
        private set

    /** true, sobald [cached] einmal aus der Datei gelesen wurde (Start ohne runBlocking, K-6). */
    @Volatile
    var loaded: Boolean = false
        private set

    suspend fun current(): AppSettings = settings.first()

    /**
     * «Wie aktuell?»; die früheren Schalter werden passend mitgeschrieben (Downgrade). «Immer kurz»
     * wird nie gespeichert ([UpdateMode.withoutService]).
     */
    suspend fun setUpdateMode(mode: UpdateMode) = edit {
        val saved = mode.withoutService()
        val flags = saved.legacyFlags
        it[SettingsKeys.updateMode] = saved.name
        it[SettingsKeys.backgroundUpdatesLegacy] = flags.backgroundUpdates
        it[SettingsKeys.liveServiceLegacy] = flags.liveService
        it[SettingsKeys.liveWebSocketLegacy] = flags.liveWebSocket
    }

    suspend fun setBackgroundInterval(minutes: Int) = edit {
        it[SettingsKeys.backgroundInterval] = AppSettings.clampBackgroundInterval(minutes)
    }

    suspend fun setExactChecks(enabled: Boolean) = edit { it[SettingsKeys.exactChecks] = enabled }


    suspend fun setLiveInterval(seconds: Int) = edit {
        it[SettingsKeys.liveInterval] = AppSettings.clampLiveInterval(seconds)
    }

    suspend fun setPriceNotifications(enabled: Boolean) = edit { it[SettingsKeys.priceNotifications] = enabled }

    suspend fun setOngoingNotifications(enabled: Boolean) = edit { it[SettingsKeys.ongoingNotifications] = enabled }

    suspend fun setNotificationChangePercent(percent: Double) = edit {
        it[SettingsKeys.notificationChange] = percent.coerceIn(0.0, 100.0)
    }

    suspend fun setTtsEnabled(enabled: Boolean) = edit { it[SettingsKeys.tts] = enabled }

    suspend fun setTtsAlarmsOnly(enabled: Boolean) = edit { it[SettingsKeys.ttsAlarmsOnly] = enabled }

    suspend fun setTtsSpeechRate(rate: Float) = edit {
        it[SettingsKeys.ttsSpeechRate] = rate.coerceIn(0.5f, 2.0f)
    }

    suspend fun setAlarmCooldown(minutes: Int) = edit {
        it[SettingsKeys.alarmCooldown] = AppSettings.clampAlarmCooldown(minutes)
    }

    suspend fun setIncludeRollingFutures(enabled: Boolean) =
        edit { it[SettingsKeys.includeRollingFutures] = enabled }

    suspend fun setIncludeAlphaTokens(enabled: Boolean) =
        edit { it[SettingsKeys.includeAlphaTokens] = enabled }

    /** Ab hier gilt nur noch der neue Wert; der frühere Schalter wird entfernt. */
    suspend fun setAssetScope(scope: AssetScope) = edit {
        it[SettingsKeys.assetScope] = scope.name
        it.remove(SettingsKeys.includeTradFiFuturesLegacy)
    }

    suspend fun setAccentColor(accent: AccentColor) = edit { it[SettingsKeys.accentColor] = accent.name }

    /** null = wie das System. */
    suspend fun setDarkMode(dark: Boolean?) = edit {
        if (dark == null) {
            it.remove(SettingsKeys.darkMode)
        } else {
            it[SettingsKeys.darkMode] = dark
        }
    }

    suspend fun setShowHttpLog(show: Boolean) = edit { it[SettingsKeys.showHttpLog] = show }

    suspend fun setDeveloperUnlocked(unlocked: Boolean) = edit { it[SettingsKeys.developerUnlocked] = unlocked }

    suspend fun setGestureHintSeen(seen: Boolean) = edit { it[SettingsKeys.gestureHintSeen] = seen }

    suspend fun setFirstAlarmCardDismissed(dismissed: Boolean) = edit { it[SettingsKeys.firstAlarmCardDismissed] = dismissed }

    /** Öffnen am lokalen Epoch-Tag [today] zählen ([UsageDays.next]); derselbe Tag schreibt nichts Neues. */
    suspend fun recordUsageDay(today: Long) = edit {
        val next = UsageDays(it[SettingsKeys.usageDays] ?: 0, it[SettingsKeys.usageLastDay]).next(today)
        it[SettingsKeys.usageDays] = next.count
        next.lastDay?.let { day -> it[SettingsKeys.usageLastDay] = day }
    }

    suspend fun setFearGreedBelow(value: Int) = edit { it[SettingsKeys.fearGreedBelow] = value.coerceIn(0, 100) }

    suspend fun setFearGreedAbove(value: Int) = edit { it[SettingsKeys.fearGreedAbove] = value.coerceIn(0, 100) }

    /** Eigene Grenze einer Bitcoin-Kennzahl; nur Werte aus [IndicatorAlertKind.choices], sonst aus. */
    suspend fun setIndicatorAlert(kind: IndicatorAlertKind, value: Int) =
        edit { it[SettingsKeys.indicatorAlert(kind)] = value.takeIf { v -> v in kind.choices } ?: 0 }

    suspend fun setGasAlertEth(tenths: Int) = edit { it[SettingsKeys.gasAlertEth] = tenths.coerceIn(0, 10_000) }

    suspend fun setGasAlertBtc(satPerVb: Int) = edit { it[SettingsKeys.gasAlertBtc] = satPerVb.coerceIn(0, 10_000) }

    suspend fun setDepegAlarm(enabled: Boolean) = edit { it[SettingsKeys.depegAlarm] = enabled }

    /** Nur Werte aus [DepegAlarm.THRESHOLD_CHOICES], sonst Standard. */
    suspend fun setDepegThreshold(tenths: Int) = edit { it[SettingsKeys.depegThreshold] = DepegAlarm.threshold(tenths) }

    /**
     * Alarmton setzen (null = Standardton). Erhöht die Kanal-Version, damit
     * [com.cryptochecker.app.notification.NotificationChannels] einen neuen Kanal anlegt.
     * @return die neue Kanal-Version
     */
    suspend fun setAlarmSound(uri: String?, name: String?): Int {
        var version = 0
        edit {
            version = (it[SettingsKeys.alarmChannelVersion] ?: 0) + 1
            it[SettingsKeys.alarmChannelVersion] = version
            if (uri == null) {
                it.remove(SettingsKeys.alarmSoundUri)
                it.remove(SettingsKeys.alarmSoundName)
            } else {
                it[SettingsKeys.alarmSoundUri] = uri
                if (name == null) it.remove(SettingsKeys.alarmSoundName) else it[SettingsKeys.alarmSoundName] = name
            }
        }
        return version
    }

    /** «Alarm-Signal» (Ton/Vibration der Kursalarme). */
    suspend fun setAlarmSignal(signal: AlarmSignal) = edit { it[SettingsKeys.alarmSignal] = signal.name }

    suspend fun setActivityAlerts(enabled: Boolean) = edit { it[SettingsKeys.activityAlerts] = enabled }
    suspend fun setActivitySensitivity(sensitivity: ActivitySensitivity) =
        edit { it[SettingsKeys.activitySensitivity] = sensitivity.name }
    suspend fun setMacroNotifications(enabled: Boolean) = edit { it[SettingsKeys.macroNotifications] = enabled }

    suspend fun setPortfolioEnabled(enabled: Boolean) = edit { it[SettingsKeys.portfolioEnabled] = enabled }

    /** Dreistelliger Währungscode, z. B. «CHF». */
    suspend fun setPortfolioCurrency(currency: String) = edit {
        val code = currency.trim().uppercase()
        if (code.length == 3 && code.all { c -> c in 'A'..'Z' }) it[SettingsKeys.portfolioCurrency] = code
    }

    /** Umgerechnete Kurse in der Merkliste zeigen. */
    suspend fun setShowConverted(show: Boolean) = edit { it[SettingsKeys.showConverted] = show }

    /** Kursfarben steigend/fallend. */
    suspend fun setPriceColorScheme(scheme: PriceColorScheme) = edit { it[SettingsKeys.priceColorScheme] = scheme.name }

    /** Mini-Chart in der Merkliste. */
    suspend fun setWatchlistSparkline(show: Boolean) = edit { it[SettingsKeys.watchlistSparkline] = show }
    suspend fun setWatchlistNames(show: Boolean) = edit { it[SettingsKeys.watchlistNames] = show }
    suspend fun setShowChangePeriod(show: Boolean) = edit { it[SettingsKeys.showChangePeriod] = show }
    suspend fun setAppIconBadge(show: Boolean) = edit { it[SettingsKeys.appIconBadge] = show }

    suspend fun setWatchlistActivityCard(show: Boolean) = edit { it[SettingsKeys.watchlistActivityCard] = show }

    /** Coin-Logos in der App. */
    suspend fun setCoinLogos(show: Boolean) = edit { it[SettingsKeys.coinLogos] = show }

    /** Coin-Logos in den Widgets. */
    suspend fun setWidgetCoinLogos(show: Boolean) = edit { it[SettingsKeys.widgetCoinLogos] = show }

    /** Coin-Logos im Portfolio. */
    suspend fun setPortfolioCoinLogos(show: Boolean) = edit { it[SettingsKeys.portfolioCoinLogos] = show }

    /** «Basis der %-Änderung». */
    suspend fun setChangeBasis(basis: ChangeBasis) = edit { it[SettingsKeys.changeBasis] = basis.name }

    /** Hoher Kontrast (Kursfarben und Nebentexte). */
    suspend fun setHighContrast(enabled: Boolean) = edit { it[SettingsKeys.highContrast] = enabled }

    /** Kursfarben tauschen (Rot = steigend). */
    suspend fun setPriceColorsInverted(inverted: Boolean) = edit { it[SettingsKeys.priceColorsInverted] = inverted }

    suspend fun setAboutSeen(seen: Boolean) = edit { it[SettingsKeys.aboutSeen] = seen }

    suspend fun setBatteryPromptSeen(seen: Boolean) = edit { it[SettingsKeys.batteryPromptSeen] = seen }

    /** Sortieren nach Spalte (gespeicherte Form); null = eigene Reihenfolge. */
    suspend fun setWatchlistColumnSort(value: String?) = edit {
        if (value == null) it.remove(SettingsKeys.watchlistColumnSort) else it[SettingsKeys.watchlistColumnSort] = value
    }

    /** null = «Alle». */
    suspend fun setWatchlistGroup(group: String?) = edit {
        if (group == null) {
            it.remove(SettingsKeys.watchlistGroup)
        } else {
            it[SettingsKeys.watchlistGroup] = group
        }
    }

    /** Nachtruhe ein/aus. */
    suspend fun setQuietHoursEnabled(enabled: Boolean) = edit { it[SettingsKeys.quietHoursEnabled] = enabled }

    /** Beginn der Nachtruhe (Minuten seit Mitternacht, 0..1439); ungültige Werte werden ignoriert. */
    suspend fun setQuietHoursStart(minute: Int) = edit {
        if (QuietHours.isValidMinute(minute)) it[SettingsKeys.quietHoursStart] = minute
    }

    /** Ende der Nachtruhe (Minuten seit Mitternacht, 0..1439); ungültige Werte werden ignoriert. */
    suspend fun setQuietHoursEnd(minute: Int) = edit {
        if (QuietHours.isValidMinute(minute)) it[SettingsKeys.quietHoursEnd] = minute
    }

    /** Portfolio-Sperre ein/aus. Ein- und (solange gesperrt) Ausschalten erst nach einer Entsperrung (siehe SettingsScreen). */
    suspend fun setAppLock(enabled: Boolean) = edit { it[SettingsKeys.appLock] = enabled }

    /** «Beträge verbergen» im Portfolio und im Portfolio-Widget. */
    suspend fun setHidePortfolioAmounts(hidden: Boolean) = edit { it[SettingsKeys.hidePortfolioAmounts] = hidden }

    /** «Portfolio in Systemsicherung» (PortfolioBackupMirror setzt bzw. entfernt den Merker für den Sicherungs-Agenten). */
    suspend fun setPortfolioSystemBackup(allowed: Boolean) = edit { it[SettingsKeys.portfolioSystemBackup] = allowed }

    /** Bestätigung nach dem ersten Alarm erledigt (nicht in der Sicherung). */
    suspend fun setFirstAlarmShown(shown: Boolean) = edit { it[SettingsKeys.firstAlarmShown] = shown }

    /** Hinweis «Wann Alarme geprüft werden» gezeigt (nicht in der Sicherung). */
    suspend fun setAlarmTimingHintShown(shown: Boolean) = edit { it[SettingsKeys.alarmTimingHintShown] = shown }

    /** Erstes Paar hinzugefügt (nicht in der Sicherung). */
    suspend fun setFirstPairAdded(added: Boolean) = edit { it[SettingsKeys.firstPairAdded] = added }

    /** Chart im Aktionsblatt: Linie (true) oder Kerzen (nicht in der Sicherung). */
    suspend fun setSheetChartLine(line: Boolean) = edit { it[SettingsKeys.sheetChartLine] = line }

    /** Wertverlauf im Portfolio auf- oder zugeklappt (nicht in der Sicherung). */
    suspend fun setPortfolioHistoryExpanded(expanded: Boolean) = edit { it[SettingsKeys.portfolioHistoryExpanded] = expanded }

    /** Zeitraum des Wertverlaufs (nicht in der Sicherung). */
    suspend fun setPortfolioHistoryRange(range: PortfolioHistoryRange) = edit { it[SettingsKeys.portfolioHistoryRange] = range.name }

    /** Darstellung des Wertverlaufs: Währung, USDT oder Vergleich (nicht in der Sicherung). */
    suspend fun setPortfolioHistoryView(view: PortfolioHistoryView) = edit { it[SettingsKeys.portfolioHistoryView] = view.name }

    /** Wertverlauf als Kerzen (true) oder Linie (nicht in der Sicherung). */
    suspend fun setPortfolioHistoryCandles(candles: Boolean) = edit { it[SettingsKeys.portfolioHistoryCandles] = candles }

    /** Wertverlauf als Gewinn/Verlust (true) oder Wert (nicht in der Sicherung). */
    suspend fun setPortfolioHistoryPnl(pnl: Boolean) = edit { it[SettingsKeys.portfolioHistoryPnl] = pnl }

    /** Einstandsmethode FIFO | Ø (nicht in der Sicherung). */
    suspend fun setCostMethod(method: CostMethod) = edit { it[SettingsKeys.costMethod] = method.name }

    /** Steuer-Vorlage der Arbeitsunterlage (in der Sicherung, «taxTemplate»). */
    suspend fun setTaxTemplate(template: com.cryptochecker.app.domain.portfolio.TaxTemplate) =
        edit { it[SettingsKeys.taxTemplate] = template.name }

    /** Hinweis zu den Länder-Vorlagen (Beta) bestätigt (nicht in der Sicherung). */
    suspend fun setTaxTemplateConfirmed() = edit { it[SettingsKeys.taxTemplateConfirmed] = true }

    /** Markt-Tab einmal gesehen — «Einordnung» und «Daten» beginnen danach zugeklappt (nicht in der Sicherung). */
    suspend fun setMarketTabSeen(seen: Boolean) = edit { it[SettingsKeys.marketTabSeen] = seen }

    /** «Mehr Marktdaten» im Markt-Tab auf- oder zugeklappt (nicht in der Sicherung). */
    suspend fun setMarketMoreExpanded(expanded: Boolean) = edit { it[SettingsKeys.marketMoreExpanded] = expanded }

    companion object {
        /** Schlüssel von «Wie aktuell?» und der früheren Schalter (auch für [UpdateModeMigration]). */
        const val KEY_UPDATE_MODE = "update_mode"
        const val KEY_BACKGROUND_UPDATES = "background_updates"
        const val KEY_LIVE_SERVICE = "live_service"
        const val KEY_LIVE_WEBSOCKET = "live_websocket"
        /** «Nicht wegwischbar» (auch für [OngoingNotificationsMigration]). */
        const val KEY_ONGOING_NOTIFICATIONS = "ongoing_notifications"
        /** «Coin-Namen», «Umgerechnete Kurse», «inkl. Alpha-Tokens» (auch für [InstallDefaultsMigration]). */
        const val KEY_WATCHLIST_NAMES = "watchlist_names"
        const val KEY_SHOW_CONVERTED = "show_converted"
        const val KEY_INCLUDE_ALPHA_TOKENS = "include_alpha_tokens"
        /** Portfolio-Tab (auch für [InstallDefaultsMigration], Build 44). */
        const val KEY_PORTFOLIO_ENABLED = "portfolio_enabled"
        /** Schlüssel, die die Migrationen vor [UpdateModeMigration] auch bei neuen Installationen schreiben. */
        val MIGRATION_WRITTEN_KEYS = setOf(
            KEY_ONGOING_NOTIFICATIONS, KEY_WATCHLIST_NAMES, KEY_SHOW_CONVERTED, KEY_INCLUDE_ALPHA_TOKENS,
            KEY_PORTFOLIO_ENABLED,
        )
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit(block)
    }
}
