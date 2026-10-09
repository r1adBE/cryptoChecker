import SwiftUI

// Runde 13b: Unterseiten der Einstellungen — wie `SettingsSubPages.kt`.
// «Alarme & Benachrichtigungen» hatte 17 Bedienelemente; Markt-Meldungen und
// Sprachausgabe stehen jetzt je auf einer eigenen Seite, erreichbar über eine
// Zeile mit Kurzwert. Einstellungen und Verhalten unverändert.

/// Kurzwerte für die Zeilen der Unterseiten — wie `SettingsSummary.kt` (Android, mit Unit-Tests).
enum SettingsSummary {
    /// Themen der Markt-Meldungen, die gezählt werden.
    static let marketAlertTopics = 5

    /// Wie viele Themen eingeschaltet sind: Marktphase, Fear & Greed (unter oder über
    /// einer Grenze), Gas (ETH oder BTC), ungewöhnliche Aktivität, Wirtschaftstermine.
    static func marketAlertCount(
        zone: Bool, fearGreedBelow: Int, fearGreedAbove: Int,
        gasEthTenths: Int, gasBtc: Int, activity: Bool, macro: Bool
    ) -> Int {
        let fearGreed = fearGreedBelow > 0 || fearGreedAbove > 0
        let gas = gasEthTenths > 0 || gasBtc > 0
        return [zone, fearGreed, gas, activity, macro].filter { $0 }.count
    }

