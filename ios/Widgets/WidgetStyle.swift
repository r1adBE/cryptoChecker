import AppIntents
import SwiftUI
import UIKit
import WidgetKit

// MARK: Farben

/// Farben eines Widgets — Werte wie `WidgetColors.kt` (Flächen und Text des
/// App-Farbschemas, Akzent aus der App-Einstellung). Feste Werte statt
/// dynamischer Farben, damit «Dunkel»/«Hell» unabhängig vom Gerät gelten.
struct WidgetPalette {
    let dark: Bool
    let accent: Color
    let base: Color
    let baseDeep: Color
    /// Neutral: weiss im Dunkeln, schwarz im Hellen (App-Name, Kurse).
    let text: Color
    let secondary: Color
    /// Gedeckt, damit es sich von Grün und Rot klar absetzt.
    let neutral: Color
    let up: Color
    let down: Color
    /// Trennlinie zwischen den Paaren der Liste: Akzentfarbe (bei hohem Kontrast der
    /// Hoch-Kontrast-Akzent) mit geringer Deckkraft, damit die Liste ruhig bleibt.
    let divider: Color
    /// Text auf der Akzentfarbe (Kurs-Etikett im Chart), wie `onPrimary` der App.
    let onAccent: Color
    /// Hoher Kontrast (Einstellung oder System): Chart-Linien und -Beschriftung kräftiger.
    let highContrast: Bool

    init(accent: AccentColor, dark: Bool, priceColors: PriceColorScheme = .default, highContrast: Bool = false,
         inverted: Bool = false) {
        self.dark = dark
        self.accent = accent.primary(dark: dark)
        onAccent = accent.onPrimary(dark: dark)
        self.highContrast = highContrast
        base = Color(hex: dark ? 0x1F1F1F : 0xEEEEEE)
        baseDeep = Color(hex: dark ? 0x151515 : 0xE4E4E4)
        text = Color(hex: dark ? 0xE2E2E2 : 0x1B1B1B)
        // Hoher Kontrast: Nebentexte ohne Abschwächung, Grau für «0.00%» dunkler
        secondary = highContrast
            ? Color(hex: dark ? 0xE2E2E2 : 0x1B1B1B)
            : Color(hex: dark ? 0xC6C6C6 : 0x474747).opacity(dark ? 0.78 : 0.85)
        neutral = Color(hex: highContrast ? (dark ? 0xC6C6C6 : 0x474747) : (dark ? 0x919191 : 0x777777))
        // Kursfarben wie in der App (Einstellung «Kursfarben», bei hohem Kontrast die
        // AAA-Werte, «Farben tauschen» vertauscht steigend/fallend).
        up = Color(hex: priceColors.upHex(dark: dark, highContrast: highContrast, inverted: inverted))
        down = Color(hex: priceColors.downHex(dark: dark, highContrast: highContrast, inverted: inverted))
        divider = accent.primary(dark: dark, highContrast: highContrast).opacity(highContrast ? 0.45 : 0.25)
    }

    /// Live-Aktivität (Sperrbildschirm, Dynamic Island): dunkle Fläche, helle Systemknöpfe.
    static let liveActivityBackground = Color.black.opacity(0.72)
    static let liveActivityForeground = Color.white

    func change(_ value: Double?) -> Color {
        guard let value else { return neutral }
        if PriceFormat.changePercent(value) == nil { return neutral }
        return value >= 0 ? up : down
    }
}

/// Hintergrund: Grundfarbe mit leichtem Verlauf und einem Hauch Akzentfarbe.
struct WidgetBackground: View {
    let palette: WidgetPalette

    var body: some View {
        ZStack {
            LinearGradient(colors: [palette.base, palette.baseDeep], startPoint: .top, endPoint: .bottom)
            RadialGradient(colors: [palette.accent.opacity(palette.dark ? 0.16 : 0.10), .clear],
                           center: .topTrailing, startRadius: 0, endRadius: 220)
        }
    }
}

/// Löst das Widget-Thema auf (System / Dunkel / Hell) und setzt Hintergrund
/// und Farbschema für den Inhalt.
struct WidgetThemed<Content: View>: View {
    let theme: WidgetThemeOption
    let accent: AccentColor
    var priceColors: PriceColorScheme = .default
    /// Einstellung «Hoher Kontrast»; «Kontrast erhöhen» des Systems zählt zusätzlich.
    var highContrast: Bool = false
    /// Einstellung «Farben tauschen (steigend ↔ fallend)».
    var inverted: Bool = false
    @ViewBuilder let content: (WidgetPalette) -> Content
    @Environment(\.colorScheme) private var systemScheme
    @Environment(\.colorSchemeContrast) private var contrast

    var body: some View {
        let dark = theme.isDark(systemDark: systemScheme == .dark)
        let palette = WidgetPalette(accent: accent, dark: dark, priceColors: priceColors,
                                    highContrast: highContrast || contrast == .increased, inverted: inverted)
        content(palette)
            .environment(\.colorScheme, dark ? .dark : .light)
            .containerBackground(for: .widget) {
                WidgetBackground(palette: palette)
            }
    }
}

// MARK: Bausteine

/// App-Logo aus dem Asset-Katalog; fehlt es im Widget-Ziel, ein Ersatzzeichen.
struct WidgetLogo: View {
    let accent: AccentColor
    let dark: Bool
    var size: CGFloat = 16

