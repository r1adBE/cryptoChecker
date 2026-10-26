import SwiftUI

// Unterseiten «Markt-Meldungen» und «Sprachausgabe» der Einstellungen — wie in `SettingsSubPages.kt`. Kurzwerte und
// Seitenrahmen in SettingsSubPages.swift.

/// Markt-Meldungen: Fear & Greed, Gas, Bitcoin-Kennzahlen, ungewöhnliche Aktivität, Wirtschaftstermine.
@MainActor
struct MarketAlertsSettingsPage: View {
    @EnvironmentObject private var data: AppData

    init() {}

    private var settings: AppSettings { data.settings }

    var body: some View {
        SettingsSubPage(title: L("settings_market_alerts")) {
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
            // Was gwei und sat/vB bedeuten (Text der Gas-Karte im Markt-Tab), dann wann gemeldet wird — wie Android
            SettingsHint(text: L("market_info_gas_what"))
            SettingsHint(text: L("settings_gas_alert_hint"))
            RowDivider()
            // Eigene Grenzen für Bitcoin-Kennzahlen: feste Zahlen zur Wahl, keine Zonen und keine Wertung
            ForEach(IndicatorAlertKind.allCases, id: \.self) { kind in
                ChoiceRow(
                    title: L(kind.labelKey),
                    options: kind.choices,
                    selection: settings.indicatorAlerts[kind.rawValue] ?? 0,
                    label: { $0 == 0 ? L("option_off") : kind.valueText($0) },
                    onSelect: { value in
                        if value > 0 { requestNotifications() }
                        data.settings.indicatorAlerts[kind.rawValue] = value > 0 ? value : nil
                        BackgroundRefresh.scheduleZone(settings: data.settings)
                    }
                )
                .settingsAnchor(kind == .MAYER_ABOVE ? "market.indicators" : "market.indicators.\(kind.rawValue.lowercased())")
            }
            SettingsHint(text: L("settings_indicator_alerts_hint"))
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
                .haptic(.selection, trigger: settings.ttsSpeechRate)

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
