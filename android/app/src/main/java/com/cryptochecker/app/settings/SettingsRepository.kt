package com.cryptochecker.app.settings

import com.cryptochecker.app.domain.market.IndicatorAlertKind
import android.content.Context
import androidx.datastore.core.DataMigration
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
import com.cryptochecker.app.domain.alarm.DepegAlarm
import com.cryptochecker.app.domain.alarm.QuietHours
import com.cryptochecker.app.domain.portfolio.CostMethod
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
    // Reihenfolge: zuerst «Nicht wegwischbar», solange eine neue Installation noch leer ist; jede
    // spätere Migration zählt die von den vorherigen geschriebenen Schlüssel nicht mit
    produceMigrations = { listOf(OngoingNotificationsMigration, InstallDefaultsMigration, UpdateModeMigration) },
)

/**
 * «Nicht wegwischbar» einmalig festschreiben (Build 42, Standard neu aus): Fehlt der Schlüssel,
 * erhält eine bestehende Installation (schon Einstellungen gespeichert) den bisherigen Standard an,
 * eine neue (noch nichts gespeichert) den neuen Standard aus ([AppSettings.ongoingForMigration]).
 * Läuft vor [UpdateModeMigration], die auch bei neuen Installationen sofort schreibt (und diesen
 * Schlüssel beim Erkennen einer neuen Installation nicht mitzählt).
 */
private object OngoingNotificationsMigration : DataMigration<Preferences> {
    private val key = booleanPreferencesKey(SettingsRepository.KEY_ONGOING_NOTIFICATIONS)

    override suspend fun shouldMigrate(currentData: Preferences): Boolean = currentData[key] == null

    override suspend fun migrate(currentData: Preferences): Preferences =
        currentData.toMutablePreferences().apply {
            this[key] = AppSettings.ongoingForMigration(existingInstall = currentData.asMap().isNotEmpty())
        }.toPreferences()

    override suspend fun cleanUp() = Unit
}

/**
 * Geänderte Standards von Build 43 einmalig festschreiben («Coin-Namen» und «Umgerechnete Kurse» neu an,
 * «inkl. Alpha-Tokens» neu aus), ebenso Build 44 (Portfolio-Tab neu aus): Fehlt ein Schlüssel, erhält eine
 * bestehende Installation den bisherigen Standard, eine neue den neuen ([AppSettings.defaultForMigration]).
 * Eine Installation aus Build 43 hat die drei ersten Schlüssel schon und gilt damit als bestehende. Läuft nach
 * [OngoingNotificationsMigration] (deren Schlüssel nicht mitzählt) und vor [UpdateModeMigration]; so gilt
 * eine Installation aus Build 42 (mit «update_mode») sicher als bestehende.
 */
private object InstallDefaultsMigration : DataMigration<Preferences> {
    private val namesKey = booleanPreferencesKey(SettingsRepository.KEY_WATCHLIST_NAMES)
    private val convertedKey = booleanPreferencesKey(SettingsRepository.KEY_SHOW_CONVERTED)
    private val alphaKey = booleanPreferencesKey(SettingsRepository.KEY_INCLUDE_ALPHA_TOKENS)
    private val portfolioKey = booleanPreferencesKey(SettingsRepository.KEY_PORTFOLIO_ENABLED)

    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        currentData[namesKey] == null || currentData[convertedKey] == null || currentData[alphaKey] == null ||
            currentData[portfolioKey] == null

    override suspend fun migrate(currentData: Preferences): Preferences {
        // Eigene Schlüssel zählen mit: Steht nur einer davon da, hat ihn der Nutzer selbst gesetzt
        val existing = AppSettings.isExistingInstall(
            currentData.asMap().keys.map { it.name },
            ignored = setOf(SettingsRepository.KEY_ONGOING_NOTIFICATIONS),
        )
        val current = AppSettings()
        return currentData.toMutablePreferences().apply {
            if (currentData[namesKey] == null) this[namesKey] = AppSettings.defaultForMigration(
                existing, AppSettings.LEGACY_WATCHLIST_NAMES, current.watchlistNames)
            if (currentData[convertedKey] == null) this[convertedKey] = AppSettings.defaultForMigration(
                existing, AppSettings.LEGACY_SHOW_CONVERTED, current.showConverted)
            if (currentData[alphaKey] == null) this[alphaKey] = AppSettings.defaultForMigration(
                existing, AppSettings.LEGACY_INCLUDE_ALPHA_TOKENS, current.includeAlphaTokens)
            if (currentData[portfolioKey] == null) this[portfolioKey] = AppSettings.defaultForMigration(
                existing, AppSettings.LEGACY_PORTFOLIO_ENABLED, current.portfolioEnabled)
        }.toPreferences()
    }

