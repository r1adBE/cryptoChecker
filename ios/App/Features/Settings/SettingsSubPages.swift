import SwiftUI

// Runde 13b: Unterseiten der Einstellungen — wie `SettingsSubPages.kt`.
// «Alarme & Benachrichtigungen» hatte 17 Bedienelemente; Markt-Meldungen und
// Sprachausgabe stehen jetzt je auf einer eigenen Seite, erreichbar über eine
// Zeile mit Kurzwert. Einstellungen und Verhalten unverändert.

/// Kurzwerte für die Zeilen der Unterseiten — wie `SettingsSummary.kt` (Android, mit Unit-Tests).
enum SettingsSummary {
    /// Themen der Markt-Meldungen, die gezählt werden.
    static let marketAlertTopics = 5

    /// Wie viele Themen eingeschaltet sind: Fear & Greed (unter oder über einer Grenze), Gas (ETH oder BTC),
    /// ungewöhnliche Aktivität, Wirtschaftstermine, eigene Grenzen der Bitcoin-Kennzahlen — wie Android.
    static func marketAlertCount(
        fearGreedBelow: Int, fearGreedAbove: Int,
        gasEthTenths: Int, gasBtc: Int, activity: Bool, macro: Bool, indicators: Bool = false
    ) -> Int {
        let fearGreed = fearGreedBelow > 0 || fearGreedAbove > 0
        let gas = gasEthTenths > 0 || gasBtc > 0
        return [fearGreed, gas, activity, macro, indicators].filter { $0 }.count
    }

    static func marketAlertCount(_ s: AppSettings) -> Int {
        marketAlertCount(
            fearGreedBelow: s.fearGreedBelow, fearGreedAbove: s.fearGreedAbove,
            gasEthTenths: s.gasAlertEthTenths, gasBtc: s.gasAlertBtc,
            activity: s.activityAlerts, macro: s.macroNotifications,
            indicators: !IndicatorAlertRules.active(s.indicatorAlerts).isEmpty
        )
    }

    enum Speech { case off, on, alarmsOnly }

    static func speech(enabled: Bool, alarmsOnly: Bool) -> Speech {
        if !enabled { return .off }
        return alarmsOnly ? .alarmsOnly : .on
    }

    /// «Aus» oder «2 aktiv».
    static func marketAlertsText(_ s: AppSettings) -> String {
        let count = marketAlertCount(s)
        return count == 0 ? L("option_off") : L("settings_market_alerts_active", count: count)
    }

    /// «Aus», «Ein» oder «Nur Alarme».
    static func speechText(_ s: AppSettings) -> String {
        switch speech(enabled: s.ttsEnabled, alarmsOnly: s.ttsAlarmsOnly) {
        case .off: return L("option_off")
        case .on: return L("settings_summary_on")
        case .alarmsOnly: return L("settings_tts_alarms_only")
        }
    }

    // MARK: Runde 23f — Kurzwerte der Hauptseite

    /// «Portfolio»: Die Sperre zählt nur bei eingeschaltetem Portfolio.
    enum Portfolio { case off, on, locked }

    static func portfolio(enabled: Bool, lock: Bool) -> Portfolio {
        if !enabled { return .off }
        return lock ? .locked : .on
    }

    /// Teile des Kurzwerts «Merkliste»; leer = nur Kurs.
    enum WatchlistPart { case names, sparkline, converted }

    static func watchlist(sparkline: Bool, converted: Bool, names: Bool = false) -> [WatchlistPart] {
        var parts: [WatchlistPart] = []
        if names { parts.append(.names) }
        if sparkline { parts.append(.sparkline) }
        if converted { parts.append(.converted) }
        return parts
    }

    /// Wahl von «Wie aktuell?» («Sparsam» oder «Live, solange offen») — wie `updatesSummary` (Android).
    static func updatesText(_ s: AppSettings) -> String {
        switch s.updateMode {
        case .SPARSAM: return L("update_mode_sparsam")
        // «Immer kurz» kommt nur aus alten Daten und wird beim Lesen schon zu «Live, solange offen»
        case .LIVE_OFFEN, .IMMER_KURZ: return L("update_mode_live")
        }
    }

    /// «Mini-Chart · ≈ CHF» oder «Nur Kurs».
    static func watchlistText(_ s: AppSettings) -> String {
        let parts = watchlist(sparkline: s.watchlistSparkline, converted: s.showConverted, names: s.watchlistNames)
        if parts.isEmpty { return L("settings_watchlist_value_price_only") }
        return parts.map { part -> String in
            switch part {
            case .names: return L("settings_watchlist_value_names")
            case .sparkline: return L("settings_watchlist_value_sparkline")
            case .converted: return "≈ " + s.portfolioCurrency
            }
        }.joined(separator: " · ")
    }

