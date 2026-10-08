import SwiftUI

/// Modus: wie das System, Hell oder Dunkel (App und Widgets); dazu hoher Kontrast.
@MainActor
struct DisplayModeSettingsPage: View {
    @EnvironmentObject private var data: AppData

    init() {}

    var body: some View {
        SettingsCardsPage(title: L("settings_theme_mode")) {
            SettingsCard {
                SettingsThemePicker(selection: data.settings.darkMode) { data.settings.darkMode = $0 }
            }
            SettingsCard {
                // Hoher Kontrast: kräftigere Kursfarben, dunklere Nebentexte
                SwitchRow(
                    title: L("settings_high_contrast"),
                    subtitle: L("settings_high_contrast_hint"),
                    isOn: $data.settings.highContrast
                )
                .settingsAnchor("display.contrast")
            }
        }
    }
}

/// Theme: Akzentfarbe der App und der Widgets.
@MainActor
struct ThemeSettingsPage: View {
    @EnvironmentObject private var data: AppData

    init() {}

    var body: some View {
        SettingsSubPage(title: L("settings_accent")) {
            SettingsAccentPicker(selection: data.settings.accentColor) { data.settings.accentColor = $0 }
            SettingsHint(text: L("ios_settings_accent_hint"), top: 2)
        }
    }
}

/// «Coin-Logos»: echte Logos (CoinGecko, alle auf einmal geladen und auf dem Gerät gespeichert)
/// statt Initialen — getrennt für App, Portfolio und Widgets; ab Werk nur im Portfolio an.
@MainActor
struct CoinLogosSettingsPage: View {
    @EnvironmentObject private var data: AppData

    init() {}

    var body: some View {
        SettingsCardsPage(title: L("settings_coin_logos")) {
            SettingsCard {
                SwitchRow(
                    title: L("settings_coin_logos_app"),
                    subtitle: L("settings_coin_logos_app_hint"),
                    isOn: $data.settings.coinLogos
                )
                .settingsAnchor("logos.app")
                RowDivider()
                SwitchRow(
                    title: L("settings_coin_logos_portfolio"),
                    subtitle: L("settings_coin_logos_portfolio_hint"),
                    isOn: $data.settings.portfolioCoinLogos
                )
                .settingsAnchor("logos.portfolio")
                RowDivider()
                SwitchRow(
                    title: L("settings_coin_logos_widgets"),
                    subtitle: L("settings_coin_logos_widgets_hint"),
                    isOn: $data.settings.widgetCoinLogos
                )
                .settingsAnchor("logos.widgets")
            }
            SettingsHint(text: L("settings_coin_logos_footer"), top: 2)
                .padding(.horizontal, 16)
        }
    }
}

/// Kursfarben: Grün steigt / Rot fällt (Standard), Rot steigt / Grün fällt (Ostasien) und die
/// Fassungen für Farbsehschwäche (Blau/Orange). Gespeichert als Schema + «getauscht».
@MainActor
struct PriceColorsSettingsPage: View {
    @EnvironmentObject private var data: AppData
    @Environment(\.appAccent) private var accent

    init() {}

    private var current: PriceColorChoice {
        PriceColorChoice.of(scheme: data.settings.priceColorScheme, inverted: data.settings.priceColorsInverted)
    }

    var body: some View {
        SettingsCardsPage(title: L("settings_price_colors")) {
            SettingsCard {
                ForEach(Array(PriceColorChoice.allCases.enumerated()), id: \.offset) { index, choice in
                    if index > 0 { RowDivider() }
                    row(choice)
                }
            }
            SettingsHint(text: L("settings_price_colors_hint"), top: 2)
                .padding(.horizontal, 16)
        }
        .sensoryFeedback(.selection, trigger: current)
    }

    private func row(_ choice: PriceColorChoice) -> some View {
        let selected = choice == current
        return Button {
            if data.settings.priceColorScheme != choice.scheme { data.settings.priceColorScheme = choice.scheme }
            if data.settings.priceColorsInverted != choice.inverted { data.settings.priceColorsInverted = choice.inverted }
        } label: {
            HStack(spacing: 12) {
                PriceArrowsView(choice: choice)
                    .frame(minWidth: 30, alignment: .leading)
                VStack(alignment: .leading, spacing: 2) {
                    Text(L(choice.labelKey))
                        .font(.body)
                        .foregroundStyle(AppColors.onSurface)
                    if choice == .RED_UP {
                        Text(L("price_colors_red_up_hint"))
                            .font(.footnote)
                            .foregroundStyle(AppColors.onSurfaceVariant)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                Spacer(minLength: 8)
                if selected {
                    Image(systemName: "checkmark")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(accent.primary)
                }
            }
            .frame(minHeight: 44)
            .padding(.vertical, Spacing.sm)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// «Basis der %-Änderung» wie Binance «Change(%) & Chart Timezone»: oben der nummerierte Hinweis,
/// darunter «Letzte 24 Std.» (Standard), die Zone des Geräts («UTC+2, 00:00 (Zeitzone des
/// Geräts)», folgt der Sommerzeit) und feste Zonen UTC+14 … UTC−12 — für Pille, Puls,
/// Aktionsblatt, Widgets und Live-Aktivität; Alarme rechnen unabhängig davon.
@MainActor
struct ChangeBasisSettingsPage: View {
    @EnvironmentObject private var data: AppData
    @Environment(\.appAccent) private var accent
    /// Zone des Geräts einmal beim Öffnen (Sommerzeit wechselt nicht, während die Seite offen ist).
    @State private var now = TimeUtils.nowMillis

    init() {}

    var body: some View {
        SettingsCardsPage(title: L("settings_change_basis", "%")) {
            VStack(alignment: .leading, spacing: Spacing.xs) {
                numbered(1, L("settings_change_basis_hint_1"))
                numbered(2, L("settings_change_basis_hint_2", L("change_basis_rolling")))
                numbered(3, L("settings_change_basis_hint_3"))
            }
            .padding(.horizontal, 16)
            .padding(.bottom, Spacing.sm)
            SettingsCard {
                ForEach(Array(ChangeBasis.allCases.enumerated()), id: \.offset) { index, basis in
                    if index > 0 { RowDivider() }
                    row(basis)
                }
            }
        }
        .sensoryFeedback(.selection, trigger: data.settings.changeBasis)
    }

    /// Ein Punkt des Hinweises, Folgezeilen eingerückt («1. …»).
    private func numbered(_ number: Int, _ text: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            Text(LocaleNumbers.integer(number) + ".")
                .frame(minWidth: 14, alignment: .leading)
            Text(text)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .font(.footnote)
        .foregroundStyle(AppColors.onSurfaceVariant)
        .accessibilityElement(children: .combine)
    }

    private func row(_ basis: ChangeBasis) -> some View {
        let selected = basis == data.settings.changeBasis
        return Button {
            if !selected { data.settings.changeBasis = basis }
        } label: {
            HStack(spacing: 12) {
                Text(A11y.changeChoiceLabel(basis, now: now))
                    .font(.body)
                    .foregroundStyle(AppColors.onSurface)
                Spacer(minLength: 8)
                if selected {
                    Image(systemName: "checkmark")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(accent.primary)
                }
            }
            .frame(minHeight: 44)
            .padding(.vertical, Spacing.sm)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}
