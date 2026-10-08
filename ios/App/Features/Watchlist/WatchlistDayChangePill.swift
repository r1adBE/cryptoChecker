import SwiftUI

/// Veränderung gemäss %-Basis (`Watch.change24h`) als Pille wie `WatchlistChangePill`,
/// daneben klein der Zeitraum «24h», «heute» oder «heute UTC» — wie `DayChangePill` in Android.
/// Ohne Bezug (oder bei veralteter Basis) eine graue Pille «—» ohne Pfeil, nie die Veränderung
/// seit der letzten Abfrage. VoiceOver: «gestiegen um 2.30% in 24 Stunden» / «… heute».
struct WatchlistDayChangePill: View {
    /// Gespeicherte Veränderung (`Watch.shownChange24h`); die Pille prüft selbst die %-Basis.
    let change: Double?
    var large = false
    @Environment(\.changeView) private var changeView
    @Environment(\.priceColorScheme) private var priceColors
    @Environment(\.priceHighContrast) private var highContrast
    @Environment(\.priceColorsInverted) private var inverted

    var body: some View {
        let value = changeView.shown(change)
        let formatted = value.flatMap { PriceFormat.changePercent($0) }
        let color = colorFor(value, formatted: formatted)
        HStack(spacing: 4) {
            HStack(spacing: 3) {
                if let value, formatted != nil {
                    Image(systemName: value >= 0 ? "arrow.up.right" : "arrow.down.right")
                        .scaledFont(size: large ? 11 : 9, weight: .bold, relativeTo: .caption)
                }
                Text(value == nil ? "—" : (formatted ?? PriceFormat.zeroPercent()))
                    .font(AppFont.amount(large ? .subheadline : .caption, weight: .semibold))
            }
            .foregroundStyle(color)
            .padding(.horizontal, Spacing.sm)
            .padding(.vertical, large ? 4 : 2)
            .background(color.opacity(0.14), in: Capsule())
            .contentTransition(.numericText())
            Text(A11y.changeShortLabel(changeView.basis))
                .font(.system(large ? Font.TextStyle.caption : .caption2, design: .rounded))
                .foregroundStyle(AppColors.onSurfaceVariant)
                .lineLimit(1)
        }
        // Pille wächst mit der Textgrösse, aber nicht über AX2 (sonst sprengt sie die Zeile)
        .dynamicTypeSize(...DynamicTypeSize.accessibility2)
        // Vorgelesen mit Richtungswort und Zeitraum statt «+»/«−»
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(A11y.change(value, basis: changeView.basis))
    }

    private func colorFor(_ value: Double?, formatted: String?) -> Color {
        guard let value, formatted != nil else { return AppColors.onSurfaceVariant }
        return priceColors.forChange(value, highContrast: highContrast, inverted: inverted)
    }
}