    static func marketAlertCount(_ s: AppSettings) -> Int {
        marketAlertCount(
            zone: s.zoneAlerts, fearGreedBelow: s.fearGreedBelow, fearGreedAbove: s.fearGreedAbove,
            gasEthTenths: s.gasAlertEthTenths, gasBtc: s.gasAlertBtc,
            activity: s.activityAlerts, macro: s.macroNotifications
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

    /// «Aktualisierung»: Live geht vor dem Hintergrund-Intervall.
    enum Updates { case off, background, live }

    static func updates(liveService: Bool, backgroundUpdates: Bool) -> Updates {
        if liveService { return .live }
        return backgroundUpdates ? .background : .off
    }

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

    /// Live-Intervall kurz: «15 s», «5 min» statt «300 Sekunden».
    static func liveIntervalText(_ seconds: Int) -> String {
        seconds >= 60 && seconds % 60 == 0 ? L("settings_minutes", seconds / 60) : L("settings_seconds", seconds)
    }

    /// «Live · 15 s», «Alle 15 min» oder «Aus».
    static func updatesText(_ s: AppSettings) -> String {
        switch updates(liveService: s.liveService, backgroundUpdates: s.backgroundUpdates) {
        case .live: return L("settings_updates_live", liveIntervalText(s.liveIntervalSeconds))
        case .background: return L("settings_updates_every", L("settings_minutes", s.backgroundIntervalMinutes))
        case .off: return L("option_off")
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

/// Runde 23f: die vier Wahlmöglichkeiten der Seite «Kursfarben» — wie `PriceColorChoice.kt`
/// (mit Unit-Tests). Gespeichert bleiben Schema + «getauscht»; Pfeile und Vorzeichen bleiben
/// richtungsgebunden (▲ = steigend), nur die Farben wechseln.
enum PriceColorChoice: CaseIterable, Identifiable {
    case GREEN_UP, RED_UP, BLUE_UP, ORANGE_UP

    var id: Self { self }

    var scheme: PriceColorScheme {
        switch self {
        case .GREEN_UP, .RED_UP: return .GREEN_RED
        case .BLUE_UP, .ORANGE_UP: return .BLUE_ORANGE
        }
    }

    var inverted: Bool { self == .RED_UP || self == .ORANGE_UP }

    static func of(scheme: PriceColorScheme, inverted: Bool) -> PriceColorChoice {
        allCases.first { $0.scheme == scheme && $0.inverted == inverted } ?? .GREEN_UP
    }

    var labelKey: String {
        switch self {
        case .GREEN_UP: return "price_colors_green_up"
        case .RED_UP: return "price_colors_red_up"
        case .BLUE_UP: return "price_colors_blue_up"
        case .ORANGE_UP: return "price_colors_orange_up"
        }
    }
}

/// «▲▼» in den Farben einer Wahl; für VoiceOver verborgen (den Namen trägt die Zeile).
@MainActor
struct PriceArrowsView: View {
    let choice: PriceColorChoice
    @Environment(\.priceHighContrast) private var highContrast

    var body: some View {
        HStack(spacing: 0) {
            Text("▲").foregroundStyle(choice.scheme.up(highContrast: highContrast, inverted: choice.inverted))
            Text("▼").foregroundStyle(choice.scheme.down(highContrast: highContrast, inverted: choice.inverted))
        }
        .font(.body.weight(.bold))
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

/// Markt-Meldungen: Phase, Fear & Greed, Gas, ungewöhnliche Aktivität, Wirtschaftstermine.
@MainActor
struct MarketAlertsSettingsPage: View {
    @EnvironmentObject private var data: AppData

    init() {}

    private var settings: AppSettings { data.settings }

    var body: some View {
        SettingsSubPage(title: L("settings_market_alerts")) {
            SwitchRow(
                title: L("settings_zone_alerts"),
                subtitle: L("settings_zone_alerts_hint"),
                isOn: Binding(
                    get: { data.settings.zoneAlerts },
                    set: { on in
                        data.settings.zoneAlerts = on
                        if on { requestNotifications() }
                    }
                )
            )
            .settingsAnchor("market.zone")
            // Fear & Greed: Meldung unter/über einer Grenze (0 = aus)
            ChoiceRow(
                title: L("settings_fng_below"),
                options: AppSettings.fearGreedBelowChoices,
                selection: settings.fearGreedBelow,
                label: { $0 == 0 ? L("option_off") : String($0) },
                onSelect: { value in
                    if value > 0 { requestNotifications() }
                    data.settings.fearGreedBelow = value
                }
            )
            .settingsAnchor("market.fng_below")
            ChoiceRow(
                title: L("settings_fng_above"),
                options: AppSettings.fearGreedAboveChoices,
                selection: settings.fearGreedAbove,
                label: { $0 == 0 ? L("option_off") : String($0) },
                onSelect: { value in
                    if value > 0 { requestNotifications() }
                    data.settings.fearGreedAbove = value
                }
            )
            .settingsAnchor("market.fng_above")
            RowDivider()
            // Gas-Alarm (#167): normale Gebühr fällt unter die Grenze
            ChoiceRow(
                title: L("settings_gas_eth_below"),
                options: AppSettings.gasEthChoices,
                selection: settings.gasAlertEthTenths,
                label: { $0 == 0 ? L("option_off") : GasFees.formatGwei(Double($0) / 10) },
                onSelect: { value in
                    if value > 0 { requestNotifications() }
                    data.settings.gasAlertEthTenths = value
                }
            )
            .settingsAnchor("market.gas_eth")
            ChoiceRow(
                title: L("settings_gas_btc_below"),
                options: AppSettings.gasBtcChoices,
                selection: settings.gasAlertBtc,
                label: { $0 == 0 ? L("option_off") : String($0) },
                onSelect: { value in
                    if value > 0 { requestNotifications() }
                    data.settings.gasAlertBtc = value
                }
            )
            .settingsAnchor("market.gas_btc")
            SettingsHint(text: L("settings_gas_alert_hint"))
            RowDivider()
            // Ungewöhnliche Aktivität: höchstens stündlich je Paar
            SwitchRow(
                title: L("settings_activity_alerts"),
                subtitle: L("settings_activity_alerts_hint"),
                isOn: Binding(
                    get: { data.settings.activityAlerts },
                    set: { on in
                        data.settings.activityAlerts = on
                        if on { requestNotifications() }
                    }
                )
            )
            .settingsAnchor("market.activity")
            // Empfindlichkeit: gilt für die Karte in der Merkliste und die Mitteilungen gleich
            ChoiceRow(
                title: L("settings_activity_sensitivity"),
                options: ActivitySensitivity.allCases,
                selection: settings.activitySensitivity,
                label: { Self.sensitivityLabel($0) },
                onSelect: { data.settings.activitySensitivity = $0 }
            )
            .settingsAnchor("market.sensitivity")
            SettingsHint(text: L("settings_activity_sensitivity_hint"))
            RowDivider()
            // Wirtschaftstermine: Morgen-Mitteilung um 08:00 an Tagen mit US-Daten (CPI, Fed …)
            SwitchRow(
                title: L("settings_macro_notifications"),
                subtitle: L("settings_macro_notifications_hint"),
                isOn: Binding(
                    get: { data.settings.macroNotifications },
                    set: { on in
                        data.settings.macroNotifications = on
                        if on { requestNotifications() }
                        MacroNotifications.refresh(settings: data.settings)
                    }
                )
            )
            .settingsAnchor("market.macro")
        }
    }

    private func requestNotifications() {
        Task { _ = await Notifier.requestPermission() }
    }

    /// «Weniger» / «Normal» / «Mehr».
    static func sensitivityLabel(_ sensitivity: ActivitySensitivity) -> String {
        switch sensitivity {
        case .LESS: return L("activity_sensitivity_less")
        case .NORMAL: return L("activity_sensitivity_normal")
        case .MORE: return L("activity_sensitivity_more")
        }
    }
}

/// Sprachausgabe: ein/aus, nur Alarme, Tempo, Probe.
@MainActor
struct SpeechSettingsPage: View {
    @EnvironmentObject private var data: AppData
    @Environment(\.appAccent) private var accent

    init() {}

    private var settings: AppSettings { data.settings }

    var body: some View {
        SettingsSubPage(title: L("settings_tts")) {
            SwitchRow(
                title: L("settings_tts"),
                subtitle: L("settings_tts_hint_silent"),
                isOn: $data.settings.ttsEnabled
            )
            .settingsAnchor("speech.enabled")
            if settings.ttsEnabled {
                RowDivider()
                SwitchRow(
                    title: L("settings_tts_alarms_only"),
                    subtitle: L("settings_tts_alarms_only_hint"),
                    isOn: $data.settings.ttsAlarmsOnly
                )
                .settingsAnchor("speech.alarms_only")
                RowDivider()
                VStack(alignment: .leading, spacing: Spacing.xs) {
                    Text(L("settings_speech_rate", settings.ttsSpeechRate))
                        .font(.body)
                        .monospacedDigit()
                    HStack(spacing: Spacing.sm) {
                        Image(systemName: "tortoise.fill")
                            .font(.footnote)
                            .foregroundStyle(AppColors.onSurfaceVariant)
                        Slider(value: $data.settings.ttsSpeechRate, in: 0.5...2.0, step: 0.25)
                            .tint(accent.primary)
                        Image(systemName: "hare.fill")
                            .font(.footnote)
                            .foregroundStyle(AppColors.onSurfaceVariant)
                    }
                }
                .padding(.top, 12)
                .sensoryFeedback(.selection, trigger: settings.ttsSpeechRate)

                Button(action: testSpeech) {
                    Label(L("settings_tts_test"), systemImage: "play.fill")
                        .font(.subheadline.weight(.semibold))
                        .padding(.horizontal, 16)
                        .padding(.vertical, Spacing.md)
                }
                .buttonStyle(TonalButtonStyle())
                .settingsAnchor("speech.test")
                .padding(.top, 8)
                .padding(.bottom, Spacing.sm)
            }
        }
    }

    /// Liest den zuletzt bekannten Kurs vor, damit die Stimme prüfbar ist.
    private func testSpeech() {
        let text: String
        if let watch = data.watches.first(where: { $0.lastPrice != nil }), let price = watch.lastPrice {
            text = SpokenText.price(watch, price)
        } else {
            text = "Crypto Checker"
        }
        Speaker.shared.speak(text, rate: settings.ttsSpeechRate, flush: true)
    }
}