    /// «App · Portfolio · Widgets», einzelne davon oder «Aus» (Coin-Logos).
    static func coinLogosText(_ s: AppSettings) -> String {
        var parts: [String] = []
        if s.coinLogos { parts.append(L("settings_coin_logos_value_app")) }
        if s.portfolioCoinLogos { parts.append(L("portfolio_title")) }
        if s.widgetCoinLogos { parts.append(L("settings_widgets")) }
        return parts.isEmpty ? L("option_off") : parts.joined(separator: " · ")
    }

    /// Wert der Zeile «Kursfarben»: Stil, getauscht mit «· Farben tauschen» — wie Android `priceColorsSummary`.
    static func priceColorsText(_ s: AppSettings) -> String {
        let name = L(s.priceColorScheme.labelKey)
        return s.priceColorsInverted ? name + " · " + L("price_colors_swap") : name
    }

    /// «System», «Hell» oder «Dunkel», bei hohem Kontrast mit Zusatz.
    static func displayModeText(_ s: AppSettings) -> String {
        let mode = L(themeModeKey(s.darkMode))
        return s.highContrast ? mode + " · " + L("settings_high_contrast") : mode
    }

    static func themeModeKey(_ dark: Bool?) -> String {
        switch dark {
        case nil: return "theme_system"
        case false?: return "theme_light"
        case true?: return "theme_dark"
        }
    }

    /// «Aus», «Ein» oder «Ein, mit Sperre».
    static func portfolioText(_ s: AppSettings) -> String {
        switch portfolio(enabled: s.portfolioEnabled, lock: s.appLock) {
        case .off: return L("option_off")
        case .on: return L("settings_summary_on")
        case .locked: return L("settings_portfolio_value_locked")
        }
    }

    static func onOff(_ on: Bool) -> String { L(on ? "settings_summary_on" : "option_off") }
}

/// Name eines Stils auf der Seite «Kursfarben» («Ruhig», «Frisch», «Traditionell», «Farbsehschwäche», «Ruhig Farbsehschwäche») —
/// wie Android `priceColorSchemeLabel`.
extension PriceColorScheme {
    var labelKey: String {
        switch self {
        case .CALM: return "price_style_calm"
        case .GREEN_RED: return "price_style_fresh"
        case .TRADITIONAL: return "price_style_traditional"
        case .BLUE_ORANGE: return "price_style_color_vision"
        case .CALM_COLOR_VISION: return "price_style_calm_color_vision"
        }
    }
}

/// «↗↘» in den Farben eines Stils (getauscht: `inverted`); für VoiceOver verborgen (den Namen
/// trägt die Zeile). Die schrägen Pfeile wie überall in der App bleiben richtungsgebunden
/// (↗ = steigend), nur die Farben wechseln.
@MainActor
struct PriceArrowsView: View {
    let scheme: PriceColorScheme
    let inverted: Bool
    @Environment(\.priceHighContrast) private var highContrast

    var body: some View {
        HStack(spacing: 2) {
            Image(systemName: "arrow.up.right")
                .foregroundStyle(scheme.up(highContrast: highContrast, inverted: inverted))
            Image(systemName: "arrow.down.right")
                .foregroundStyle(scheme.down(highContrast: highContrast, inverted: inverted))
        }
        // Klein wie der Wert unter dem Titel der Zeile «Kursfarben» (Android: bodySmall)
        .font(.footnote.weight(.bold))
        .accessibilityHidden(true)
    }
}

/// Gerüst einer Unterseite: scrollbarer Inhalt in lesbarer Breite, Titel in der Leiste.
/// Aus der Suche geöffnet (`settingsHighlight`): scrollt zum gesuchten Punkt.
@MainActor
struct SettingsSubPage<Content: View>: View {
    let title: String
    let content: () -> Content
    @Environment(\.settingsHighlight) private var highlight

    init(title: String, @ViewBuilder content: @escaping () -> Content) {
        self.title = title
        self.content = content
    }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    SettingsCard { content() }
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .padding(.bottom, 24)
                .readableContentWidth()
            }
            .task { await settingsScrollToHighlight(highlight, proxy: proxy) }
        }
        .background(AppColors.background.ignoresSafeArea())
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// Wie `SettingsSubPage`, aber mehrere Karten (`SettingsCard`) und Hinweise untereinander (Runde 23f).
@MainActor
struct SettingsCardsPage<Content: View>: View {
    let title: String
    let content: () -> Content
    @Environment(\.settingsHighlight) private var highlight

    init(title: String, @ViewBuilder content: @escaping () -> Content) {
        self.title = title
        self.content = content
    }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    content()
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .padding(.bottom, 24)
                .readableContentWidth()
            }
            .task { await settingsScrollToHighlight(highlight, proxy: proxy) }
        }
        .background(AppColors.background.ignoresSafeArea())
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// Suche in den Einstellungen: nach dem Einschieben der Seite zum gesuchten Punkt scrollen.
@MainActor
private func settingsScrollToHighlight(_ anchor: String?, proxy: ScrollViewProxy) async {
    guard let anchor else { return }
    try? await Task.sleep(nanoseconds: SettingsHighlightTiming.delayNanos)
    withAnimation { proxy.scrollTo(anchor, anchor: .center) }
}
