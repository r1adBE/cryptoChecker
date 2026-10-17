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
    /// Neutral: weiss im Dunkeln, schwarz im Hellen (App-Name, Kurse).
    let text: Color
    let secondary: Color
    /// Gedeckt, damit es sich von Grün und Rot klar absetzt.
    let neutral: Color
    let up: Color
    let down: Color
    /// Trennlinie zwischen den Paaren der Liste: neutral (Haupttext-Farbe, hell im Dunkeln,
    /// dunkel im Hellen) mit geringer Deckkraft statt Akzentfarbe — wie `WidgetContrast.dividerColor`.
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
        // Werte aus `ColorTokens` (gleich wie die App und Android)
        base = Color(hex: ColorTokens.surfaceContainer.hex(dark: dark))
        text = Color(hex: ColorTokens.onSurface.hex(dark: dark))
        // Nebentexte als feste Farbe (onSurfaceVariant, AA auf `base`) wie Android
        // `WidgetColors.secondaryTextColor`; hoher Kontrast: wie der Haupttext
        secondary = highContrast
            ? Color(hex: ColorTokens.onSurface.hex(dark: dark))
            : Color(hex: ColorTokens.onSurfaceVariant.hex(dark: dark))
        neutral = Color(hex: (highContrast ? ColorTokens.onSurfaceVariant : ColorTokens.widgetNeutral).hex(dark: dark))
        // Kursfarben wie in der App (Einstellung «Kursfarben», bei hohem Kontrast die
        // AAA-Werte, «Farben tauschen» vertauscht steigend/fallend).
        // Akzentfarbe: zu ähnliche Kursfarben nehmen ihre Ersatzfassung (wie Android)
        up = Color(hex: priceColors.upHex(dark: dark, highContrast: highContrast, inverted: inverted, accent: accent))
        down = Color(hex: priceColors.downHex(dark: dark, highContrast: highContrast, inverted: inverted, accent: accent))
        divider = Color(hex: ColorTokens.onSurface.hex(dark: dark)).opacity(highContrast ? 0.45 : 0.25)
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

/// Hintergrund: flache Grundfarbe (surfaceContainer), neutral ohne Akzentfarbe — L-19: wie Android
/// (`WidgetColors.baseColor`, flach), kein Verlauf mehr; `containerBackground` braucht nur eine Fläche.
/// Die Akzentfarbe bleibt dem App-Logo und dem Kurs-Etikett im Chart vorbehalten.
struct WidgetBackground: View {
    let palette: WidgetPalette