    var body: some View {
        if let image = UIImage(named: accent.logoName(dark: dark)) {
            Image(uiImage: image)
                .resizable()
                .interpolation(.high)
                .aspectRatio(contentMode: .fit)
                .frame(width: size, height: size)
                .accessibilityHidden(true)
        } else {
            ZStack {
                RoundedRectangle(cornerRadius: size * 0.28, style: .continuous)
                    .fill(accent.primary(dark: dark))
                Image(systemName: "chart.line.uptrend.xyaxis")
                    .font(.system(size: size * 0.55, weight: .bold))
                    .foregroundStyle(AppColors.onVivid)
            }
            .frame(width: size, height: size)
            .accessibilityHidden(true)
        }
    }
}

/// Runder Coin-Kreis mit Kürzel — wie `CoinBadge` der App.
struct WidgetCoinBadge: View {
    let symbol: String
    var size: CGFloat = 26
    let dark: Bool

    private var hue: Double {
        var h = 5381
        for b in symbol.utf8 { h = (h &* 33) &+ Int(b) }
        return Double(abs(h % 360)) / 360
    }

    var body: some View {
        let label = String(symbol.prefix(symbol.count > 4 ? 3 : 4))
        ZStack {
            Circle().fill(Color(hue: hue, saturation: 0.45, brightness: 0.85).opacity(dark ? 0.22 : 0.30))
            Circle().strokeBorder(Color(hue: hue, saturation: 0.6, brightness: 0.9).opacity(0.25), lineWidth: 0.5)
            Text(label)
                .font(.system(size: size * (symbol.count > 3 ? 0.28 : 0.34), weight: .bold, design: .rounded))
                .foregroundStyle(Color(hue: hue, saturation: dark ? 0.55 : 0.7, brightness: dark ? 0.95 : 0.7))
                .lineLimit(1)
                .minimumScaleFactor(0.5)
                .padding(2)
        }
        .frame(width: size, height: size)
        // Zierde: Das Kürzel steht schon im Paar
        .accessibilityHidden(true)
    }
}

/// Veränderung in Prozent: Kursfarbe und immer mit Vorzeichen + / −; ohne Bewegung
/// oder ohne Vorkurs grau «0.00%» (wie Android) — so sehen alle Zeilen gleich aus.
struct WidgetChangeLabel: View {
    let change: Double?
    let palette: WidgetPalette
    var size: CGFloat = 11
    var showsText = true
    /// Pfeil wie in der Prozent-Pille der App; folgt dem Vorzeichen (nie dem Farbtausch),
    /// bei «0.00%» keiner.
    var showsArrow = false
    /// Veränderung über 24 Stunden (`Watch.change24h`): ohne Wert grau «—» statt «0.00%»,
    /// VoiceOver mit Zeitraum («… in 24 Stunden»).
    var day = false
    /// Klein und grau hinter dem Wert, z. B. «24h» (wenn daneben ein anderer Zeitraum steht).
    var suffix: String? = nil
    /// %-Basis von `day`-Werten (VoiceOver: «… in 24 Stunden» / «… heute»).
    var basis: ChangeBasis = .ROLLING_24H

    static var zeroText: String { String(format: "%.2f%%", locale: Locale.current, 0.0) }

    var body: some View {
        if showsText {
            let text = (day && change == nil) ? "—" : (change.flatMap { PriceFormat.changePercent($0) } ?? Self.zeroText)
            HStack(spacing: 2) {
                if showsArrow {
                    ChangeArrowIcon(change: change)
                        .font(.system(size: size * 0.75, weight: .bold))
                }
                Text(text)
                    .font(.system(size: size, weight: .semibold))
                    .monospacedDigit()
                    .lineLimit(1)
                    // Mit Pfeil in engen Zeilen lieber etwas kleiner als abgeschnitten
                    .minimumScaleFactor(0.85)
                    .accessibilityLabel(day ? A11y.change(change, basis: basis) : (A11y.change(change) ?? L("a11y_change_flat")))
                if let suffix {
                    Text(suffix)
                        .font(.system(size: size * 0.8, weight: .medium))
                        .foregroundStyle(palette.secondary)
                        .lineLimit(1)
                        .accessibilityHidden(true)
                }
            }
            .foregroundStyle(palette.change(change))
        }
    }
}

/// Knopf «Aktualisieren» (interaktiv, iOS 17).
struct WidgetRefreshButton: View {
    let palette: WidgetPalette
    var size: CGFloat = 26

    var body: some View {
        Button(intent: RefreshPricesIntent()) {
            Image(systemName: "arrow.clockwise")
                .font(.system(size: size * 0.46, weight: .bold))
                .foregroundStyle(palette.text) // neutral wie der Titel, nicht in der Akzentfarbe
                .frame(width: size, height: size)
                .background(Circle().fill(palette.text.opacity(palette.dark ? 0.12 : 0.08)))
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(L("action_refresh"))
    }
}

/// Kurs gross, Gegenwert klein daneben.
struct WidgetPriceText: View {
    let price: Double?
    let quote: String
    let size: CGFloat
    let palette: WidgetPalette
    var weight: Font.Weight = .bold

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: size * 0.18) {
            Text(PriceFormat.price(price))
                .font(.system(size: size, weight: weight, design: .rounded))
                .monospacedDigit()
                .foregroundStyle(palette.text)
                .lineLimit(1)
                .minimumScaleFactor(0.45)
                .invalidatableContent()
            if price != nil, (price ?? 0) > 0 {
                Text(quote)
                    .font(.system(size: max(9, size * 0.42), weight: .semibold))
                    .foregroundStyle(palette.secondary)
                    .lineLimit(1)
                    .fixedSize()
            }
        }
    }
}
