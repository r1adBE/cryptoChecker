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
    /// Fear-&-Greed-Stufen (RGB) aus genau den Kursfarben `up`/`down` (Angst = fallend, Gier = steigend), nur für
    /// Grafik — wie Android `WidgetColors.scaleSteps`.
    let scaleSteps: [UInt32]

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
        let upHex: UInt32 = priceColors.upHex(dark: dark, highContrast: highContrast, inverted: inverted, accent: accent)
        let downHex: UInt32 = priceColors.downHex(dark: dark, highContrast: highContrast, inverted: inverted, accent: accent)
        up = Color(hex: upHex)
        down = Color(hex: downHex)
        scaleSteps = MarketScalePalette.steps(up: upHex, down: downHex, dark: dark)
        divider = Color(hex: ColorTokens.onSurface.hex(dark: dark)).opacity(highContrast ? 0.45 : 0.25)
    }

    /// Palette nach den App-Einstellungen (Akzent, Kursfarben, Tausch, hoher Kontrast) — für die
    /// Live-Aktivität, die keine Widget-Konfiguration hat.
    static func fromSettings(dark: Bool, systemHighContrast: Bool = false) -> WidgetPalette {
        let settings = SharedStorage.loadSettings()
        return WidgetPalette(accent: settings.accentColor, dark: dark, priceColors: settings.priceColorScheme,
                             highContrast: settings.highContrast || systemHighContrast,
                             inverted: settings.priceColorsInverted)
    }

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

/// Live-Aktivität im Widget-Design: dieselbe Palette wie die Home-Widgets mit Thema «System»
/// (Sperrbildschirm hell → helle Fläche, dunkel → surfaceContainer #1F1F1F). `dark: true` erzwingt
/// die Dunkel-Fassung (Dynamic Island: immer schwarzer Grund des Systems).
struct LiveActivityThemed<Content: View>: View {
    var dark: Bool? = nil
    @ViewBuilder let content: (WidgetPalette) -> Content
    @Environment(\.colorScheme) private var systemScheme
    @Environment(\.colorSchemeContrast) private var contrast

    var body: some View {
        let isDark = dark ?? (systemScheme == .dark)
        let palette = WidgetPalette.fromSettings(dark: isDark, systemHighContrast: contrast == .increased)
        content(palette)
            .environment(\.colorScheme, isDark ? .dark : .light)
    }
}

// MARK: Schrift

/// J-18: Nebentexte in Widgets (Uhrzeit, Börse, Fusszeilen) — mindestens 12 pt (wie Android 12 sp) und mit Dynamic Type
/// (`@ScaledMetric` zu `.caption2`), höchstens 1.25 × gross, damit das Widget nicht überläuft.
enum WidgetTextSize {
    static let minSecondary: CGFloat = 12
    static let maxScale: CGFloat = 1.25

    /// Grösse für `base` beim Dynamic-Type-Faktor `scale`: nie unter 12 pt, nie kleiner als `base`,
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
