import SwiftUI

// Index der Suche in den Einstellungen (Oberfläche in SettingsSearchUi.swift): alle durchsuchbaren Punkte und die
// Unterseite je Treffer — wie der Index in `SettingsSearchCatalog.kt`.

// MARK: - Index

@MainActor
enum SettingsSearchCatalog {

    /// Alle durchsuchbaren Punkte in der Sprache der App — Hauptseite und Unterseiten, in der
    /// Reihenfolge der Seiten. Ids sind zugleich die Anker (`settingsAnchor`).
    static func items(settings: AppSettings) -> [SettingsSearchItem] {
        var items: [SettingsSearchItem] = []
        func add(_ id: String, _ title: String, _ path: String, _ target: SettingsSearchTarget, _ synonyms: [String] = []) {
            items.append(SettingsSearchItem(entry: SettingsSearchEntry(id: id, title: title, path: path, synonyms: synonyms),
                                            target: target))
        }

        // Hauptseite Allgemein · Darstellung · Mehr; der Rest unter «Erweitert». Der Pfad einer Seite kommt
        // aus ihrem Ort (`SettingsSearchPage.home`), damit er nach jeder Umstellung zur Anordnung passt
        let general = L("settings_group_general")
        let appearance = L("settings_section_appearance")
        let more = L("settings_group_more")
        let advanced = L("settings_row_advanced")
        func homePath(_ page: SettingsSearchPage) -> String {
            switch page.home {
            case .general: return general
            case .appearance: return appearance
            case .more: return more
            case .advanced: return advanced
            case nil: return ""
            }
        }
        func addPage(_ id: String, _ page: SettingsSearchPage, _ title: String, anchor: String? = nil, _ synonyms: [String] = []) {
            add(id, title, homePath(page), .page(page, anchor: anchor), synonyms)
        }
        addPage("page.advanced", .advanced, advanced)
        // Seite «Portfolio» (Zeile unter «Allgemein», einziger Ort für alles zum Portfolio): früh im Index, damit sie
        // bei «Portfolio» vor «Portfolio-Sperre» und «Portfolio in Systemsicherung» steht (gleich viele Punkte →
        // frühere Stelle zuerst) — wie Android
        let portfolio = L("portfolio_title")
        addPage("page.portfolio", .portfolio, portfolio, [L("settings_portfolio_tab"), L("portfolio_setting_hint")])
        add("main.language", L("settings_section_language"), general, .main("main.language"), [L("ios_settings_language_hint")])
        let currency = L("settings_row_currency")
        addPage("page.currency", .currency, currency, [L("currency_search")])
        add("currency.conversion", L("settings_conversion_currency"), currency, .page(.currency, anchor: "currency.conversion"),
            [L("settings_conversion_currency_hint")])
        let updates = L("settings_row_updates")
        addPage("page.updates", .updates, updates)
        let updatesPath = updates
        // «Wie aktuell?»: eine Wahl statt der früheren drei Schalter; deren Namen bleiben Suchwörter (wie Android)
        add("updates.mode", L("update_mode_title"), updatesPath, .page(.updates, anchor: "updates.mode"),
            [L("update_mode_sparsam"), L("update_mode_live"), L("settings_background_updates"),
             L("settings_live_service"), L("settings_live_websocket")])
        add("updates.background_interval", L("settings_background_interval"), updatesPath,
            .page(.updates, anchor: "updates.background_interval"), [L("settings_ios_background_hint")])
        // Alarm-Protokoll: Zeile in der Karte «Letzte Prüfungen» (ganz unten auf der Seite) — dorthin springen, wie Android
        add("updates.alarm_log", L("alarm_log_title"), updatesPath, .page(.updates, anchor: "updates.check_runs"),
            [L("alarm_log_open"), L("alarm_log_intro"), L("settings_check_runs_title")])
        let watchlist = L("settings_row_watchlist")
        addPage("page.watchlist", .watchlist, watchlist)
        add("watchlist.names", L("settings_watchlist_names"), watchlist, .page(.watchlist, anchor: "watchlist.names"),
            [L("settings_watchlist_names_hint")])
        add("watchlist.sparkline", L("settings_watchlist_sparkline"), watchlist, .page(.watchlist, anchor: "watchlist.sparkline"),
            [L("settings_watchlist_sparkline_hint")])
        add("watchlist.converted", L("settings_show_converted"), watchlist, .page(.watchlist, anchor: "watchlist.converted"),
            [L("settings_show_converted_hint", settings.portfolioCurrency)])
        add("watchlist.activity", L("settings_watchlist_activity_card"), watchlist, .page(.watchlist, anchor: "watchlist.activity"),
            [L("settings_watchlist_activity_card_hint"), "⚡"])

        // 2 Darstellung
        let mode = L("settings_theme_mode")
        addPage("page.display_mode", .displayMode, mode,
            [L("theme_system"), L("theme_light"), L("theme_dark")])
        add("display.contrast", L("settings_high_contrast"), mode, .page(.displayMode, anchor: "display.contrast"),
            [L("settings_high_contrast_hint")])
        addPage("page.theme", .theme, L("settings_accent"),
            AccentColor.allCases.map { L($0.labelKey) })
        addPage("page.price_colors", .priceColors, L("settings_price_colors"),
            [L("settings_price_colors_hint")] + PriceColorScheme.allCases.map { L($0.labelKey) })
        add("price_colors.swap", L("price_colors_swap"), L("settings_price_colors"), .page(.priceColors, anchor: "price_colors.swap"),
            [L("price_colors_red_up_hint")])
        // %-Änderung unter «Merkliste» (Review D13), Coin-Logos unter «Erweitert», Widgets unter «Darstellung»
        let changeBasis = L("settings_change_basis", "%")
        add("page.change_basis", changeBasis, watchlist, .page(.changeBasis, anchor: nil),
            [L("settings_change_basis_hint_1"), L("change_basis_rolling"), L("change_basis_since_last"), L("change_basis_device", "UTC"),
             // Alle Zonen in einem Text: «UTC+8» findet die Seite einmal, nicht 27 fast gleiche Treffer
             ChangeBasis.allCases.filter { $0.kind == .utcDay }.map { A11y.changeZoneChoice($0) }.joined(separator: " ")])
        let logos = L("settings_coin_logos")
        addPage("page.coin_logos", .coinLogos, logos,
            [L("settings_coin_logos_footer"), "CoinGecko"])
        add("logos.app", L("settings_coin_logos_app"), logos, .page(.coinLogos, anchor: "logos.app"),
            [L("settings_coin_logos_app_hint")])
        add("logos.portfolio", L("settings_coin_logos_portfolio"), logos, .page(.coinLogos, anchor: "logos.portfolio"),
            [L("settings_coin_logos_portfolio_hint")])
        add("logos.widgets", L("settings_coin_logos_widgets"), logos, .page(.coinLogos, anchor: "logos.widgets"),
            [L("settings_coin_logos_widgets_hint")])
        addPage("page.widgets", .widgets, L("settings_widgets"))

        // 3 Alarme (unter «Allgemein»); Markt-Meldungen und Sprachausgabe unter «Erweitert»
        let alarms = L("settings_row_alarms")
        addPage("page.alarms", .alarms, alarms)
        // «Damit Alarme ankommen»: Zeile oben auf der Seite, öffnet die Übersicht (Mitteilungen, Hintergrund, letzte Prüfung)
        add("alarms.delivery", L("delivery_title"), alarms, .page(.alarms, anchor: "alarms.delivery"),
            [L("delivery_notifications"), L("delivery_background_refresh"), L("delivery_time_sensitive"),
             L("delivery_last_check")])
        add("alarms.price", L("settings_price_notifications"), alarms, .page(.alarms, anchor: "alarms.price"),
            [L("ios_settings_price_notifications_hint")])
        add("alarms.change", L("settings_notification_change"), alarms, .page(.alarms, anchor: "alarms.change"),
            [L("settings_notification_change_hint")])
        add("alarms.cooldown", L("settings_alarm_cooldown"), alarms, .page(.alarms, anchor: "alarms.cooldown"))
        add("alarms.sound", L("settings_alarm_sound"), alarms, .page(.alarms, anchor: "alarms.sound"))
        add("alarms.signal", L("settings_alarm_signal"), alarms, .page(.alarms, anchor: "alarms.signal"),
            [L("ios_settings_alarm_signal_footer")])
        add("alarms.quiet", L("settings_quiet_hours"), alarms, .page(.alarms, anchor: "alarms.quiet"),
            [L("settings_quiet_hours_from"), L("settings_quiet_hours_to")])
        add("alarms.badge", L("settings_app_badge"), alarms, .page(.alarms, anchor: "alarms.badge"),
            [L("ios_settings_app_badge_hint")])
        add("alarms.depeg", L("settings_depeg_alarm"), alarms, .page(.alarms, anchor: "alarms.depeg"),
            [L("settings_depeg_alarm_hint"), "USDT", "Depeg"])
        add("alarms.test", L("alarm_test"), alarms, .page(.alarms, anchor: "alarms.test"), [L("alarm_test_hint")])
        let market = L("settings_market_alerts")
        addPage("page.market_alerts", .marketAlerts, market)
        add("market.fng_below", L("settings_fng_below"), market, .page(.marketAlerts, anchor: "market.fng_below"))
        add("market.fng_above", L("settings_fng_above"), market, .page(.marketAlerts, anchor: "market.fng_above"))
        add("market.gas_eth", L("settings_gas_eth_below"), market, .page(.marketAlerts, anchor: "market.gas_eth"),
            [L("settings_gas_alert_hint")])
        add("market.gas_btc", L("settings_gas_btc_below"), market, .page(.marketAlerts, anchor: "market.gas_btc"),
            [L("settings_gas_alert_hint")])
        add("market.indicators", L("neutral_btc_indicators_title"), market, .page(.marketAlerts, anchor: "market.indicators"),
            [L("settings_indicator_alerts_hint")])
        add("market.activity", L("settings_activity_alerts"), market, .page(.marketAlerts, anchor: "market.activity"),
            [L("settings_activity_alerts_hint")])
        add("market.sensitivity", L("settings_activity_sensitivity"), market, .page(.marketAlerts, anchor: "market.sensitivity"),
            [L("settings_activity_sensitivity_hint")])
        add("market.macro", L("settings_macro_notifications"), market, .page(.marketAlerts, anchor: "market.macro"),
            [L("settings_macro_notifications_hint")])
        let speech = L("settings_tts")
        addPage("page.speech", .speech, speech, anchor: "speech.enabled", [L("settings_tts_hint_silent")])
        add("speech.alarms_only", L("settings_tts_alarms_only"), speech, .page(.speech, anchor: "speech.alarms_only"),
            [L("settings_tts_alarms_only_hint")])
        add("speech.test", L("settings_tts_test"), speech, .page(.speech, anchor: "speech.test"))

        // 4 Portfolio (unter «Allgemein»; die Seite selbst steht oben im Index)
        add("portfolio.tab", L("settings_portfolio_tab"), portfolio, .page(.portfolio, anchor: "portfolio.tab"),
            [L("portfolio_setting_hint")])
        add("portfolio.lock", L("settings_portfolio_lock"), portfolio, .page(.portfolio, anchor: "portfolio.lock"),
            [L("settings_portfolio_lock_hint")])
        add("portfolio.hide", L("portfolio_hide_amounts"), portfolio, .page(.portfolio, anchor: "portfolio.hide"),
            [L("portfolio_hide_amounts_hint")])
        add("portfolio.cost_method", L("settings_cost_method"), portfolio, .page(.portfolio, anchor: "portfolio.cost_method"),
            [L("settings_cost_method_fifo"), L("settings_cost_method_average")])
        add("portfolio.tax_template", L("tax_template_title"), portfolio, .page(.portfolio, anchor: "portfolio.tax_template"),
            [L("tax_template_hint")])
        add("portfolio.system_backup", L("settings_portfolio_system_backup"), portfolio,
            .page(.portfolio, anchor: "portfolio.system_backup"), [L("settings_portfolio_system_backup_hint_ios")])
        add("portfolio.address_server", L("settings_address_server_title"), portfolio,
            .page(.portfolio, anchor: "portfolio.address_server"), [L("settings_address_server_hint")])

        // 5 Sicherung (unter «Mehr»)
        let backup = L("backup_title")
        addPage("page.backup", .backup, backup, [L("backup_hint")])
        add("backup.export", L("backup_export"), backup, .page(.backup, anchor: "backup.actions"))
        add("backup.import", L("backup_import"), backup, .page(.backup, anchor: "backup.actions"))
        // «App zurücksetzen»: eigene rote Karte ganz unten auf der Hauptseite
        add("main.reset", L("settings_reset_app"), "", .main("main.reset"), [L("settings_reset_app_confirm")])
        // «Token & TradFi» mit Alpha und QTLY: eigene Seite unter «Erweitert» (vorher «Merkliste»);
        // frühere Namen bleiben Suchwörter
        let futures = L("settings_asset_scope_title")
        addPage("page.markets", .markets, futures)
        let scopeWords: [String] = [
            L("settings_asset_scope_tokens"), L("settings_asset_scope_tokens_hint"),
            L("settings_asset_scope_tradfi"), L("settings_asset_scope_tradfi_hint"),
            L("settings_asset_scope_both"), "TradFi", "Token",
            L("settings_tradfi_futures"), L("settings_tradfi_futures_hint"),
        ]
        add("futures.scope", L("settings_asset_scope_title"), futures, .page(.markets, anchor: "futures.scope"),
            scopeWords)
        add("futures.alpha", L("settings_asset_scope_alpha"), futures, .page(.markets, anchor: "futures.alpha"),
            [L("settings_asset_scope_alpha_hint"), L("group_alpha"), "Alpha", "α"])
        add("futures.rolling", L("settings_rolling_futures_qtly"), futures, .page(.markets, anchor: "futures.rolling"),
            [L("settings_rolling_futures_hint"), "QTLY", L("settings_rolling_futures"), L("settings_row_dated_futures")])

        // 6 Über die App (unter «Mehr»): Links und Lizenzen auf der Seite selbst
        let about = L("settings_row_about")
        addPage("page.about", .about, about)
        add("about.privacy", L("about_privacy_policy"), about, .page(.about, anchor: "about.privacy"))
        add("about.exchange", L("about_request_exchange"), about, .page(.about, anchor: "about.exchange"))
        add("about.source", L("about_source_code"), about, .page(.about, anchor: "about.source"))
        add("about.licenses", L("about_licenses"), about, .page(.licenses, anchor: nil))
        add("about.diagnostics", L("diagnostics_share"), about, .page(.about, anchor: "about.diagnostics"),
            [L("diagnostics_share_hint")])
        // Entwickler nur, wenn freigeschaltet und im Debug-Build (wie die Zeile unter «Erweitert»)
        if settings.developerUnlocked && DeveloperOptions.available {
            let developer = L("settings_section_developer")
            addPage("page.developer", .developer, developer)
            add("developer.http_log", L("settings_http_log"), developer, .page(.developer, anchor: "developer.http_log"),
                [L("settings_http_log_hint")])
        }
        return items
    }

