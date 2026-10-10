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
/// statt Initialen — getrennt für App, Portfolio und Widgets; ab Werk in App und Portfolio an.
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

/// Kursfarben: drei Stile als Karten — «Frisch» (Standard, Grün/Rot), «Traditionell»
/// (Olivgrün/Himbeer) und «Farbsehschwäche» (Blau/Orange); darunter «Farben tauschen»
/// (Ostasien: Rot = steigend) und hoher Kontrast. Gespeichert als Schema + «getauscht».
/// Wie Android `PriceColorsPage`.
@MainActor
struct PriceColorsSettingsPage: View {
    @EnvironmentObject private var data: AppData

    init() {}

    var body: some View {
        SettingsCardsPage(title: L("settings_price_colors")) {
            VStack(spacing: 8) {
                ForEach(PriceColorScheme.allCases) { scheme in
                    card(scheme)
                }
            }
            SettingsCard {
                SwitchRow(
                    title: L("price_colors_swap"),
                    subtitle: L("price_colors_red_up_hint"),
                    isOn: $data.settings.priceColorsInverted
                )
                .settingsAnchor("price_colors.swap")
                RowDivider()
                SwitchRow(
                    title: L("settings_high_contrast"),
                    subtitle: L("settings_high_contrast_hint"),
                    isOn: $data.settings.highContrast
                )
            }
            SettingsHint(text: L("settings_price_colors_hint"), top: 2)
                .padding(.horizontal, 16)
        }
        .sensoryFeedback(.selection, trigger: data.settings.priceColorScheme)
    }

    private func card(_ scheme: PriceColorScheme) -> some View {
        PriceStyleCard(scheme: scheme,
                       selected: scheme == data.settings.priceColorScheme,
                       inverted: data.settings.priceColorsInverted) {
            if data.settings.priceColorScheme != scheme { data.settings.priceColorScheme = scheme }
        }
    }
}

/// Karte eines Kursfarben-Stils — wie Android `PriceStyleCard`: zwei Farbfelder (steigend,
/// fallend), der Name und rechts eine kleine Kerzen-Vorschau. Felder und Vorschau zeigen genau
/// die Farben, die gelten würden (hoher Kontrast und «Farben tauschen» eingerechnet); alle
/// Kerzen gefüllt, wie in den echten Charts. Gewählt: Rand in der Akzentfarbe (wie der gewählte
/// Gruppen-Chip), Name kräftiger, Häkchen. VoiceOver: Name, Taste, «ausgewählt».
@MainActor
struct PriceStyleCard: View {
    let scheme: PriceColorScheme
    let selected: Bool
    let inverted: Bool
    let action: () -> Void

    @Environment(\.appAccent) private var accent
    @Environment(\.priceHighContrast) private var highContrast

    private var up: Color { scheme.up(highContrast: highContrast, inverted: inverted) }
    private var down: Color { scheme.down(highContrast: highContrast, inverted: inverted) }
    private var shape: RoundedRectangle { RoundedRectangle(cornerRadius: 16, style: .continuous) }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 8) {
                    swatches
                    title
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                PriceStyleCandles(up: up, down: down)
                    .frame(width: 112, height: 48)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .frame(minHeight: 72)
            .background(AppColors.container, in: shape)
            .overlay(shape.strokeBorder(selected ? accent.primary : Color.clear, lineWidth: 2))
            .contentShape(shape)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(L(scheme.labelKey))
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
    }

    /// Zwei kleine abgerundete Farbfelder: steigend, fallend.
    private var swatches: some View {
        HStack(spacing: 6) {
            RoundedRectangle(cornerRadius: 4, style: .continuous).fill(up).frame(width: 16, height: 16)
            RoundedRectangle(cornerRadius: 4, style: .continuous).fill(down).frame(width: 16, height: 16)
        }
        .accessibilityHidden(true)
    }

    private var title: some View {
        HStack(spacing: 6) {
            Text(L(scheme.labelKey))
                .font(.body.weight(selected ? .semibold : .regular))
                .foregroundStyle(AppColors.onSurface)
                .fixedSize(horizontal: false, vertical: true)
            if selected {
                Image(systemName: "checkmark")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(accent.primary)
                    .accessibilityHidden(true)
            }
        }
    }
}

