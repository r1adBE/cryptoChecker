import SwiftUI

// MARK: Werte

/// «Puls» über der Merkliste: wie viele Paare steigen bzw. fallen und der
/// einfache Durchschnitt ihrer Veränderung — wie `WatchlistPulse` in Android.
///
/// Grundlage ist derselbe Wert wie die Prozent-Pille der Zeile
/// (`Watch.change24h`, Veränderung über 24 Stunden, siehe `DayChange`).
/// Paare ohne 24-h-Wert (Pille «—») fallen weg, werden aber ehrlich als `missing` gezählt
/// («· 510 ohne 24h-Wert»). «Unverändert» (unter 0.005 %, Pille grau «0.00%») zählt weder als steigend
/// noch als fallend, geht aber in den Durchschnitt ein.
struct WatchlistPulseStats: Equatable {
    let up: Int
    let down: Int
    /// Einfacher Durchschnitt (gleiches Gewicht je Paar) in Prozent.
    let average: Double
    /// Gezeigte Paare mit Kurs, aber ohne 24-h-Wert.
    var missing: Int = 0

    /// nil, wenn weniger als zwei Paare einen 24-h-Wert haben. Als «ohne 24h-Wert» zählen nur
    /// Paare mit Kurs, die noch gehandelt werden (ohne Kurs gibt es keine Pille).
    static func make(_ watches: [Watch], view: ChangeView = ChangeView()) -> WatchlistPulseStats? {
        // Nicht gehandelte Paare zählen weder als steigend/fallend noch als «ohne 24h-Wert»;
        // veraltete %-Basis (gewechselt, neuer Tag): alle «—»
        make(changes: watches.map { view.shown($0) },
             hasPrice: watches.map { $0.lastPrice != nil && !ConnectionErrors.isNotTraded($0.lastError) })
    }

    /// Wie `WatchPulse.of(changes, hasPrice)` in Android.
    static func make(changes values: [Double?], hasPrice: [Bool]) -> WatchlistPulseStats? {
        var missing = 0
        for (index, value) in values.enumerated() where index < hasPrice.count && hasPrice[index] {
            if value == nil || !(value ?? 0).isFinite { missing += 1 }
        }
        return make(changes: values, missing: missing)
    }

    /// Wie `WatchPulse.of` in Android: aus den 24-h-Veränderungen (nil = kein Wert);
    /// `missing` Standard: alle nil/ungültigen Werte.
    static func make(changes values: [Double?], missing: Int? = nil) -> WatchlistPulseStats? {
        let changes = values.compactMap { $0 }.filter(\.isFinite)
        guard changes.count >= 2 else { return nil }
        // Gleiche Schwelle wie die Pille (`PriceFormat.changePercent` → nil = «0.00%»)
        let moving = changes.filter { PriceFormat.changePercent($0) != nil }
        return WatchlistPulseStats(
            up: moving.filter { $0 > 0 }.count,
            down: moving.filter { $0 < 0 }.count,
            average: changes.reduce(0, +) / Double(changes.count),
            missing: max(0, missing ?? (values.count - changes.count))
        )
    }
}

// MARK: Ansicht

/// Schlanke Zeile direkt über der Liste: «▲ 7 steigen · ▼ 3 fallen · Ø +1.80% 24h» (Veränderung über 24 Stunden),
/// dazu klein «· 510 ohne 24h-Wert», wenn gezeigte Paare mit Kurs keinen 24-h-Wert haben
/// als kleine Pillen im Stil der Zeilen-Pille (gleiche Form und Tönung).
/// Zählungen in Steigend- bzw. Fallend-Farbe mit Dreieck, Durchschnitt mit Vorzeichen
/// und Pfeil — die Bedeutung hängt nie allein an der Farbe. VoiceOver liest einen Satz.
struct WatchlistPulseLine: View {
    let stats: WatchlistPulseStats
    @Environment(\.priceColorScheme) private var priceColors
    @Environment(\.priceHighContrast) private var highContrast
    @Environment(\.priceColorsInverted) private var inverted
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.changeView) private var changeView

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: Spacing.xs) { pills }
            // Sehr grosse Schrift: untereinander statt abgeschnitten
            VStack(alignment: .leading, spacing: 4) { pills }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .dynamicTypeSize(...DynamicTypeSize.accessibility2)
        .animation(reduceMotion ? nil : .snappy, value: stats)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(spoken)
    }

    /// Ein Satz für VoiceOver; fehlen 24-h-Werte, wird das mitgesagt.
    private var spoken: String {
        let pulse = L("a11y_watchlist_pulse", L("a11y_watchlist_pulse_rising", count: stats.up),
                      L("a11y_watchlist_pulse_falling", count: stats.down),
                      A11y.change(stats.average, basis: changeView.basis))
        guard stats.missing > 0 else { return pulse }
        let missingKey = changeView.basis.isDay ? "a11y_watchlist_pulse_missing_day" : "a11y_watchlist_pulse_missing"
        return L("a11y_watchlist_pulse_combined", pulse, L(missingKey, count: stats.missing))
    }

    @ViewBuilder
    private var pills: some View {
        let upColor = priceColors.up(highContrast: highContrast, inverted: inverted)
        let downColor = priceColors.down(highContrast: highContrast, inverted: inverted)
        pill(icon: "arrowtriangle.up.fill", text: L("watchlist_pulse_up", count: stats.up),
             value: Double(stats.up), color: upColor)
        pill(icon: "arrowtriangle.down.fill", text: L("watchlist_pulse_down", count: stats.down),
             value: Double(stats.down), color: downColor)
        let formatted = PriceFormat.changePercent(stats.average)
        pill(icon: formatted == nil ? nil : (stats.average > 0 ? "arrow.up.right" : "arrow.down.right"),
             // Zeitraum wie neben den Pillen der Zeilen: «Ø +1.80% 24h» bzw. «… heute»
             text: L("watchlist_pulse_avg", formatted ?? PriceFormat.zeroPercent())
                 + (changeView.showPeriod ? " " + A11y.changeShortLabel(changeView.basis) : ""),
             value: stats.average,
             color: formatted == nil ? AppColors.onSurfaceVariant
                 : priceColors.forChange(stats.average, highContrast: highContrast, inverted: inverted))
        // Klein und grau, ohne Pille: «· 510 ohne 24h-Wert»
        if stats.missing > 0 {
            Text("· " + L(changeView.basis.isDay ? "watchlist_pulse_missing_day" : "watchlist_pulse_missing",
                          count: stats.missing))
                .font(AppFont.amount(.caption2))
                .foregroundStyle(AppColors.onSurfaceVariant)
                .lineLimit(1)
                .contentTransition(.numericText(value: Double(stats.missing)))
                .fixedSize()
        }
    }

    private func pill(icon: String?, text: String, value: Double, color: Color) -> some View {
        HStack(spacing: 3) {
            if let icon {
                Image(systemName: icon)
                    .scaledFont(size: 9, weight: .bold, relativeTo: .caption)
            }
            Text(text)
                .font(AppFont.amount(.caption, weight: .semibold))
                .lineLimit(1)
                .contentTransition(.numericText(value: value))
        }
        .foregroundStyle(color)
        .changePillBackground(color)
        .fixedSize()
    }
}