    var body: some View {
        palette.base
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

// MARK: Schrift

/// J-18: Nebentexte in Widgets (Uhrzeit, Börse, Fusszeilen) — mindestens 11 pt und mit Dynamic Type
/// (`@ScaledMetric` zu `.caption2`), höchstens 1.25 × gross, damit das Widget nicht überläuft.
enum WidgetTextSize {
    static let minSecondary: CGFloat = 11
    static let maxScale: CGFloat = 1.25

    /// Grösse für `base` beim Dynamic-Type-Faktor `scale`: nie unter 11 pt, nie kleiner als `base`,
    /// höchstens `maxScale` × `base`.
    static func secondary(_ base: CGFloat, scale: CGFloat = 1) -> CGFloat {
        max(base, minSecondary) * min(max(scale, 1), maxScale)
    }
}

private struct WidgetSecondaryFont: ViewModifier {
    let size: CGFloat
    let weight: Font.Weight
    let monospacedDigit: Bool
    @ScaledMetric(relativeTo: .caption2) private var scale: CGFloat = 1

    private var font: Font {
        let base = Font.system(size: WidgetTextSize.secondary(size, scale: scale), weight: weight)
        return monospacedDigit ? base.monospacedDigit() : base
    }

    func body(content: Content) -> some View {
        content.font(font)
    }
}

extension View {
    /// Nebentext im Widget (siehe `WidgetTextSize`); Ziffern gleich breit mit `monospacedDigit`.
    func widgetSecondaryFont(_ size: CGFloat = WidgetTextSize.minSecondary, weight: Font.Weight = .regular,
                             monospacedDigit: Bool = false) -> some View {
        modifier(WidgetSecondaryFont(size: size, weight: weight, monospacedDigit: monospacedDigit))
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

/// Coin-Logos der Widgets je Basis-Symbol (vom Timeline-Provider geladen, `WidgetLogos`).
/// Leer: Schalter «Coin-Logos in Widgets» aus, Vorschau oder noch nicht geladen → Initialen.
private struct WidgetLogosKey: EnvironmentKey {
    static let defaultValue: [String: UIImage] = [:]
}

/// Schalter «Coin-Logos in Widgets»; aus → keine Plakette (weder Logo noch Initialen).
private struct WidgetLogosEnabledKey: EnvironmentKey {
    static let defaultValue = false
}

extension EnvironmentValues {
    var widgetLogos: [String: UIImage] {
        get { self[WidgetLogosKey.self] }
        set { self[WidgetLogosKey.self] = newValue }
    }

    var widgetLogosEnabled: Bool {
        get { self[WidgetLogosEnabledKey.self] }
        set { self[WidgetLogosEnabledKey.self] = newValue }
    }
}

/// Runde Coin-Plakette im Widget, nur mit Schalter «Coin-Logos in Widgets»: echtes Logo, sonst
/// Kreis mit Initialen — nie ein kaputtes Bild; Schalter aus → nichts. Mit `accent` wie in der App
/// und Android (Akzentfarbe mit 14 %). `always` (Live-Aktivität, kein Logo-Schalter): immer der
/// Kreis mit der Farbe aus dem Symbol. `logo: false` bei DEX-Pools.
struct WidgetCoinBadge: View {
    let symbol: String
    var size: CGFloat = 26
    let dark: Bool
    var accent: Color? = nil
    var logo: Bool = true
    var always: Bool = false
    @Environment(\.widgetLogos) private var logos
    @Environment(\.widgetLogosEnabled) private var enabled

    private var hue: Double {
        var h = 5381
        for b in CoinLogos.displaySymbol(symbol).utf8 { h = (h &* 33) &+ Int(b) }
        return Double(abs(h % 360)) / 360
    }

    var body: some View {
        if always || enabled { circle }
    }

    private var circle: some View {
        ZStack {
            if !always, logo, let image = logos[symbol] {
                // Dunkel heller Grund, damit dunkle Logos sichtbar bleiben (wie Android)
                Circle().fill(dark ? Color.dynamic(ColorTokens.onSurface).opacity(0.92)
                                   : Color.dynamic(ColorTokens.onSurfaceVariant).opacity(0.11))
                Image(uiImage: image)
                    .resizable()
                    .interpolation(.high)
                    .scaledToFit()
                    .clipShape(Circle())
            } else if let accent {
                let label = CoinLogos.initials(symbol)
                Circle().fill(accent.opacity(0.14))
                Text(label)
                    .font(.system(size: size * (label.count <= 3 ? 0.33 : 0.275), weight: .bold))
                    .foregroundStyle(accent)
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                    .padding(2)
            } else {
                let label = CoinLogos.initials(symbol)
                Circle().fill(Color(hue: hue, saturation: 0.45, brightness: 0.85).opacity(dark ? 0.22 : 0.30))
                Circle().strokeBorder(Color(hue: hue, saturation: 0.6, brightness: 0.9).opacity(0.25), lineWidth: 0.5)
                Text(label)
                    .font(.system(size: size * (label.count > 3 ? 0.28 : 0.34), weight: .bold, design: .rounded))
                    .foregroundStyle(Color(hue: hue, saturation: dark ? 0.55 : 0.7, brightness: dark ? 0.95 : 0.7))
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                    .padding(2)
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
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
    /// Klein und grau VOR dem Wert, z. B. «24h ↘ −0.25%» (Zeitraum der Veränderung in Listenzeilen);
    /// ohne Wert («—») keiner.
    var prefix: String? = nil
    /// %-Basis von `day`-Werten (VoiceOver: «… in 24 Stunden» / «… heute»).
    var basis: ChangeBasis = .ROLLING_24H
    /// Grau ohne Kursfarbe (deaktiviertes Paar: letzter Wert, keine Abfrage).
    var neutral = false

    static var zeroText: String { String(format: "%.2f%%", locale: Locale.current, 0.0) }

    var body: some View {
        if showsText {
            let text = (day && change == nil) ? "—" : (change.flatMap { PriceFormat.changePercent($0) } ?? Self.zeroText)
            HStack(spacing: 2) {
                if let prefix, change != nil {
                    WidgetChangePrefix(text: prefix, size: size, palette: palette)
                }
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
            .foregroundStyle(neutral ? palette.secondary : palette.change(change))
        }
    }
}

/// MEIN Gewinn/Verlust einer Zeile des Portfolio-Widgets: «24h +1’250.00 CHF · +1.50%» in der Kursfarbe nach dem
/// Betrag (nie nach dem Farbtausch, 0 grau); passt es nicht, nur der Betrag (`PortfolioWidgetViews.pnlTexts`);
/// unbekannt grau «—». VoiceOver liest die Zeile als Ganzes. Wie Android `PortfolioWidgetRenderer.fitPnl`.
struct WidgetPnlLabel: View {
    let change: PortfolioListTotalChange?
    let currency: String
    let palette: WidgetPalette
    var hidden = false
    var size: CGFloat = 11
    /// Klein und grau VOR dem Wert, z. B. «24h»; ohne Wert («—») keiner.
    var prefix: String? = nil

    var body: some View {
        if let change {
            let texts = PortfolioWidgetViews.pnlTexts(change, currency: currency, hidden: hidden)
            ViewThatFits(in: .horizontal) {
                ForEach(texts, id: \.self) { line($0) }
            }
            .foregroundStyle(palette.change(Double(change.direction)))
        } else {
            Text("—")
                .font(.system(size: size, weight: .semibold))
                .foregroundStyle(palette.secondary)
                .lineLimit(1)
        }
    }

    private func line(_ text: String) -> some View {
        HStack(spacing: 2) {
            if let prefix {
                WidgetChangePrefix(text: prefix, size: size, palette: palette)
            }
            Text(text)
                .font(.system(size: size, weight: .semibold))
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.85)
                .privacySensitive()
        }
    }
}

/// Zeitraum vor einer Veränderung («24h»): 80 % der Schrift, grau, für VoiceOver verborgen
/// (das Label nennt den Zeitraum schon). Wie Android `PortfolioWidgetRenderer.periodPrefixed`.
private struct WidgetChangePrefix: View {
    let text: String
    let size: CGFloat
    let palette: WidgetPalette

    var body: some View {
        Text(text)
            .font(.system(size: size * 0.8, weight: .medium))
            .foregroundStyle(palette.secondary)
            .lineLimit(1)
            .padding(.trailing, 2)
            .accessibilityHidden(true)
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
    /// Farbe des Kurses; nil = `palette.text`. Deaktiviert: `palette.secondary` statt Deckkraft (wie Android).
    var color: Color? = nil

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: size * 0.18) {
            Text(PriceFormat.price(price))
                .font(.system(size: size, weight: weight, design: .rounded))
                .monospacedDigit()
                .foregroundStyle(color ?? palette.text)
                .lineLimit(1)
                .minimumScaleFactor(0.45)
                .invalidatableContent()
            if price != nil, (price ?? 0) > 0 {
                Text(quote)
                    .font(.system(size: max(CGFloat(WidgetQuoteText.minQuoteSize), size * 0.42), weight: .semibold))
                    .foregroundStyle(palette.secondary)
                    .lineLimit(1)
                    .fixedSize()
            }
        }
    }
}

extension Text {
    /// `text` mit kleiner, grauer Währung `quote` (`WidgetQuoteText`) — wie der Börsenname der Merkliste:
    /// der Rest in `size`/`weight`, die Währung in `WidgetQuoteText.quoteSize` und `quoteColor`. Ohne
    /// Währung im Text (z. B. «•••») der ganze Text gleich. Wie `WidgetToolkit.quoteText` (Android).
    static func quoted(_ text: String, quote: String, size: CGFloat, weight: Font.Weight = .semibold,
                       design: Font.Design = .default, quoteColor: Color) -> Text {
        let font = Font.system(size: size, weight: weight, design: design)
        let parts = WidgetQuoteText.split(text, quote: quote)
        guard let currency = parts.quote else { return Text(verbatim: text).font(font) }
        let quoteFont = Font.system(size: CGFloat(WidgetQuoteText.quoteSize(for: Double(size))), weight: .semibold)
        let before = Text(verbatim: parts.before).font(font)
        let middle = Text(verbatim: currency).font(quoteFont).foregroundStyle(quoteColor)
        let after = Text(verbatim: parts.after).font(font)
        return before + middle + after
    }
}
