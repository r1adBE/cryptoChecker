import SwiftUI

/// Veränderung über 24 Stunden (`Watch.change24h`) als Pille wie `WatchlistChangePill`,
/// daneben klein «24h» — wie `DayChangePill` in Android. Ohne 24-h-Bezug eine graue
/// Pille «—» ohne Pfeil, nie die Veränderung seit der letzten Abfrage.
/// VoiceOver: «gestiegen um 2.30% in 24 Stunden».
struct WatchlistDayChangePill: View {
    let change: Double?
    var large = false
    @Environment(\.priceColorScheme) private var priceColors
    @Environment(\.priceHighContrast) private var highContrast
    @Environment(\.priceColorsInverted) private var inverted

    var body: some View {
        let value = change.flatMap { $0.isFinite ? $0 : nil }
        let formatted = value.flatMap { PriceFormat.changePercent($0) }
        let color = colorFor(value, formatted: formatted)
        HStack(spacing: 4) {
            HStack(spacing: 3) {
                if let value, formatted != nil {
                    Image(systemName: value >= 0 ? "arrow.up.right" : "arrow.down.right")
                        .scaledFont(size: large ? 11 : 9, weight: .bold, relativeTo: .caption)
                }
                Text(value == nil ? "—" : (formatted ?? "0.00%"))
                    .font(.system(large ? Font.TextStyle.subheadline : .caption, design: .rounded).weight(.semibold).monospacedDigit())
            }
            .foregroundStyle(color)
            .padding(.horizontal, large ? 10 : 8)
            .padding(.vertical, large ? 4 : 2)
            .background(color.opacity(0.14), in: Capsule())
            .contentTransition(.numericText())
            Text(L("widget_range_short_24h"))
                .font(.system(large ? Font.TextStyle.caption : .caption2, design: .rounded))
                .foregroundStyle(AppColors.onSurfaceVariant)
                .lineLimit(1)
        }
        // Pille wächst mit der Textgrösse, aber nicht über AX2 (sonst sprengt sie die Zeile)
        .dynamicTypeSize(...DynamicTypeSize.accessibility2)
        // Vorgelesen mit Richtungswort und Zeitraum statt «+»/«−»
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(A11y.change24h(value))
    }

    private func colorFor(_ value: Double?, formatted: String?) -> Color {
        guard let value, formatted != nil else { return AppColors.onSurfaceVariant }
        return priceColors.forChange(value, highContrast: highContrast, inverted: inverted)
    }
}
