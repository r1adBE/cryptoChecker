package com.cryptochecker.app.settings

import com.cryptochecker.app.domain.market.IndicatorAlertKind
import android.content.Context
import androidx.datastore.preferences.core.edit
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import com.cryptochecker.app.data.local.StorageRecoveryNotice
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Einstellungen lesen und schreiben. Ablage in `SettingsDataStore.kt`, Schlüssel in [SettingsKeys].
 */
@Singleton
class SettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : SettingsBackupTarget {

    // Lesefehler (I/O): Standardwerte statt Absturz; Portfolio-Sperre dann und nach beschädigter Datei an ([SettingsLockFallback])
    val settings: Flow<AppSettings> = SettingsLockFallback.settings(context, context.settingsDataStore.data)
        .map { read ->
            read.also {
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

    /** true, sobald [cached] einmal aus der Datei gelesen wurde (Start ohne runBlocking). */
    @Volatile
    var loaded: Boolean = false
        private set

    override suspend fun current(): AppSettings = settings.first()

    /** «Wie aktuell?». */
    override suspend fun setUpdateMode(mode: UpdateMode) = edit { it[SettingsKeys.updateMode] = mode.name }

    override suspend fun setBackgroundInterval(minutes: Int) = edit {
        it[SettingsKeys.backgroundInterval] = AppSettings.clampBackgroundInterval(minutes)
    }

    suspend fun setExactChecks(enabled: Boolean) = edit { it[SettingsKeys.exactChecks] = enabled }

    /** Preis-Overlay ein/aus (gerätebezogen). */
    suspend fun setPriceOverlay(enabled: Boolean) = edit { it[SettingsKeys.priceOverlay] = enabled }

    override suspend fun setPriceNotifications(enabled: Boolean) = edit { it[SettingsKeys.priceNotifications] = enabled }

    override suspend fun setOngoingNotifications(enabled: Boolean) = edit { it[SettingsKeys.ongoingNotifications] = enabled }

    override suspend fun setNotificationChangePercent(percent: Double) = edit {
        it[SettingsKeys.notificationChange] = percent.coerceIn(0.0, 100.0)
    }

    override suspend fun setTtsEnabled(enabled: Boolean) = edit { it[SettingsKeys.tts] = enabled }

    override suspend fun setTtsAlarmsOnly(enabled: Boolean) = edit { it[SettingsKeys.ttsAlarmsOnly] = enabled }

    override suspend fun setTtsSpeechRate(rate: Float) = edit {
        it[SettingsKeys.ttsSpeechRate] = rate.coerceIn(0.5f, 2.0f)
    }

    override suspend fun setAlarmCooldown(minutes: Int) = edit {
        it[SettingsKeys.alarmCooldown] = AppSettings.clampAlarmCooldown(minutes)
    }

    override suspend fun setIncludeRollingFutures(enabled: Boolean) =
        edit { it[SettingsKeys.includeRollingFutures] = enabled }

    override suspend fun setIncludeAlphaTokens(enabled: Boolean) =
        edit { it[SettingsKeys.includeAlphaTokens] = enabled }

    override suspend fun setAssetScope(scope: AssetScope) = edit { it[SettingsKeys.assetScope] = scope.name }

    override suspend fun setAccentColor(accent: AccentColor) = edit { it[SettingsKeys.accentColor] = accent.name }

    /** null = wie das System. */
    override suspend fun setDarkMode(dark: Boolean?) = edit {
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

    // Markt-Meldungen: ausgeschaltet → gemerkten Stand vergessen (MarketAlertReset), sonst bliebe das Wiedereinschalten
    // mit derselben Grenze stumm, wenn während «Aus» keine Prüfung lief
    override suspend fun setFearGreedBelow(value: Int) {
        edit { it[SettingsKeys.fearGreedBelow] = value.coerceIn(0, 100) }
        if (value <= 0) MarketAlertReset.fearGreedOff(context, below = true)
    }

    override suspend fun setFearGreedAbove(value: Int) {
        edit { it[SettingsKeys.fearGreedAbove] = value.coerceIn(0, 100) }
        if (value <= 0) MarketAlertReset.fearGreedOff(context, below = false)
    }

    /** Eigene Grenze einer Bitcoin-Kennzahl; nur Werte aus [IndicatorAlertKind.choices], sonst aus. */
    override suspend fun setIndicatorAlert(kind: IndicatorAlertKind, value: Int) {
        val stored = value.takeIf { v -> v in kind.choices } ?: 0
        edit { it[SettingsKeys.indicatorAlert(kind)] = stored }
        if (stored <= 0) MarketAlertReset.indicatorOff(context, kind)
    }

    override suspend fun setGasAlertEth(tenths: Int) {
        edit { it[SettingsKeys.gasAlertEth] = tenths.coerceIn(0, 10_000) }
        if (tenths <= 0) MarketAlertReset.gasOff(context, eth = true)
    }

    override suspend fun setGasAlertBtc(satPerVb: Int) {
        edit { it[SettingsKeys.gasAlertBtc] = satPerVb.coerceIn(0, 10_000) }
        if (satPerVb <= 0) MarketAlertReset.gasOff(context, eth = false)
    }

    override suspend fun setDepegAlarm(enabled: Boolean) = edit { it[SettingsKeys.depegAlarm] = enabled }

    /** Nur Werte aus [DepegAlarm.THRESHOLD_CHOICES], sonst Standard. */
    override suspend fun setDepegThreshold(tenths: Int) = edit { it[SettingsKeys.depegThreshold] = DepegAlarm.threshold(tenths) }

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
    override suspend fun setAlarmSignal(signal: AlarmSignal) = edit { it[SettingsKeys.alarmSignal] = signal.name }

    override suspend fun setActivityAlerts(enabled: Boolean) = edit { it[SettingsKeys.activityAlerts] = enabled }
    override suspend fun setActivitySensitivity(sensitivity: ActivitySensitivity) =
        edit { it[SettingsKeys.activitySensitivity] = sensitivity.name }
    override suspend fun setMacroNotifications(enabled: Boolean) = edit { it[SettingsKeys.macroNotifications] = enabled }

    override suspend fun setPortfolioEnabled(enabled: Boolean) = edit { it[SettingsKeys.portfolioEnabled] = enabled }

    /** Dreistelliger Währungscode, z. B. «CHF». */
    override suspend fun setPortfolioCurrency(currency: String) = edit {
        val code = currency.trim().uppercase()
        if (code.length == 3 && code.all { c -> c in 'A'..'Z' }) it[SettingsKeys.portfolioCurrency] = code
    }

    /** Umgerechnete Kurse in der Merkliste zeigen. */
    override suspend fun setShowConverted(show: Boolean) = edit { it[SettingsKeys.showConverted] = show }

    /** Kursfarben steigend/fallend. */
    override suspend fun setPriceColorScheme(scheme: PriceColorScheme) = edit { it[SettingsKeys.priceColorScheme] = scheme.name }

    /** Mini-Chart in der Merkliste. */
    override suspend fun setWatchlistSparkline(show: Boolean) = edit { it[SettingsKeys.watchlistSparkline] = show }
    override suspend fun setWatchlistNames(show: Boolean) = edit { it[SettingsKeys.watchlistNames] = show }
    override suspend fun setAppIconBadge(show: Boolean) = edit { it[SettingsKeys.appIconBadge] = show }

    override suspend fun setWatchlistActivityCard(show: Boolean) = edit { it[SettingsKeys.watchlistActivityCard] = show }

    /** Coin-Logos in der App. */
    override suspend fun setCoinLogos(show: Boolean) = edit { it[SettingsKeys.coinLogos] = show }

    /** Coin-Logos in den Widgets. */
    override suspend fun setWidgetCoinLogos(show: Boolean) = edit { it[SettingsKeys.widgetCoinLogos] = show }

    /** Coin-Logos im Portfolio. */
    override suspend fun setPortfolioCoinLogos(show: Boolean) = edit { it[SettingsKeys.portfolioCoinLogos] = show }

    /** «Basis der %-Änderung». */
    override suspend fun setChangeBasis(basis: ChangeBasis) = edit { it[SettingsKeys.changeBasis] = basis.name }

    /** Hoher Kontrast (Kursfarben und Nebentexte). */
    override suspend fun setHighContrast(enabled: Boolean) = edit { it[SettingsKeys.highContrast] = enabled }

    /** Kursfarben tauschen (Rot = steigend). */
    override suspend fun setPriceColorsInverted(inverted: Boolean) = edit { it[SettingsKeys.priceColorsInverted] = inverted }

    suspend fun setAboutSeen(seen: Boolean) = edit { it[SettingsKeys.aboutSeen] = seen }

    suspend fun setBatteryPromptSeen(seen: Boolean) = edit { it[SettingsKeys.batteryPromptSeen] = seen }

    /**
     * Spalten-Sortierung aus dem gespeicherten Stand weiterschalten — in einem Schreibvorgang, damit schnelle Tipps nie
     * einen veralteten Wert lesen (sonst schrieb der dritte Tipp den zweiten Zustand nochmals, statt aufzuheben).
     */
    suspend fun updateWatchlistColumnSort(next: (String?) -> String?) = edit {
        val value = next(it[SettingsKeys.watchlistColumnSort])
        if (value == null) it.remove(SettingsKeys.watchlistColumnSort) else it[SettingsKeys.watchlistColumnSort] = value
    }

    /** Sortieren nach Spalte (gespeicherte Form); null = eigene Reihenfolge. */
    override suspend fun setWatchlistColumnSort(value: String?) = edit {
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
    override suspend fun setQuietHoursEnabled(enabled: Boolean) = edit { it[SettingsKeys.quietHoursEnabled] = enabled }

    /** Beginn der Nachtruhe (Minuten seit Mitternacht, 0..1439); ungültige Werte werden ignoriert. */
    override suspend fun setQuietHoursStart(minute: Int) = edit {
        if (QuietHours.isValidMinute(minute)) it[SettingsKeys.quietHoursStart] = minute
    }

    /** Ende der Nachtruhe (Minuten seit Mitternacht, 0..1439); ungültige Werte werden ignoriert. */
    override suspend fun setQuietHoursEnd(minute: Int) = edit {
        if (QuietHours.isValidMinute(minute)) it[SettingsKeys.quietHoursEnd] = minute
    }

    /** Portfolio-Sperre ein/aus. Ein- und (solange gesperrt) Ausschalten erst nach einer Entsperrung (siehe SettingsScreen). */
    override suspend fun setAppLock(enabled: Boolean) {
        edit { it[SettingsKeys.appLock] = enabled }
        // Selbst gesetzt: nach beschädigten Einstellungen nicht mehr «unbekannt» (SettingsLockFallback)
        withContext(Dispatchers.IO) { StorageRecoveryNotice.confirmAppLock(context) }
    }

    /** «Beträge verbergen» im Portfolio und im Portfolio-Widget. */
    override suspend fun setHidePortfolioAmounts(hidden: Boolean) = edit { it[SettingsKeys.hidePortfolioAmounts] = hidden }

    /** «Portfolio in Systemsicherung» (PortfolioBackupMirror setzt bzw. entfernt den Merker für den Sicherungs-Agenten). */
    override suspend fun setPortfolioSystemBackup(allowed: Boolean) = edit { it[SettingsKeys.portfolioSystemBackup] = allowed }

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
    override suspend fun setCostMethod(method: CostMethod) = edit { it[SettingsKeys.costMethod] = method.name }

    /** Steuer-Vorlage der Arbeitsunterlage (in der Sicherung, «taxTemplate»). */
    override suspend fun setTaxTemplate(template: com.cryptochecker.app.domain.portfolio.TaxTemplate) =
        edit { it[SettingsKeys.taxTemplate] = template.name }

    /** Eigener Bitcoin-Server für Adressabfragen ([AddressServerStore]; in der Sicherung, «addressServerBtc»). */
    val addressServerFlow: Flow<String?> = AddressServerStore.flow(context.settingsDataStore)
    override suspend fun addressServer(): String? = addressServerFlow.first()
    override suspend fun setAddressServer(url: String?) = AddressServerStore.set(context.settingsDataStore, url)

    /** Hinweis zu den Länder-Vorlagen (Beta) bestätigt (nicht in der Sicherung). */
    suspend fun setTaxTemplateConfirmed() = edit { it[SettingsKeys.taxTemplateConfirmed] = true }

    /** Markt-Tab einmal gesehen — «Einordnung» und «Daten» beginnen danach zugeklappt (nicht in der Sicherung). */
    suspend fun setMarketTabSeen(seen: Boolean) = edit { it[SettingsKeys.marketTabSeen] = seen }

    /** «Mehr Marktdaten» im Markt-Tab auf- oder zugeklappt (nicht in der Sicherung). */
    suspend fun setMarketMoreExpanded(expanded: Boolean) = edit { it[SettingsKeys.marketMoreExpanded] = expanded }

    companion object {
        /** «Wie aktuell?». */
        const val KEY_UPDATE_MODE = "update_mode"
        /** «Nicht wegwischbar». */
        const val KEY_ONGOING_NOTIFICATIONS = "ongoing_notifications"
        /** «Coin-Namen», «Umgerechnete Kurse», «inkl. Alpha-Tokens». */
        const val KEY_WATCHLIST_NAMES = "watchlist_names"
        const val KEY_SHOW_CONVERTED = "show_converted"
        const val KEY_INCLUDE_ALPHA_TOKENS = "include_alpha_tokens"
        /** Portfolio-Tab. */
        const val KEY_PORTFOLIO_ENABLED = "portfolio_enabled"
        /** Merker «Portfolio-Daten prüfen» nach neu angelegten Einstellungen ([PortfolioDataCheck]). */
        const val KEY_PORTFOLIO_DATA_CHECK = "portfolio_data_check"
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit(block)
    }
}
