import SwiftUI

// Runde 31: Suche in den Einstellungen — wie `SettingsSearchUi.kt`. Die Hauptseite hat ein
// Suchfeld (`.searchable`); der Index umfasst ihre Zeilen und die Punkte der Unterseiten (Titel,
// Hinweise als Synonyme). Ein Treffer öffnet die Unterseite und hebt den Punkt kurz hervor
// (scrollt dorthin); Zeilen der Hauptseite werden dort hervorgehoben. Abgleich in `SettingsSearch`.

/// Unterseiten, zu denen ein Treffer führt.
enum SettingsSearchPage: Hashable {
    case currency, updates, watchlist, displayMode, theme, priceColors, changeBasis, coinLogos
    case alarms, marketAlerts, speech, portfolio, backup, about, developer
}

/// Wohin ein Treffer führt; `anchor` ist der Punkt, der kurz hervorgehoben wird.
enum SettingsSearchTarget: Hashable {
    /// Zeile der Hauptseite (Sprache, Widgets, Links).
    case main(String)
    case page(SettingsSearchPage, anchor: String?)

    var anchor: String? {
        switch self {
        case .main(let anchor): return anchor
        case .page(_, let anchor): return anchor
        }
    }
}

/// Ein Eintrag der Suche mit seinem Ziel.
struct SettingsSearchItem: Hashable {
    let entry: SettingsSearchEntry
    let target: SettingsSearchTarget
}

// MARK: - Hervorheben

private struct SettingsHighlightKey: EnvironmentKey {
    static let defaultValue: String? = nil
}

extension EnvironmentValues {
    /// Punkt, der auf der gerade gezeigten Seite hervorgehoben werden soll (oder keiner).
    var settingsHighlight: String? {
        get { self[SettingsHighlightKey.self] }
        set { self[SettingsHighlightKey.self] = newValue }
    }
}

/// Ziel der Suche: Ist der Punkt gemeint (`settingsHighlight`), hinterlegt er sich kurz in der
/// Akzentfarbe; zu ihm scrollen `SettingsSubPage`/`SettingsCardsPage` bzw. die Hauptseite (über
/// die `id`). Ohne Bewegung erscheint die Hinterlegung ohne Überblendung.
private struct SettingsAnchorModifier: ViewModifier {
    let id: String
    @Environment(\.settingsHighlight) private var highlight
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.appAccent) private var accent
    @State private var shown = false

    func body(content: Content) -> some View {
        content
            .background {
                RoundedRectangle(cornerRadius: Spacing.sm, style: .continuous)
                    .fill(accent.primary.opacity(shown ? SettingsHighlightTiming.opacity : 0))
                    .padding(.horizontal, -Spacing.sm)
                    .accessibilityHidden(true)
            }
            .id(id)
            .task(id: highlight == id) {
                guard highlight == id else {
                    shown = false
                    return
                }
                try? await Task.sleep(nanoseconds: SettingsHighlightTiming.delayNanos)
                withAnimation(reduceMotion ? nil : .easeOut(duration: 0.2)) { shown = true }
                try? await Task.sleep(nanoseconds: SettingsHighlightTiming.holdNanos)
                withAnimation(reduceMotion ? nil : .easeIn(duration: 0.7)) { shown = false }
            }
    }
}

/// Zeiten und Stärke der Hervorhebung (wie Android).
enum SettingsHighlightTiming {
    /// Bis die Seite eingeschoben ist.
    static let delayNanos: UInt64 = 350_000_000
    static let holdNanos: UInt64 = 1_200_000_000
    /// Hauptseite: so lange bleibt das Ziel gesetzt.
    static let mainNanos: UInt64 = 2_500_000_000
    static let opacity: Double = 0.16
}