    override suspend fun cleanUp() = Unit
}

/**
 * «Wie aktuell?» beim ersten Lesen nach dem Update einmalig aus den früheren Schaltern übernehmen
 * und speichern ([UpdateMode.resolve]): «Häufig aktualisieren» oder «Live-Kurse» an → Live, solange
 * offen, sonst Sparsam. Neue Installation (noch nichts gespeichert) → Standard. Ein gespeichertes
 * «Immer kurz» (vor 1.0) wird einmalig als Live, solange offen gespeichert; der frühere Schalter
 * «Häufig aktualisieren» dabei auf aus, damit eine ältere Version nach einem Downgrade dasselbe liest.
 */
private object UpdateModeMigration : DataMigration<Preferences> {
    private val modeKey = stringPreferencesKey(SettingsRepository.KEY_UPDATE_MODE)
    private val liveServiceKey = booleanPreferencesKey(SettingsRepository.KEY_LIVE_SERVICE)
    private val webSocketKey = booleanPreferencesKey(SettingsRepository.KEY_LIVE_WEBSOCKET)
    private val backgroundKey = booleanPreferencesKey(SettingsRepository.KEY_BACKGROUND_UPDATES)

    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        UpdateMode.needsMigration(currentData[modeKey])

    override suspend fun migrate(currentData: Preferences): Preferences {
        val mode = UpdateMode.resolve(
            stored = currentData[modeKey],
            legacyLiveService = currentData[liveServiceKey],
            legacyWebSocket = currentData[webSocketKey],
            legacyBackground = currentData[backgroundKey],
            // Die eben von [OngoingNotificationsMigration] und [InstallDefaultsMigration] geschriebenen
            // Schlüssel nicht mitzählen, sonst gälte jede neue Installation als bestehende (Sparsam statt Standard)
            existingInstall = AppSettings.isExistingInstall(
                currentData.asMap().keys.map { it.name },
                ignored = SettingsRepository.MIGRATION_WRITTEN_KEYS,
            ),
        )
        return currentData.toMutablePreferences().apply {
            this[modeKey] = mode.name
            if (currentData[liveServiceKey] == true) this[liveServiceKey] = false
        }.toPreferences()
    }

    override suspend fun cleanUp() = Unit
}

@Singleton
class SettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private object Keys {
        /** «Wie aktuell?» ([UpdateMode.name]). */
        val updateMode = stringPreferencesKey(KEY_UPDATE_MODE)
        /** Frühere Schalter: nur noch mitgeschrieben (aus [UpdateMode.legacyFlags]) für ältere Versionen. */
        val backgroundUpdatesLegacy = booleanPreferencesKey(KEY_BACKGROUND_UPDATES)
        val backgroundInterval = intPreferencesKey("background_interval_minutes")
        /** «Pünktlich prüfen» (Build 46). */
        val exactChecks = booleanPreferencesKey("exact_checks")
        val liveServiceLegacy = booleanPreferencesKey(KEY_LIVE_SERVICE)
        val liveInterval = intPreferencesKey("live_interval_seconds")
        val liveWebSocketLegacy = booleanPreferencesKey(KEY_LIVE_WEBSOCKET)
        val priceNotifications = booleanPreferencesKey("price_notifications")
        val ongoingNotifications = booleanPreferencesKey(KEY_ONGOING_NOTIFICATIONS)
        /** Früher ganze Prozent; wird nur noch gelesen, falls der neue Wert fehlt. */
        val notificationChangeLegacy = intPreferencesKey("notification_change_percent")
        /** Freie Eingabe mit Nachkommastellen, z. B. 2,5 %. */
        val notificationChange = doublePreferencesKey("notification_change_percent_exact")
        val tts = booleanPreferencesKey("tts_enabled")
        val ttsAlarmsOnly = booleanPreferencesKey("tts_alarms_only")
        val ttsSpeechRate = floatPreferencesKey("tts_speech_rate")
        val alarmCooldown = intPreferencesKey("alarm_cooldown_minutes")
        val includeRollingFutures = booleanPreferencesKey("include_rolling_futures")
        /** Früherer Schalter «TradFi»; wird nur noch gelesen, falls [assetScope] fehlt. */
        val includeTradFiFuturesLegacy = booleanPreferencesKey("include_tradfi_futures")
        /** «Token & TradFi» ([AssetScope.name]). */
        val assetScope = stringPreferencesKey("asset_scope")
        /** «inkl. Alpha-Tokens». */
        val includeAlphaTokens = booleanPreferencesKey(KEY_INCLUDE_ALPHA_TOKENS)
        val accentColor = stringPreferencesKey("accent_color")
        val darkMode = booleanPreferencesKey("dark_mode")
        val showHttpLog = booleanPreferencesKey("show_http_log")
        val developerUnlocked = booleanPreferencesKey("developer_unlocked")
        val gestureHintSeen = booleanPreferencesKey("gesture_hint_seen")
        // «zone_alerts» (Meldung «Marktphase gewechselt») gibt es nicht mehr; der Schlüssel bleibt unbenutzt liegen
        val fearGreedBelow = intPreferencesKey("fear_greed_below")
        val fearGreedAbove = intPreferencesKey("fear_greed_above")