/// Feste Kerzenreihe (`PriceStylePreview`): leichter Aufwärtstrend mit ein paar fallenden Kerzen,
/// ältere Kerzen blasser. Nur Dekoration (VoiceOver liest den Namen der Karte).
@MainActor
struct PriceStyleCandles: View {
    let up: Color
    let down: Color

    var body: some View {
        Canvas { context, size in
            PriceStylePreview.draw(in: &context, size: size, up: up, down: down)
        }
        .accessibilityHidden(true)
    }
}

/// Kerzen der Vorschau — dieselben Werte wie Android `PriceStylePreview`.
enum PriceStylePreview {
    /// 31 Schlusskurse → 30 Kerzen (Eröffnung = Schluss der vorigen).
    static let closes: [Int] = [
        20, 23, 25, 22, 24, 27, 30, 28, 25, 27, 30, 32, 34, 31, 35, 37,
        39, 36, 32, 36, 39, 44, 42, 47, 50, 52, 50, 53, 57, 59, 63,
    ]

    /// Dochtlänge je Kerze (oben und unten).
    static let wicks: [Int] = [2, 1, 3, 1, 3, 1, 3, 1, 2, 3, 2, 2, 2, 3, 2, 2, 2, 1, 1, 1, 1, 3, 2, 3, 2, 2, 2, 2, 3, 1]

    static let low: Double = {
        var value = Int.max
        for i in wicks.indices { value = min(value, min(closes[i], closes[i + 1]) - wicks[i]) }
        return Double(value)
    }()

    static let high: Double = {
        var value = Int.min
        for i in wicks.indices { value = max(value, max(closes[i], closes[i + 1]) + wicks[i]) }
        return Double(value)
    }()

    /// Deckkraft: die ältesten Kerzen 25 %, ab 70 % der Reihe voll.
    static func alpha(_ index: Int) -> Double {
        0.25 + 0.75 * min(1, Double(index) / (Double(wicks.count) * 0.7))
    }

    /// Docht oben und unten getrennt, damit er sich bei Transparenz nicht mit dem Körper überlagert.
    static func draw(in context: inout GraphicsContext, size: CGSize, up: Color, down: Color) {
        let slot = size.width / CGFloat(wicks.count)
        let bodyWidth = slot * 0.62
        let span = high - low
        func y(_ value: Int) -> CGFloat {
            size.height - CGFloat((Double(value) - low) / span) * size.height
        }
        for i in wicks.indices {
            let open = closes[i]
            let close = closes[i + 1]
            let top = max(open, close)
            let bottom = min(open, close)
            let color = (close >= open ? up : down).opacity(alpha(i))
            let cx = slot * CGFloat(i) + slot / 2
            let bodyTop = y(top)
            let bodyBottom = y(bottom)
            var line = Path()
            line.move(to: CGPoint(x: cx, y: y(top + wicks[i])))
            line.addLine(to: CGPoint(x: cx, y: bodyTop))
            line.move(to: CGPoint(x: cx, y: bodyBottom))
            line.addLine(to: CGPoint(x: cx, y: y(bottom - wicks[i])))
            context.stroke(line, with: .color(color), lineWidth: 1)
            let body = CGRect(x: cx - bodyWidth / 2, y: bodyTop, width: bodyWidth,
                              height: max(bodyBottom - bodyTop, 1))
            context.fill(Path(body), with: .color(color))
        }
    }
}

/// «Basis der %-Änderung» wie Binance «Change(%) & Chart Timezone»: oben der nummerierte Hinweis,
/// darunter «Letzte 24 Std.» (Standard), «Seit letzter Aktualisierung», die Zone des Geräts («UTC+2, 00:00 (Zeitzone des
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
                numbered(3, L("settings_change_basis_hint_since_last", L("change_basis_since_last")))
                numbered(4, L("settings_change_basis_hint_3"))
            }
            .padding(.horizontal, 16)
            .padding(.bottom, Spacing.sm)
            SettingsCard {
                SwitchRow(
                    title: L("settings_change_period"),
                    subtitle: L("settings_change_period_hint"),
                    isOn: $data.settings.showChangePeriod
                )
                .settingsAnchor("change.period")
            }
            SettingsCard {
                ForEach(Array(ChangeBasis.allCases.enumerated()), id: \.offset) { _, basis in
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
        return SettingsChoiceRow(title: A11y.changeChoiceLabel(basis, now: now), selected: selected) {
            if !selected { data.settings.changeBasis = basis }
        }
    }
}
