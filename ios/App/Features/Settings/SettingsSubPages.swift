import SwiftUI

// Runde 13b: Unterseiten der Einstellungen — wie `SettingsSubScreens.kt`.
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
}

/// Zeile, die zu einer Unterseite führt: Symbol, Titel, Hinweis, rechts der Kurzwert.
@MainActor
struct SettingsSubPageLink<Destination: View>: View {
    let icon: String
    let title: String
    let subtitle: String?
    let value: String
    let destination: () -> Destination
    @Environment(\.appAccent) private var accent

    init(
        icon: String,
        title: String,
        subtitle: String? = nil,
        value: String,
        @ViewBuilder destination: @escaping () -> Destination
    ) {
        self.icon = icon
        self.title = title
        self.subtitle = subtitle
        self.value = value
        self.destination = destination
    }

    var body: some View {
        NavigationLink {
            destination()
        } label: {
            HStack(spacing: 12) {
                Image(systemName: icon)
                    .font(.body.weight(.medium))
                    .foregroundStyle(accent.primary)
                    .frame(width: 26)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.body).foregroundStyle(AppColors.onSurface)
                    if let subtitle, !subtitle.isEmpty {
                        Text(subtitle)
                            .font(.footnote)
                            .foregroundStyle(AppColors.onSurfaceVariant)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                Spacer(minLength: 8)
                Text(value)
                    .font(.body)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .lineLimit(1)
                // Spiegelt sich in Rechts-nach-links-Sprachen automatisch
                Image(systemName: "chevron.forward")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .accessibilityHidden(true)
            }
            .padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(title)
        .accessibilityValue(value)
        .accessibilityHint(subtitle ?? "")
        .accessibilityAddTraits(.isButton)
    }
}

/// Gerüst einer Unterseite: scrollbarer Inhalt in lesbarer Breite, Titel in der Leiste.
@MainActor
private struct SettingsSubPage<Content: View>: View {
    let title: String
    let content: () -> Content

    init(title: String, @ViewBuilder content: @escaping () -> Content) {
        self.title = title
        self.content = content
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                SettingsCard { content() }
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .padding(.bottom, 24)
            .readableContentWidth()
        }
        .background(AppColors.background.ignoresSafeArea())
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
    }
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
        }
    }

    private func requestNotifications() {
        Task { _ = await Notifier.requestPermission() }
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
            if settings.ttsEnabled {
                RowDivider()
                SwitchRow(
                    title: L("settings_tts_alarms_only"),
                    subtitle: L("settings_tts_alarms_only_hint"),
                    isOn: $data.settings.ttsAlarmsOnly
                )
                RowDivider()
                VStack(alignment: .leading, spacing: 6) {
                    Text(L("settings_speech_rate", settings.ttsSpeechRate))
                        .font(.body)
                        .monospacedDigit()
                    HStack(spacing: 10) {
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
                        .padding(.vertical, 10)
                }
                .buttonStyle(TonalButtonStyle())
                .padding(.top, 8)
                .padding(.bottom, 10)
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