extension View {
    /// Macht den Punkt zum Ziel der Suche in den Einstellungen.
    func settingsAnchor(_ id: String) -> some View {
        modifier(SettingsAnchorModifier(id: id))
    }
}

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

        // 1 Allgemein
        let general = L("settings_group_general")
        add("main.language", L("settings_section_language"), general, .main("main.language"), [L("ios_settings_language_hint")])
        let currency = L("settings_row_currency")
        add("page.currency", currency, general, .page(.currency, anchor: nil), [L("currency_search")])
        add("currency.conversion", L("settings_conversion_currency"), currency, .page(.currency, anchor: "currency.conversion"),
            [L("settings_conversion_currency_hint")])
        let updates = L("settings_row_updates")
        add("page.updates", updates, general, .page(.updates, anchor: nil))
        add("updates.background", L("settings_background_updates"), updates, .page(.updates, anchor: "updates.background"),
            [L("settings_background_updates_hint")])
        add("updates.background_interval", L("settings_background_interval"), updates,
            .page(.updates, anchor: "updates.background_interval"), [L("settings_ios_background_hint")])
        add("updates.live", L("settings_live_service"), updates, .page(.updates, anchor: "updates.live"),
            [L("ios_settings_live_service_hint")])
        add("updates.live_interval", L("settings_live_interval"), updates, .page(.updates, anchor: "updates.live_interval"))
        add("updates.live_websocket", L("settings_live_websocket"), updates, .page(.updates, anchor: "updates.live_websocket"),
            [L("settings_live_websocket_hint")])
        let watchlist = L("settings_row_watchlist")
        add("page.watchlist", watchlist, general, .page(.watchlist, anchor: nil))
        add("watchlist.names", L("settings_watchlist_names"), watchlist, .page(.watchlist, anchor: "watchlist.names"),
            [L("settings_watchlist_names_hint")])
        add("watchlist.sparkline", L("settings_watchlist_sparkline"), watchlist, .page(.watchlist, anchor: "watchlist.sparkline"),
            [L("settings_watchlist_sparkline_hint")])
        add("watchlist.converted", L("settings_show_converted"), watchlist, .page(.watchlist, anchor: "watchlist.converted"),
            [L("settings_show_converted_hint", settings.portfolioCurrency)])
        add("watchlist.activity", L("settings_watchlist_activity_card"), watchlist, .page(.watchlist, anchor: "watchlist.activity"),
            [L("settings_watchlist_activity_card_hint"), "⚡"])

        // 2 Darstellung
        let appearance = L("settings_section_appearance")
        let mode = L("settings_theme_mode")
        add("page.display_mode", mode, appearance, .page(.displayMode, anchor: nil),
            [L("theme_system"), L("theme_light"), L("theme_dark")])
        add("display.contrast", L("settings_high_contrast"), mode, .page(.displayMode, anchor: "display.contrast"),
            [L("settings_high_contrast_hint")])
        add("page.theme", L("settings_accent"), appearance, .page(.theme, anchor: nil),
            AccentColor.allCases.map { L($0.labelKey) })
        add("page.price_colors", L("settings_price_colors"), appearance, .page(.priceColors, anchor: nil),
            [L("settings_price_colors_hint")] + PriceColorChoice.allCases.map { L($0.labelKey) })
        add("page.change_basis", L("settings_change_basis", "%"), appearance, .page(.changeBasis, anchor: nil),
            [L("settings_change_basis_hint_1"), L("change_basis_rolling"), L("change_basis_since_last"), L("change_basis_device", "UTC"),
             // Alle Zonen in einem Text: «UTC+8» findet die Seite einmal, nicht 27 fast gleiche Treffer
             ChangeBasis.allCases.filter { $0.kind == .utcDay }.map { A11y.changeZoneChoice($0) }.joined(separator: " ")])
        add("change.period", L("settings_change_period"), L("settings_change_basis", "%"), .page(.changeBasis, anchor: "change.period"),
            [L("settings_change_period_hint")])
        let logos = L("settings_coin_logos")
        add("page.coin_logos", logos, appearance, .page(.coinLogos, anchor: nil),
            [L("settings_coin_logos_footer"), "CoinGecko"])
        add("logos.app", L("settings_coin_logos_app"), logos, .page(.coinLogos, anchor: "logos.app"),
            [L("settings_coin_logos_app_hint")])
        add("logos.portfolio", L("settings_coin_logos_portfolio"), logos, .page(.coinLogos, anchor: "logos.portfolio"),
            [L("settings_coin_logos_portfolio_hint")])
        add("logos.widgets", L("settings_coin_logos_widgets"), logos, .page(.coinLogos, anchor: "logos.widgets"),
            [L("settings_coin_logos_widgets_hint")])
        add("main.widgets", L("settings_widgets"), appearance, .main("main.widgets"))

        // 3 Alarme & Mitteilungen
        let alerts = L("settings_group_alerts")
        let alarms = L("settings_row_alarms")
        add("page.alarms", alarms, alerts, .page(.alarms, anchor: nil))
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
        add("alarms.test", L("alarm_test"), alarms, .page(.alarms, anchor: "alarms.test"), [L("alarm_test_hint")])
        let market = L("settings_market_alerts")
        add("page.market_alerts", market, alerts, .page(.marketAlerts, anchor: nil))
        add("market.zone", L("settings_zone_alerts"), market, .page(.marketAlerts, anchor: "market.zone"),
            [L("settings_zone_alerts_hint")])
        add("market.fng_below", L("settings_fng_below"), market, .page(.marketAlerts, anchor: "market.fng_below"))
        add("market.fng_above", L("settings_fng_above"), market, .page(.marketAlerts, anchor: "market.fng_above"))
        add("market.gas_eth", L("settings_gas_eth_below"), market, .page(.marketAlerts, anchor: "market.gas_eth"),
            [L("settings_gas_alert_hint")])
        add("market.gas_btc", L("settings_gas_btc_below"), market, .page(.marketAlerts, anchor: "market.gas_btc"),
            [L("settings_gas_alert_hint")])
        add("market.activity", L("settings_activity_alerts"), market, .page(.marketAlerts, anchor: "market.activity"),
            [L("settings_activity_alerts_hint")])
        add("market.sensitivity", L("settings_activity_sensitivity"), market, .page(.marketAlerts, anchor: "market.sensitivity"),
            [L("settings_activity_sensitivity_hint")])
        add("market.macro", L("settings_macro_notifications"), market, .page(.marketAlerts, anchor: "market.macro"),
            [L("settings_macro_notifications_hint")])
        let speech = L("settings_tts")
        add("page.speech", speech, alerts, .page(.speech, anchor: "speech.enabled"), [L("settings_tts_hint_silent")])
        add("speech.alarms_only", L("settings_tts_alarms_only"), speech, .page(.speech, anchor: "speech.alarms_only"),
            [L("settings_tts_alarms_only_hint")])
        add("speech.test", L("settings_tts_test"), speech, .page(.speech, anchor: "speech.test"))

        // 4 Portfolio
        let portfolio = L("portfolio_title")
        add("page.portfolio", portfolio, portfolio, .page(.portfolio, anchor: nil))
        add("portfolio.tab", L("settings_portfolio_tab"), portfolio, .page(.portfolio, anchor: "portfolio.tab"),
            [L("portfolio_setting_hint")])
        add("portfolio.lock", L("settings_portfolio_lock"), portfolio, .page(.portfolio, anchor: "portfolio.lock"),
            [L("settings_portfolio_lock_hint")])
        add("portfolio.hide", L("portfolio_hide_amounts"), portfolio, .page(.portfolio, anchor: "portfolio.hide"),
            [L("portfolio_hide_amounts_hint")])
        add("portfolio.system_backup", L("settings_portfolio_system_backup"), portfolio,
            .page(.portfolio, anchor: "portfolio.system_backup"), [L("settings_portfolio_system_backup_hint")])

        // 5 Daten
        let dataGroup = L("settings_group_data_only")
        let backup = L("backup_title")
        add("page.backup", backup, dataGroup, .page(.backup, anchor: nil), [L("backup_hint")])
        add("backup.export", L("backup_export"), backup, .page(.backup, anchor: "backup.actions"))
        add("backup.import", L("backup_import"), backup, .page(.backup, anchor: "backup.actions"))
        add("main.reset", L("settings_reset_app"), dataGroup, .main("main.reset"), [L("settings_reset_app_confirm")])
        // Laufzeit-Futures und TradFi liegen auf der Seite «Merkliste»
        let futures = L("settings_row_watchlist")
        add("futures.rolling", L("settings_rolling_futures"), futures, .page(.watchlist, anchor: "futures.rolling"),
            [L("settings_rolling_futures_hint")])
        add("futures.tradfi", L("settings_tradfi_futures"), futures, .page(.watchlist, anchor: "futures.tradfi"),
            [L("settings_tradfi_futures_hint"), "TradFi", L("settings_row_dated_futures")])

        // 6 Über
        let about = L("settings_section_about")
        add("page.about", L("settings_row_about"), about, .page(.about, anchor: nil))
        add("main.privacy", L("about_privacy_policy"), about, .main("main.privacy"))
        add("main.exchange", L("about_request_exchange"), about, .main("main.exchange"))
        add("main.source", L("about_source_code"), about, .main("main.source"))
        add("main.licenses", L("about_licenses"), about, .main("main.licenses"))
        // Entwickler nur, wenn freigeschaltet (wie die Zeile der Hauptseite)
        if settings.developerUnlocked {
            let developer = L("settings_section_developer")
            add("page.developer", developer, about, .page(.developer, anchor: nil))
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
        }
    }
}