        /** Eigene Grenze je Bitcoin-Kennzahl ([IndicatorAlertKind]); 0 = aus. */
        fun indicatorAlert(kind: IndicatorAlertKind) = intPreferencesKey("indicator_alert_" + kind.key.lowercase())
        val aboutSeen = booleanPreferencesKey("about_seen")
        val batteryPromptSeen = booleanPreferencesKey("battery_prompt_seen")
        val watchlistGroup = stringPreferencesKey("watchlist_group")
        val watchlistColumnSort = stringPreferencesKey("watchlist_column_sort")
        val activityAlerts = booleanPreferencesKey("activity_alerts")
        val activitySensitivity = stringPreferencesKey("activity_sensitivity")
        val macroNotifications = booleanPreferencesKey("macro_notifications")
        val portfolioEnabled = booleanPreferencesKey(KEY_PORTFOLIO_ENABLED)
        val portfolioCurrency = stringPreferencesKey("portfolio_currency")
        val showConverted = booleanPreferencesKey(KEY_SHOW_CONVERTED)
        val priceColorScheme = stringPreferencesKey("price_color_scheme")
        val watchlistSparkline = booleanPreferencesKey("watchlist_sparkline")
        val watchlistNames = booleanPreferencesKey(KEY_WATCHLIST_NAMES)
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
        val depegAlarm = booleanPreferencesKey("depeg_alarm")
        val depegThreshold = intPreferencesKey("depeg_threshold_tenths")
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
        val alarmTimingHintShown = booleanPreferencesKey("alarm_timing_hint_shown")
        val firstPairAdded = booleanPreferencesKey("first_pair_added")
        val sheetChartLine = booleanPreferencesKey("sheet_chart_line")
        val portfolioHistoryExpanded = booleanPreferencesKey("portfolio_history_expanded")
        val portfolioHistoryRange = stringPreferencesKey("portfolio_history_range")
        val portfolioHistoryView = stringPreferencesKey("portfolio_history_view")
        val portfolioHistoryCandles = booleanPreferencesKey("portfolio_history_candles")
        val portfolioHistoryPnl = booleanPreferencesKey("portfolio_history_pnl")
        val costMethod = stringPreferencesKey("portfolio_cost_method")
        val taxTemplate = stringPreferencesKey("tax_template")
        val taxTemplateConfirmed = booleanPreferencesKey("tax_template_confirmed")
        val marketTabSeen = booleanPreferencesKey("market_tab_seen")
        val marketMoreExpanded = booleanPreferencesKey("market_more_expanded")
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
            // Die Migration ([UpdateModeMigration]) hat den Wert beim ersten Lesen gesetzt
            updateMode = UpdateMode.fromNameOrNull(prefs[Keys.updateMode]) ?: defaults.updateMode,
            // Grenzen wie beim Setzen (früher gespeicherte, zu grosse Werte), wie iOS
            backgroundIntervalMinutes = AppSettings.clampBackgroundInterval(
                prefs[Keys.backgroundInterval] ?: defaults.backgroundIntervalMinutes
            ),
            exactChecks = prefs[Keys.exactChecks] ?: defaults.exactChecks,
            liveIntervalSeconds = AppSettings.clampLiveInterval(prefs[Keys.liveInterval] ?: defaults.liveIntervalSeconds),
            priceNotifications = prefs[Keys.priceNotifications] ?: defaults.priceNotifications,
            ongoingNotifications = prefs[Keys.ongoingNotifications]
                ?: defaults.ongoingNotifications,
            notificationChangePercent = prefs[Keys.notificationChange]
                ?: prefs[Keys.notificationChangeLegacy]?.toDouble()
                ?: defaults.notificationChangePercent,
            ttsEnabled = prefs[Keys.tts] ?: defaults.ttsEnabled,
            ttsAlarmsOnly = prefs[Keys.ttsAlarmsOnly] ?: defaults.ttsAlarmsOnly,
            ttsSpeechRate = prefs[Keys.ttsSpeechRate] ?: defaults.ttsSpeechRate,
            alarmCooldownMinutes = AppSettings.clampAlarmCooldown(prefs[Keys.alarmCooldown] ?: defaults.alarmCooldownMinutes),
            includeRollingFutures = prefs[Keys.includeRollingFutures]
                ?: defaults.includeRollingFutures,
            // Fehlt der neue Wert (Update): einmalig aus dem früheren Schalter übernehmen
            assetScope = AssetScope.resolve(prefs[Keys.assetScope], prefs[Keys.includeTradFiFuturesLegacy]),
            includeAlphaTokens = prefs[Keys.includeAlphaTokens] ?: defaults.includeAlphaTokens,
            accentColor = AccentColor.fromName(prefs[Keys.accentColor]),
            darkMode = prefs[Keys.darkMode],
            showHttpLog = prefs[Keys.showHttpLog] ?: defaults.showHttpLog,
            developerUnlocked = prefs[Keys.developerUnlocked] ?: defaults.developerUnlocked,
            gestureHintSeen = prefs[Keys.gestureHintSeen] ?: defaults.gestureHintSeen,
            fearGreedBelow = prefs[Keys.fearGreedBelow] ?: defaults.fearGreedBelow,
            fearGreedAbove = prefs[Keys.fearGreedAbove] ?: defaults.fearGreedAbove,
            indicatorAlerts = IndicatorAlertKind.entries
                .mapNotNull { kind -> prefs[Keys.indicatorAlert(kind)]?.takeIf { it in kind.choices && it > 0 }?.let { kind to it } }
                .toMap(),
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
            depegAlarm = prefs[Keys.depegAlarm] ?: defaults.depegAlarm,
            depegThresholdTenths = DepegAlarm.threshold(prefs[Keys.depegThreshold] ?: defaults.depegThresholdTenths),
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
            alarmTimingHintShown = prefs[Keys.alarmTimingHintShown] ?: defaults.alarmTimingHintShown,
            firstPairAdded = prefs[Keys.firstPairAdded] ?: defaults.firstPairAdded,
            sheetChartLine = prefs[Keys.sheetChartLine] ?: defaults.sheetChartLine,
            portfolioHistoryExpanded = prefs[Keys.portfolioHistoryExpanded] ?: defaults.portfolioHistoryExpanded,
            portfolioHistoryRange = PortfolioHistoryRange.fromName(prefs[Keys.portfolioHistoryRange]),
            portfolioHistoryView = PortfolioHistoryView.fromName(prefs[Keys.portfolioHistoryView]),
            portfolioHistoryCandles = prefs[Keys.portfolioHistoryCandles] ?: defaults.portfolioHistoryCandles,
            portfolioHistoryPnl = prefs[Keys.portfolioHistoryPnl] ?: defaults.portfolioHistoryPnl,
            // Fehlt oder unbekannt (auch bestehende Installationen): FIFO
            costMethod = CostMethod.fromName(prefs[Keys.costMethod]),
            // Fehlt oder unbekannt: Neutral
            taxTemplate = com.cryptochecker.app.domain.portfolio.TaxTemplate.fromName(prefs[Keys.taxTemplate]),
            taxTemplateConfirmed = prefs[Keys.taxTemplateConfirmed] ?: defaults.taxTemplateConfirmed,
            marketTabSeen = prefs[Keys.marketTabSeen] ?: defaults.marketTabSeen,
            marketMoreExpanded = prefs[Keys.marketMoreExpanded] ?: defaults.marketMoreExpanded,
        ).also {
            cached = it
            loaded = true
        }
    }

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
        it[Keys.updateMode] = saved.name
        it[Keys.backgroundUpdatesLegacy] = flags.backgroundUpdates
        it[Keys.liveServiceLegacy] = flags.liveService
        it[Keys.liveWebSocketLegacy] = flags.liveWebSocket
    }

    suspend fun setBackgroundInterval(minutes: Int) = edit {
        it[Keys.backgroundInterval] = AppSettings.clampBackgroundInterval(minutes)
    }

    suspend fun setExactChecks(enabled: Boolean) = edit { it[Keys.exactChecks] = enabled }


    suspend fun setLiveInterval(seconds: Int) = edit {
        it[Keys.liveInterval] = AppSettings.clampLiveInterval(seconds)
    }

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
        it[Keys.alarmCooldown] = AppSettings.clampAlarmCooldown(minutes)
    }

    suspend fun setIncludeRollingFutures(enabled: Boolean) =
        edit { it[Keys.includeRollingFutures] = enabled }

    suspend fun setIncludeAlphaTokens(enabled: Boolean) =
        edit { it[Keys.includeAlphaTokens] = enabled }

    /** Ab hier gilt nur noch der neue Wert; der frühere Schalter wird entfernt. */
    suspend fun setAssetScope(scope: AssetScope) = edit {
        it[Keys.assetScope] = scope.name
        it.remove(Keys.includeTradFiFuturesLegacy)
    }

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

    suspend fun setFearGreedBelow(value: Int) = edit { it[Keys.fearGreedBelow] = value.coerceIn(0, 100) }

    suspend fun setFearGreedAbove(value: Int) = edit { it[Keys.fearGreedAbove] = value.coerceIn(0, 100) }

    /** Eigene Grenze einer Bitcoin-Kennzahl; nur Werte aus [IndicatorAlertKind.choices], sonst aus. */
    suspend fun setIndicatorAlert(kind: IndicatorAlertKind, value: Int) =
        edit { it[Keys.indicatorAlert(kind)] = value.takeIf { v -> v in kind.choices } ?: 0 }

    suspend fun setGasAlertEth(tenths: Int) = edit { it[Keys.gasAlertEth] = tenths.coerceIn(0, 10_000) }

    suspend fun setGasAlertBtc(satPerVb: Int) = edit { it[Keys.gasAlertBtc] = satPerVb.coerceIn(0, 10_000) }

    suspend fun setDepegAlarm(enabled: Boolean) = edit { it[Keys.depegAlarm] = enabled }

    /** Nur Werte aus [DepegAlarm.THRESHOLD_CHOICES], sonst Standard. */
    suspend fun setDepegThreshold(tenths: Int) = edit { it[Keys.depegThreshold] = DepegAlarm.threshold(tenths) }

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

    /** Hinweis «Wann Alarme geprüft werden» gezeigt (nicht in der Sicherung). */
    suspend fun setAlarmTimingHintShown(shown: Boolean) = edit { it[Keys.alarmTimingHintShown] = shown }

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

    /** Wertverlauf als Kerzen (true) oder Linie (nicht in der Sicherung). */
    suspend fun setPortfolioHistoryCandles(candles: Boolean) = edit { it[Keys.portfolioHistoryCandles] = candles }

    /** Wertverlauf als Gewinn/Verlust (true) oder Wert (nicht in der Sicherung). */
    suspend fun setPortfolioHistoryPnl(pnl: Boolean) = edit { it[Keys.portfolioHistoryPnl] = pnl }

    /** Einstandsmethode FIFO | Ø (nicht in der Sicherung). */
    suspend fun setCostMethod(method: CostMethod) = edit { it[Keys.costMethod] = method.name }

    /** Steuer-Vorlage der Arbeitsunterlage (in der Sicherung, «taxTemplate»). */
    suspend fun setTaxTemplate(template: com.cryptochecker.app.domain.portfolio.TaxTemplate) =
        edit { it[Keys.taxTemplate] = template.name }

    /** Hinweis zu den Länder-Vorlagen (Beta) bestätigt (nicht in der Sicherung). */
    suspend fun setTaxTemplateConfirmed() = edit { it[Keys.taxTemplateConfirmed] = true }

    /** Markt-Tab einmal gesehen — «Einordnung» und «Daten» beginnen danach zugeklappt (nicht in der Sicherung). */
    suspend fun setMarketTabSeen(seen: Boolean) = edit { it[Keys.marketTabSeen] = seen }

    /** «Mehr Marktdaten» im Markt-Tab auf- oder zugeklappt (nicht in der Sicherung). */
    suspend fun setMarketMoreExpanded(expanded: Boolean) = edit { it[Keys.marketMoreExpanded] = expanded }

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
