package com.cryptochecker.app.settings

import com.cryptochecker.app.domain.market.IndicatorAlertKind
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.cryptochecker.app.settings.SettingsRepository.Companion.KEY_BACKGROUND_UPDATES
import com.cryptochecker.app.settings.SettingsRepository.Companion.KEY_INCLUDE_ALPHA_TOKENS
import com.cryptochecker.app.settings.SettingsRepository.Companion.KEY_LIVE_SERVICE
import com.cryptochecker.app.settings.SettingsRepository.Companion.KEY_LIVE_WEBSOCKET
import com.cryptochecker.app.settings.SettingsRepository.Companion.KEY_ONGOING_NOTIFICATIONS
import com.cryptochecker.app.settings.SettingsRepository.Companion.KEY_PORTFOLIO_ENABLED
import com.cryptochecker.app.settings.SettingsRepository.Companion.KEY_SHOW_CONVERTED
import com.cryptochecker.app.settings.SettingsRepository.Companion.KEY_UPDATE_MODE
import com.cryptochecker.app.settings.SettingsRepository.Companion.KEY_WATCHLIST_NAMES

/** Schlüssel der Einstellungen im DataStore ([SettingsRepository], [settingsDataStore]). */
object SettingsKeys {
    /** «Wie aktuell?» ([UpdateMode.name]). */
    val updateMode = stringPreferencesKey(KEY_UPDATE_MODE)
    /** Frühere Schalter: nur noch mitgeschrieben (aus [UpdateMode.legacyFlags]) für ältere Versionen. */
    val backgroundUpdatesLegacy = booleanPreferencesKey(KEY_BACKGROUND_UPDATES)
    val backgroundInterval = intPreferencesKey("background_interval_minutes")
    /** «Pünktlich prüfen». */
    val exactChecks = booleanPreferencesKey("exact_checks")
    val liveServiceLegacy = booleanPreferencesKey(KEY_LIVE_SERVICE)
    val liveInterval = intPreferencesKey("live_interval_seconds")
    val liveWebSocketLegacy = booleanPreferencesKey(KEY_LIVE_WEBSOCKET)
    val priceNotifications = booleanPreferencesKey("price_notifications")
    val ongoingNotifications = booleanPreferencesKey(KEY_ONGOING_NOTIFICATIONS)
    /** Preis-Overlay (nur Android, gerätebezogen, nicht in der Sicherung). */
    val priceOverlay = booleanPreferencesKey("price_overlay")
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
    val firstAlarmCardDismissed = booleanPreferencesKey("first_alarm_card_dismissed")
    val usageDays = intPreferencesKey("usage_days")
    val usageLastDay = longPreferencesKey("usage_last_day")
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
