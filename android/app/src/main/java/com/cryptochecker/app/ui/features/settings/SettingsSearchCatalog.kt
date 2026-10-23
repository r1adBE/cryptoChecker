// Index der Suche in den Einstellungen (Oberfläche in `SettingsSearchUi`): alle durchsuchbaren Punkte und das Ziel
// je Treffer — wie `SettingsSearchCatalog.swift`. Eigene Datei, weil der Index den Grossteil der Suche ausmacht.
package com.cryptochecker.app.ui.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.cryptochecker.app.BuildConfig
import com.cryptochecker.app.R
import com.cryptochecker.app.settings.AccentColor
import com.cryptochecker.app.settings.AppSettings
import com.cryptochecker.app.settings.PriceColorScheme
import com.cryptochecker.app.settings.SettingsSearchEntry
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.util.ChangeBasisText

/** Ein Eintrag der Suche mit seinem Ziel. */
internal data class SettingsSearchItem(val entry: SettingsSearchEntry, val target: SettingsSearchTarget)

/**
 * Alle durchsuchbaren Punkte in der Sprache der App — Hauptseite und Unterseiten, in der
 * Reihenfolge der Seiten. Ids sind zugleich die Anker ([SettingsAnchor]).
 */
@Composable
internal fun settingsSearchItems(settings: AppSettings): List<SettingsSearchItem> {
    val items = ArrayList<SettingsSearchItem>()
    fun add(id: String, title: String, path: String, target: SettingsSearchTarget, vararg synonyms: String) {
        items += SettingsSearchItem(SettingsSearchEntry(id, title, path, synonyms.toList()), target)
    }

    // Hauptseite Allgemein · Darstellung · Mehr; der Rest unter «Erweitert». Der Pfad einer Seite kommt
    // aus ihrem Ort (SettingsPage.home), damit er nach jeder Umstellung zur Anordnung passt
    val general = stringResource(R.string.settings_group_general)
    val appearance = stringResource(R.string.settings_section_appearance)
    val more = stringResource(R.string.settings_group_more)
    val advanced = stringResource(R.string.settings_row_advanced)
    fun homePath(page: SettingsPage): String = when (page.home) {
        SettingsHome.GENERAL -> general
        SettingsHome.APPEARANCE -> appearance
        SettingsHome.MORE -> more
        SettingsHome.ADVANCED -> advanced
        null -> ""
    }
    fun addPage(page: SettingsPage, title: String, vararg synonyms: String) =
        add("page." + page.name.lowercase(), title, homePath(page), SettingsSearchTarget.Page(page), *synonyms)
    addPage(SettingsPage.ADVANCED, advanced)
    // Seite «Portfolio» (Zeile unter «Allgemein», einziger Ort für alles zum Portfolio): früh im Index, damit sie
    // bei «Portfolio» vor «Portfolio-Sperre» und «Portfolio in Systemsicherung» steht (gleich viele Punkte →
    // frühere Stelle zuerst)
    val portfolio = stringResource(R.string.portfolio_title)
    addPage(SettingsPage.PORTFOLIO, portfolio,
        stringResource(R.string.settings_portfolio_tab), stringResource(R.string.portfolio_setting_hint))
    add("main.language", stringResource(R.string.settings_section_language), homePath(SettingsPage.LANGUAGE),
        SettingsSearchTarget.Page(SettingsPage.LANGUAGE), stringResource(R.string.settings_language_choose),
        stringResource(R.string.language_search))
    val currency = stringResource(R.string.settings_row_currency)
    addPage(SettingsPage.CURRENCY, currency, stringResource(R.string.currency_search))
    add("currency.conversion", stringResource(R.string.settings_conversion_currency), currency,
        SettingsSearchTarget.Page(SettingsPage.CURRENCY, "currency.conversion"),
        stringResource(R.string.settings_conversion_currency_hint))
    val updates = stringResource(R.string.settings_row_updates)
    addPage(SettingsPage.UPDATES, updates)
    val updatesPath = updates
    // «Wie aktuell?»: eine Wahl statt der früheren Schalter; deren Namen bleiben Suchwörter
    // («Immer kurz» bzw. «Häufig aktualisieren» nicht mehr: seit 1.0 entfernt)
    add("updates.mode", stringResource(R.string.update_mode_title), updatesPath,
        SettingsSearchTarget.Page(SettingsPage.UPDATES, "updates.mode"),
        stringResource(R.string.update_mode_sparsam), stringResource(R.string.update_mode_live),
        stringResource(R.string.settings_background_updates), stringResource(R.string.settings_live_websocket))
    // Akkunutzung steht ganz unten auf der Seite: eigener Treffer, damit die Suche dorthin springt
    add("updates.battery", stringResource(R.string.settings_battery_restricted), updatesPath,
        SettingsSearchTarget.Page(SettingsPage.UPDATES, "updates.battery"),
        stringResource(R.string.settings_battery_ok), stringResource(R.string.settings_battery_hint))
    add("updates.background_interval", stringResource(R.string.settings_background_interval), updatesPath,
        SettingsSearchTarget.Page(SettingsPage.UPDATES, "updates.background_interval"))
    add("updates.ongoing", stringResource(R.string.settings_ongoing_notifications), updatesPath,
        SettingsSearchTarget.Page(SettingsPage.UPDATES, "updates.ongoing"),
        stringResource(R.string.settings_ongoing_notifications_hint))
    // Alarm-Protokoll: Knopf in der Karte «Letzte Prüfungen» (ganz unten auf der Seite) — dorthin springen
    add("updates.alarm_log", stringResource(R.string.alarm_log_title), updatesPath,
        SettingsSearchTarget.Page(SettingsPage.UPDATES, "updates.check_runs"),
        stringResource(R.string.alarm_log_open), stringResource(R.string.alarm_log_intro),
        stringResource(R.string.settings_check_runs_title))
    val watchlist = stringResource(R.string.settings_row_watchlist)
    addPage(SettingsPage.WATCHLIST, watchlist)
    add("watchlist.names", stringResource(R.string.settings_watchlist_names), watchlist,
        SettingsSearchTarget.Page(SettingsPage.WATCHLIST, "watchlist.names"),
        stringResource(R.string.settings_watchlist_names_hint))
    add("watchlist.sparkline", stringResource(R.string.settings_watchlist_sparkline), watchlist,
        SettingsSearchTarget.Page(SettingsPage.WATCHLIST, "watchlist.sparkline"),
        stringResource(R.string.settings_watchlist_sparkline_hint))
    add("watchlist.converted", stringResource(R.string.settings_show_converted), watchlist,
        SettingsSearchTarget.Page(SettingsPage.WATCHLIST, "watchlist.converted"),
        stringResource(R.string.settings_show_converted_hint, settings.portfolioCurrency))
    add("watchlist.activity", stringResource(R.string.settings_watchlist_activity_card), watchlist,
        SettingsSearchTarget.Page(SettingsPage.WATCHLIST, "watchlist.activity"),
        stringResource(R.string.settings_watchlist_activity_card_hint), "⚡")

    // 2 Darstellung
    val mode = stringResource(R.string.settings_theme_mode)
    addPage(SettingsPage.DISPLAY_MODE, mode,
        stringResource(R.string.theme_system), stringResource(R.string.theme_light), stringResource(R.string.theme_dark))
    add("display.contrast", stringResource(R.string.settings_high_contrast), mode,
        SettingsSearchTarget.Page(SettingsPage.DISPLAY_MODE, "display.contrast"),
        stringResource(R.string.settings_high_contrast_hint))
    addPage(SettingsPage.THEME, stringResource(R.string.settings_accent),
        *AccentColor.entries.map { stringResource(it.labelRes) }.toTypedArray())
    addPage(SettingsPage.PRICE_COLORS, stringResource(R.string.settings_price_colors),
        stringResource(R.string.settings_price_colors_hint),
        *PriceColorScheme.entries.map { stringResource(priceColorSchemeLabel(it)) }.toTypedArray())
    add("price_colors.swap", stringResource(R.string.price_colors_swap), stringResource(R.string.settings_price_colors),
        SettingsSearchTarget.Page(SettingsPage.PRICE_COLORS, "price_colors.swap"),
        stringResource(R.string.price_colors_red_up_hint))
    // %-Änderung unter «Merkliste» (Review D13), Coin-Logos unter «Erweitert», Widgets unter «Darstellung»
    val changeBasis = stringResource(R.string.settings_change_basis, "%")
    add("page.change_basis", changeBasis, watchlist, SettingsSearchTarget.Page(SettingsPage.CHANGE_BASIS),
        stringResource(R.string.settings_change_basis_hint_1),
        stringResource(R.string.change_basis_rolling),
        stringResource(R.string.change_basis_since_last),
        stringResource(R.string.change_basis_device, "UTC"),
        // Alle Zonen in einem Text: «UTC+8» findet die Seite einmal, nicht 27 fast gleiche Treffer
        ChangeBasis.entries.filter { it.kind == ChangeBasis.Kind.UTC_DAY }
            .joinToString(" ") { ChangeBasisText.zoneChoice(it) })
    add("change.period", stringResource(R.string.settings_change_period), changeBasis,
        SettingsSearchTarget.Page(SettingsPage.CHANGE_BASIS, "change.period"),
        stringResource(R.string.settings_change_period_hint))
    val logos = stringResource(R.string.settings_coin_logos)
    addPage(SettingsPage.COIN_LOGOS, logos,
        stringResource(R.string.settings_coin_logos_footer), "CoinGecko")
    add("logos.app", stringResource(R.string.settings_coin_logos_app), logos,
        SettingsSearchTarget.Page(SettingsPage.COIN_LOGOS, "logos.app"),
        stringResource(R.string.settings_coin_logos_app_hint))
    add("logos.portfolio", stringResource(R.string.settings_coin_logos_portfolio), logos,
        SettingsSearchTarget.Page(SettingsPage.COIN_LOGOS, "logos.portfolio"),
        stringResource(R.string.settings_coin_logos_portfolio_hint))
    add("logos.widgets", stringResource(R.string.settings_coin_logos_widgets), logos,
        SettingsSearchTarget.Page(SettingsPage.COIN_LOGOS, "logos.widgets"),
        stringResource(R.string.settings_coin_logos_widgets_hint))
    addPage(SettingsPage.WIDGETS, stringResource(R.string.settings_widgets))

    // 3 Alarme (unter «Allgemein»); Markt-Meldungen und Sprachausgabe unter «Erweitert»
    val alarms = stringResource(R.string.settings_row_alarms)
    addPage(SettingsPage.ALARMS, alarms)
    // «Damit Alarme ankommen»: Zeile oben auf der Seite, öffnet die Übersicht (Erlaubnis, Kanal, Akku, letzte Prüfung)
    add("alarms.delivery", stringResource(R.string.delivery_title), alarms,
        SettingsSearchTarget.Page(SettingsPage.ALARMS, "alarms.delivery"),
        stringResource(R.string.delivery_notifications), stringResource(R.string.delivery_channel),
        stringResource(R.string.delivery_battery), stringResource(R.string.delivery_dnd),
        stringResource(R.string.delivery_last_check))
    add("alarms.price", stringResource(R.string.settings_price_notifications), alarms,
        SettingsSearchTarget.Page(SettingsPage.ALARMS, "alarms.price"),
        stringResource(R.string.settings_price_notifications_hint))
    add("alarms.change", stringResource(R.string.settings_notification_change), alarms,
        SettingsSearchTarget.Page(SettingsPage.ALARMS, "alarms.change"),
        stringResource(R.string.settings_notification_change_hint))
    add("alarms.cooldown", stringResource(R.string.settings_alarm_cooldown), alarms,
        SettingsSearchTarget.Page(SettingsPage.ALARMS, "alarms.cooldown"))
    add("alarms.sound", stringResource(R.string.settings_alarm_sound), alarms,
        SettingsSearchTarget.Page(SettingsPage.ALARMS, "alarms.sound"))
    add("alarms.signal", stringResource(R.string.settings_alarm_signal), alarms,
        SettingsSearchTarget.Page(SettingsPage.ALARMS, "alarms.signal"),
        stringResource(R.string.settings_alarm_signal_system_hint))
    add("alarms.quiet", stringResource(R.string.settings_quiet_hours), alarms,
        SettingsSearchTarget.Page(SettingsPage.ALARMS, "alarms.quiet"),
        stringResource(R.string.settings_quiet_hours_from), stringResource(R.string.settings_quiet_hours_to))
    add("alarms.badge", stringResource(R.string.settings_app_badge), alarms,
        SettingsSearchTarget.Page(SettingsPage.ALARMS, "alarms.badge"),
        stringResource(R.string.settings_app_badge_hint))
    add("alarms.depeg", stringResource(R.string.settings_depeg_alarm), alarms,
        SettingsSearchTarget.Page(SettingsPage.ALARMS, "alarms.depeg"),
        stringResource(R.string.settings_depeg_alarm_hint), "USDT", "Depeg")
    add("alarms.test", stringResource(R.string.alarm_test), alarms,
        SettingsSearchTarget.Page(SettingsPage.ALARMS, "alarms.test"),
        stringResource(R.string.alarm_test_hint))
    val market = stringResource(R.string.settings_market_alerts)
    add("page.market_alerts", market, advanced, SettingsSearchTarget.MarketAlerts())
    add("market.fng_below", stringResource(R.string.settings_fng_below), market,
        SettingsSearchTarget.MarketAlerts("market.fng_below"))
    add("market.fng_above", stringResource(R.string.settings_fng_above), market,
        SettingsSearchTarget.MarketAlerts("market.fng_above"))
    add("market.gas_eth", stringResource(R.string.settings_gas_eth_below), market,
        SettingsSearchTarget.MarketAlerts("market.gas_eth"), stringResource(R.string.settings_gas_alert_hint))
    add("market.gas_btc", stringResource(R.string.settings_gas_btc_below), market,
        SettingsSearchTarget.MarketAlerts("market.gas_btc"), stringResource(R.string.settings_gas_alert_hint))
    add("market.indicators", stringResource(R.string.neutral_btc_indicators_title), market,
        SettingsSearchTarget.MarketAlerts("market.indicators"), stringResource(R.string.settings_indicator_alerts_hint))
    add("market.activity", stringResource(R.string.settings_activity_alerts), market,
        SettingsSearchTarget.MarketAlerts("market.activity"), stringResource(R.string.settings_activity_alerts_hint))
    add("market.sensitivity", stringResource(R.string.settings_activity_sensitivity), market,
        SettingsSearchTarget.MarketAlerts("market.sensitivity"),
        stringResource(R.string.settings_activity_sensitivity_hint))
    add("market.macro", stringResource(R.string.settings_macro_notifications), market,
        SettingsSearchTarget.MarketAlerts("market.macro"), stringResource(R.string.settings_macro_notifications_hint))
    val speech = stringResource(R.string.settings_tts)
    add("page.speech", speech, advanced, SettingsSearchTarget.Speech("speech.enabled"),
        stringResource(R.string.settings_tts_hint_silent))
    add("speech.alarms_only", stringResource(R.string.settings_tts_alarms_only), speech,
        SettingsSearchTarget.Speech("speech.alarms_only"), stringResource(R.string.settings_tts_alarms_only_hint))
    add("speech.test", stringResource(R.string.settings_tts_test), speech, SettingsSearchTarget.Speech("speech.test"))

    // 4 Portfolio (unter «Allgemein»; die Seite selbst steht oben im Index)
    add("portfolio.tab", stringResource(R.string.settings_portfolio_tab), portfolio,
        SettingsSearchTarget.Page(SettingsPage.PORTFOLIO, "portfolio.tab"), stringResource(R.string.portfolio_setting_hint))
    add("portfolio.lock", stringResource(R.string.settings_portfolio_lock), portfolio,
        SettingsSearchTarget.Page(SettingsPage.PORTFOLIO, "portfolio.lock"),
        stringResource(R.string.settings_portfolio_lock_hint))
    add("portfolio.hide", stringResource(R.string.portfolio_hide_amounts), portfolio,
        SettingsSearchTarget.Page(SettingsPage.PORTFOLIO, "portfolio.hide"),
        stringResource(R.string.portfolio_hide_amounts_hint))
    add("portfolio.cost_method", stringResource(R.string.settings_cost_method), portfolio,
        SettingsSearchTarget.Page(SettingsPage.PORTFOLIO, "portfolio.cost_method"),
        stringResource(R.string.settings_cost_method_fifo), stringResource(R.string.settings_cost_method_average))
    add("portfolio.tax_template", stringResource(R.string.tax_template_title), portfolio,
        SettingsSearchTarget.Page(SettingsPage.PORTFOLIO, "portfolio.tax_template"),
        stringResource(R.string.tax_template_hint))
    add("portfolio.system_backup", stringResource(R.string.settings_portfolio_system_backup), portfolio,
        SettingsSearchTarget.Page(SettingsPage.PORTFOLIO, "portfolio.system_backup"),
        stringResource(R.string.settings_portfolio_system_backup_hint))
    add("portfolio.address_server", stringResource(R.string.settings_address_server_title), portfolio,
        SettingsSearchTarget.Page(SettingsPage.PORTFOLIO, "portfolio.address_server"),
        stringResource(R.string.settings_address_server_hint))

    // 5 Sicherung (unter «Mehr»)
    val backup = stringResource(R.string.backup_title)
    addPage(SettingsPage.BACKUP, backup,
        stringResource(R.string.backup_hint))
    add("backup.export", stringResource(R.string.backup_export), backup,
        SettingsSearchTarget.Page(SettingsPage.BACKUP, "backup.actions"))
    add("backup.import", stringResource(R.string.backup_import), backup,
        SettingsSearchTarget.Page(SettingsPage.BACKUP, "backup.actions"))
    // «App zurücksetzen»: eigene rote Karte ganz unten auf der Hauptseite
    add("main.reset", stringResource(R.string.settings_reset_app), "", SettingsSearchTarget.Main("main.reset"),
        stringResource(R.string.settings_reset_app_confirm))
    // «Token & TradFi» mit Alpha und QTLY: eigene Seite unter «Erweitert» (vorher «Merkliste»);
    // frühere Namen bleiben Suchwörter
    val futures = stringResource(R.string.settings_asset_scope_title)
    addPage(SettingsPage.MARKETS, futures)
    add("futures.scope", stringResource(R.string.settings_asset_scope_title), futures,
        SettingsSearchTarget.Page(SettingsPage.MARKETS, "futures.scope"),
        stringResource(R.string.settings_asset_scope_tokens), stringResource(R.string.settings_asset_scope_tokens_hint),
        stringResource(R.string.settings_asset_scope_tradfi), stringResource(R.string.settings_asset_scope_tradfi_hint),
        stringResource(R.string.settings_asset_scope_both), "TradFi", "Token",
        stringResource(R.string.settings_tradfi_futures), stringResource(R.string.settings_tradfi_futures_hint))
    add("futures.alpha", stringResource(R.string.settings_asset_scope_alpha), futures,
        SettingsSearchTarget.Page(SettingsPage.MARKETS, "futures.alpha"),
        stringResource(R.string.settings_asset_scope_alpha_hint), stringResource(R.string.group_alpha), "Alpha", "α")
    add("futures.rolling", stringResource(R.string.settings_rolling_futures_qtly), futures,
        SettingsSearchTarget.Page(SettingsPage.MARKETS, "futures.rolling"),
        stringResource(R.string.settings_rolling_futures_hint), "QTLY",
        stringResource(R.string.settings_rolling_futures), stringResource(R.string.settings_row_dated_futures))

    // 6 Über die App (unter «Mehr»): Links und Lizenzen auf der Seite selbst
    val about = stringResource(R.string.settings_row_about)
    addPage(SettingsPage.ABOUT, about)
    add("about.privacy", stringResource(R.string.about_privacy_policy), about,
        SettingsSearchTarget.Page(SettingsPage.ABOUT, "about.privacy"))
    add("about.exchange", stringResource(R.string.about_request_exchange), about,
        SettingsSearchTarget.Page(SettingsPage.ABOUT, "about.exchange"))
    add("about.source", stringResource(R.string.about_source_code), about,
        SettingsSearchTarget.Page(SettingsPage.ABOUT, "about.source"))
    add("about.licenses", stringResource(R.string.about_licenses), about, SettingsSearchTarget.Page(SettingsPage.LICENSES))
    add("about.diagnostics", stringResource(R.string.diagnostics_share), about,
        SettingsSearchTarget.Page(SettingsPage.ABOUT, "about.diagnostics"),
        stringResource(R.string.diagnostics_share_hint))
    // Entwickler nur, wenn freigeschaltet und im Debug-Build (wie die Zeile unter «Erweitert»)
    if (settings.developerUnlocked && BuildConfig.DEBUG) {
        val developer = stringResource(R.string.settings_section_developer)
        addPage(SettingsPage.DEVELOPER, developer)
        add("developer.http_log", stringResource(R.string.settings_http_log), developer,
            SettingsSearchTarget.Page(SettingsPage.DEVELOPER, "developer.http_log"),
            stringResource(R.string.settings_http_log_hint))
    }
    return items
}
