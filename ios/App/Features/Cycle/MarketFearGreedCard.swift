import SwiftUI

enum CycleFearGreedStyle {
    static func level(_ value: Int) -> Int { MarketScalePalette.level(value) }

    static func labelKey(_ value: Int) -> String {
        switch level(value) {
        case 0: "fng_extreme_fear"
        case 1: "fng_fear"
        case 2: "fng_neutral"
        case 3: "fng_greed"
        default: "fng_extreme_greed"
        }
    }

    /// J-15: Stufenfarben aus den aktiven Kursfarben (Farbsehschwäche, hoher Kontrast, Tausch) —
    /// nur für die Skala, nie für die Zahl.
    static func colors(_ scheme: PriceColorScheme, dark: Bool, highContrast: Bool, inverted: Bool,
                       accent: AccentColor? = nil) -> [Color] {
        MarketScalePalette.steps(scheme, dark: dark, highContrast: highContrast, inverted: inverted, accent: accent)
            .map { Color(hex: $0) }
    }
}

/// Fear & Greed als erste Zeile unter «Einordnung»: Wert rechts, Stufe und Vortag darunter,
/// die Skala 0–100 in voller Breite direkt unter der Zeile. Tippen zeigt Verlauf und Quelle.
/// Wie `FearGreedRow` in Android.
struct CycleFearGreedRow: View {
    let state: CycleLoad<FearGreed>
    let onRetry: () -> Void
    var first = true
    /// Herkunft und Stand (alternative.me) für die Nebenzeile.
    var stamp: DataStamp? = nil
    @State private var expanded = false
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.priceColorScheme) private var priceColorScheme
    @Environment(\.priceHighContrast) private var priceHighContrast
    @Environment(\.priceColorsInverted) private var priceColorsInverted
    @Environment(\.appAccent) private var appAccent

    var body: some View {
        let fg = state.value
        let label = fg.map { L(CycleFearGreedStyle.labelKey($0.value)) } ?? ""
        MarketRow(
            title: L("insights_fng_title"),
            secondary: fg?.yesterday.map { L("market_row_fng_yesterday", label, LocaleNumbers.integer($0)) } ?? label,
            value: fg.map { LocaleNumbers.integer($0.value) },
            state: Self.rowState(state),
            first: first,
            stamp: stamp,
            onRetry: onRetry,
            expanded: fg == nil ? nil : $expanded,
            info: .fearGreed,
            // J-15: Zahl in der Textfarbe (Stufenfarben erreichten als Schrift kein AA); die Stufe zeigen
            // Skala und Markierung, das Wort steht daneben
            valueColor: fg.map { _ in AppColors.onSurface },
            // Skala bleibt auch beim Laden und bei Fehler stehen (grau) — darunter springt nichts
            below: AnyView(scale(fg?.value)),
            details: AnyView(VStack(alignment: .leading, spacing: 0) {
                if let fg {
                    Text(L("insights_fng_history",
                           fg.yesterday.map { LocaleNumbers.integer($0) } ?? "—",
                           fg.weekAgo.map { LocaleNumbers.integer($0) } ?? "—",
                           fg.monthAgo.map { LocaleNumbers.integer($0) } ?? "—"))
                        .font(.footnote)
                        .monospacedDigit()
                        .foregroundStyle(AppColors.onSurfaceVariant)
                    CycleSourceText(text: L("insights_source_fng"))
                }
            })
        )
    }

    /// Skala 0–100 mit Markierung; nil = grauer Platzhalter in derselben Höhe (18 pt).
    @ViewBuilder
    private func scale(_ value: Int?) -> some View {
        if let value {
            CycleScaleBar(colors: CycleFearGreedStyle.colors(priceColorScheme, dark: colorScheme == .dark,
                                                             highContrast: priceHighContrast,
                                                             inverted: priceColorsInverted, accent: appAccent),
                          fraction: Double(value) / 100)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(L("a11y_gauge", L("fng_extreme_fear"), L("fng_extreme_greed"), value))
        } else {
            Capsule()
                .fill(AppColors.containerHighest)
                .frame(height: 8)
                .frame(maxWidth: .infinity, minHeight: 18, maxHeight: 18)
                .accessibilityHidden(true)
        }
    }

    /// Zustand der Zeile aus dem Ladezustand; Fehler mit «Marktdaten gerade nicht verfügbar» (wie Android).
    static func rowState<T>(_ load: CycleLoad<T>, failure: String = L("pulse_unavailable")) -> MarketRowState {
        switch load {
        case .loading: .loading
        case .failed: .failed(failure)
        case .loaded: .shown
        }
    }
}
