package com.cryptochecker.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cryptochecker.app.domain.activity.ActivitySensitivity
import com.cryptochecker.app.domain.alarm.AlarmSignal
import com.cryptochecker.app.domain.alarm.QuietHours
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryRange
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryView
import com.cryptochecker.app.domain.watch.ChangeBasis
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Beschädigte Datei (z. B. halb geschrieben oder aus einer Systemsicherung wiederhergestellt):
 * durch leere Einstellungen ersetzen (= Standardwerte) statt bei jedem Start abzustürzen.
 */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler { e ->
        Timber.w(e, "Einstellungen beschädigt – Standardwerte")
        emptyPreferences()
    },
)

@Singleton
class SettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private object Keys {
        val backgroundUpdates = booleanPreferencesKey("background_updates")
        val backgroundInterval = intPreferencesKey("background_interval_minutes")
        val liveService = booleanPreferencesKey("live_service")
        val liveInterval = intPreferencesKey("live_interval_seconds")
        val liveWebSocket = booleanPreferencesKey("live_websocket")
        val priceNotifications = booleanPreferencesKey("price_notifications")
        val ongoingNotifications = booleanPreferencesKey("ongoing_notifications")
        /** Früher ganze Prozent; wird nur noch gelesen, falls der neue Wert fehlt. */
        val notificationChangeLegacy = intPreferencesKey("notification_change_percent")
        /** Freie Eingabe mit Nachkommastellen, z. B. 2,5 %. */
        val notificationChange = doublePreferencesKey("notification_change_percent_exact")
        val tts = booleanPreferencesKey("tts_enabled")
        val ttsAlarmsOnly = booleanPreferencesKey("tts_alarms_only")
        val ttsSpeechRate = floatPreferencesKey("tts_speech_rate")
        val alarmCooldown = intPreferencesKey("alarm_cooldown_minutes")
        val includeRollingFutures = booleanPreferencesKey("include_rolling_futures")
        val includeTradFiFutures = booleanPreferencesKey("include_tradfi_futures")
        val accentColor = stringPreferencesKey("accent_color")
        val darkMode = booleanPreferencesKey("dark_mode")
        val showHttpLog = booleanPreferencesKey("show_http_log")
        val developerUnlocked = booleanPreferencesKey("developer_unlocked")
        val gestureHintSeen = booleanPreferencesKey("gesture_hint_seen")
        val zoneAlerts = booleanPreferencesKey("zone_alerts")
        val fearGreedBelow = intPreferencesKey("fear_greed_below")
        val fearGreedAbove = intPreferencesKey("fear_greed_above")
        val aboutSeen = booleanPreferencesKey("about_seen")
        val batteryPromptSeen = booleanPreferencesKey("battery_prompt_seen")
        val watchlistGroup = stringPreferencesKey("watchlist_group")
        val watchlistColumnSort = stringPreferencesKey("watchlist_column_sort")
        val activityAlerts = booleanPreferencesKey("activity_alerts")
        val activitySensitivity = stringPreferencesKey("activity_sensitivity")
        val macroNotifications = booleanPreferencesKey("macro_notifications")
        val portfolioEnabled = booleanPreferencesKey("portfolio_enabled")
        val portfolioCurrency = stringPreferencesKey("portfolio_currency")
        val showConverted = booleanPreferencesKey("show_converted")
        val priceColorScheme = stringPreferencesKey("price_color_scheme")
        val watchlistSparkline = booleanPreferencesKey("watchlist_sparkline")
        val watchlistNames = booleanPreferencesKey("watchlist_names")
        val showChangePeriod = booleanPreferencesKey("show_change_period")
        val appIconBadge = booleanPreferencesKey("app_icon_badge")
        val watchlistActivityCard = booleanPreferencesKey("watchlist_activity_card")
        val coinLogos = booleanPreferencesKey("coin_logos")
        val widgetCoinLogos = booleanPreferencesKey("widget_coin_logos")
        val portfolioCoinLogos = booleanPreferencesKey("portfolio_coin_logos")
        val changeBasis = stringPreferencesKey("change_basis")
        val highContrast = booleanPreferencesKey("high_contrast")
        val priceColorsInverted = booleanPreferencesKey("price_colors_inverted")
        val gasAlertEth = intPreferencesKey("gas_alert_eth_tenths")
        val gasAlertBtc = intPreferencesKey("gas_alert_btc")
        val alarmSoundUri = stringPreferencesKey("alarm_sound_uri")
        val alarmSoundName = stringPreferencesKey("alarm_sound_name")
        val alarmChannelVersion = intPreferencesKey("alarm_channel_version")
        val alarmSignal = stringPreferencesKey("alarm_signal")
        val quietHoursEnabled = booleanPreferencesKey("quiet_hours_enabled")
        val quietHoursStart = intPreferencesKey("quiet_hours_start")
        val quietHoursEnd = intPreferencesKey("quiet_hours_end")
        val appLock = booleanPreferencesKey("app_lock")
        val hidePortfolioAmounts = booleanPreferencesKey("hide_portfolio_amounts")
        val portfolioSystemBackup = booleanPreferencesKey("portfolio_system_backup")
        val firstAlarmShown = booleanPreferencesKey("first_alarm_shown")
        val firstPairAdded = booleanPreferencesKey("first_pair_added")
        val sheetChartLine = booleanPreferencesKey("sheet_chart_line")
        val portfolioHistoryExpanded = booleanPreferencesKey("portfolio_history_expanded")
        val portfolioHistoryRange = stringPreferencesKey("portfolio_history_range")
        val portfolioHistoryView = stringPreferencesKey("portfolio_history_view")
        val marketTabSeen = booleanPreferencesKey("market_tab_seen")
    }

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
            backgroundUpdates = prefs[Keys.backgroundUpdates] ?: defaults.backgroundUpdates,
            backgroundIntervalMinutes = prefs[Keys.backgroundInterval]
                ?: defaults.backgroundIntervalMinutes,
            liveService = prefs[Keys.liveService] ?: defaults.liveService,
            liveIntervalSeconds = prefs[Keys.liveInterval] ?: defaults.liveIntervalSeconds,
            liveWebSocket = prefs[Keys.liveWebSocket] ?: defaults.liveWebSocket,
            priceNotifications = prefs[Keys.priceNotifications] ?: defaults.priceNotifications,
            ongoingNotifications = prefs[Keys.ongoingNotifications]
                ?: defaults.ongoingNotifications,
            notificationChangePercent = prefs[Keys.notificationChange]
                ?: prefs[Keys.notificationChangeLegacy]?.toDouble()
                ?: defaults.notificationChangePercent,
            ttsEnabled = prefs[Keys.tts] ?: defaults.ttsEnabled,
            ttsAlarmsOnly = prefs[Keys.ttsAlarmsOnly] ?: defaults.ttsAlarmsOnly,
            ttsSpeechRate = prefs[Keys.ttsSpeechRate] ?: defaults.ttsSpeechRate,
            alarmCooldownMinutes = prefs[Keys.alarmCooldown] ?: defaults.alarmCooldownMinutes,
            includeRollingFutures = prefs[Keys.includeRollingFutures]
                ?: defaults.includeRollingFutures,
            includeTradFiFutures = prefs[Keys.includeTradFiFutures] ?: defaults.includeTradFiFutures,
            accentColor = AccentColor.fromName(prefs[Keys.accentColor]),
            darkMode = prefs[Keys.darkMode],
            showHttpLog = prefs[Keys.showHttpLog] ?: defaults.showHttpLog,
            developerUnlocked = prefs[Keys.developerUnlocked] ?: defaults.developerUnlocked,
            gestureHintSeen = prefs[Keys.gestureHintSeen] ?: defaults.gestureHintSeen,
            zoneAlerts = prefs[Keys.zoneAlerts] ?: defaults.zoneAlerts,
            fearGreedBelow = prefs[Keys.fearGreedBelow] ?: defaults.fearGreedBelow,
            fearGreedAbove = prefs[Keys.fearGreedAbove] ?: defaults.fearGreedAbove,
            aboutSeen = prefs[Keys.aboutSeen] ?: defaults.aboutSeen,
            batteryPromptSeen = prefs[Keys.batteryPromptSeen] ?: defaults.batteryPromptSeen,
            watchlistGroup = prefs[Keys.watchlistGroup],
            watchlistColumnSort = prefs[Keys.watchlistColumnSort],
            activityAlerts = prefs[Keys.activityAlerts] ?: defaults.activityAlerts,
            activitySensitivity = ActivitySensitivity.fromName(prefs[Keys.activitySensitivity]),
            macroNotifications = prefs[Keys.macroNotifications] ?: defaults.macroNotifications,
            portfolioEnabled = prefs[Keys.portfolioEnabled] ?: defaults.portfolioEnabled,
            portfolioCurrency = prefs[Keys.portfolioCurrency] ?: defaults.portfolioCurrency,
            showConverted = prefs[Keys.showConverted] ?: defaults.showConverted,
            priceColorScheme = PriceColorScheme.fromName(prefs[Keys.priceColorScheme]),
            watchlistSparkline = prefs[Keys.watchlistSparkline] ?: defaults.watchlistSparkline,
            watchlistNames = prefs[Keys.watchlistNames] ?: defaults.watchlistNames,
            showChangePeriod = prefs[Keys.showChangePeriod] ?: defaults.showChangePeriod,
            appIconBadge = prefs[Keys.appIconBadge] ?: defaults.appIconBadge,
            watchlistActivityCard = prefs[Keys.watchlistActivityCard] ?: defaults.watchlistActivityCard,
            coinLogos = prefs[Keys.coinLogos] ?: defaults.coinLogos,
            widgetCoinLogos = prefs[Keys.widgetCoinLogos] ?: defaults.widgetCoinLogos,
            portfolioCoinLogos = prefs[Keys.portfolioCoinLogos] ?: defaults.portfolioCoinLogos,
            changeBasis = ChangeBasis.fromName(prefs[Keys.changeBasis]),
            highContrast = prefs[Keys.highContrast] ?: defaults.highContrast,
            // Nie gesetzt: Standard nach Region des Geräts
            priceColorsInverted = prefs[Keys.priceColorsInverted] ?: defaults.priceColorsInverted,
            gasAlertEthTenths = prefs[Keys.gasAlertEth] ?: defaults.gasAlertEthTenths,
            gasAlertBtc = prefs[Keys.gasAlertBtc] ?: defaults.gasAlertBtc,
            alarmSoundUri = prefs[Keys.alarmSoundUri],
            alarmSoundName = prefs[Keys.alarmSoundName],
            alarmChannelVersion = prefs[Keys.alarmChannelVersion] ?: defaults.alarmChannelVersion,
            alarmSignal = AlarmSignal.fromName(prefs[Keys.alarmSignal]),
            quietHoursEnabled = prefs[Keys.quietHoursEnabled] ?: defaults.quietHoursEnabled,
            quietHoursStart = prefs[Keys.quietHoursStart]?.takeIf { QuietHours.isValidMinute(it) }
                ?: defaults.quietHoursStart,
            quietHoursEnd = prefs[Keys.quietHoursEnd]?.takeIf { QuietHours.isValidMinute(it) }
                ?: defaults.quietHoursEnd,
            appLock = prefs[Keys.appLock] ?: defaults.appLock,
            hidePortfolioAmounts = prefs[Keys.hidePortfolioAmounts] ?: defaults.hidePortfolioAmounts,
            portfolioSystemBackup = prefs[Keys.portfolioSystemBackup] ?: defaults.portfolioSystemBackup,
            firstAlarmShown = prefs[Keys.firstAlarmShown] ?: defaults.firstAlarmShown,
            firstPairAdded = prefs[Keys.firstPairAdded] ?: defaults.firstPairAdded,
            sheetChartLine = prefs[Keys.sheetChartLine] ?: defaults.sheetChartLine,
            portfolioHistoryExpanded = prefs[Keys.portfolioHistoryExpanded] ?: defaults.portfolioHistoryExpanded,
            portfolioHistoryRange = PortfolioHistoryRange.fromName(prefs[Keys.portfolioHistoryRange]),
            portfolioHistoryView = PortfolioHistoryView.fromName(prefs[Keys.portfolioHistoryView]),
            marketTabSeen = prefs[Keys.marketTabSeen] ?: defaults.marketTabSeen,
        ).also { cached = it }
    }

    /**
     * Zuletzt gelesene Einstellungen, ohne zu warten. Für Stellen, die nicht
     * suspendieren können (z. B. Farbe einer Benachrichtigung). Vor dem ersten
     * Lesen die Standardwerte.
     */
    @Volatile
    var cached: AppSettings = AppSettings()
        private set

    suspend fun current(): AppSettings = settings.first()

    suspend fun setBackgroundUpdates(enabled: Boolean) = edit { it[Keys.backgroundUpdates] = enabled }

    suspend fun setBackgroundInterval(minutes: Int) = edit {
        it[Keys.backgroundInterval] =
            minutes.coerceAtLeast(AppSettings.MIN_BACKGROUND_INTERVAL_MINUTES)
    }

    suspend fun setLiveService(enabled: Boolean) = edit { it[Keys.liveService] = enabled }

    suspend fun setLiveInterval(seconds: Int) = edit {
        it[Keys.liveInterval] = seconds.coerceAtLeast(AppSettings.MIN_LIVE_INTERVAL_SECONDS)
    }

    suspend fun setLiveWebSocket(enabled: Boolean) = edit { it[Keys.liveWebSocket] = enabled }

    suspend fun setPriceNotifications(enabled: Boolean) = edit { it[Keys.priceNotifications] = enabled }

    suspend fun setOngoingNotifications(enabled: Boolean) = edit { it[Keys.ongoingNotifications] = enabled }

    suspend fun setNotificationChangePercent(percent: Double) = edit {
        it[Keys.notificationChange] = percent.coerceIn(0.0, 100.0)
    }

    suspend fun setTtsEnabled(enabled: Boolean) = edit { it[Keys.tts] = enabled }

    suspend fun setTtsAlarmsOnly(enabled: Boolean) = edit { it[Keys.ttsAlarmsOnly] = enabled }

    suspend fun setTtsSpeechRate(rate: Float) = edit {
        it[Keys.ttsSpeechRate] = rate.coerceIn(0.5f, 2.0f)
    }

    suspend fun setAlarmCooldown(minutes: Int) = edit {
        it[Keys.alarmCooldown] = minutes.coerceAtLeast(0)
    }

    suspend fun setIncludeRollingFutures(enabled: Boolean) =
        edit { it[Keys.includeRollingFutures] = enabled }

    suspend fun setIncludeTradFiFutures(enabled: Boolean) =
        edit { it[Keys.includeTradFiFutures] = enabled }

    suspend fun setAccentColor(accent: AccentColor) = edit { it[Keys.accentColor] = accent.name }

    /** null = wie das System. */
    suspend fun setDarkMode(dark: Boolean?) = edit {
        if (dark == null) {
            it.remove(Keys.darkMode)
        } else {
            it[Keys.darkMode] = dark
        }
    }

    suspend fun setShowHttpLog(show: Boolean) = edit { it[Keys.showHttpLog] = show }

    suspend fun setDeveloperUnlocked(unlocked: Boolean) = edit { it[Keys.developerUnlocked] = unlocked }

    suspend fun setGestureHintSeen(seen: Boolean) = edit { it[Keys.gestureHintSeen] = seen }

    suspend fun setZoneAlerts(enabled: Boolean) = edit { it[Keys.zoneAlerts] = enabled }

    suspend fun setFearGreedBelow(value: Int) = edit { it[Keys.fearGreedBelow] = value.coerceIn(0, 100) }

    suspend fun setFearGreedAbove(value: Int) = edit { it[Keys.fearGreedAbove] = value.coerceIn(0, 100) }

    suspend fun setGasAlertEth(tenths: Int) = edit { it[Keys.gasAlertEth] = tenths.coerceIn(0, 10_000) }

    suspend fun setGasAlertBtc(satPerVb: Int) = edit { it[Keys.gasAlertBtc] = satPerVb.coerceIn(0, 10_000) }

    /**
     * Alarmton setzen (null = Standardton). Erhöht die Kanal-Version, damit
     * [com.cryptochecker.app.notification.NotificationChannels] einen neuen Kanal anlegt.
     * @return die neue Kanal-Version
     */
    suspend fun setAlarmSound(uri: String?, name: String?): Int {
        var version = 0
        edit {
            version = (it[Keys.alarmChannelVersion] ?: 0) + 1
            it[Keys.alarmChannelVersion] = version
            if (uri == null) {
                it.remove(Keys.alarmSoundUri)
                it.remove(Keys.alarmSoundName)
            } else {
                it[Keys.alarmSoundUri] = uri
                if (name == null) it.remove(Keys.alarmSoundName) else it[Keys.alarmSoundName] = name
            }
        }
        return version
    }

    /** «Alarm-Signal» (Ton/Vibration der Kursalarme). */
    suspend fun setAlarmSignal(signal: AlarmSignal) = edit { it[Keys.alarmSignal] = signal.name }

    suspend fun setActivityAlerts(enabled: Boolean) = edit { it[Keys.activityAlerts] = enabled }
    suspend fun setActivitySensitivity(sensitivity: ActivitySensitivity) =
        edit { it[Keys.activitySensitivity] = sensitivity.name }
    suspend fun setMacroNotifications(enabled: Boolean) = edit { it[Keys.macroNotifications] = enabled }

    suspend fun setPortfolioEnabled(enabled: Boolean) = edit { it[Keys.portfolioEnabled] = enabled }

    /** Dreistelliger Währungscode, z. B. «CHF». */
    suspend fun setPortfolioCurrency(currency: String) = edit {
        val code = currency.trim().uppercase()
        if (code.length == 3 && code.all { c -> c in 'A'..'Z' }) it[Keys.portfolioCurrency] = code
    }

    /** Umgerechnete Kurse in der Merkliste zeigen. */
    suspend fun setShowConverted(show: Boolean) = edit { it[Keys.showConverted] = show }

    /** Kursfarben steigend/fallend. */
    suspend fun setPriceColorScheme(scheme: PriceColorScheme) = edit { it[Keys.priceColorScheme] = scheme.name }

    /** Mini-Chart in der Merkliste. */
    suspend fun setWatchlistSparkline(show: Boolean) = edit { it[Keys.watchlistSparkline] = show }
    suspend fun setWatchlistNames(show: Boolean) = edit { it[Keys.watchlistNames] = show }
    suspend fun setShowChangePeriod(show: Boolean) = edit { it[Keys.showChangePeriod] = show }
    suspend fun setAppIconBadge(show: Boolean) = edit { it[Keys.appIconBadge] = show }

    suspend fun setWatchlistActivityCard(show: Boolean) = edit { it[Keys.watchlistActivityCard] = show }

    /** Coin-Logos in der App. */
    suspend fun setCoinLogos(show: Boolean) = edit { it[Keys.coinLogos] = show }

    /** Coin-Logos in den Widgets. */
    suspend fun setWidgetCoinLogos(show: Boolean) = edit { it[Keys.widgetCoinLogos] = show }

    /** Coin-Logos im Portfolio. */
    suspend fun setPortfolioCoinLogos(show: Boolean) = edit { it[Keys.portfolioCoinLogos] = show }

    /** «Basis der %-Änderung». */
    suspend fun setChangeBasis(basis: ChangeBasis) = edit { it[Keys.changeBasis] = basis.name }

    /** Hoher Kontrast (Kursfarben und Nebentexte). */
    suspend fun setHighContrast(enabled: Boolean) = edit { it[Keys.highContrast] = enabled }

    /** Kursfarben tauschen (Rot = steigend). */
    suspend fun setPriceColorsInverted(inverted: Boolean) = edit { it[Keys.priceColorsInverted] = inverted }

    suspend fun setAboutSeen(seen: Boolean) = edit { it[Keys.aboutSeen] = seen }

    suspend fun setBatteryPromptSeen(seen: Boolean) = edit { it[Keys.batteryPromptSeen] = seen }

    /** Sortieren nach Spalte (gespeicherte Form); null = eigene Reihenfolge. */
    suspend fun setWatchlistColumnSort(value: String?) = edit {
        if (value == null) it.remove(Keys.watchlistColumnSort) else it[Keys.watchlistColumnSort] = value
    }

    /** null = «Alle». */
    suspend fun setWatchlistGroup(group: String?) = edit {
        if (group == null) {
            it.remove(Keys.watchlistGroup)
        } else {
            it[Keys.watchlistGroup] = group
        }
    }

    /** Nachtruhe ein/aus. */
    suspend fun setQuietHoursEnabled(enabled: Boolean) = edit { it[Keys.quietHoursEnabled] = enabled }

    /** Beginn der Nachtruhe (Minuten seit Mitternacht, 0..1439); ungültige Werte werden ignoriert. */
    suspend fun setQuietHoursStart(minute: Int) = edit {
        if (QuietHours.isValidMinute(minute)) it[Keys.quietHoursStart] = minute
    }

    /** Ende der Nachtruhe (Minuten seit Mitternacht, 0..1439); ungültige Werte werden ignoriert. */
    suspend fun setQuietHoursEnd(minute: Int) = edit {
        if (QuietHours.isValidMinute(minute)) it[Keys.quietHoursEnd] = minute
    }

    /** Portfolio-Sperre ein/aus. Ein- und (solange gesperrt) Ausschalten erst nach einer Entsperrung (siehe SettingsScreen). */
    suspend fun setAppLock(enabled: Boolean) = edit { it[Keys.appLock] = enabled }

    /** «Beträge verbergen» im Portfolio und im Portfolio-Widget. */
    suspend fun setHidePortfolioAmounts(hidden: Boolean) = edit { it[Keys.hidePortfolioAmounts] = hidden }

    /** «Portfolio in Systemsicherung» (PortfolioBackupMirror schreibt bzw. löscht die Kopie). */
    suspend fun setPortfolioSystemBackup(allowed: Boolean) = edit { it[Keys.portfolioSystemBackup] = allowed }

    /** Bestätigung nach dem ersten Alarm erledigt (nicht in der Sicherung). */
    suspend fun setFirstAlarmShown(shown: Boolean) = edit { it[Keys.firstAlarmShown] = shown }

    /** Erstes Paar hinzugefügt (nicht in der Sicherung). */
    suspend fun setFirstPairAdded(added: Boolean) = edit { it[Keys.firstPairAdded] = added }

    /** Chart im Aktionsblatt: Linie (true) oder Kerzen (nicht in der Sicherung). */
    suspend fun setSheetChartLine(line: Boolean) = edit { it[Keys.sheetChartLine] = line }

    /** Wertverlauf im Portfolio auf- oder zugeklappt (nicht in der Sicherung). */
    suspend fun setPortfolioHistoryExpanded(expanded: Boolean) = edit { it[Keys.portfolioHistoryExpanded] = expanded }

    /** Zeitraum des Wertverlaufs (nicht in der Sicherung). */
    suspend fun setPortfolioHistoryRange(range: PortfolioHistoryRange) = edit { it[Keys.portfolioHistoryRange] = range.name }

    /** Darstellung des Wertverlaufs: Währung, USDT oder Vergleich (nicht in der Sicherung). */
    suspend fun setPortfolioHistoryView(view: PortfolioHistoryView) = edit { it[Keys.portfolioHistoryView] = view.name }

    /** Markt-Tab einmal gesehen — «Einordnung» und «Daten» beginnen danach zugeklappt (nicht in der Sicherung). */
    suspend fun setMarketTabSeen(seen: Boolean) = edit { it[Keys.marketTabSeen] = seen }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit(block)
    }
}