// MARK: - Treffer

/// Treffer als Zeilen der Liste: Titel, darunter grau die Seite bzw. Gruppe; leer ein Hinweis.
/// Unterseiten öffnen sich mit dem Anker; Zeilen der Hauptseite meldet `onMain` (die Suche
/// schliesst sich dafür).
@MainActor
struct SettingsSearchResults: View {
    let items: [SettingsSearchItem]
    let query: String
    let onMain: (String) -> Void

    @Environment(\.dismissSearch) private var dismissSearch

    private var hits: [SettingsSearchItem] {
        // Sprache der App (nicht der Region): Türkisch «I» → «ı» usw.
        let language = Bundle.main.preferredLocalizations.first ?? "en"
        let index = SettingsSearch.index(items.map(\.entry), locale: Locale(identifier: language))
        let byId = Dictionary(items.map { ($0.entry.id, $0) }, uniquingKeysWith: { first, _ in first })
        return SettingsSearch.search(index, query: query).compactMap { byId[$0.id] }
    }

    var body: some View {
        let found = hits
        Section {
            if found.isEmpty {
                Text(L("watchlist_search_empty", query.trimmingCharacters(in: .whitespacesAndNewlines)))
                    .font(.subheadline)
                    .foregroundStyle(AppColors.onSurfaceVariant)
            }
            ForEach(found, id: \.entry.id) { item in
                switch item.target {
                case .main(let anchor):
                    Button {
                        dismissSearch()
                        onMain(anchor)
                    } label: {
                        resultLabel(item.entry, trailing: true)
                    }
                    .buttonStyle(.plain)
                case .page(let page, let anchor):
                    NavigationLink {
                        SettingsSearchCatalog.destination(page)
                            .environment(\.settingsHighlight, anchor)
                    } label: {
                        resultLabel(item.entry, trailing: false)
                    }
                }
            }
        }
        .listRowBackground(AppColors.container)
    }

    private func resultLabel(_ entry: SettingsSearchEntry, trailing: Bool) -> some View {
        HStack(spacing: Spacing.md) {
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.title)
                    .font(.body)
                    .foregroundStyle(AppColors.onSurface)
                if !entry.path.isEmpty && entry.path != entry.title {
                    Text(entry.path)
                        .font(.footnote)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: Spacing.sm)
            // NavigationLink setzt den Pfeil selbst; Zeilen der Hauptseite bekommen ihn hier
            if trailing {
                Image(systemName: "chevron.forward")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AppColors.outline)
                    .accessibilityHidden(true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}