    /// Unterseite zu einem Ziel.
    @ViewBuilder
    static func destination(_ page: SettingsSearchPage) -> some View {
        switch page {
        case .currency: CurrencySettingsPage()
        case .updates: UpdatesSettingsPage()
        case .watchlist: WatchlistSettingsPage()
        case .displayMode: DisplayModeSettingsPage()
        case .theme: ThemeSettingsPage()
        case .priceColors: PriceColorsSettingsPage()
        case .changeBasis: ChangeBasisSettingsPage()
        case .coinLogos: CoinLogosSettingsPage()
        case .alarms: AlarmSettingsPage()
        case .marketAlerts: MarketAlertsSettingsPage()
        case .speech: SpeechSettingsPage()
        case .portfolio: PortfolioSettingsPage()
        case .backup: BackupSettingsPage()
        case .about: AboutSettingsPage()
        case .developer: DeveloperSettingsPage()
        case .widgets: WidgetsSearchDestination()
        case .advanced: AdvancedSettingsPage()
        case .markets: MarketsSettingsPage()
        case .licenses: LicensesSheet(asPage: true)
        }
    }
}

/// Widget-Seite als Ziel der Suche (wie die Zeile unter «Darstellung»), mit dem Portfolio-Schalter.
@MainActor
private struct WidgetsSearchDestination: View {
    @EnvironmentObject private var data: AppData

    var body: some View {
        AddWidgetHelpSheet(portfolioEnabled: data.settings.portfolioEnabled, asPage: true)
    }
}
