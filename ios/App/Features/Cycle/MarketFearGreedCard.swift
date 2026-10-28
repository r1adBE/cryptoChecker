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
}

/// Fear & Greed als erste Zeile unter «Einordnung» im Stil des Puls-Widgets: die Zahl
/// gross mit dem Wort der Stufe daneben, darunter eine neutrale Spur 0–100 mit EINEM Punkt in der Farbe der Stufe —
/// kein Regenbogen-Verlauf. Tippen zeigt Gestern / Woche / Monat und die Quelle. Auf «Jetzt» kompakt (`compact`: Zahl
/// in Titelgrösse statt Display) — dort ist die Heldenkarte darüber die einzige betonte Karte. Wie `FearGreedRow` in Android.
struct CycleFearGreedRow: View {
    let state: CycleLoad<FearGreed>
    let onRetry: () -> Void
    var first = true
    /// Herkunft und Stand (alternative.me) für die Nebenzeile.
    var stamp: DataStamp? = nil
    var compact = false
    @State private var expanded = false

    var body: some View {
        let fg = state.value
        MarketRow(
            title: L("insights_fng_title"),
            state: Self.rowState(state),
            first: first,
            stamp: stamp,
            onRetry: onRetry,
            expanded: fg == nil ? nil : $expanded,
            info: .fearGreed,
            // Spur bleibt auch beim Laden und bei Fehler stehen (grau) — darunter springt nichts
            below: AnyView(CycleFearGreedGauge(value: fg?.value, compact: compact)),
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

    /// Zustand der Zeile aus dem Ladezustand; Fehler mit «Marktdaten gerade nicht verfügbar» (wie Android).
    static func rowState<T>(_ load: CycleLoad<T>, failure: String = L("pulse_unavailable")) -> MarketRowState {
        switch load {
        case .loading: .loading
        case .failed: .failed(failure)
        case .loaded: .shown
        }
    }
}

/// Zahl gross und Wort der Stufe, darunter die neutrale Spur mit dem Stufenpunkt. Punktfarbe wie im Puls-Widget
/// aus den Kursfarben (`MarketScaleColors`: Angst = fallend, Gier = steigend, je Stufe ≥ 3:1 als Grafik), passend zu
/// den Kurspfeilen; Zahl und Wort in der Textfarbe — die Farbe trägt nie
/// allein. nil = grauer Platzhalter in derselben Höhe. Wie `FearGreedGauge` in Android.
struct CycleFearGreedGauge: View {
    let value: Int?
    /// Zahl in `AppFont.headline` (22 pt) statt `.title` (28 pt) — wie Android `headline` statt `displayCompact`.
    var compact = false
    private let marker: CGFloat = 14
    @Environment(\.layoutDirection) private var layoutDirection
    @Environment(\.priceColorScheme) private var priceColors
    @Environment(\.priceHighContrast) private var highContrast
    @Environment(\.priceColorsInverted) private var inverted

    var body: some View {
        if let value {
            content(value)
        } else {
            Capsule()
                .fill(AppColors.containerHighest)
                .frame(height: 4)
                .frame(maxWidth: .infinity, minHeight: 18, maxHeight: 18)
                .accessibilityHidden(true)
        }
    }

    private func content(_ value: Int) -> some View {
        let label: String = L(CycleFearGreedStyle.labelKey(value))
        let number: String = LocaleNumbers.integer(value)
        let gauge: String = L("a11y_gauge", L("fng_extreme_fear"), L("fng_extreme_greed"), value)
        let dot: Color = MarketScaleColors.step(CycleFearGreedStyle.level(value), scheme: priceColors,
                                               highContrast: highContrast, inverted: inverted)
        return VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .lastTextBaseline, spacing: Spacing.sm) {
                Text(verbatim: number)
                    .font((compact ? AppFont.headline : .title.weight(.semibold)).monospacedDigit())
                    .foregroundStyle(AppColors.onSurface)
                Text(label)
                    .font(.callout.weight(.medium))
                    .foregroundStyle(AppColors.onSurface)
            }
            track(fraction: Double(value) / 100, dot: dot)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        // Ein Satz: «72, Gier, Extreme Angst bis Extreme Gier: 72»
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(number + ", " + label + ", " + gauge)
    }

    /// Neutrale Spur mit dem Punkt; folgt der Leserichtung (bei RTL beginnt 0 rechts).
    private func track(fraction: Double, dot: Color) -> some View {
        let rtl: Bool = layoutDirection == .rightToLeft
        let clamped: Double = min(max(fraction, 0), 1)
        let position: Double = rtl ? 1 - clamped : clamped
        return GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule()
                    .fill(AppColors.outlineVariant)
                    .frame(height: 4)
                // Rand in der Fläche, damit sich der Punkt von der Spur abhebt
                Circle()
                    .fill(AppColors.surface)
                    .frame(width: marker, height: marker)
                    .overlay(Circle().fill(dot).padding(2))
                    .offset(x: max(geo.size.width - marker, 0) * position)
            }
            .frame(width: geo.size.width, height: 18)
            .environment(\.layoutDirection, .leftToRight)
        }
        .frame(height: 18)
    }
}
