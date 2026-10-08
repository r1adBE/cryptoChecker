import SwiftUI

enum CycleFearGreedStyle {
    static func level(_ value: Int) -> Int {
        if value < 25 { return 0 }
        if value < 45 { return 1 }
        if value <= 55 { return 2 }
        if value <= 75 { return 3 }
        return 4
    }

    static func labelKey(_ value: Int) -> String {
        switch level(value) {
        case 0: "fng_extreme_fear"
        case 1: "fng_fear"
        case 2: "fng_neutral"
        case 3: "fng_greed"
        default: "fng_extreme_greed"
        }
    }

    /// Gleiche fünf Farben wie die Zonen.
    static func color(_ value: Int) -> Color { CycleZonePalette.gradient[level(value)] }
}

/// Fear & Greed als erste Zeile unter «Einordnung»: Wert rechts, Stufe und Vortag darunter,
/// die Skala 0–100 in voller Breite direkt unter der Zeile. Tippen zeigt Verlauf und Quelle.
/// Wie `FearGreedRow` in Android.
struct CycleFearGreedRow: View {
    let state: CycleLoad<FearGreed>
    let onRetry: () -> Void
    var divider = false
    /// Herkunft und Stand (alternative.me) für die Nebenzeile.
    var stamp: DataStamp? = nil
    @State private var expanded = false

    var body: some View {
        let fg = state.value
        let label = fg.map { L(CycleFearGreedStyle.labelKey($0.value)) } ?? ""
        MarketRow(
            title: L("insights_fng_title"),
            secondary: fg?.yesterday.map { L("market_row_fng_yesterday", label, LocaleNumbers.integer($0)) } ?? label,
            value: fg.map { LocaleNumbers.integer($0.value) },
            state: Self.rowState(state),
            divider: divider,
            stamp: stamp,
            onRetry: onRetry,
            expanded: fg == nil ? nil : $expanded,
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
            CycleScaleBar(colors: CycleZonePalette.gradient, fraction: Double(value) / 100)
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

    /// Zustand der Zeile aus dem Ladezustand; Fehler mit «Etwas ist schiefgelaufen».
    static func rowState<T>(_ load: CycleLoad<T>, failure: String = L("something_went_wrong")) -> MarketRowState {
        switch load {
        case .loading: .loading
        case .failed: .failed(failure)
        case .loaded: .shown
        }
    }
}
